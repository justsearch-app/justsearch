package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileOperationsToolTest {

  @TempDir Path tempDir;

  private Path root;
  private AtomicReference<Map<Path, Path>> capturedMappings;
  // Tempdoc 875 §C.6 — the indexed roots are a live lookup, not a constant: a user can remove a
  // watched root between an operation and its undo. Holding them in a reference lets a test
  // reproduce that without changing what any existing test sees (it starts as List.of(root)).
  private AtomicReference<List<Path>> indexedRoots;
  private FileOperationsTool tool;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("indexed");
    Files.createDirectories(root);
    capturedMappings = new AtomicReference<>();
    indexedRoots = new AtomicReference<>(List.of(root));
    tool =
        new FileOperationsTool(
            context -> indexedRoots.get(),
            (pathMappings, context) -> {
              capturedMappings.set(pathMappings);
              return pathMappings.size();
            },
            new FileOperationLog(tempDir.resolve("data").resolve("file-operations")));
  }

  @Test
  void executeMoveWithValidJson() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("moved.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move file"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Move should succeed: " + result.message());
    assertNotNull(result.executionId(), "Batch ID should be set");
    assertTrue(result.message().contains("successfully"));
    assertFalse(Files.exists(src));
    assertTrue(Files.exists(dest));
    assertNotNull(capturedMappings.get(), "Index update should have been called");
  }

  @Test
  void executeMkdirWithValidJson() {
    Path dest = root.resolve("new-folder");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
    assertNull(capturedMappings.get(), "MKDIR should not trigger index update");
  }

  @Test
  void executeMkdirAcceptsPathAliasForDestination() {
    // 543-fwd UPDATE 10 P2 — smaller local models routinely emit `path` instead of the
    // schema's canonical `destination` (this is the exact shape that NPE'd live).
    Path dest = root.resolve("alias-folder");

    String json =
        """
        {"operations": [{"op": "MKDIR", "path": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
  }

  @Test
  void executeMissingDestinationReturnsCleanValidationError() {
    // Untrusted agent input — a missing field must yield a clear, self-correcting
    // message, NEVER a NullPointerException ("Cannot invoke ... because ... is null").
    OperationResult result = tool.execute("{\"operations\": [{\"op\": \"MKDIR\"}]}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(
        result.message().contains("missing required field 'destination'"), result.message());
    assertFalse(result.message().contains("Cannot invoke"), result.message());
  }

  @Test
  void executeUnknownOpReturnsCleanValidationError() {
    Path dest = root.resolve("x");
    String json =
        """
        {"operations": [{"op": "FROBNICATE", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("unknown op 'FROBNICATE'"), result.message());
    assertFalse(result.message().contains("No enum constant"), result.message());
  }

  @Test
  void executeDoesNotMaskDeeperIllegalArgumentAsValidationError() {
    // Fix A — the arg-validation catch is scoped to OperationArgException. A genuine
    // IllegalArgumentException raised deeper (here InvalidPathException, an
    // IllegalArgumentException, from Path.of on a NUL-bearing path) must fall through to
    // the logged "Execution error" handler, NOT be relabeled as a clean validation error.
    String json = "{\"operations\": [{\"op\": \"MKDIR\", \"destination\": \"bad\\u0000path\"}]}";
    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("Execution error"), result.message());
    assertFalse(result.message().contains("missing required field"), result.message());
  }

  @Test
  void executeEmptyOperationsReturnsFailure() {
    OperationResult result = tool.execute("{\"operations\": []}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No operations"));
  }

  @Test
  void executeMissingOperationsReturnsFailure() {
    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No operations"));
  }

  @Test
  void executeInvalidJsonReturnsFailure() {
    OperationResult result = tool.execute("not json", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("Execution error") || result.message().contains("error"));
  }

  @Test
  void executeValidationFailureReturnsDetails() {
    // Source doesn't exist
    Path src = root.resolve("no-such-file.txt");
    Path dest = root.resolve("dest.txt");

    String json =
        """
        {"operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("Validation failed"));
    assertTrue(result.message().contains("SOURCE_MISSING"));
  }

  @Test
  void executeMultipleOperations() throws IOException {
    // Pre-create directory since validation runs before execution
    Path dir = root.resolve("sub");
    Files.createDirectories(dir);
    Path src = root.resolve("file.txt");
    Files.writeString(src, "hello");
    Path dest = root.resolve("sub").resolve("file.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move file into sub directory"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"),
                dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(Files.exists(dest));
    assertEquals("hello", Files.readString(dest));
  }

  @Test
  void executeBatchSizeLimitExceeded() {
    var sb = new StringBuilder("{\"operations\": [");
    for (int i = 0; i <= FileOperationsTool.MAX_BATCH_SIZE; i++) {
      if (i > 0) sb.append(",");
      String dest = root.resolve("dir-" + i).toString().replace("\\", "\\\\");
      sb.append("{\"op\": \"MKDIR\", \"destination\": \"").append(dest).append("\"}");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString(), EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("exceeds limit"), result.message());
    assertTrue(result.message().contains(String.valueOf(FileOperationsTool.MAX_BATCH_SIZE)));
  }

  @Test
  void executeBatchSizeAtLimitSucceeds() {
    var sb = new StringBuilder("{\"operations\": [");
    for (int i = 0; i < FileOperationsTool.MAX_BATCH_SIZE; i++) {
      if (i > 0) sb.append(",");
      String dest = root.resolve("limit-dir-" + i).toString().replace("\\", "\\\\");
      sb.append("{\"op\": \"MKDIR\", \"destination\": \"").append(dest).append("\"}");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Exactly MAX_BATCH_SIZE should succeed: " + result.message());
  }

  // Tempdoc 877 §2.1: `schemaBatchLimitMatchesConstant` is gone, not relocated intact. The
  // tool-local schema constant it asserted against is deleted, and the catalog description that
  // replaced it INTERPOLATES MAX_BATCH_SIZE — so "the schema states the constant" is true by
  // construction and cannot be tested. What survives, in AgentToolCatalogContractTest
  // (app-services), is the falsifiable half: `operations.maxItems == null`, i.e. the limit rides
  // the description so this tool's own over-size message is the one the model sees. The limit
  // itself is enforced HERE, by executeBatchSizeLimitExceeded above.

  // ===== Undo tests =====

  @Test
  void undoMoveRestoresSourceFile() throws IOException {
    Path src = root.resolve("original.txt");
    Files.writeString(src, "precious data");
    Path dest = root.resolve("moved.txt");

    String json =
        """
        {
          "operations": [
            {"op": "MOVE", "source": "%s", "destination": "%s"}
          ],
          "explanation": "Move for undo test"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult moveResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(moveResult.success(), "Move should succeed: " + moveResult.message());
    assertFalse(Files.exists(src));
    assertTrue(Files.exists(dest));

    // Now undo
    OperationResult undoResult = tool.undo(moveResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo should succeed: " + undoResult.message());
    assertTrue(Files.exists(src), "Source should be restored after undo");
    assertFalse(Files.exists(dest), "Destination should be removed after undo");
    assertEquals("precious data", Files.readString(src));
  }

  @Test
  void undoCopyDeletesCopiedFile() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.exists(dest));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo COPY should succeed: " + undoResult.message());
    assertTrue(Files.exists(src), "Original source should remain");
    assertFalse(Files.exists(dest), "Copied file should be deleted");
  }

  @Test
  void undoSkipsACopyTargetTheUserEditedSinceTheAgentActed() throws IOException {
    // Tempdoc 577 §2.14 Root III (#16) — conflict-detection: undoing a COPY normally DELETES the
    // copy. If the user edited that copy after the agent created it, a blind delete would destroy
    // their work. The undo must detect the since-edit (mtime > recorded action time) and SKIP it.
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.exists(dest));

    // The user edits the copy LATER (well past the conflict tolerance) — simulate by writing new
    // content and stamping its mtime comfortably after the recorded op time.
    Files.writeString(dest, "the user's own edits");
    Files.setLastModifiedTime(
        dest, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(120)));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo should still succeed (partial): " + undoResult.message());
    assertTrue(
        Files.exists(dest), "A since-edited copy must NOT be blindly deleted by undo");
    assertEquals(
        "the user's own edits", Files.readString(dest), "The user's edit must be preserved");
    assertTrue(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "Undo must report the conflict-skipped target: " + undoResult.message());
  }

  @Test
  void undoStillRevertsACopyTargetTheUserDidNotTouch() throws IOException {
    // The conflict guard must not over-fire: an untouched target still reverts normally (the COPY
    // undo deletes it). This pins that the modified-since check does not flag the agent's own write.
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");

    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult copyResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(copyResult.success(), copyResult.message());

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), undoResult.message());
    assertFalse(
        Files.exists(dest), "An untouched copy reverts normally (no false conflict)");
    assertFalse(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "No conflict should be reported for an untouched target: " + undoResult.message());
  }

  // --- Directory-copy undo (tempdoc 875 §C.6 / finding 2) ---

  /** Creates {@code <root>/tree} holding one nested file, and returns it. */
  private Path createSourceTree() throws IOException {
    Path tree = root.resolve("tree");
    Files.createDirectories(tree.resolve("nested"));
    Files.writeString(tree.resolve("nested").resolve("note.txt"), "original");
    return tree;
  }

  private OperationResult copyTreeTo(Path source, Path dest) {
    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                source.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));
    OperationResult copyResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.isDirectory(dest), "Precondition: the directory copy exists");
    return copyResult;
  }

  @Test
  void undoOfACopiedDirectoryOutsideTheRootsIsSkippedNotDeleted() throws IOException {
    // Undoing a COPY is a RECURSIVE DELETE. The MOVE/RENAME arm re-validates through
    // executor.validate(..., EngineContextTestFixtures.AGENT_LOOP); before 875 the COPY arm deleted with no containment check at all, so
    // a root removed between the operation and the undo left undo deleting outside the sandbox.
    Path source = createSourceTree();
    Path dest = root.resolve("tree-copy");
    OperationResult copyResult = copyTreeTo(source, dest);

    // The user re-points the indexed roots elsewhere; the copy is now outside the sandbox.
    Path otherRoot = Files.createDirectories(tempDir.resolve("other-indexed"));
    indexedRoots.set(List.of(otherRoot));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo should still succeed (partial): " + undoResult.message());
    assertTrue(
        Files.isDirectory(dest),
        "A copy outside the indexed roots must NOT be recursively deleted by undo");
    assertTrue(
        Files.exists(dest.resolve("nested").resolve("note.txt")),
        "The tree's contents must survive: " + undoResult.message());
    assertTrue(
        undoResult.message().contains("outside the indexed root folders"),
        "Undo must report the skipped out-of-roots target: " + undoResult.message());
  }

  @Test
  void undoOfACopiedDirectoryWithANestedEditIsReportedAsChangedSince() throws IOException {
    // A directory's own mtime tracks entry add/remove, NOT edits to files inside it. Before 875 the
    // conflict check stat'ed only the directory, so a copied tree whose nested file the user edited
    // read as untouched and was deleted recursively.
    Path source = createSourceTree();
    Path dest = root.resolve("tree-copy");
    OperationResult copyResult = copyTreeTo(source, dest);

    Path nested = dest.resolve("nested").resolve("note.txt");
    Files.writeString(nested, "the user's own edits");
    Files.setLastModifiedTime(
        nested, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(120)));

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo should still succeed (partial): " + undoResult.message());
    assertTrue(
        Files.isDirectory(dest),
        "A tree with a since-edited nested file must NOT be recursively deleted");
    assertEquals(
        "the user's own edits", Files.readString(nested), "The user's edit must be preserved");
    assertTrue(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "Undo must report the conflict-skipped tree: " + undoResult.message());
  }

  @Test
  void undoOfAnUntouchedCopiedDirectorySucceeds() throws IOException {
    // The two guards above must not over-fire: an in-root, untouched copied tree still reverts.
    Path source = createSourceTree();
    Path dest = root.resolve("tree-copy");
    OperationResult copyResult = copyTreeTo(source, dest);

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), undoResult.message());
    assertFalse(Files.exists(dest), "An untouched in-root copied directory reverts: " + undoResult.message());
    assertTrue(Files.isDirectory(source), "The original tree must remain");
    assertFalse(
        undoResult.message().contains("outside the indexed root folders"),
        "No containment skip should be reported: " + undoResult.message());
    assertFalse(
        undoResult.message().toLowerCase(java.util.Locale.ROOT).contains("changed since"),
        "No conflict should be reported for an untouched tree: " + undoResult.message());
  }

  @Test
  void undoMkdirDeletesEmptyDirectory() throws IOException {
    Path dest = root.resolve("undo-dir");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult mkdirResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(mkdirResult.success(), mkdirResult.message());
    assertTrue(Files.isDirectory(dest));

    OperationResult undoResult = tool.undo(mkdirResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo MKDIR should succeed: " + undoResult.message());
    assertFalse(Files.exists(dest), "Empty directory should be removed");
  }

  @Test
  void undoMkdirSkipsNonEmptyDirectory() throws IOException {
    Path dest = root.resolve("undo-dir-nonempty");

    String json =
        """
        {"operations": [{"op": "MKDIR", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult mkdirResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(mkdirResult.success(), mkdirResult.message());

    // Put a file inside so it's non-empty
    Files.writeString(dest.resolve("child.txt"), "can't delete parent");

    OperationResult undoResult = tool.undo(mkdirResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undoResult.success(), "Undo should succeed (skipping non-empty dir): " + undoResult.message());
    assertTrue(Files.isDirectory(dest), "Non-empty directory should remain");
    assertTrue(undoResult.message().contains("skipped"), "Output should mention skipped: " + undoResult.message());
  }

  // --- Tempdoc 909 items 7/8: a COPY-undo deletes only what it can prove it wrote ---

  /**
   * The defect this closes. {@code modifiedSince} compares mtime against the recorded action time,
   * so a write that preserves the timestamp — an editor that restores it, a restore-from-backup, a
   * sync client writing the server's mtime, a filesystem whose granularity swallowed the edit — was
   * invisible, and the undo deleted the user's content. Content identity answers directly.
   */
  @Test
  void undoRefusesToDeleteACopyWhoseContentChangedUnderAnUnchangedMtime() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");
    OperationResult copyResult = copyFileTo(src, dest);

    java.nio.file.attribute.FileTime asCopied = Files.getLastModifiedTime(dest);
    Files.writeString(dest, "the user's own edits, written with the original timestamp");
    Files.setLastModifiedTime(dest, asCopied); // the mtime guard now sees nothing

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(undoResult.success(), "undo still succeeds (partial): " + undoResult.message());
    assertTrue(Files.exists(dest), "a copy whose CONTENT changed must not be deleted");
    assertEquals(
        "the user's own edits, written with the original timestamp",
        Files.readString(dest),
        "the user's bytes must survive the undo");
    assertTrue(
        undoResult.message().contains("no longer hold the content the agent copied"),
        "undo must say why it refused: " + undoResult.message());
  }

  /** The same guarantee for a copied TREE, whose undo is a recursive delete. */
  @Test
  void undoRefusesToDeleteACopiedTreeWhoseNestedContentChanged() throws IOException {
    Path source = root.resolve("tree");
    Files.createDirectories(source.resolve("nested"));
    Files.writeString(source.resolve("nested").resolve("note.txt"), "original");
    Path dest = root.resolve("tree-copy");
    OperationResult copyResult = copyFileTo(source, dest);

    Path copiedNote = dest.resolve("nested").resolve("note.txt");
    java.nio.file.attribute.FileTime asCopied = Files.getLastModifiedTime(copiedNote);
    Files.writeString(copiedNote, "the user's own edit inside the copied tree");
    Files.setLastModifiedTime(copiedNote, asCopied);

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(Files.isDirectory(dest), "the tree must not be recursively deleted");
    assertEquals("the user's own edit inside the copied tree", Files.readString(copiedNote));
    assertTrue(
        undoResult.message().contains("no longer hold the content the agent copied"),
        "undo must say why it refused: " + undoResult.message());
  }

  /**
   * A journal written before the digest existed cannot prove anything about the file on disk. The
   * conservative branch is the default one: preserve, and tell the user the app could not verify it.
   */
  @Test
  void undoPreservesACopyRecordedByALegacyJournalWithNoContentIdentity() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");
    OperationResult copyResult = copyFileTo(src, dest);
    String batchId = copyResult.executionId().orElseThrow();
    downgradeJournalToV1(batchId);

    OperationResult undoResult = tool.undo(batchId, EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(undoResult.success(), "undo still succeeds (partial): " + undoResult.message());
    assertTrue(Files.exists(dest), "an unverifiable copy must be preserved, not deleted");
    assertEquals("copy me", Files.readString(dest));
    assertTrue(
        undoResult.message().contains("could not be verified against the operation log"),
        "undo must name the unverifiable target: " + undoResult.message());
  }

  /** The guard must not over-fire: an untouched copy still reverts, and its identity was recorded. */
  @Test
  void undoStillDeletesACopyWhoseContentIsUnchanged() throws IOException {
    Path src = root.resolve("source.txt");
    Files.writeString(src, "copy me");
    Path dest = root.resolve("copied.txt");
    OperationResult copyResult = copyFileTo(src, dest);

    var executed = executedEntries(copyResult.executionId().orElseThrow());
    assertTrue(
        String.valueOf(executed.get(0).get("destinationDigest")).startsWith("sha256:"),
        "the forward COPY must record what it left at the destination: " + executed);

    OperationResult undoResult = tool.undo(copyResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(undoResult.success(), undoResult.message());
    assertFalse(Files.exists(dest), "an unchanged copy reverts normally");
    assertFalse(
        undoResult.message().contains("no longer hold the content"),
        "no false refusal for an untouched copy: " + undoResult.message());
  }

  private OperationResult copyFileTo(Path source, Path dest) {
    String json =
        """
        {"operations": [{"op": "COPY", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                source.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));
    OperationResult copyResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(copyResult.success(), copyResult.message());
    assertTrue(Files.exists(dest), "precondition: the copy exists");
    return copyResult;
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> executedEntries(String batchId) throws IOException {
    Path file =
        tempDir.resolve("data").resolve("file-operations").resolve(batchId + ".json");
    var mapper = new tools.jackson.databind.ObjectMapper();
    Map<String, Object> log = mapper.readValue(file.toFile(), Map.class);
    return (List<Map<String, Object>>) log.get("executed");
  }

  /** Rewrite a just-written journal as the v1 shape an earlier install produced. */
  @SuppressWarnings("unchecked")
  private void downgradeJournalToV1(String batchId) throws IOException {
    Path file =
        tempDir.resolve("data").resolve("file-operations").resolve(batchId + ".json");
    var mapper = new tools.jackson.databind.ObjectMapper();
    Map<String, Object> log = mapper.readValue(file.toFile(), Map.class);
    log.put("schemaVersion", 1);
    for (Map<String, Object> entry : (List<Map<String, Object>>) log.get("executed")) {
      entry.remove("destinationDigest");
    }
    Files.writeString(file, mapper.writeValueAsString(log));
  }

  @Test
  void undoMissingBatchReturnsFailure() {
    OperationResult result = tool.undo("nonexistent-batch-id", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No operation log"));
  }

  @Test
  void undoUnfinalizedBatchReturnsFailure() throws IOException {
    // Manually create an unfinalized batch in the log
    Path src = root.resolve("unfin-src.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("unfin-dest.txt");

    // Start a batch but don't finalize it via the log directly
    var log = new FileOperationLog(tempDir.resolve("data").resolve("file-operations"));
    log.startBatch("unfin-batch", "test", List.of(
        new FileOperation(FileOperation.OpType.MOVE, src, dest)));
    log.recordSuccess("unfin-batch", 0, null);
    // Note: no log.finalizeBatch()

    OperationResult result = tool.undo("unfin-batch", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("unfinalized"), result.message());
  }

  @Test
  void undoCalledTwiceSecondIsNoOp() throws IOException {
    Path src = root.resolve("idem-src.txt");
    Files.writeString(src, "data");
    Path dest = root.resolve("idem-dest.txt");

    String json =
        """
        {"operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}]}
        """
            .formatted(
                src.toString().replace("\\", "\\\\"),
                dest.toString().replace("\\", "\\\\"));

    OperationResult moveResult = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(moveResult.success(), moveResult.message());
    assertTrue(Files.exists(dest));

    // First undo — restores file
    OperationResult undo1 = tool.undo(moveResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(undo1.success(), "First undo should succeed: " + undo1.message());
    assertTrue(Files.exists(src), "Source should be restored");
    assertFalse(Files.exists(dest), "Dest should be removed");

    // Second undo — dest no longer exists so the reverse MOVE fails.
    // This is correct: undo is not idempotent, the files have already been restored.
    OperationResult undo2 = tool.undo(moveResult.executionId().orElseThrow(), EngineContextTestFixtures.AGENT_LOOP);
    // Should not crash (no exception), but may report failures for individual operations
    assertNotNull(undo2, "Second undo should return a result, not crash");
  }

  // ===== Conflict strategy tests (via tool JSON) =====

  @Test
  void executeWithSkipConflictStrategy() throws IOException {
    Path src = root.resolve("skip-src.txt");
    Files.writeString(src, "new");
    Path dest = root.resolve("skip-dest.txt");
    Files.writeString(dest, "existing");

    String json =
        """
        {
          "operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}],
          "conflict_strategy": "SKIP"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("skipped"), "Output should mention skipped: " + result.message());
    assertTrue(Files.exists(src), "Source should remain (skipped)");
    assertEquals("existing", Files.readString(dest), "Dest should be unchanged");
  }

  @Test
  void executeWithAutoSuffixConflictStrategy() throws IOException {
    Path src = root.resolve("suffix-src.txt");
    Files.writeString(src, "new content");
    Path dest = root.resolve("suffix-dest.txt");
    Files.writeString(dest, "existing content");

    String json =
        """
        {
          "operations": [{"op": "MOVE", "source": "%s", "destination": "%s"}],
          "conflict_strategy": "AUTO_SUFFIX"
        }
        """
            .formatted(
                src.toString().replace("\\", "\\\\"), dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertFalse(Files.exists(src), "Source should be moved");
    assertEquals("existing content", Files.readString(dest), "Original dest unchanged");

    Path suffixed = root.resolve("suffix-dest (1).txt");
    assertTrue(Files.exists(suffixed), "Auto-suffixed file should exist");
    assertEquals("new content", Files.readString(suffixed));
  }

  @Test
  void executeWithInvalidConflictStrategy() {
    Path dest = root.resolve("dir");
    String json =
        """
        {
          "operations": [{"op": "MKDIR", "destination": "%s"}],
          "conflict_strategy": "INVALID"
        }
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("Invalid conflict_strategy"));
  }

  @Test
  void executeCaseInsensitiveOpType() throws IOException {
    Path dest = root.resolve("case-dir");

    String json =
        """
        {"operations": [{"op": "mkdir", "destination": "%s"}]}
        """
            .formatted(dest.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json, EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(Files.isDirectory(dest));
  }
}
