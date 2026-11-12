/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.indexing.SourceOrigin;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OriginApprovalEvaluatorTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC);
  @TempDir Path directory;

  @Test
  void durableOriginEvidenceNeedsNeitherOperationHistoryNorWatchedCoverage() {
    var authority = OperationAuthority.inMemory();
    var auto = origin(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.SYSTEM_INTERNAL);
    assertTrue(authority.roots().watchedPaths().isEmpty());
    assertTrue(authority.evaluateOriginApproval(auto));
    for (OperationAuthorizationBasis basis : List.of(
        new OperationAuthorizationBasis.OperationGrant("core.ingest-files", SourceTier.UNTRUSTED),
        new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED))) {
      authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      authority.grants().revokeFamily("file-operations", SourceTier.UNTRUSTED);
      var origin = origin(basis, TransportTag.MCP);
      assertFalse(authority.evaluateOriginApproval(origin));
      authority.grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
      authority.grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
      assertTrue(authority.evaluateOriginApproval(origin));
      if (basis instanceof OperationAuthorizationBasis.OperationGrant) authority.grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
      else authority.grants().revokeFamily("file-operations", SourceTier.UNTRUSTED);
      assertFalse(authority.evaluateOriginApproval(origin), "another matching grant must not substitute");
    }
  }

  @Test
  void strictBasisGateAndTransportValidationNeverInferPermission() {
    var authority = OperationAuthority.inMemory();
    var auto = origin(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.SYSTEM_INTERNAL);
    assertFalse(authority.evaluateOriginApproval(null));
    assertFalse(authority.evaluateOriginApproval(new SourceOrigin(auto.id(), auto.kind(), auto.root(), auto.approval(), 1)), "legacy jsa1 text has no restart authority");
    assertFalse(authority.evaluateOriginApproval(origin(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.MCP)), "AUTO must still be AUTO");
    assertFalse(authority.evaluateOriginApproval(origin(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.PLUGIN_EMITTED)));
    var mismatch = new SourceOrigin.Approval(auto.approval().authorizationBasis(), "SYSTEM_INTERNAL", "UNTRUSTED", "INTERNAL", "core.ingest-files", auto.approval().operationKey(), SourceOrigin.ApprovalClass.DURABLE, SourceOrigin.EvidenceSource.GATE);
    assertFalse(authority.evaluateOriginApproval(new SourceOrigin(auto.id(), auto.kind(), auto.root(), mismatch, 1, auto.restartBinding())));
    assertFalse(authority.evaluateOriginApproval(origin(new OperationAuthorizationBasis.PreparedContinuation(OperationKeys.generate(CLOCK), UUID.randomUUID()), TransportTag.SYSTEM_INTERNAL)));
    var declared = SourceOrigin.accepted(SourceOrigin.Kind.IMPORT, auto.root(), SourceOrigin.Approval.declared(context(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.SYSTEM_INTERNAL)), 1);
    assertFalse(authority.evaluateOriginApproval(declared));
    var granted = origin(new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED), TransportTag.MCP);
    authority.grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertTrue(authority.evaluateOriginApproval(granted));
    authority.hardStop().engage();
    authority.grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertFalse(authority.evaluateOriginApproval(granted));
  }

  @Test
  void activeBindingRequiresExactParentPlanRevisionAndUninterruptedSelection() {
    var authority = OperationAuthority.inMemory();
    var origin = origin(new OperationAuthorizationBasis.StructuralAuto(), TransportTag.SYSTEM_INTERNAL);
    var restart = origin.restartBinding();
    var binding = new ImportApprovalBinding(1, restart.bindingId(), origin.approval().operationKey(), null, restart.planHash(), 4);
    assertTrue(authority.evaluateImportApproval(origin, new ImportApprovalState(binding, 4, null), null));
    assertFalse(authority.evaluateImportApproval(origin, new ImportApprovalState(null, 0, null), null));
    assertFalse(authority.evaluateImportApproval(origin, new ImportApprovalState(binding, 5, null), null));
    assertFalse(authority.evaluateImportApproval(origin, new ImportApprovalState(binding, 4, "revoked"), null), "recreated grants do not clear interruption");
    for (ImportApprovalBinding wrong : List.of(
        new ImportApprovalBinding(1, UUID.randomUUID().toString(), binding.parentOperationKey(), null, binding.planHash(), 4),
        new ImportApprovalBinding(1, binding.id(), OperationKeys.generate(CLOCK), null, binding.planHash(), 4),
        new ImportApprovalBinding(1, binding.id(), binding.parentOperationKey(), null, "b".repeat(64), 4))) {
      assertFalse(authority.evaluateImportApproval(origin, new ImportApprovalState(wrong, 4, null), null));
    }
    assertEquals(binding, ImportApprovalBinding.decode(binding.encode()));
    assertThrows(RuntimeException.class, () -> ImportApprovalBinding.decode(binding.encode().replace("\"version\":1", "\"version\":99")));
    assertThrows(RuntimeException.class, () -> ImportApprovalBinding.decode(binding.encode().replace("\"version\":1", "\"version\":1,\"version\":1")));
    assertThrows(RuntimeException.class, () -> ImportApprovalBinding.decode(binding.encode().replace("\"version\":1", "\"version\":\"1\"")));
  }

  @Test
  void sessionRequiresExactLiveAcceptedOwnerFromThisAuthority() {
    var authority = OperationAuthority.inMemory();
    var origin = origin(new OperationAuthorizationBasis.EphemeralCapsule(), TransportTag.MCP);
    var plan = new RecordedRootPlan("generation", List.of(origin.root()));
    var live = new AtomicBoolean(true);
    var row = new OperationRecord(1, origin.approval().operationKey(),
        OperationDescriptor.invocation(OperationKind.INGEST, "core.ingest-files", "{}", false),
        context(new OperationAuthorizationBasis.EphemeralCapsule(), TransportTag.MCP), "AGENT", null, null,
        OperationState.RUNNING, null, null, 0, 0, 0, 1, 1L, 1, null, null, null,
        OperationHistoryMode.NONE, null, null);
    assertFalse(authority.evaluateOriginApproval(origin));
    var binding = new ImportApprovalBinding(1, origin.restartBinding().bindingId(), origin.approval().operationKey(),
        null, origin.restartBinding().planHash(), 0);
    var active = new ImportApprovalState(binding, 0, null);
    assertFalse(authority.evaluateImportApproval(origin, null, null), "missing active selection cannot authorize");
    var proof = authority.liveOriginApproval(row, plan, live::get);
    assertTrue(authority.evaluateImportApproval(origin, active, proof));
    assertFalse(OperationAuthority.inMemory().evaluateImportApproval(origin, active, proof), "new process cannot reuse live ownership");
    var other = origin(new OperationAuthorizationBasis.EphemeralCapsule(), TransportTag.MCP);
    assertFalse(authority.evaluateImportApproval(other, active, proof));
    var successorBinding = new ImportApprovalBinding(1, OperationKeys.generate(CLOCK), row.key(),
        binding.id(), binding.planHash(), 1);
    var successor = new SourceOrigin(UUID.randomUUID().toString(), origin.kind(), origin.root(), origin.approval(),
        origin.acceptedAt(), new SourceOrigin.RestartBinding(1, successorBinding.id(), binding.planHash(), origin.id()));
    assertFalse(authority.evaluateImportApproval(successor, new ImportApprovalState(successorBinding, 1, null), proof),
        "a previous live proof cannot authorize the selected renewal");
    live.set(false);
    assertFalse(authority.evaluateImportApproval(origin, active, proof));
    assertThrows(IllegalArgumentException.class, () -> authority.liveOriginApproval(row, plan, live::get));
  }

  private SourceOrigin origin(OperationAuthorizationBasis basis, TransportTag transport) {
    var root = new RecordedRootPlan.Root(directory.resolve("import"), "docs", false, false, List.of(), List.of());
    String hash = CanonicalOperationArguments.digest(new RecordedRootPlan("generation", List.of(root)).toReplayPayload());
    String key = OperationKeys.generate(CLOCK);
    return new SourceOrigin(UUID.randomUUID().toString(), SourceOrigin.Kind.IMPORT, root,
        SourceOrigin.Approval.gated(context(basis, transport), "core.ingest-files", key), 1,
        new SourceOrigin.RestartBinding(1, key, hash, null));
  }

  private static EngineContext context(OperationAuthorizationBasis basis, TransportTag transport) {
    return EngineProvenance.context(EngineContext.ClientKind.MCP_CLIENT, "primitive", Optional.empty(), Optional.of(basis.encode()), transport,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
  }
}
