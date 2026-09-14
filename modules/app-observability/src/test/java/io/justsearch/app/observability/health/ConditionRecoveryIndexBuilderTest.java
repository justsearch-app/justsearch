package io.justsearch.app.observability.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationInvocation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ConditionRecoveryIndexBuilder (slice 447-impl-D)")
final class ConditionRecoveryIndexBuilderTest {

  private static final Source SRC = Source.forProcess("test", "test-instance", "1.0");

  private static HealthEvent assertedCondition(
      String id, String subject, Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        id,
        Instant.parse("2026-05-08T01:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        new AssertedCondition(
            subject,
            ConditionStatus.TRUE,
            "TestReason",
            Instant.parse("2026-05-08T01:00:00Z"),
            Optional.empty(),
            recovery,
            List.of()));
  }

  private static HealthEvent thresholdState(
      String id, String subject, Optional<OperationInvocation> recovery) {
    return new HealthEvent(
        id,
        Instant.parse("2026-05-08T01:00:00Z"),
        SRC,
        Severity.WARNING,
        Optional.of("health-events." + id + ".message"),
        new ThresholdState(
            subject,
            ThresholdPhase.FIRING,
            java.util.Map.of("magnitude", 87),
            Instant.parse("2026-05-08T01:00:00Z"),
            Optional.empty(),
            recovery,
            List.of()));
  }

  @Test
  @DisplayName("empty store produces empty index with version 0")
  void emptyStore() {
    ConditionStore store = new ConditionStore();
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    assertTrue(index.entries().isEmpty());
    assertEquals(0L, index.catalogVersion());
  }

  @Test
  @DisplayName("conditions without recovery do not appear in the index")
  void conditionsWithoutRecoveryExcluded() {
    ConditionStore store = new ConditionStore();
    store.upsert(assertedCondition("index.unavailable", "worker", Optional.empty()));
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    assertTrue(index.entries().isEmpty(), "no recovery → no inverse entry");
  }

  @Test
  @DisplayName("conditions with recovery group by Operation target")
  void conditionsGroupByTarget() {
    ConditionStore store = new ConditionStore();
    OperationInvocation reindexInv =
        new OperationInvocation(new OperationRef("core.reindex"), "{\"force\":true}");
    OperationInvocation rebuildInv = OperationInvocation.of(new OperationRef("core.rebuild-index"));

    store.upsert(assertedCondition("schema.reindex-required", "worker.schema", Optional.of(reindexInv)));
    store.upsert(assertedCondition("schema.blocked", "worker.schema", Optional.of(reindexInv)));
    store.upsert(assertedCondition("index.unavailable", "worker", Optional.of(rebuildInv)));

    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);

    assertEquals(2, index.entries().size(), "two distinct recovery targets");

    ConditionRecoveryEntry reindexEntry =
        index.entries().stream()
            .filter(e -> e.target().equals(new OperationRef("core.reindex")))
            .findFirst()
            .orElseThrow();
    assertEquals(2, reindexEntry.conditions().size(), "two conditions reference core.reindex");

    ConditionRecoveryEntry rebuildEntry =
        index.entries().stream()
            .filter(e -> e.target().equals(new OperationRef("core.rebuild-index")))
            .findFirst()
            .orElseThrow();
    assertEquals(1, rebuildEntry.conditions().size());
  }

  @Test
  void sameTargetPreservesDistinctConditionArgumentsInWireProjection() {
    ConditionStore store = new ConditionStore();
    var target = new OperationRef("core.reindex");
    store.upsert(assertedCondition("a.condition", "worker.a",
        Optional.of(new OperationInvocation(target, "{\"force\":true}"))));
    store.upsert(assertedCondition("b.condition", "worker.b",
        Optional.of(new OperationInvocation(target, "{\"force\":false}"))));
    var index = ConditionRecoveryIndexBuilder.build(store);
    assertEquals(1, index.entries().size());
    var conditions = index.entries().getFirst().conditions();
    assertEquals("{\"force\":true}", conditions.getFirst().defaultArgsJson());
    assertEquals("{\"force\":false}", conditions.getLast().defaultArgsJson());
    var wire = new tools.jackson.databind.ObjectMapper().valueToTree(index);
    assertEquals("{\"force\":true}", wire.at("/entries/0/conditions/0/defaultArgsJson").asString());
    assertEquals("{\"force\":false}", wire.at("/entries/0/conditions/1/defaultArgsJson").asString());
  }

  @Test
  @DisplayName("ConditionRefs are sorted by (conditionId, subject)")
  void conditionRefsSorted() {
    ConditionStore store = new ConditionStore();
    OperationInvocation inv = OperationInvocation.of(new OperationRef("core.reindex"));
    store.upsert(assertedCondition("z.condition", "worker", Optional.of(inv)));
    store.upsert(assertedCondition("a.condition", "worker", Optional.of(inv)));

    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    ConditionRecoveryEntry entry = index.entries().get(0);
    assertEquals("a.condition", entry.conditions().get(0).conditionId());
    assertEquals("z.condition", entry.conditions().get(1).conditionId());
  }

  @Test
  @DisplayName("ThresholdState recovery contributes to the index (447-followup/2.2)")
  void thresholdStateContributes() {
    ConditionStore store = new ConditionStore();
    OperationInvocation reindexInv = OperationInvocation.of(new OperationRef("core.reindex"));
    OperationInvocation gcInv = OperationInvocation.of(new OperationRef("core.gc-cache"));

    store.upsert(assertedCondition("schema.reindex-required", "worker.schema", Optional.of(reindexInv)));
    store.upsert(thresholdState("memory.pressure", "head.memory", Optional.of(gcInv)));

    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);

    assertEquals(2, index.entries().size(), "AssertedCondition + ThresholdState both contribute");

    ConditionRecoveryEntry gcEntry =
        index.entries().stream()
            .filter(e -> e.target().equals(new OperationRef("core.gc-cache")))
            .findFirst()
            .orElseThrow();
    assertEquals(1, gcEntry.conditions().size());
    assertEquals("memory.pressure", gcEntry.conditions().get(0).conditionId());
    assertEquals("head.memory", gcEntry.conditions().get(0).subject());
  }

  @Test
  @DisplayName("ThresholdState without recovery is excluded")
  void thresholdStateWithoutRecoveryExcluded() {
    ConditionStore store = new ConditionStore();
    store.upsert(thresholdState("gpu.saturated", "head.gpu", Optional.empty()));
    ConditionRecoveryIndex index = ConditionRecoveryIndexBuilder.build(store);
    assertTrue(index.entries().isEmpty(), "ThresholdState with empty recovery → no inverse entry");
  }
}
