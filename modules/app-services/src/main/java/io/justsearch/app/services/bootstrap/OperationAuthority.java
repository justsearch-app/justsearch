/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.intent.IndexedRootGrantScope;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import io.justsearch.app.services.registry.executor.GlobalHardStop;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.app.services.worker.WatchedRootsState;
import java.nio.file.Path;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.executor.RecordedIngestPlanResolver;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.Optional;
import java.util.function.Function;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/** One preloaded authorization owner shared by recovery and subsequent request handling. */
public final class OperationAuthority {
  private final WatchedRootsState roots;
  private final DurableGrantStore grants;
  private final ConsentCapsuleService capsules = new ConsentCapsuleService();
  private final GlobalHardStop hardStop = new GlobalHardStop();
  private final IntentSourceCatalog sources = CoreIntentSourceCatalog.catalog();
  private final TrustEvaluator trust = new CoreTrustEvaluator();
  private final IntentGateEvaluator evaluator = new IntentGateEvaluator(trust, sources);
  private final IndexedRootGrantScope scope;
  private final CoreOperationCatalog coreOperations =
      new CoreOperationCatalog();
  private final AgentToolsOperationCatalog agentOperations = new AgentToolsOperationCatalog();

  private OperationAuthority(WatchedRootsState roots, DurableGrantStore grants) {
    this.roots = Objects.requireNonNull(roots, "roots");
    this.grants = Objects.requireNonNull(grants, "grants");
    scope = new IndexedRootGrantScope(Set.of(AgentToolsOperationCatalog.INGEST_FILES, CoreOperationCatalog.REINDEX));
    scope.bindIndexedRoots(context -> roots.watchedPaths());
    evaluator.setHardStopSignal(hardStop::isEngaged);
    hardStop.setOnEngage(() -> {
      capsules.revokeNonUser();
      grants.revokeNonUser();
    });
  }

  /** Load the process directory before launching any index bootstrap work. */
  public static OperationAuthority load(Path dataDirectory) {
    return load(dataDirectory, PersistenceMode.resolveMode());
  }

  static OperationAuthority load(Path dataDirectory, PersistenceMode mode) {
    Path directory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    WatchedRootsState roots = WatchedRootsState.load(directory);
    Path grantFile = Objects.requireNonNull(mode, "mode").isWritable()
        ? directory.resolve("ui").resolve("durable-grants.json") : null;
    return new OperationAuthority(roots, new DurableGrantStore(Clock.systemUTC(), grantFile));
  }

  /** Explicit isolated authority for test compositions without durable state. */
  public static OperationAuthority inMemory() {
    return new OperationAuthority(WatchedRootsState.inMemory(), new DurableGrantStore());
  }

  /** A point-in-time verdict only; the index owner must still claim under current authority. */
  public sealed interface RecordedIngestRecoveryDecision {
    record Authorized(RecordedRootPlan plan) implements RecordedIngestRecoveryDecision {
      public Authorized { Objects.requireNonNull(plan, "plan"); }
    }
    record Wait() implements RecordedIngestRecoveryDecision {}
    record Refused(OperationReceipt receipt) implements RecordedIngestRecoveryDecision {
      public Refused { Objects.requireNonNull(receipt, "receipt"); }
    }
  }

  /**
   * Revalidate one accepted frozen invocation without writing a row, granting a permit or running
   * an effect. Permanent refusals precede temporary readiness so revoked work cannot wait forever.
   * The caller supplies readiness from the initialized index runtime, not the later Head client.
   */
  public RecordedIngestRecoveryDecision evaluateRecordedIngest(
      OperationRecord row, OperationStore.Preparation stored, Optional<String> servingGeneration,
      Function<RequiredCapability, Boolean> capabilities) {
    Objects.requireNonNull(servingGeneration, "servingGeneration");
    Objects.requireNonNull(capabilities, "capabilities");
    final IngestPolicyBinding binding;
    final RecordedRootPlan plan;
    try {
      binding = ingestPolicyBinding(row);
      plan = new RecordedIngestPlanResolver().resolve(row, stored);
    } catch (IllegalArgumentException | NullPointerException invalidBinding) {
      return refused("RECOVERY_BINDING_INVALID");
    }
    Operation operation = binding.operation();
    TransportTag transport = binding.transport();
    OperationAuthorizationBasis basis = binding.basis();
    if (!scope.coversPlan(operation, plan, row.context())) {
      return refused("RECOVERY_SCOPE_REFUSED");
    }
    var verdict = evaluator.evaluate(operation.policy().risk(), transport);
    if (verdict.gateBehavior() == GateBehavior.DENY) {
      return refused("RECOVERY_AUTHORIZATION_REFUSED");
    }
    boolean authorized = switch (basis) {
      case OperationAuthorizationBasis.StructuralAuto ignored ->
          verdict.gateBehavior() == GateBehavior.AUTO;
      case OperationAuthorizationBasis.EphemeralCapsule ignored -> false;
      case OperationAuthorizationBasis.OperationGrant grant -> grants.isAllowed(
          new DurableGrantStore.DurableGrant(DurableGrantStore.GrantKind.OPERATION,
              grant.target(), grant.sourceTier()), operation.id().value(),
          operation.policy().capabilityFamily(), operation.policy().risk(), row.context());
      case OperationAuthorizationBasis.FamilyGrant grant -> grants.isAllowed(
          new DurableGrantStore.DurableGrant(DurableGrantStore.GrantKind.FAMILY,
              grant.target(), grant.sourceTier()), operation.id().value(),
          operation.policy().capabilityFamily(), operation.policy().risk(), row.context());
    };
    if (!authorized) {
      return refused("RECOVERY_AUTHORIZATION_REFUSED");
    }
    if (servingGeneration.isEmpty()) {
      return new RecordedIngestRecoveryDecision.Wait();
    }
    if (!plan.generation().equals(servingGeneration.orElseThrow())) {
      return refused("RECOVERY_GENERATION_MISMATCH");
    }
    try {
      for (RequiredCapability required : operation.policy().requiredCapabilities()) {
        if (!Boolean.TRUE.equals(capabilities.apply(required))) {
          return new RecordedIngestRecoveryDecision.Wait();
        }
      }
    } catch (RuntimeException unavailableCapability) {
      return new RecordedIngestRecoveryDecision.Wait();
    }
    return new RecordedIngestRecoveryDecision.Authorized(plan);
  }

