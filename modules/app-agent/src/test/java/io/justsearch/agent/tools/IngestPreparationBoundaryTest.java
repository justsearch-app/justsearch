package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.agent.EngineContextTestFixtures;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestPreparationBoundaryTest {

  private static final InvocationProvenance PROVENANCE =
      InvocationProvenance.agentLoop(Instant.EPOCH);
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  @TempDir Path tempDir;

  @Test
  void malformedNullAndNonObjectArgumentsRefuseAsBadRequest() {
    assertBadRequest("{", "Invalid ingest arguments");
    assertBadRequest("null", "Ingest arguments must be a JSON object");
    assertBadRequest("[]", "Ingest arguments must be a JSON object");
    assertBadRequest("\"paths\"", "Ingest arguments must be a JSON object");
  }

  @Test
  void pathsMustBeACompleteNonEmptyArrayWithinTheBatchLimit() {
    assertBadRequest("{}", "paths must contain between 1 and 100 entries");
    assertBadRequest("{\"paths\":null}", "paths must contain between 1 and 100 entries");
    assertBadRequest(
        json(Map.of("paths", "note.md")), "paths must contain between 1 and 100 entries");
    assertBadRequest(
        json(Map.of("paths", List.of())), "paths must contain between 1 and 100 entries");
    assertBadRequest("{\"paths\":[null]}", "Each ingest path must be a non-empty string");
    assertBadRequest(
        json(Map.of("paths", List.of("  "))), "Each ingest path must be a non-empty string");
    assertBadRequest(
        json(Map.of("paths", List.of(Map.of("path", "note.md")))),
        "Each ingest path must be a non-empty string");

    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i <= 100; i++) {
      tooMany.add("file-" + i + ".md");
    }
    assertBadRequest(
        json(Map.of("paths", tooMany)), "paths must contain between 1 and 100 entries");
  }

  @Test
  void explicitCollectionMustBeNonBlankStringAndNotReserved() throws IOException {
    Path file = Files.writeString(tempDir.resolve("note.md"), "content");
    List<String> paths = List.of(file.toString());

    assertBadRequest(json(Map.of("paths", paths, "collection", "   ")),
        "collection must be a non-empty string when supplied");
    for (String reserved : List.of("agent-history", "AGENT-HISTORY", "justsearch-help")) {
      assertBadRequest(json(Map.of("paths", paths, "collection", reserved)),
          "is reserved for app-internal documents");
    }
    assertBadRequest(json(Map.of("paths", paths, "collection", 17)), "collection must be a string");
  }

  @Test
  void explicitCollectionLengthBoundaryIsCheckedBeforePreparationSnapshots() throws IOException {
    Path file = Files.writeString(tempDir.resolve("note.md"), "content");
    AtomicInteger rootReads = new AtomicInteger();
    AtomicInteger exclusionReads = new AtomicInteger();
    AtomicInteger generationReads = new AtomicInteger();
    IngestTool tool = new IngestTool(RecordedIngestionService.unavailable(),
        ignored -> {
          rootReads.incrementAndGet();
          return List.of();
        },
        ignored -> {
          generationReads.incrementAndGet();
          return "generation-1";
        },
        () -> {
          exclusionReads.incrementAndGet();
          return List.of();
        });

    OperationPreparationRefused refused = assertThrows(OperationPreparationRefused.class,
        () -> prepare(tool, json(Map.of("paths", List.of(file.toString()),
            "collection", "x".repeat(RecordedRootPlan.MAX_COLLECTION_LENGTH + 1)))));
    assertFalse(refused.refusal().success());
    assertEquals("BAD_REQUEST", refused.refusal().errorCode().orElseThrow());
    assertTrue(refused.refusal().message().contains("at most 256 characters"),
        refused.refusal().message());
    assertEquals(
        0, rootReads.get(), "invalid collection must be refused before reading watched roots");
    assertEquals(
        0, exclusionReads.get(), "invalid collection must be refused before reading exclusions");
    assertEquals(
        0, generationReads.get(), "invalid collection must be refused before reading generation");

    String maximumCollection = "x".repeat(RecordedRootPlan.MAX_COLLECTION_LENGTH);
    OperationPreparation accepted = prepare(tool,
        json(Map.of("paths", List.of(file.toString()), "collection", maximumCollection)));
    assertEquals(
        maximumCollection,
        RecordedRootPlan.fromReplayPayload(accepted.replayPayloadJson()).roots().get(0).collection());
    assertEquals(1, rootReads.get());
    assertEquals(1, exclusionReads.get());
    assertEquals(1, generationReads.get());
  }

  @Test
  void absoluteMixedFilesFreezeOutOfRootNullableAndDeepestWatchedCollections() throws IOException {
    Path watched = Files.createDirectories(tempDir.resolve("watched"));
    Path deepest = Files.createDirectories(watched.resolve("private"));
    Path nullableRoot = Files.createDirectories(tempDir.resolve("unlabelled"));
    Path watchedFile = Files.writeString(watched.resolve("public.md"), "public");
    Path deepestFile = Files.writeString(deepest.resolve("private.md"), "private");
    Path nullableFile = Files.writeString(nullableRoot.resolve("default.md"), "default");
    Path outsideFile = Files.writeString(tempDir.resolve("outside.md"), "outside");

    List<RootBinding> bindings = List.of(
        new RootBinding(watched, "watched-docs"),
        new RootBinding(deepest, "private-docs"),
        new RootBinding(nullableRoot, null));
    OperationPreparation prepared = prepare(
        tool(bindings),
        json(Map.of("paths", List.of(
            watchedFile.toString(), deepestFile.toString(), nullableFile.toString(),
            outsideFile.toString()))));
    RecordedRootPlan plan = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());
    Map<Path, String> collectionByPath = new LinkedHashMap<>();
    plan.roots().forEach(root -> collectionByPath.put(root.path(), root.collection()));

    assertEquals(4, plan.roots().size(), "all requested files must remain in the mixed plan");
    assertTrue(plan.roots().stream().allMatch(RecordedRootPlan.Root::singleFile));
    assertEquals("watched-docs", collectionByPath.get(watchedFile.toAbsolutePath().normalize()));
    assertEquals("private-docs", collectionByPath.get(deepestFile.toAbsolutePath().normalize()),
        "the deepest watched root owns its nested file");
    assertTrue(collectionByPath.containsKey(nullableFile.toAbsolutePath().normalize()));
    assertNull(collectionByPath.get(nullableFile.toAbsolutePath().normalize()),
        "a nullable watched label remains the index-default collection");
    assertEquals(IngestCollectionPolicy.OUT_OF_ROOT,
        collectionByPath.get(outsideFile.toAbsolutePath().normalize()));
  }

  @Test
  void relativeRootNamePrefixAndRootLocalPathsResolveAgainstWatchedRoots() throws IOException {
    Path docsRoot = Files.createDirectories(tempDir.resolve("docs"));
    Path namedFile = docsRoot.resolve("explanation").resolve("overview.md");
    Files.createDirectories(namedFile.getParent());
    Files.writeString(namedFile, "named");
    Path localRoot = Files.createDirectories(tempDir.resolve("workspace"));
    Path localFile = localRoot.resolve("src").resolve("readme.md");
    Files.createDirectories(localFile.getParent());
    Files.writeString(localFile, "local");
    IngestTool tool = tool(List.of(
        new RootBinding(docsRoot, "docs"), new RootBinding(localRoot, "code")));

    RecordedRootPlan namedPlan = RecordedRootPlan.fromReplayPayload(
        prepare(tool, json(Map.of("paths", List.of("docs/explanation/overview.md"))))
            .replayPayloadJson());
    RecordedRootPlan localPlan = RecordedRootPlan.fromReplayPayload(
        prepare(tool, json(Map.of("paths", List.of("src/readme.md"))))
            .replayPayloadJson());

    assertEquals(namedFile.toAbsolutePath().normalize(), namedPlan.roots().get(0).path(),
        "a leading root-name component is stripped before resolving beneath that root");
    assertEquals(localFile.toAbsolutePath().normalize(), localPlan.roots().get(0).path(),
        "an existing relative path resolves beneath a watched root");
  }

  @Test
  void symbolicLinkInputIsRefusedWithoutFollowingIt() throws IOException {
    Path target = Files.writeString(tempDir.resolve("target.md"), "content");
    Path link = tempDir.resolve("linked.md");
    createSymlinkOrSkip(link, target);

    assertBadRequest(json(Map.of("paths", List.of(link.toString()))),
        "Path is not a readable regular file or directory");
  }

  @Test
  void unreadableRegularFileIsRefused() throws IOException {
    Path file = Files.writeString(tempDir.resolve("unreadable.md"), "content");
    assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"),
        "this filesystem does not expose POSIX permissions");
    var permissions = Files.getPosixFilePermissions(file);
    try {
      Files.setPosixFilePermissions(file, java.util.Set.of());
      assumeTrue(!Files.isReadable(file), "this process can bypass file read permissions");
      assertBadRequest(json(Map.of("paths", List.of(file.toString()))),
          "Path is not a readable regular file or directory");
    } finally {
      Files.setPosixFilePermissions(file, permissions);
    }
  }

  @Test
  void socketInputIsRefusedAsAnUnsupportedFileType() throws IOException {
    Path socketPath = tempDir.resolve("ingest.sock");
    java.nio.channels.ServerSocketChannel socket;
    try {
      socket = java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
    } catch (UnsupportedOperationException unsupported) {
      assumeTrue(false, "Unix-domain sockets are unsupported: " + unsupported);
      return;
    }
    try (socket) {
      socket.bind(java.net.UnixDomainSocketAddress.of(socketPath));
      assertTrue(Files.readAttributes(socketPath, java.nio.file.attribute.BasicFileAttributes.class,
          java.nio.file.LinkOption.NOFOLLOW_LINKS).isOther(), "fixture must be a special file");
      assertBadRequest(json(Map.of("paths", List.of(socketPath.toString()))),
          "Path is not a readable regular file or directory");
    }
  }

  @Test
  void validationRefusesForcedRootsAndUnsupportedSchemaOrContent() throws IOException {
    Path file = Files.writeString(tempDir.resolve("note.md"), "content");
    IngestTool tool = tool(List.of());
    OperationPreparation prepared = prepare(tool, json(Map.of("paths", List.of(file.toString()))));
    RecordedRootPlan originalPlan =
        RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());
    RecordedRootPlan.Root original = originalPlan.roots().get(0);
    RecordedRootPlan forcedPlan = new RecordedRootPlan(originalPlan.generation(), List.of(
        new RecordedRootPlan.Root(
            original.path(), original.collection(), true, original.singleFile(),
            original.excludePatterns(), original.excludedSubtrees())));

    assertInvalidPreparedPlan("Ingest preparation must contain non-forced roots",
        new OperationPreparation(prepared.argumentsJson(), RecordedRootPlan.SCHEMA,
            forcedPlan.toReplayPayload()), tool);
    assertInvalidPreparedPlan("Unsupported ingest preparation",
        new OperationPreparation(prepared.argumentsJson(), RecordedRootPlan.SCHEMA + "-v2",
            prepared.replayPayloadJson()), tool);
    assertInvalidPreparedPlan("Unsupported ingest preparation",
        new OperationPreparation(prepared.argumentsJson(), prepared.replaySchema(),
            prepared.replayPayloadJson(), OperationPreparation.Content.CONTENT), tool);
  }

  @Test
  void approvalPreviewUsesBoundedFrozenPathsAfterInputsAreDeleted() throws IOException {
    List<Path> files = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      files.add(Files.writeString(tempDir.resolve("file-" + i + ".md"), "content"));
    }
    IngestTool tool = tool(List.of());
    OperationPreparation prepared = prepare(
        tool, json(Map.of("paths", files.stream().map(Path::toString).toList())));
    for (Path file : files) {
      Files.delete(file);
    }

    String summary = tool.approvalPreview(prepared).summary();

    assertTrue(summary.startsWith("Ingest 7 prepared root(s): "), summary);
    assertTrue(summary.contains(files.get(0).toAbsolutePath().normalize().toString()),
        "preview must retain a meaningful path from the frozen plan after deletion");
    assertFalse(summary.contains(files.get(6).toAbsolutePath().normalize().toString()),
        "preview must not list more than six prepared paths");
    assertTrue(summary.endsWith("(+1 more)"), summary);
  }

  private static OperationPreparation prepare(IngestTool tool, String argumentsJson) {
    return tool.prepare(argumentsJson, PROVENANCE, EngineContextTestFixtures.AGENT_LOOP);
  }

  private void assertBadRequest(String argumentsJson, String expectedMessage) {
    OperationPreparationRefused refused = assertThrows(OperationPreparationRefused.class,
        () -> prepare(tool(List.of()), argumentsJson));
    assertFalse(refused.refusal().success());
    assertEquals("BAD_REQUEST", refused.refusal().errorCode().orElseThrow());
    assertTrue(refused.refusal().message().contains(expectedMessage),
        "expected refusal reason '" + expectedMessage + "' but received '"
            + refused.refusal().message() + "'");
  }

  private static void assertInvalidPreparedPlan(String expectedMessage,
      OperationPreparation prepared, IngestTool tool) {
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> tool.validatePreparation(prepared));
    assertEquals(expectedMessage, refused.getMessage());
  }

  private static IngestTool tool(List<RootBinding> bindings) {
    return new IngestTool(RecordedIngestionService.unavailable(),
        ignored -> bindings, ignored -> "generation-1", List::of);
  }

  private static String json(Object value) {
    return JSON.writeValueAsString(value);
  }

  private static void createSymlinkOrSkip(Path link, Path target) throws IOException {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException unsupported) {
      assumeTrue(false, "symbolic links are unsupported by this filesystem: " + unsupported);
    } catch (FileSystemException failure) {
      String reason = String.valueOf(failure.getReason()).toLowerCase(Locale.ROOT);
      if (reason.contains("not supported") || reason.contains("not permitted")
          || reason.contains("privilege")) {
        assumeTrue(false, "symbolic links are unsupported in this environment: " + failure);
      }
      throw failure;
    }
  }
}
