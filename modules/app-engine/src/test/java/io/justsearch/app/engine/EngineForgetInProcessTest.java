/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.handlers.ForgetIndexedPathHandler;
import io.justsearch.app.services.settings.SettingsCommitCoordinator;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.DefaultWorkerAppServices;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Actual process factory/shared settings owner/SQLite/Lucene; no substitute FORGET response. */
@Timeout(90)
final class EngineForgetInProcessTest {
  private static final Clock CLOCK = Clock.systemUTC();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path directory;

  @Test
  void S55RealWatchedFileOwnerRemovesParentChunksAndJobsKeepsPrecisePolicyAndOriginals() throws Exception {
    Path watched = Files.createDirectory(directory.resolve("watched"));
    Path exact = Files.writeString(watched.resolve("report\\[1]*.txt "), "original physical bytes");
    Path sibling = Files.writeString(watched.resolve("report\\[1]*.txt"), "sibling physical bytes");
    try (var f = new Fixture(directory.resolve("process"), List.of(watched))) {
      f.seed(exact, "private index text"); f.seed(sibling, "public sibling text");
      var result = f.forget(exact, "FILE");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("CONFIRMED", outcome.path("removal").asString());
      assertEquals("INSTALLED", outcome.path("keepOut").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(exact)));
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(sibling)));
      assertTrue(f.queue.forgetJobsAbsent(key(exact), false));
      assertEquals("original physical bytes", Files.readString(exact));
      assertEquals("sibling physical bytes", Files.readString(sibling));
      var entries = f.settings.inspect().settings().getExcludePatterns();
      assertEquals(1, entries.size());
      var literal = LiteralExclusion.fromEntry(entries.getFirst());
      assertEquals(exact.toString(), literal.literal().path());
      var policy = io.justsearch.configuration.excludes.ExclusionPatterns.fromRawJsonStrict(
          f.config.get().ui().excludePatterns(), false);
      assertTrue(policy.isExcludedPath(watched, exact));
      assertFalse(policy.isExcludedPath(watched, sibling));
      assertEquals(0, f.root.admission().activeWorkCount());
    }
  }

  @Test
  void S55OutsideFileCompletesWithoutPermanentRuleAndUnrestrictedNewWriterCanPublishAgain() throws Exception {
    Path outside = Files.writeString(directory.resolve("outside.txt"), "source remains");
    try (var f = new Fixture(directory.resolve("process"), List.of())) {
      f.seed(outside, "old indexed text");
      var result = f.forget(outside, "FILE");
      var outcome = JSON.valueToTree(result.structuredData().get("forget"));
      assertEquals("COMPLETE", outcome.path("status").asString(), result.message());
      assertEquals("NOT_REQUIRED", outcome.path("keepOut").asString());
      assertEquals(0, f.runtime.indexCountOps().countByIdAndChunksStrict(key(outside)));
      assertTrue(f.settings.inspect().settings().getExcludePatterns().isEmpty());
      String forgetKey = outcome.path("operationKey").asString();
      var withdrawal = f.root.forgetService().withdraw(forgetKey, webview());
      assertEquals("COMPLETE", JSON.valueToTree(withdrawal.structuredData().get("forget")).path("status").asString());
      assertTrue(withdrawal.message().contains("already complete"));
      assertEquals(OperationState.COMPLETE, f.operations.find(forgetKey).orElseThrow().state());
      assertEquals("forget_complete", f.operations.find(forgetKey).orElseThrow().receipt().code());
      // This direct writer proves no lasting keep-out. Accepted intake is checked separately.
      f.seed(outside, "new deliberate index text");
      assertEquals(3, f.runtime.indexCountOps().countByIdAndChunksStrict(key(outside)));
      assertEquals("source remains", Files.readString(outside));
    }
  }

  static String key(Path path) { return PathNormalizer.normalizeKey(path); }
  static Object field(Object owner, Class<?> type, String name) throws Exception {
    var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
  }
  static EngineContext webview() {
    return EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "forget-real-engine",
        Optional.empty(), Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
  }

  static final class Fixture implements AutoCloseable {
    final ConfigStore previousConfig = ConfigStore.globalOrNull();
    final String previousData = System.getProperty("justsearch.data.dir");
    final String previousHome = System.getProperty("user.home");
    final Map<String, String> previousFixtureProperties = new LinkedHashMap<>();
    final ConfigStore config;
    final UiSettingsStore settings;
    final SqliteOperationStore operations;
    final OperationAttemptRunnerImpl attempts;
    final OperationAuthority authority;
    final DefaultEngineProcessResources resources;
    final EngineRoot root;
    final EngineKnowledgeClient client;
    final KnowledgeServer server;
    final RunningRuntime runtime;
    final SqliteJobQueue queue;
    final io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore identities;
    final IndexingLoop loop;
    Fixture(Path data, List<Path> watched) throws Exception {
      this(data, watched, 30_000);
    }
    Fixture(Path data, List<Path> watched, long deadlineMs) throws Exception {
      Files.createDirectories(data);
      System.setProperty("justsearch.data.dir", data.toString());
      System.setProperty("user.home", data.resolve("home").toString());
      for (var entry : Map.of("justsearch.models.dir", data.resolve("missing-models").toString(),
          "justsearch.index.base_path", data.resolve("index").toString(),
          "justsearch.ssot.path", data.resolve("SSOT").toString()).entrySet()) {
        previousFixtureProperties.put(entry.getKey(), System.getProperty(entry.getKey()));
        System.setProperty(entry.getKey(), entry.getValue());
      }
      EngineTestHarness.publishConfig(data, data.resolve("index"),
          Map.of("justsearch.models.dir", data.resolve("missing-models").toString()));
      config = ConfigStore.global();
      if (!watched.isEmpty()) Files.writeString(data.resolve("watched_roots.json"), JSON.writeValueAsString(Map.of(
          "schemaVersion", 1, "roots", watched.stream().map(path -> Map.of("path", path.toString(), "collection", "documents")).toList())));
      settings = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, data.resolve("settings.json"));
      config.update(io.justsearch.app.services.config.ConfigStoreRebuilder.prepare(settings.inspect().settings()));
      operations = new SqliteOperationStore(data.resolve("operations.db"));
      var settingsOwner = new SettingsCommitCoordinator(settings, config,
          () -> { throw new AssertionError("Unexpected settings restart"); }, candidate -> OperationResult.success("prepared"));
      attempts = new OperationAttemptRunnerImpl(operations, CLOCK,
          Set.of(OperationKind.INGEST, OperationKind.REINDEX, OperationKind.ACCEPT_GAPS,
              OperationKind.FORGET, OperationKind.SETTINGS_APPLY, OperationKind.RECONFIGURE),
          settingsOwner, new RecordedIngestPlanResolver());
      authority = OperationAuthority.load(data);
      resources = new DefaultEngineProcessResources(config.publicationLock());
      root = EngineRoot.forProcess(operations, attempts, deadlineMs, 100,
          code -> { throw new AssertionError("Unexpected fatal writer exit " + code); },
          io.justsearch.app.api.runtime.ManagedChildRegistry.noop(),
          () -> { throw new AssertionError("Unexpected migration restart"); }, authority, config, resources);
      try {
        client = (EngineKnowledgeClient) root.start(new GpuSchedulingGauge(), IpcTelemetry.noop());
        server = (KnowledgeServer) field(root, EngineRoot.class, "server");
        runtime = (RunningRuntime) field(server, KnowledgeServer.class, "ingestLifecycle");
        queue = (SqliteJobQueue) field(server, KnowledgeServer.class, "jobQueue");
        identities = (io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore)
            field(server, KnowledgeServer.class, "documentIdentityStore");
        loop = (IndexingLoop) field(server.appServices(), DefaultWorkerAppServices.class, "indexingLoop");
        runtime.commitOps().stopCommitTimer();
        assertTrue(root.forgetService().hasOwner(), "explicit process factory binds actual shared settings owner");
      } catch (Exception | Error failure) {
        try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); } throw failure;
      }
    }
    void seed(Path path, String content) {
      String id = key(path);
      String uid = identities.resolve(io.justsearch.indexerworker.identity.DocumentIdentityStore.pathHash(id),
          System.currentTimeMillis()).docUid();
      String revision = io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex(content);
      for (int position = -1; position < 2; position++) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(SchemaFields.DOC_ID, position < 0 ? id : io.justsearch.indexing.chunking.ChunkIds.newChunkDocId());
        fields.put(SchemaFields.PATH, id); fields.put(SchemaFields.COLLECTION, "documents");
        fields.put(SchemaFields.DOC_UID, position < 0 ? uid : uid + "#" + position);
        fields.put(SchemaFields.CHUNK_TOTAL, 2); fields.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, revision);
        if (position < 0) {
          fields.put(SchemaFields.CONTENT, content); fields.put(SchemaFields.CONTENT_SHA256, revision);
        } else {
          int split = content.length() / 2;
          int start = position == 0 ? 0 : split; int end = position == 0 ? split : content.length();
          fields.put(SchemaFields.PARENT_DOC_ID, id); fields.put(SchemaFields.IS_CHUNK, true);
          fields.put(SchemaFields.CHUNK_INDEX, position); fields.put(SchemaFields.CHUNK_START_CHAR, start);
          fields.put(SchemaFields.CHUNK_END_CHAR, end); fields.put(SchemaFields.CHUNK_CONTENT, content.substring(start, end));
        }
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack(); runtime.commitOps().maybeRefreshBlocking();
      assertEquals(3, assertDoesNotThrow(() -> runtime.indexCountOps().countByIdAndChunksStrict(id)),
          "fixture must establish actual parent and both owned chunks before the operation");
    }
    OperationResult forget(Path path, String scope) {
      var result = forgetAttempt(path, scope);
      String key = JSON.valueToTree(result.structuredData().get("forget")).path("operationKey").asString();
      assertEquals(OperationState.COMPLETE, operations.find(key).orElseThrow().state(), result.message());
      return result;
    }
    OperationResult forgetAttempt(Path path, String scope) {
      var context = webview();
      var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
      String arguments = JSON.writeValueAsString(Map.of("path", path.toString(), "scope", scope));
      var handler = new ForgetIndexedPathHandler(root::forgetService);
      OperationPreparation prepared = handler.prepare(arguments, provenance, context);
      String key = OperationKeys.generate(CLOCK); UUID nonce = UUID.randomUUID();
      var descriptor = OperationDescriptor.invocation(OperationKind.FORGET, "core.forget-indexed-path", arguments, false);
      var request = new OperationAttemptRunner.Request(key, descriptor, context, provenance);
      String envelope = JSON.writeValueAsString(Map.of("version", 1, "key", key, "nonce", nonce,
          "descriptor", descriptor, "preparation", prepared, "context", context, "executor", ExecutorTag.UI,
          "occurredAt", provenance.occurredAt()));
      var saved = attempts.withPreparation(request, accepted -> attempts.savePreparation(accepted.request(),
          new OperationStore.Preparation(nonce, new OperationPreparedPayload(false, envelope))).orElseThrow());
      var accepted = attempts.acceptPrepared(request, saved.nonce());
      var result = attempts.start(accepted, handle -> handler.executePrepared(prepared, provenance, context, handle));
      return result.response();
    }
    @Override public void close() throws Exception {
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (root.admission().activeWorkCount() != 0 && System.nanoTime() < until) Thread.sleep(5);
      try { root.close(); }
      finally {
        try { resources.close(); }
        finally {
          try { operations.close(); }
          finally {
            ConfigStore.restoreGlobal(config, previousConfig);
            if (previousData == null) System.clearProperty("justsearch.data.dir"); else System.setProperty("justsearch.data.dir", previousData);
            if (previousHome == null) System.clearProperty("user.home"); else System.setProperty("user.home", previousHome);
            previousFixtureProperties.forEach((name, value) -> {
              if (value == null) System.clearProperty(name); else System.setProperty(name, value);
            });
          }
        }
      }
    }
  }
}
