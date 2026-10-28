/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Strict snapshot/membership/discovery checks; queue certificates are covered against real SQLite. */
final class NativePolicyReplayBoundaryTest {
  @TempDir Path directory;
  private static final String CANDIDATE = "g-native-policy";

  private ConfigStore configuration(List<?> entries) {
    return new ConfigStore(ResolvedConfig.builder().contributeBaseSources()
        .putSettings("justsearch.data.dir", directory.resolve("data").toString())
        .putSettings("justsearch.index.base_path", directory.resolve("index").toString())
        .putSettings("justsearch.ui.exclude_patterns",
        JsonMapper.builder().build().writeValueAsString(entries)).build());
  }

  private NativePolicyReplay policy(ConfigStore configuration, List<ResolvedConfig.FileSource> roots) {
    var queue = mock(JobQueue.class, withSettings().extraInterfaces(SwitchBufferCapableQueue.class));
    var target = mock(RunningRuntime.class);
    when(target.openedIndexPath()).thenReturn(directory.resolve(CANDIDATE));
    var publisher = mock(WorkerIngestService.class);
    when(publisher.ownsForgetQueue(queue)).thenReturn(true);
    try {
      // Preserve the same pure declared-root fixture on the original baseline, where these
      // registry snapshot methods do not exist. No repaired behavior is supplied to that tree.
      var capture = RootWatcherRegistry.class.getDeclaredMethod("policyRoots");
      Object scope = capture.invoke(new RootWatcherRegistry());
      when(WorkerIngestService.class.getDeclaredMethod("nativePolicyRoots").invoke(publisher)).thenReturn(scope);
      var current = WorkerIngestService.class.getDeclaredMethod("nativePolicyRootScopeCurrent", scope.getClass(), Path.class, Path.class);
      var scopeCurrent = scope.getClass().getDeclaredMethod("current", Path.class, Path.class);
      when(current.invoke(publisher, any(), any(), any())).thenAnswer(call ->
          scopeCurrent.invoke(scope, call.getArgument(1), call.getArgument(2)));
    } catch (NoSuchMethodException baseline) {
      // Baseline still executes every original assertion and the new ancestor-directory oracle.
    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    return new NativePolicyReplay(configuration, configuration.publicationLock(), queue, target,
        publisher, CANDIDATE, roots);
  }

  private SyncDirectoryOps discovery() {
    var reads = mock(ReadPathOps.class);
    when(reads.search(any(), anyInt(), anySet(), any(), nullable(String.class)))
        .thenReturn(new LuceneRuntimeTypes.SearchResult(List.of(), 0, 0L));
    return new SyncDirectoryOps(reads, null, null, null, IndexingPacing.unthrottled(), null);
  }

  @Test
  void currentPublicationIdentityRejectsIdenticalPolicyAba() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    var literal = LiteralExclusion.create(root.resolve("private.txt"), LiteralExclusion.Scope.FILE);
    var store = configuration(List.of(literal));
    var roots = List.of(new ResolvedConfig.FileSource(root, null));
    var attempt = policy(store, roots);
    assertTrue(attempt.current());
    assertTrue(attempt.excludes(root.resolve("private.txt"), root, "default"));
    store.update(configuration(List.of()).currentSnapshot());
    store.update(configuration(List.of(literal)).currentSnapshot());
    assertFalse(attempt.current(), "equal serialized bytes cannot revive an old published snapshot");
    assertFalse(attempt.excludes(root.resolve("private.txt"), root, null));
    assertTrue(policy(store, roots).excludes(root.resolve("private.txt"), root, null));
  }

