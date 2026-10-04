/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class OperationPolicyTest {
  @Test
  void existingConstructorShapesDefaultToOrdinaryOperation() {
    var six = new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false);
    assertEquals(OperationKind.OPERATION, six.recordKind());
    assertEquals(Optional.empty(), six.declaredSurvival());

    var seven = new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty());
    assertEquals(OperationKind.OPERATION, seven.recordKind());
    assertEquals(Optional.empty(), seven.declaredSurvival());

    var eight = new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty(), Optional.empty());
    assertEquals(OperationKind.OPERATION, eight.recordKind());
    assertEquals(Optional.empty(), eight.declaredSurvival());

    var nine = new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty(), Optional.empty(), Optional.empty());
    assertEquals(OperationKind.OPERATION, nine.recordKind());
    assertEquals(Optional.empty(), nine.declaredSurvival());

    var ten = new OperationPolicy(RiskTier.LOW, ConfirmStrategy.None.INSTANCE,
        AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(), Set.of(), false,
        Optional.empty(), Optional.empty(), Optional.empty(), OperationKind.REINDEX);
    assertEquals(OperationKind.REINDEX, ten.recordKind());
    assertEquals(Optional.empty(), ten.declaredSurvival());
  }

  @Test
  void policyCopyMethodsPreserveDeclaredSurvivalAndOverridesAreOrderIndependent() {
    var original = policy(OperationKind.OPERATION, Optional.empty());
    var inverse = new OperationRef("core.inverse-fixture");

    var declared = original.withDeclaredSurvival(EngineContext.Survival.DURABLE);
    var allWithers = declared.withRecordKind(OperationKind.REINDEX)
        .withInverseOperationRef(inverse).withCapabilityFamily("fixture-family");
    var survivalFirst = original.withDeclaredSurvival(EngineContext.Survival.DURABLE)
        .withRecordKind(OperationKind.REINDEX);
    var kindFirst = original.withRecordKind(OperationKind.REINDEX)
        .withDeclaredSurvival(EngineContext.Survival.DURABLE);

    assertEquals(survivalFirst, kindFirst);
    assertEquals(OperationKind.REINDEX, allWithers.recordKind());
    assertEquals(Optional.of(EngineContext.Survival.DURABLE), allWithers.declaredSurvival());
    assertEquals(OperationKind.OPERATION, original.recordKind());
    assertEquals(Optional.empty(), original.declaredSurvival());
    assertEquals(original.risk(), allWithers.risk());
    assertEquals(original.confirm(), allWithers.confirm());
    assertEquals(original.audit(), allWithers.audit());
    assertEquals(original.retry(), allWithers.retry());
    assertEquals(original.requiredCapabilities(), allWithers.requiredCapabilities());
    assertEquals(original.undoSupported(), allWithers.undoSupported());
    assertEquals(original.advisoryClass(), allWithers.advisoryClass());
    assertEquals(Optional.of(inverse), allWithers.inverseOperationRef());
    assertEquals(Optional.of("fixture-family"), allWithers.capabilityFamily());
    assertThrows(NullPointerException.class, () -> original.withRecordKind(null));
    assertThrows(NullPointerException.class, () -> original.withDeclaredSurvival(null));
    assertThrows(NullPointerException.class, () -> policy(OperationKind.OPERATION, null));
  }

  @Test
  void recordKindsUseOneClosedPersistentVocabulary() {
    for (OperationKind kind : OperationKind.values()) {
      assertEquals(kind, OperationKind.fromWire(kind.wireValue()));
    }
    assertEquals("memory", OperationKind.MEMORY.wireValue());
    assertEquals("note", OperationKind.NOTE.wireValue());
    assertThrows(IllegalArgumentException.class, () -> OperationKind.fromWire("REINDEX"));
    assertThrows(IllegalArgumentException.class, () -> OperationKind.fromWire("unknown"));
  }

  private static OperationPolicy policy(
      OperationKind recordKind, Optional<EngineContext.Survival> declaredSurvival) {
    return new OperationPolicy(
        RiskTier.HIGH,
        ConfirmStrategy.Inline.INSTANCE,
        AuditPolicy.METADATA_ONLY,
        RetryPolicy.noRetry(),
        Set.of(),
        true,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        recordKind,
        declaredSurvival);
  }
}
