package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.PipelineConfig;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for PipelineConfig-based search requests through the composable retrieval path
 * (256: Phase E).
 *
 * <p>Tests non-preset PipelineConfig combinations and verifies degradation signaling (effectiveMode,
 * vectorBlocked, hybridFallback, spladeSkipReason) when components are unavailable.
 */
@DisplayName("SearchOrchestrator composable path (256-E6)")
final class SearchOrchestratorComposablePathTest {

  @Nested
  @DisplayName("Pipeline-based degradation signaling")
  class PipelineDegradation {

    @Test
    @DisplayName("{sparse, dense} + no embedding service → hybridFallback=true, effectiveMode=TEXT")
    void sparseDenseNoEmbedding() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);
        // No embedding service set → dense leg fails with NO_EMBEDDING_SERVICE

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setDenseEnabled(true)
                            .build())
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "hybridFallback should be true");
        assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getHybridFallbackReason());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertFalse(response.getSearchTrace().getDegradation().getVectorBlocked(), "vectorBlocked should be false (sparse available)");
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }

    @Test
    @DisplayName("{sparse, dense} + embedding blocked → hybridFallback=true, effectiveMode=TEXT")
    void sparseDenseEmbeddingBlocked() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);
        EmbeddingCompatibilityController controller =
            new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setDenseEnabled(true)
                            .build())
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "hybridFallback should be true");
        assertEquals(
            "LEGACY_INDEX_NO_FINGERPRINT",
            response.getSearchTrace().getDegradation().getHybridFallbackReason(),
            "should propagate compat controller's reason code");
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }

    @Test
    @DisplayName("{sparse, dense} + embed returns null → hybridFallback=true, effectiveMode=TEXT")
    void sparseDenseEmbeddingFails() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        EmbeddingService embeddingService = embeddingServiceAvailableButNullEmbed();
        var service = new WorkerSearchService(lifecycle, embeddingService);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setDenseEnabled(true)
                            .build())
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "hybridFallback should be true");
        assertEquals("EMBEDDING_GENERATION_FAILED", response.getSearchTrace().getDegradation().getHybridFallbackReason());
        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        embeddingService.close();
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }

    @Test
    @DisplayName("{dense} + compat blocked → vectorBlocked=true with reason code")
    void denseOnlyCompatBlocked() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);
        EmbeddingCompatibilityController controller =
            new EmbeddingCompatibilityController(Map::of, () -> 1L);
        forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
        service.setEmbeddingCompatController(controller);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder().setDenseEnabled(true).build())
                    .build());

        assertTrue(response.getSearchTrace().getDegradation().getVectorBlocked(), "vectorBlocked should be true");
        assertEquals("LEGACY_INDEX_NO_FINGERPRINT", response.getSearchTrace().getDegradation().getVectorBlockedReason());
        assertEquals("VECTOR", response.getSearchTrace().getEffectiveMode());
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }

    @Test
    @DisplayName("{sparse, splade} + no SPLADE encoder → degrades to TEXT, spladeSkipReason set")
    void sparseSpladeNoEncoder() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);
        // No SPLADE encoder available → SPLADE leg fails

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setSpladeEnabled(true)
                            .build())
                    .build());

        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertFalse(response.getSearchTrace().getDegradation().getSpladeSkipReason().isEmpty(), "spladeSkipReason should be set");
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }

    @Test
    @DisplayName(
        "{sparse, dense, splade} + no embedding + no SPLADE → TEXT, hybridFallback + spladeSkipReason")
    void allThreeNoEmbeddingNoSplade() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);
        // No embedding service, no SPLADE encoder → both dense and SPLADE fail

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder()
                            .setSparseEnabled(true)
                            .setDenseEnabled(true)
                            .setSpladeEnabled(true)
                            .build())
                    .build());

        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(response.getSearchTrace().getDegradation().getHybridFallback(), "hybridFallback should be true (dense failed)");
        assertEquals("NO_EMBEDDING_SERVICE", response.getSearchTrace().getDegradation().getHybridFallbackReason());
        assertFalse(
            response.getSearchTrace().getDegradation().getSpladeSkipReason().isEmpty(), "spladeSkipReason should be set (SPLADE failed)");
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }
  }

  @Nested
  @DisplayName("Sparse-only via PipelineConfig")
  class SparseOnlyPipeline {

    @Test
    @DisplayName("{sparse} pipeline returns results and effectiveMode=TEXT")
    void sparseOnlyReturnsResults() throws Exception {
      String prevConfig = System.getProperty("justsearch.config");
      try (RunningRuntime lifecycle = newLifecycleWithOneDoc("doc-1", "Hello world")) {
        var service = new WorkerSearchService(lifecycle);

        SearchResponse response =
            invokeSearch(
                service,
                SearchRequest.newBuilder()
                    .setQuery("Hello")
                    .setLimit(10)
                    .setPipeline(
                        PipelineConfig.newBuilder().setSparseEnabled(true).build())
                    .build());

        assertEquals("TEXT", response.getSearchTrace().getEffectiveMode());
        assertTrue(response.getTotalHits() > 0, "should find indexed document");
        assertFalse(response.getSearchTrace().getDegradation().getVectorBlocked());
        assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
      } finally {
        restoreProperty("justsearch.config", prevConfig);
      }
    }
  }

  // ---- Test infrastructure (duplicated from WorkerSearchServiceReasonCodeContractTest) ----

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleWithOneDoc(String docId, String content)
      throws Exception {
    FieldCatalogDef catalog = FieldCatalogDef.forChunkTesting(4);
    RunningRuntime lifecycle = newLifecycleWithCatalog(catalog);
    var runtime = lifecycle;
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, docId,
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static RunningRuntime newLifecycleWithCatalog(FieldCatalogDef catalog)
      throws Exception {
    Path base = Files.createTempDirectory("justsearch-composable-path-test-");
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: composable\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    lifecycle.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void forceEmbeddingCompatState(
      EmbeddingCompatibilityController controller, State state, String reasonCode)
      throws Exception {
    Field stateField = EmbeddingCompatibilityController.class.getDeclaredField("state");
    stateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<State> stateRef = (AtomicReference<State>) stateField.get(controller);
    stateRef.set(state);

    Field reasonField = EmbeddingCompatibilityController.class.getDeclaredField("reasonCode");
    reasonField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> reasonRef = (AtomicReference<String>) reasonField.get(controller);
    reasonRef.set(reasonCode);
  }

  private static EmbeddingService embeddingServiceAvailableButNullEmbed() throws Exception {
    EmbeddingService svc = new EmbeddingService(Path.of("dummy.gguf"), EmbeddingConfig.DISABLED);

    Field initializedField = EmbeddingService.class.getDeclaredField("initialized");
    initializedField.setAccessible(true);
    AtomicBoolean initialized = (AtomicBoolean) initializedField.get(svc);
    initialized.set(true);

    Field availableField = EmbeddingService.class.getDeclaredField("available");
    availableField.setAccessible(true);
    availableField.setBoolean(svc, true);

    return svc;
  }
}
