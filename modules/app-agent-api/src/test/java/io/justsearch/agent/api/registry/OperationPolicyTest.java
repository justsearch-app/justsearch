/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class OperationPolicyTest {
  @Test
  void existingConstructorShapesDefaultToOrdinaryOperation() {
    assertEquals(OperationKind.OPERATION, new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false).recordKind());
    assertEquals(OperationKind.OPERATION, new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty()).recordKind());
    assertEquals(OperationKind.OPERATION, new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty(), Optional.empty()).recordKind());
    assertEquals(OperationKind.OPERATION, new OperationPolicy(RiskTier.LOW,
        ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(),
        Set.of(), false, Optional.empty(), Optional.empty(), Optional.empty()).recordKind());
  }

  @Test
  void policyWithersPreserveTheDeclaredKindAndIndependentAxes() {
    var original = new OperationPolicy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE,
        AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(), Set.of(), true);
    var inverse = new OperationRef("core.inverse-fixture");
    var kindFirst = original.withRecordKind(OperationKind.REINDEX)
        .withInverseOperationRef(inverse).withCapabilityFamily("fixture-family");
    var kindLast = original.withInverseOperationRef(inverse).withCapabilityFamily("fixture-family")
        .withRecordKind(OperationKind.REINDEX);
    assertEquals(kindFirst, kindLast);
    assertEquals(OperationKind.REINDEX, kindFirst.recordKind());
    assertEquals(OperationKind.OPERATION, original.recordKind());
    assertEquals(original.risk(), kindFirst.risk());
    assertEquals(original.confirm(), kindFirst.confirm());
    assertEquals(original.audit(), kindFirst.audit());
    assertEquals(original.retry(), kindFirst.retry());
    assertEquals(original.requiredCapabilities(), kindFirst.requiredCapabilities());
    assertEquals(original.undoSupported(), kindFirst.undoSupported());
    assertEquals(original.advisoryClass(), kindFirst.advisoryClass());
    assertEquals(Optional.of(inverse), kindFirst.inverseOperationRef());
    assertEquals(Optional.of("fixture-family"), kindFirst.capabilityFamily());
    assertThrows(NullPointerException.class, () -> original.withRecordKind(null));
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
}