  /**
   * Current continuation policy for one already-bound child effect. This boolean does not prove
   * fresh origin or grant permission: only the private winning child body may combine it with its
   * live server/generation activation and admitted parent. Never use it for restart reconciliation.
   * The caller validates the immutable parent envelope and exact child membership outside the jobs
   * lock; the accepted row snapshot need not already say RUNNING. No capsule is consumed here.
   */
  public boolean allowsFreshRecordedIngest(OperationRecord parent, RecordedRootPlan boundPlan) {
    if (boundPlan == null || boundPlan.roots().size() != 1) {
      return false;
    }
    final IngestPolicyBinding binding;
    try {
      binding = ingestPolicyBinding(parent);
    } catch (IllegalArgumentException | NullPointerException invalidBinding) {
      return false;
    }
    Operation operation = binding.operation();
    var verdict = evaluator.evaluate(operation.policy().risk(), binding.transport());
    if (verdict.gateBehavior() == GateBehavior.DENY) {
      return false;
    }
    return switch (binding.basis()) {
      case OperationAuthorizationBasis.StructuralAuto ignored ->
          verdict.gateBehavior() == GateBehavior.AUTO;
      case OperationAuthorizationBasis.EphemeralCapsule ignored -> true;
      case OperationAuthorizationBasis.OperationGrant grant ->
          scope.coversPlan(operation, boundPlan, parent.context()) && grants.isAllowed(
              new DurableGrantStore.DurableGrant(DurableGrantStore.GrantKind.OPERATION,
                  grant.target(), grant.sourceTier()), operation.id().value(),
              operation.policy().capabilityFamily(), operation.policy().risk(), parent.context());
      case OperationAuthorizationBasis.FamilyGrant grant ->
          scope.coversPlan(operation, boundPlan, parent.context()) && grants.isAllowed(
              new DurableGrantStore.DurableGrant(DurableGrantStore.GrantKind.FAMILY,
                  grant.target(), grant.sourceTier()), operation.id().value(),
              operation.policy().capabilityFamily(), operation.policy().risk(), parent.context());
    };
  }

  private IngestPolicyBinding ingestPolicyBinding(OperationRecord parent) {
    String operationRef = parent.descriptor().operationRef();
    boolean ingest = parent.descriptor().kind() == OperationKind.INGEST
        && AgentToolsOperationCatalog.INGEST_FILES.value().equals(operationRef);
    boolean reindex = parent.descriptor().kind() == OperationKind.REINDEX
        && CoreOperationCatalog.REINDEX.value().equals(operationRef);
    if (!ingest && !reindex) {
      throw new IllegalArgumentException("Unsupported recorded ingestion producer");
    }
    Operation operation = coreOperations.findByIdValue(operationRef)
        .or(() -> agentOperations.findByIdValue(operationRef)).orElseThrow(
            () -> new IllegalArgumentException("Unknown recorded operation"));
    TransportTag transport = TransportTag.valueOf(parent.context().transport());
    if (sources.findByTransport(transport).isEmpty()) {
      throw new IllegalArgumentException("Unregistered recorded transport");
    }
    EngineProvenance.sourceTier(parent.context());
    OperationAuthorizationBasis basis =
        OperationAuthorizationBasis.decode(parent.context().grantReference().orElse(null));
    return new IngestPolicyBinding(operation, transport, basis);
  }

  /** Short-lived projection of existing catalog and row fields, never an execution capability. */
  private record IngestPolicyBinding(
      Operation operation, TransportTag transport, OperationAuthorizationBasis basis) {}

  private static RecordedIngestRecoveryDecision.Refused refused(String code) {
    return new RecordedIngestRecoveryDecision.Refused(new OperationReceipt(code, null));
  }

  public WatchedRootsState roots() { return roots; }
  public DurableGrantStore grants() { return grants; }
  public ConsentCapsuleService capsules() { return capsules; }
  public GlobalHardStop hardStop() { return hardStop; }
  public IntentSourceCatalog sources() { return sources; }
  public TrustEvaluator trust() { return trust; }
  public IntentGateEvaluator evaluator() { return evaluator; }
  public IndexedRootGrantScope scope() { return scope; }
  public CoreOperationCatalog coreOperations() { return coreOperations; }
  public AgentToolsOperationCatalog agentOperations() { return agentOperations; }
}