  @Test
  void defaultOwnerIsExactAndNestedIndependentRootsRemainOutsideParentAuthority() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path child = Files.createDirectory(root.resolve("child"));
    var attempt = policy(configuration(List.of("**/*.txt")), List.of(
        new ResolvedConfig.FileSource(root, null), new ResolvedConfig.FileSource(child, "other")));
    assertTrue(attempt.excludes(root.resolve("private.txt"), root, "default"));
    assertFalse(attempt.excludes(root.resolve("private.txt"), root, "other"));
    assertFalse(attempt.excludes(child.resolve("private.txt"), root, null));
    assertTrue(attempt.excludes(child.resolve("private.txt"), child, "other"));
    assertFalse(attempt.excludes(root, root, null));
    assertFalse(attempt.excludes(directory.resolve("outside.txt"), root, null));
  }

  @Test
  void fileLiteralAtANowDirectoryNeverPrunesItsEligibleChildren() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path formerFile = Files.createDirectory(root.resolve("x"));
    Path child = Files.writeString(formerFile.resolve("a.txt"), "eligible child");
    var attempt = policy(configuration(List.of(LiteralExclusion.create(formerFile, LiteralExclusion.Scope.FILE))),
        List.of(new ResolvedConfig.FileSource(root, null)));
    assertFalse(attempt.excludesDirectory(formerFile, root, null));
    try (var difference = discovery().discoverCandidateDifference(root.toString(), true, () -> false, attempt, null)) {
      assertEquals(List.of(child), difference.additions());
      assertTrue(difference.deletions().isEmpty());
    }
  }

  @Test @EnabledOnOs(OS.LINUX)
  void knownExcludedFileNeedsNoContentReadButEligibleUnreadableSiblingRemainsUnknown() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path hidden = Files.writeString(root.resolve("private.txt"), "do not read for omission");
    Path sibling = Files.writeString(root.resolve("sibling.txt"), "eligible");
    var attempt = policy(configuration(List.of(LiteralExclusion.create(hidden, LiteralExclusion.Scope.FILE))),
        List.of(new ResolvedConfig.FileSource(root, null)));
    var hiddenMode = Files.getPosixFilePermissions(hidden);
    var siblingMode = Files.getPosixFilePermissions(sibling);
    try {
      Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("---------"));
      assertFalse(Files.isReadable(hidden), "SETUP: real source content is unreadable");
      try (var difference = discovery().discoverCandidateDifference(root.toString(), true, () -> false, attempt, null)) {
        assertEquals(List.of(sibling), difference.additions());
        assertTrue(difference.deletions().isEmpty());
      }
      Files.setPosixFilePermissions(sibling, PosixFilePermissions.fromString("---------"));
      assertThrows(IOException.class,
          () -> discovery().discoverCandidateDifference(root.toString(), true, () -> false, attempt, null));
    } finally {
      Files.setPosixFilePermissions(hidden, hiddenMode); Files.setPosixFilePermissions(sibling, siblingMode);
    }
  }

  @Test @EnabledOnOs(OS.LINUX)
  void folderProofCanPruneItsSubtreeButCannotHideAnUnreadableRoot() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    Path subtree = Files.createDirectory(root.resolve("private"));
    Files.writeString(subtree.resolve("a.txt"), "excluded child");
    var roots = List.of(new ResolvedConfig.FileSource(root, null));
    var attempt = policy(configuration(List.of(LiteralExclusion.create(subtree, LiteralExclusion.Scope.FOLDER))), roots);
    var subtreeMode = Files.getPosixFilePermissions(subtree);
    var rootMode = Files.getPosixFilePermissions(root);
    try {
      Files.setPosixFilePermissions(subtree, PosixFilePermissions.fromString("---------"));
      assertTrue(attempt.excludesDirectory(subtree, root, null));
      try (var difference = assertDoesNotThrow(() -> discovery().discoverCandidateDifference(
          root.toString(), true, () -> false, attempt, null))) {
        assertTrue(difference.additions().isEmpty());
      }
      Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("---------"));
      assertThrows(IOException.class,
          () -> discovery().discoverCandidateDifference(root.toString(), true, () -> false, attempt, null));
    } finally {
      Files.setPosixFilePermissions(root, rootMode); Files.setPosixFilePermissions(subtree, subtreeMode);
    }
  }

  @Test
  void declaredAncestorDirectoryGlobPrunesOnlyWithTheExistingDescendantAuthority() throws Exception {
    Path ancestor = Files.createDirectory(directory.resolve("ancestor"));
    Path root = Files.createDirectory(ancestor.resolve("child"));
    Path subtree = Files.createDirectory(root.resolve("private"));
    Files.writeString(subtree.resolve("a.txt"), "excluded bytes");
    var roots = List.of(new ResolvedConfig.FileSource(ancestor, "ancestor"),
        new ResolvedConfig.FileSource(root, "nested"));
    var folder = policy(configuration(List.of("child/private/**")), roots);
    assertTrue(folder.excludesDirectory(subtree, root, "nested"));
    try (var difference = discovery().discoverCandidateDifference(root.toString(), true, () -> false, folder, "nested")) {
      assertTrue(difference.additions().isEmpty());
    }
    assertFalse(policy(configuration(List.of("child/private/*.txt")), roots).excludesDirectory(subtree, root, "nested"));
    assertFalse(policy(configuration(List.of(LiteralExclusion.create(subtree, LiteralExclusion.Scope.FILE))), roots)
        .excludesDirectory(subtree, root, "nested"));
  }

  @Test
  void malformedPolicyOrUnsharedPublicationOwnerHasNoOmissionAuthority() throws Exception {
    Path root = Files.createDirectory(directory.resolve("root"));
    var malformed = new ConfigStore(ResolvedConfig.builder().contributeBaseSources()
        .putSettings("justsearch.data.dir", directory.resolve("data").toString())
        .putSettings("justsearch.index.base_path", directory.resolve("index").toString())
        .putSettings("justsearch.ui.exclude_patterns", "{\"not\":\"an array\"}").build());
    assertThrows(IllegalArgumentException.class, () -> policy(malformed, List.of(new ResolvedConfig.FileSource(root, null))));
    var queue = mock(JobQueue.class, withSettings().extraInterfaces(SwitchBufferCapableQueue.class));
    assertThrows(IllegalArgumentException.class, () -> new NativePolicyReplay(configuration(List.of()),
        new ReentrantReadWriteLock(), queue, mock(RunningRuntime.class), mock(WorkerIngestService.class),
        CANDIDATE, List.of(new ResolvedConfig.FileSource(root, null))));
  }
}
