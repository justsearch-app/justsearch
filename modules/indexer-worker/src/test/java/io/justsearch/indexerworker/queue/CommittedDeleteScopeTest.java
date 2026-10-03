/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.loop.SourceContentHash;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite proof for the read-only committed-delete scope predicates. */
final class CommittedDeleteScopeTest {

  @TempDir Path tempDir;
  private Path dbPath;
  private SqliteJobQueue queue;

  @BeforeEach
  void setUp() throws Exception {
    dbPath = tempDir.resolve("jobs.db");
    queue = new SqliteJobQueue(dbPath);
    queue.open();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (queue != null) queue.close();
  }

  @Test
  void pathScopeSeesOnlyPendingAndProcessingRows() throws Exception {
    Path root = tempDir.resolve("states");
    Path pending = root.resolve("pending.txt");
    Path processing = root.resolve("processing.txt");
    Path done = root.resolve("done.txt");
    Path failed = root.resolve("failed.txt");
    queue.enqueue(List.of(pending, processing, done, failed), "states");
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(root.toString()),
        "pending rows must be visible before polling");
    queue.pollPending(4);

    setState(done, "DONE");
    setState(failed, "FAILED");
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(root.toString()));

    setState(pending, "DONE");
    setState(processing, "FAILED");
    assertFalse(queue.hasNonterminalJobsByPathPrefixStrict(root.toString()));
  }

  @Test
  void unrelatedPendingRowsDoNotEnterPathOrCollectionScope() {
    Path target = tempDir.resolve("target").resolve("file.txt");
    Path unrelated = tempDir.resolve("other").resolve("file.txt");
    queue.enqueue(List.of(target), "target-collection");
    queue.enqueue(List.of(unrelated), "other-collection");

    assertFalse(queue.hasNonterminalJobsByPathPrefixStrict(
        tempDir.resolve("missing").toString()));
    assertFalse(queue.hasNonterminalJobsByCollectionStrict("missing-collection"));
    assertTrue(queue.hasNonterminalJobsByCollectionStrict("target-collection"));
  }

  @Test
  void pathScopeTreatsPercentUnderscoreAndNeighborBoundaryLiterally() throws Exception {
    Path literalRoot = tempDir.resolve("folder_%");
    Path child = literalRoot.resolve("child.txt");
    Path wildcardSibling = tempDir.resolve("folderXanything").resolve("sibling.txt");
    Path boundarySibling = tempDir.resolve("folder_%-neighbor").resolve("sibling.txt");
    queue.enqueue(List.of(child, wildcardSibling, boundarySibling));

    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(literalRoot.toString()));
    setState(child, "DONE");
    assertFalse(queue.hasNonterminalJobsByPathPrefixStrict(literalRoot.toString()));
  }

  @Test
  void prefixDeleteExcludingAcceptedSurvivorPreservesOnlyThatPath() {
    Path root = tempDir.resolve("survivor-scope");
    Path survivor = root.resolve("survivor.txt");
    Path removed = root.resolve("removed.txt");
    Path secondRemoved = root.resolve("second-removed.txt");
    queue.enqueue(List.of(survivor, removed, secondRemoved), "scope");

    assertEquals(2, queue.deleteByPathPrefixExcludingAcceptedSurvivors(
        root.toString(), List.of(survivor.toString())));
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(root.toString()));
    assertFalse(queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
        root.toString(), List.of(survivor.toString())));
    assertEquals(1, queue.deleteByExactPath(survivor.toString()));
    assertFalse(queue.hasNonterminalJobsByPathPrefixStrict(root.toString()));
  }

  @Test
  void excludedPrefixDeletionRetainsLiteralSurvivorAndLeavesWildcardNeighbors() {
    Path literalRoot = tempDir.resolve("folder_%");
    Path survivor = literalRoot.resolve("survivor.txt");
    Path wildcardSibling = tempDir.resolve("folderXanything").resolve("sibling.txt");
    Path boundarySibling = tempDir.resolve("folder_%-neighbor").resolve("sibling.txt");
    queue.enqueue(List.of(survivor, wildcardSibling, boundarySibling));

    assertEquals(0, queue.deleteByPathPrefixExcludingAcceptedSurvivors(
        literalRoot.toString(), List.of(survivor.toString())));
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(literalRoot.toString()));
    assertFalse(queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
        literalRoot.toString(), List.of(survivor.toString())));
    assertEquals(1, queue.deleteByPathPrefix(literalRoot.toString()));
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(wildcardSibling.getParent().toString()));
    assertTrue(queue.hasNonterminalJobsByPathPrefixStrict(boundarySibling.getParent().toString()));
  }

  @Test
  void capturedUnacknowledgedRowsStillBlockExcludedScopeRead() throws Exception {
    Path root = tempDir.resolve("captured-survivor");
    Files.createDirectories(root);
    Path captured = Files.writeString(root.resolve("captured.txt"), "captured bytes");
    var opened = queue.beginCapturedWalk("captured-survivor", "a".repeat(64), true);
    var entry = new JobQueue.EnqueueEntry(captured, Files.size(captured), null,
        SourceContentHash.sha256(captured));
    assertEquals(1, queue.enqueueRecordedEntries("captured-survivor", opened.enumerationEpoch(),
        List.of(entry), "scope"));

    assertEquals(1, queue.deleteByPathPrefixExcludingAcceptedSurvivors(root.toString(), List.of()));
    assertTrue(queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
        root.toString(), List.of()), "an unacknowledged captured row must remain blocking");
  }

  @Test
  void allTerminalRowsStillBlockWhenTheyAreNotAcceptedSurvivors() throws Exception {
    Path root = tempDir.resolve("terminal-scope");
    Path done = root.resolve("done.txt");
    Path failed = root.resolve("failed.txt");
    queue.enqueue(List.of(done, failed));
    setState(done, "DONE");
    setState(failed, "FAILED");

    assertTrue(queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(root.toString(), List.of()));
    assertFalse(queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
        root.toString(), List.of(done.toString(), failed.toString())));
  }

  @Test
  void collectionScopeMapsNullAndBlankLegacyRowsToDefault() throws Exception {
    Path nullCollection = tempDir.resolve("default-null.txt");
    Path blankCollection = tempDir.resolve("default-blank.txt");
    queue.enqueue(List.of(nullCollection, blankCollection));
    setCollection(nullCollection, null);
    setCollection(blankCollection, "   ");

    assertTrue(queue.hasNonterminalJobsByCollectionStrict("default"));
    setState(nullCollection, "DONE");
    assertTrue(queue.hasNonterminalJobsByCollectionStrict("default"),
        "blank legacy collection must map to the default bucket");
    setState(blankCollection, "DONE");
    assertFalse(queue.hasNonterminalJobsByCollectionStrict("default"));
    assertFalse(queue.hasNonterminalJobsByCollectionStrict("named-collection"));
    queue.enqueue(List.of(tempDir.resolve("named.txt")), "Named-Collection");
    assertFalse(queue.hasNonterminalJobsByCollectionStrict("named-collection"),
        "collection matching remains exact and case-sensitive");
    assertTrue(queue.hasNonterminalJobsByCollectionStrict("Named-Collection"));
    assertThrows(IllegalArgumentException.class,
        () -> queue.hasNonterminalJobsByCollectionStrict(null));
    assertThrows(IllegalArgumentException.class,
        () -> queue.hasNonterminalJobsByCollectionStrict("  "));
  }

  @Test
  void invalidPrefixAndClosedQueueRefuseRatherThanReportEmpty() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> queue.hasNonterminalJobsByPathPrefixStrict(null));
    assertThrows(IllegalArgumentException.class,
        () -> queue.hasNonterminalJobsByPathPrefixStrict("  "));
    queue.close();

    assertThrows(IllegalStateException.class,
        () -> queue.hasNonterminalJobsByPathPrefixStrict(tempDir.toString()));
    assertThrows(IllegalStateException.class,
        () -> queue.hasNonterminalJobsByCollectionStrict("default"));
    assertThrows(IllegalStateException.class,
        () -> queue.hasJobsByPathPrefixOutsideCertifiedReplayPaths(
            tempDir.toString(), List.of()));
    assertThrows(IllegalStateException.class,
        () -> queue.deleteByPathPrefixExcludingAcceptedSurvivors(tempDir.toString(), List.of()));
  }

  private void setState(Path path, String state) throws SQLException {
    String normalized = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        PreparedStatement update = raw.prepareStatement(
            "UPDATE jobs SET state = ? WHERE path = ?")) {
      update.setString(1, state);
      update.setString(2, normalized);
      assertTrue(update.executeUpdate() == 1, "expected one job row for " + path);
    }
  }

  private void setCollection(Path path, String collection) throws SQLException {
    String normalized = PathNormalizer.normalizePath(path.toAbsolutePath().toString());
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        PreparedStatement update = raw.prepareStatement(
            "UPDATE jobs SET collection = ? WHERE path = ?")) {
      update.setString(1, collection);
      update.setString(2, normalized);
      assertTrue(update.executeUpdate() == 1, "expected one job row for " + path);
    }
  }
}
