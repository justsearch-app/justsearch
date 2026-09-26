/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.Plugin;
import io.justsearch.agent.api.registry.PluginContributions;
import io.justsearch.agent.api.registry.PluginRef;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.app.services.registry.preview.CapabilityAvailability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tempdoc 560 WS4 — the operation-catalog collapse + two-phase composition.
 *
 * <p>Before WS4 the boot built {@code CapabilityAvailability.withCapabilityDerivedAvailability} over
 * the core catalog and (separately) the agent-tools catalog, then appended MCP-host operations to the
 * agent-tools catalog <em>after</em> the derivation — so any MCP (or other post-merge) contribution
 * carrying a {@link io.justsearch.agent.api.registry.RequiredCapability} silently bypassed the
 * capability-derived availability gate. This collapses the three sources (core + agent-tools + the
 * MCP-host's already-installed contributions) into the one {@link ContributionRegistry} composer and
 * derives availability <strong>once over the full merged set</strong>, closing that gap structurally.
 *
 * <p>Two-phase, because the MCP-host installs its contributions into the same registry between the
 * two base installs and the derivation:
 *
 * <ol>
 *   <li>{@link #installBaseCatalogs} — install the core + agent-tools static catalogs as CORE-owner
 *       contributions (the {@code core.navigate-to-surface} duplicate was reconciled to a single
 *       canonical declaration in {@code AgentToolsOperationCatalog} so the two install collision-free).
 *   <li>(the caller connects the MCP-host, which installs its servers' contributions into the same
 *       registry)
 *   <li>{@link #deriveAndPartition} — derive availability once over {@code registry.operations()},
 *       then partition the merged set back into the dual-catalog shape every downstream consumer
 *       still reads (UI registry path vs. agent-tools / executor), keyed by contribution owner.
 * </ol>
 *
 * <p>The dual-catalog API is preserved (rather than collapsed to a single catalog) to keep the
 * blast radius bounded: {@code OperationSubstrateInit} unions the two, {@code RegistryController}
 * flat-maps both, and the agent loop reads the agent-tools partition — all unchanged. The literal
 * single-window unification of those readers is WS5's concern.
 */
public final class OperationCatalogComposition {

  /** Contribution owner for the core operation catalog. */
  public static final PluginRef CORE_OWNER = new PluginRef("core.operations");

  /** Contribution owner for the agent-tools operation catalog (+ where MCP ops partition to). */
  public static final PluginRef AGENT_TOOLS_OWNER = new PluginRef("core.agent-tools");

  /** Contribution owner for projected workflow tools (tempdoc 560 WS5; partition to agent-tools). */
  public static final PluginRef WORKFLOW_OWNER = new PluginRef("core.workflows");

  private OperationCatalogComposition() {}

  /** The partitioned dual-catalog result every downstream consumer still reads. */
  public record Result(OperationCatalog operationCatalog, OperationCatalog agentToolsCatalog) {}

  /** A tooling facade without the finite ingestion owner cannot offer its durable operations. */
  public static OperationCatalog forRecordedIngestionOwner(OperationCatalog catalog,
      io.justsearch.app.api.operations.RecordedIngestionService ingestion) {
    if (ingestion.hasOwner()) return catalog;
    return OperationCatalog.of(catalog.namespace(), catalog.definitions().stream()
        .filter(operation -> operation.policy().recordKind() != io.justsearch.agent.api.registry.OperationKind.INGEST
            && operation.policy().recordKind() != io.justsearch.agent.api.registry.OperationKind.REINDEX).toList());
  }

  /**
   * Phase 1: install the two static catalogs into the one registry as CORE-owner contributions.
   * Idempotent only across distinct registries — call once per boot. Both are CORE-tier so they may
   * mint {@code core.*} refs; the install validates ref-uniqueness across all owners (the reconciled
   * {@code navigate-to-surface} no longer collides).
   */
  public static void installBaseCatalogs(
      ContributionRegistry registry, OperationCatalog coreBase, OperationCatalog agentToolsBase) {
    registry.install(
        new ContributionRegistry.Installation(
            ownerPlugin(CORE_OWNER), coreBase.definitions(), Map.of()));
    registry.install(
        new ContributionRegistry.Installation(
            ownerPlugin(AGENT_TOOLS_OWNER), agentToolsBase.definitions(), Map.of()));
  }

  /**
   * Phase 1c (tempdoc 560 WS5): install the projected workflow tools as a CORE-owner contribution.
   * They partition to the agent-tools catalog (owner != {@link #CORE_OWNER}), so the model sees
   * workflows in the same tool list as core operations and MCP tools. No-op when none are projected.
   */
  public static void installWorkflowOps(
      ContributionRegistry registry, List<Operation> workflowOps) {
    if (workflowOps.isEmpty()) {
      return;
    }
    registry.install(
        new ContributionRegistry.Installation(
            ownerPlugin(WORKFLOW_OWNER), workflowOps, Map.of()));
  }

  /**
   * Phase 2: derive capability-availability ONCE over the full composed set (core + agent-tools +
   * any MCP-host contributions installed in between), then partition back by owner. An operation owned
   * by {@link #CORE_OWNER} lands in the {@code operationCatalog}; everything else (agent-tools + MCP)
   * lands in the {@code agentToolsCatalog} — reproducing the pre-WS4 split while guaranteeing every
   * operation, whenever it was contributed, passed through the single derivation.
   */
  public static Result deriveAndPartition(
      ContributionRegistry registry, String coreNamespace, String agentToolsNamespace) {
    OperationCatalog merged =
        CapabilityAvailability.withCapabilityDerivedAvailability(
            OperationCatalog.of(coreNamespace, registry.operations()));
    List<Operation> coreOps = new ArrayList<>();
    List<Operation> agentOps = new ArrayList<>();
    for (Operation op : merged.definitions()) {
      if (registry.ownerOf(op.id()).filter(CORE_OWNER::equals).isPresent()) {
        coreOps.add(op);
      } else {
        agentOps.add(op);
      }
    }
    return new Result(
        OperationCatalog.of(coreNamespace, coreOps),
        OperationCatalog.of(agentToolsNamespace, agentOps));
  }

  private static Plugin ownerPlugin(PluginRef id) {
    return new Plugin(
        id,
        Presentation.of(
            new I18nKey("plugin." + id.value() + ".label"),
            new I18nKey("plugin." + id.value() + ".description")),
        Provenance.core("1.0"),
        Audience.OPERATOR,
        PluginContributions.empty(),
        // §5 keystone: a Plugin is NonEmpty<ConsumerHook>. The CORE catalogs are consumed by the
        // operation executor + the registry surfaces; declare a single realized consumer so the
        // owner manifest is representable.
        List.of(new ConsumerHook.Realized(id.value(), Audience.OPERATOR)));
  }
}
