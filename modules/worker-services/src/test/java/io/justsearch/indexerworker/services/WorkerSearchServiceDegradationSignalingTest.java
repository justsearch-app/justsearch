package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController;
import io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerSearchServiceDegradationSignalingTest {

  @Test
  void vectorModeReturnsEmptyWithVectorBlockedAndReasonWhenEmbeddingQueriesDisallowed() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try {
      RunningRuntime lifecycle = newLifecycleWithOneDoc("Hello world");
      var service = new WorkerSearchService(lifecycle);

      EmbeddingCompatibilityController controller =
          new EmbeddingCompatibilityController(Map::of, () -> 1L);
      forceEmbeddingCompatState(controller, State.BLOCKED_LEGACY, "LEGACY_INDEX_NO_FINGERPRINT");
      service.setEmbeddingCompatController(controller);

      SearchResponse response =
          invokeSearch(
              service,
              SearchRequest.newBuilder()
                  .setQuery("ignored")
                  .setLimit(10)
                  .setMode(SearchMode.SEARCH_MODE_VECTOR)
                  .build());

      assertEquals("VECTOR", response.getSearchTrace().getEffectiveMode());
      assertTrue(response.getSearchTrace().getDegradation().getVectorBlocked());
      assertEquals("LEGACY_INDEX_NO_FINGERPRINT", response.getSearchTrace().getDegradation().getVectorBlockedReason());
      assertFalse(response.getSearchTrace().getDegradation().getHybridFallback());
      assertEquals(0, response.getTotalHits());

      lifecycle.close();
    } finally {
      if (prevConfig == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prevConfig);
      }
    }
  }

  @Test
  void hybridModeFallsBackToTextWithHybridFallbackAndReasonWhenEmbeddingQueriesDisallowed() throws Exception {
    String prevConfig = System.getProperty("justsearch.config");
    try {
      RunningRuntime lifecycle = newLifecycleWithOneDoc("Hello world");
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
                  .setMode(SearchMode.SEARCH_MODE_HYBRID)
                  .build());

      assertEquals("TEXT", response.getSearchTrace().getEffectiveMode(), "Blocked HYBRID should fall back to TEXT");
      assertFalse(response.getSearchTrace().getDegradation().getVectorBlocked());
      assertTrue(response.getSearchTrace().getDegradation().getHybridFallback());
      assertEquals("LEGACY_INDEX_NO_FINGERPRINT", response.getSearchTrace().getDegradation().getHybridFallbackReason());
      assertTrue(response.getTotalHits() >= 1, "Fallback TEXT should return hits");

      lifecycle.close();
    } finally {
      if (prevConfig == null) {
        System.clearProperty("justsearch.config");
      } else {
        System.setProperty("justsearch.config", prevConfig);
      }
    }
  }

  private static SearchResponse invokeSearch(WorkerSearchService service, SearchRequest request) {
    SearchResponse response = service.search(request, CallContext.none());
    assertNotNull(response);
    return response;
  }

  private static RunningRuntime newLifecycleWithOneDoc(String content) throws Exception {
    Path base = Files.createTempDirectory("justsearch-grpc-search-degradation-test-");
    String yaml =
        "app:\n  data_dir: " + base.toString().replace("\\", "\\\\") + "\n"
            + "index:\n  collections:\n    - name: grpcsearchdegrade\n      roots: ['ignored']\n"
            + "vector:\n  dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    System.setProperty("justsearch.config", cfg.toString());

    FieldCatalogDef catalog =
        new FieldCatalogDef(
            "test",
            List.of(
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_ID,
                    "keyword",
                    true,
                    true,
                    List.of("id", "sort"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.DOC_UID,
                    "keyword",
                    false,
                    true,
                    List.of("sort", "tiebreak"),
                    null,
                    null,
                    false),
                new FieldCatalogDef.FieldDef(
                    SchemaFields.CONTENT,
                    "text",
                    true,
                    false,
                    List.of("highlight"),
                    null,
                    "icu",
                    false)));

    var lifecycle = IndexSchema.fromCatalog(catalog).ephemeral().open();
    var runtime = lifecycle;
    runtime.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, "doc-1",
                SchemaFields.DOC_UID, "doc-1#0",
                SchemaFields.CONTENT, content)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
    return lifecycle;
  }

  private static void forceEmbeddingCompatState(
      EmbeddingCompatibilityController controller, State state, String reasonCode) throws Exception {
    Field stateField = EmbeddingCompatibilityController.class.getDeclaredField("state");
    stateField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<State> stateRef =
        (AtomicReference<State>) stateField.get(controller);
    stateRef.set(state);

    Field reasonField = EmbeddingCompatibilityController.class.getDeclaredField("reasonCode");
    reasonField.setAccessible(true);
    @SuppressWarnings("unchecked")
    AtomicReference<String> reasonRef =
        (AtomicReference<String>) reasonField.get(controller);
    reasonRef.set(reasonCode);
  }
}
