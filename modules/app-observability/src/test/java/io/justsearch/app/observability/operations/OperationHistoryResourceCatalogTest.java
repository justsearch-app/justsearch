package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.Category;
import io.justsearch.agent.api.registry.HistoryPolicy;
import io.justsearch.agent.api.registry.OnOverflow;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.agent.api.registry.SubscriptionMode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("OperationHistoryResourceCatalog")
final class OperationHistoryResourceCatalogTest {

  private static Resource entry() {
    return new OperationHistoryResourceCatalog().definitions().get(0);
  }

  @Test
  @DisplayName("catalog ships exactly one Resource entry")
  void exactlyOneEntry() {
    ResourceCatalog catalog = new OperationHistoryResourceCatalog();
    assertEquals(1, catalog.definitions().size());
  }

  @Test
  @DisplayName("namespace is core")
  void namespaceIsCore() {
    assertEquals("core", new OperationHistoryResourceCatalog().namespace());
  }

  @Test
  @DisplayName("entry shape: EVENT_STREAM × SSE_STREAM + endpoint + kind + schema URL")
  void entryShape() {
    Resource e = entry();
    assertSame(Category.EVENT_STREAM, e.category());
    assertEquals(SubscriptionMode.SSE_STREAM, e.subscriptionMode());
    assertEquals("/api/operation-history/stream", e.endpoint());
    assertEquals("operation-history", e.kind());
    assertNotNull(e.schema());
    assertFalse(e.schema().isBlank());
    assertTrue(e.schema().endsWith("operation-history-entry.v1.json"));
  }

  @Test
  void eventStreamDeclaresAndPublishesInvocationIdentity() {
    Resource resource = entry();
    assertEquals(Category.EVENT_STREAM, resource.category());
    assertEquals("operationKey", resource.primaryKey());
    Map<?, ?> wire = JsonMapper.builder().build().convertValue(resource, Map.class);
    assertEquals("operationKey", wire.get("primaryKey"));
  }

  @Test
  @DisplayName("HistoryPolicy is present (EVENT_STREAM Category requires it)")
  void historyPolicyPresent() {
    assertTrue(entry().history().isPresent(), "EVENT_STREAM Resource must declare HistoryPolicy");
  }

  @Test
  @DisplayName("HistoryPolicy declares the durable source")
  void historyPolicyModeIsDurable() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertSame(HistoryPolicy.Mode.DURABLE, p.mode());
  }

  @Test
  @DisplayName("HistoryPolicy declares thirty-day storage retention, separate from the 200-row read limit")
  void historyRetentionMatchesStore() {
    HistoryPolicy policy = entry().history().orElseThrow();
    assertTrue(policy.capacity().isEmpty());
    assertEquals(Duration.ofDays(30), policy.retention().orElseThrow());
  }

  @Test
  @DisplayName("HistoryPolicy onOverflow is EVICT_OLDEST")
  void historyPolicyEvictOldest() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertSame(OnOverflow.EVICT_OLDEST, p.onOverflow());
  }

  @Test
  @DisplayName("HistoryPolicy resumeWindow is 5 minutes")
  void historyPolicyResumeWindow() {
    HistoryPolicy p = entry().history().orElseThrow();
    assertEquals(Duration.ofMinutes(5), p.resumeWindow());
  }

  @Test
  @DisplayName("recovery is empty (per slice 444b: no singular per-Resource recovery)")
  void recoveryEmpty() {
    assertTrue(entry().recovery().isEmpty());
  }

  @Test
  @DisplayName("ResourceRef is core.operation-history (regex-compliant)")
  void operationIdShape() {
    assertEquals(new ResourceRef("core.operation-history"), entry().id());
  }

  @Test
  @DisplayName("findById resolves the entry")
  void findByIdResolves() {
    ResourceCatalog catalog = new OperationHistoryResourceCatalog();
    assertTrue(
        catalog.findById(new ResourceRef("core.operation-history")).isPresent(),
        "Catalog must resolve its own entry by id");
  }

  @Test
  @DisplayName("recent read bound remains 200")
  void historyCapacityConstantPinned() {
    assertEquals(200, OperationHistoryStore.DEFAULT_CAPACITY);
  }
}
