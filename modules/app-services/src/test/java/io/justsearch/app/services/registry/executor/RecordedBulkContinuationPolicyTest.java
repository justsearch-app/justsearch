/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class RecordedBulkContinuationPolicyTest {
  @Test
  void changedCatalogContractCannotInheritAnEarlierBulkApproval() {
    var catalog = new CoreOperationCatalog();
    for (var profile : RecordedBulkPlan.Profile.values()) {
      Operation current = catalog.findByIdValue(profile.operationRef()).orElseThrow();
      assertTrue(RecordedBulkPlan.continuationPolicy(current));
      for (var changed : List.of(
          policy(RiskTier.MEDIUM, ConfirmStrategy.Inline.INSTANCE, OperationKind.REINDEX, EngineContext.Survival.DURABLE),
          policy(RiskTier.HIGH, ConfirmStrategy.None.INSTANCE, OperationKind.REINDEX, EngineContext.Survival.DURABLE),
          policy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE, OperationKind.OPERATION, EngineContext.Survival.DURABLE),
          policy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE, OperationKind.REINDEX, EngineContext.Survival.INTERACTIVE))) {
        assertFalse(RecordedBulkPlan.continuationPolicy(withPolicy(current, changed)), changed.toString());
      }
    }
  }

  @Test
  void unrelatedOperationCannotOptInOnlyByCopyingBulkPolicy() {
    var catalog = new CoreOperationCatalog();
    Operation ordinary = catalog.findByIdValue(CoreOperationCatalog.REINDEX.value()).orElseThrow();
    Operation bulk = catalog.findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
    assertFalse(RecordedBulkPlan.continuationPolicy(withPolicy(ordinary, bulk.policy())));
  }

  private static OperationPolicy policy(RiskTier risk, ConfirmStrategy confirm, OperationKind kind,
      EngineContext.Survival survival) {
    return new OperationPolicy(risk, confirm, AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(), Set.of(), false)
        .withRecordKind(kind).withDeclaredSurvival(survival);
  }

  @Test
  void alternateProducerCannotBorrowTheCoreBulkNameAndPolicy() {
    var current = new CoreOperationCatalog().findByIdValue(CoreOperationCatalog.BULK_REINDEX.value()).orElseThrow();
    var alternateBinding = new Operation(current.id(), current.presentation(), current.intf(), current.policy(),
        current.availability(), current.lineage(), new io.justsearch.agent.api.registry.Binding("plugin.bulk"),
        current.provenance(), current.executors(), current.audience(), current.consumers());
    assertFalse(RecordedBulkPlan.continuationPolicy(alternateBinding));
    var plugin = new Operation(current.id(), current.presentation(), current.intf(), current.policy(),
        current.availability(), current.lineage(), current.binding(),
        new io.justsearch.agent.api.registry.Provenance(io.justsearch.agent.api.registry.TrustTier.TRUSTED_PLUGIN,
            "plugin", "1"), current.executors(), current.audience(), current.consumers());
    assertFalse(RecordedBulkPlan.continuationPolicy(plugin));
  }

  private static Operation withPolicy(Operation operation, OperationPolicy policy) {
    return new Operation(operation.id(), operation.presentation(), operation.intf(), policy,
        operation.availability(), operation.lineage(), operation.binding(), operation.provenance(),
        operation.executors(), operation.audience(), operation.consumers());
  }
}
