/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class IndexOwnershipRuntimeAdmissionTest extends RuntimeTestBase {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(strings = {"writable", "read-only", "deferred"})
  void everyManagedOpenInspectsTheWholeNamespaceBeforeLucene(String mode) throws Exception {
    Path active = seedOwnedIndex();
    Path base = active.getParent().getParent();
    var schema = buildSchemaWithDim(4);
    Path foreign = Files.createDirectory(base.resolve("indices/foreign"));
    Files.writeString(foreign.resolve("payload"), "foreign bytes");
    var builder = schema.atPath(active).withExecutorRegistrations(testLuceneExecutors()).withFallbackIndexPath(base);
    RuntimeException refusal = assertThrows(RuntimeException.class, () -> {
      switch (mode) {
        case "writable" -> { try (var ignored = builder.open()) { } }
        case "read-only" -> { try (var ignored = builder.openReadOnly()) { } }
        case "deferred" -> { try (var ignored = builder.openDeferred()) { } }
        default -> fail(mode);
      }
    });
    boolean ownershipReason = false;
    for (Throwable cause = refusal; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains("index.ownership_unproven")) ownershipReason = true;
    }
    assertTrue(ownershipReason, refusal.toString());
    assertEquals("foreign bytes", Files.readString(foreign.resolve("payload")));
    assertFalse(Files.exists(foreign.resolve(".justsearch-generation.sentinel")));
    Files.move(foreign, tempDir.resolve("foreign-saved"));
    try (var resumed = builder.openReadOnly()) { assertEquals(1, resumed.indexCountOps().docCount()); }
  }
  @org.junit.jupiter.api.Test
  void recoveryObligationIsDurableBeforeBackupAndReplacement() throws Exception {
    Path active = seedOwnedIndex();
    Path commit;
    try (var files = Files.list(active)) {
      commit = files.filter(path -> path.getFileName().toString().startsWith("segments_")).findFirst().orElseThrow();
    }
    Files.write(commit, new byte[] {1, 2, 3});
    var configuration = io.justsearch.configuration.resolved.ResolvedConfig.builder();
    configuration.contributeEnvRegistry();
    configuration.contributeYaml(new tools.jackson.databind.ObjectMapper(new tools.jackson.dataformat.yaml.YAMLFactory())
        .readTree("index:\n  auto_recovery: true\n  recovery:\n    policy: BACKUP_REBUILD\n"));
    var entered = new java.util.concurrent.atomic.AtomicBoolean();
    try (var backups = org.mockito.Mockito.mockStatic(SafeIndexPathOps.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
      backups.when(() -> SafeIndexPathOps.backupDirectory(org.mockito.ArgumentMatchers.eq(active),
          org.mockito.ArgumentMatchers.any(Path.class))).thenAnswer(invocation -> {
            entered.set(true);
            assertTrue(IndexRecoveryMarker.exists(active), "a process death after this rename must retain the source-rebuild obligation");
            return invocation.callRealMethod();
          });
      assertDoesNotThrow(() -> {
        try (var recovered = buildSchemaWithDim(4).atPath(active)
            .withFallbackIndexPath(active.getParent().getParent())
            .withExecutorRegistrations(testLuceneExecutors()).withConfig(configuration.build()).open()) {
          assertEquals(0, recovered.indexCountOps().docCount());
        }
      });
      assertTrue(entered.get());
      assertTrue(IndexRecoveryMarker.exists(active));
    }
  }

  private Path seedOwnedIndex() throws Exception {
    Path base = dataDir();
    Path active = Files.createDirectories(base.resolve("indices/g-owned"));
    Files.writeString(active.resolve(".justsearch-generation.sentinel"),
        "justsearch_generation_sentinel_v1\ngeneration_id=g-owned\ncreated_at_ms=1\n");
    Files.writeString(active.resolve(".justsearch-index-generation.json"),
        "{\"format_version\":1,\"generation_id\":\"g-owned\",\"source\":\"new\",\"created_at_ms\":2}");
    var schema = buildSchemaWithDim(4);
    try (var seed = schema.atPath(active).withExecutorRegistrations(testLuceneExecutors()).open()) {
      seed.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "retained", SchemaFields.DOC_UID, "retained#0",
          SchemaFields.CONTENT, "retained search content")));
      seed.commitOps().commitAndTrack();
    }
    return active;
  }

}
