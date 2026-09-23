package io.justsearch.ui.api.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;

/** Minimal retained-search fixture for MCP projection tests. */
final class McpSearchSessionFixture {
  private McpSearchSessionFixture() {}

  static void stub(KnowledgeHttpApiAdapter adapter, KnowledgeSearchResponse response) {
    when(adapter.openSearch(any(), any(EngineContext.class)))
        .thenAnswer(ignored -> of(adapter, response));
  }

  static KnowledgeHttpApiAdapter.SearchSession of(
      KnowledgeHttpApiAdapter adapter, KnowledgeSearchResponse response) {
    ConfigStore store = ConfigStore.globalOrNull();
    ResolvedConfig config = store == null ? null : store.get();
    return new KnowledgeHttpApiAdapter.SearchSession() {
      @Override public KnowledgeSearchResponse response() { return response; }
      @Override public ResolvedConfig config() { return config; }
      @Override public StatusFacts statusFacts(EngineContext context) {
        var status = adapter.status(context);
        if (status == null) return new StatusFacts(-1L, 100, 100);
        var extras = status.extras();
        double embedding = extras.get("embeddingCoveragePercent") instanceof Number n
            ? n.doubleValue() : 100;
        double splade = extras.get("spladeCoveragePercent") instanceof Number n
            ? n.doubleValue() : 100;
        return new StatusFacts(status.docCount(), embedding, splade);
      }
      @Override public void close() {}
    };
  }
}
