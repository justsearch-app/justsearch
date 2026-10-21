/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.snapshot;

import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.ConsumerDeclaring;
import io.justsearch.agent.api.registry.ConsumerHook;
import io.justsearch.agent.api.registry.ContributionRegistry;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.Prompt;
import io.justsearch.agent.api.registry.PromptCatalog;
import io.justsearch.agent.api.registry.Resource;
import io.justsearch.agent.api.registry.ResourceCatalog;
import io.justsearch.app.services.bootstrap.phases.BootstrapHelpers;
import io.justsearch.app.services.bootstrap.phases.OperationCatalogComposition;
import io.justsearch.app.services.bootstrap.phases.ResourceSubstrateInit;
import io.justsearch.app.services.mcphost.McpHostService;
import io.justsearch.app.services.registry.SurfaceConsumerIndex;
import io.justsearch.app.services.registry.emitter.AgentOperationEmitter;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Serializes the registry declarations across the <b>Operation + Resource + Prompt</b> axes (§4.4),
 * each with its <em>merged</em> consumer set, to a JSON snapshot the Node {@code consumer-presence}
 * governance gate reads (tempdoc 560 §5/§6 — the NonEmpty&lt;ConsumerHook&gt; keystone). Governance
 * gates run in Node with no JVM, and the source {@code consumers()} is always empty in production
 * (real consumers are <em>derived</em>), so the truth must be computed in-JVM here and handed across
 * as a JSON artifact under {@code tmp/}.
 *
 * <p><b>Merged consumer rule.</b> An operation's consumers are its inline {@link ConsumerHook}s
 * union its <em>executor-derived</em> consumers: every {@link ExecutorTag} is a real consuming
 * surface (AGENT ⇒ the agent loop — witnessed by {@code AgentOperationEmitter}; UI ⇒ the UI host;
 * CLI ⇒ the CLI). An operation with no executor and no inline consumer is genuinely unconsumed and
 * the gate fails it. (Surface-derived consumers from {@code SurfaceConsumerIndex} are an additional
 * source that can be folded in when the surface catalog is wired into this exporter.)
 */
public final class RegistrySnapshotExporter {
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  public static final String DEFAULT_RELATIVE_PATH = "tmp/consumer-presence/registry-snapshot.json";

  private RegistrySnapshotExporter() {}

  /** One snapshot row: a declaration + its merged consumer count, audience, and provenance tier. */
  public record Entry(
      String id,
      String kind,
      int consumerCount,
      List<String> consumers,
      List<String> executors,
      String audience,
      String provenance) {}

  /**
   * The operation set this snapshot describes, composed the way production composes it
   * ({@code SubstratePhase.run}, the two-phase {@link OperationCatalogComposition}): the two static
   * base catalogs install into ONE {@link ContributionRegistry}, the workflow projection resolves
   * against that registry and installs its {@code core.workflow-*} tools into the SAME registry, and
   * {@code deriveAndPartition} then derives capability-availability once over the merged set. The
   * order is mirrored rather than re-invented — the projection resolves each workflow's
   * {@code ToolStep}s against what the registry already holds, so installing the bases first is
   * load-bearing (tempdoc 876 B.4/B.6).
   *
   * <p><b>Offline by construction.</b> The exporter has no MCP host, so
   * {@code new McpHostService(List.of())} connects to nothing and contributes nothing. That is the
   * intended reading: the snapshot describes the SHIPPED static + projected set — what every install
   * has before an operator configures an MCP server. {@code core.demo-compose}, whose ToolSteps
   * compose {@code vendor.mcphost.*}, is therefore correctly not projected. The dev-gated
   * {@code ExamplePlugin} is likewise omitted: a build artifact must not vary with a
   * {@code -Djustsearch.demo.plugin} system property.
   *
   * <p>Public because it is the ONE build-tier composition: {@link #buildOperationEntries()}, the
   * runtime witness and the shape-validator harness all read it rather than each re-deriving a
   * composition of their own (the representation-drift class this tempdoc closes).
   */
  public static List<Operation> composedOperations() {
    CoreOperationCatalog coreBase = new CoreOperationCatalog();
    AgentToolsOperationCatalog agentToolsBase = new AgentToolsOperationCatalog();
    try (McpHostService mcpHost = new McpHostService(List.of())) {
      ContributionRegistry contributions = mcpHost.contributionRegistry();
      OperationCatalogComposition.installBaseCatalogs(contributions, coreBase, agentToolsBase);
      mcpHost.connect();
      OperationCatalogComposition.installWorkflowOps(
          contributions,
          io.justsearch.app.services.conversation.WorkflowOperationProjection.project(
              io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog(),
              contributions.operations()));
      OperationCatalogComposition.Result composed =
          OperationCatalogComposition.deriveAndPartition(
              contributions, coreBase.namespace(), agentToolsBase.namespace());
      List<Operation> ops = new ArrayList<>(composed.operationCatalog().definitions());
      ops.addAll(composed.agentToolsCatalog().definitions());
      return List.copyOf(ops);
    }
  }

  /**
   * Build the snapshot rows for every Operation the composed substrate holds: the core and
   * agent-tools base catalogs plus the projected {@code core.workflow-*} tools
   * ({@link #composedOperations()}). Both this and {@link #agentWitnessDeliveredIds()} read that one
   * composition, so the two sides the {@code runtime-witness} gate cross-checks cannot describe
   * different sets.
   */
  public static List<Entry> buildOperationEntries() {
    List<Operation> ops = composedOperations();

    List<Entry> entries = new ArrayList<>();
    for (Operation op : ops) {
      List<String> consumerIds = operationConsumerIds(op);
      entries.add(
          new Entry(
              op.id().value(),
              "operation",
              consumerIds.size(),
              consumerIds,
              op.executors().stream().map(Enum::name).sorted().toList(),
              op.audience().name(),
              op.provenance().tier().name()));
    }
    return entries;
  }

  /**
   * The merged consumer ids for an operation: its inline {@link ConsumerHook}s union its
   * executor-derived consumers (every {@link ExecutorTag} implies a consumer — {@code AGENT} →
   * {@code agent-loop}, etc.). This is the SINGLE consumer-presence merge the build-tier snapshot
   * uses; the live-registry witness ({@link LiveWitness}, ADR-0042) reuses it verbatim over the live
   * registry rather than forking the notion. An operation with at least one executor therefore always
   * has a consumer; only a zero-executor, zero-inline-consumer operation is an orphan.
   */
  public static List<String> operationConsumerIds(Operation op) {
    Map<String, ConsumerHook> merged = new LinkedHashMap<>();
    for (ConsumerHook hook : op.consumers()) {
      merged.putIfAbsent(hook.consumerId(), hook);
    }
    for (ExecutorTag tag : op.executors()) {
      String consumerId = executorConsumerId(tag);
      merged.putIfAbsent(consumerId, new ConsumerHook.Realized(consumerId, executorAudience(tag)));
    }
    return List.copyOf(merged.keySet());
  }

  /**
   * Build the rows for every OBSERVABLE Resource declaration (tempdoc 560 §4.4). A resource's
   * consumers are its inline {@link ConsumerHook}s union its <em>surface-derived</em> consumers
   * (a Surface that declares it in {@code consumes.resources} — {@link SurfaceConsumerIndex}); there
   * is no executor-derived consumer for resources. A resource that no surface consumes and that
   * declares no inline consumer is unconsumed and the gate fails it.
   */
  public static List<Entry> buildResourceEntries() {
    List<Entry> entries = new ArrayList<>();
    ResourceSubstrateInit.Output out;
    try {
      out = ResourceSubstrateInit.run(BootstrapHelpers.initialRuntimeContext());
    } catch (RuntimeException e) {
      System.err.println( // NOPMD - offline fallback diagnostic; this exporter has no SLF4J logger
          "RegistrySnapshotExporter: resource axis unavailable offline: " + e);
      return entries;
    }
    SurfaceConsumerIndex index = new SurfaceConsumerIndex(List.of(out.coreSurfaceCatalog()));
    List<ResourceCatalog> catalogs =
        List.of(
            out.resourceCatalog(),
            out.runtimeContextResourceCatalog(),
            out.serverCapabilitiesResourceCatalog(),
            // Tempdoc 560 WS7b — the Brain inference-runtime OBSERVABLE participant.
            out.coreInferenceResourceCatalog(),
            // Tempdoc 575 §17 Face C — Brain install/pack OBSERVABLE polled-state participants.
            out.aiInstallResourceCatalog(),
            out.aiPackImportResourceCatalog(),
            out.indexingJobsResourceCatalog(),
            out.failedIndexingJobsResourceCatalog(),
            out.indexedRootsResourceCatalog(),
            out.conditionRecoveryIndexResourceCatalog());
    for (ResourceCatalog catalog : catalogs) {
      for (Resource r : catalog.definitions()) {
        // Tempdoc 560 Fix D: a Resource's consumers are its inline ConsumerHooks ∪ surface-derived
        // (a Surface that declares it in consumes.resources). We do NOT count "has an SSE_STREAM
        // endpoint" as a consumer — that is the 543 "counting-substrate-as-consumer" trap (an
        // endpoint is a PUBLICATION capability, not proof a subscriber reads it). A resource whose
        // real reader is an ad-hoc bridge declares that bridge as an inline ConsumerHook (honest);
        // a resource with no reader at all stays unconsumed and is frozen-at-reserved via exemptions.
        List<ConsumerHook> merged =
            SurfaceConsumerIndex.merge(r.consumers(), index.consumersOf(r.id()));
        entries.add(
            new Entry(
                r.id().value(),
                "resource",
                merged.size(),
                merged.stream().map(ConsumerHook::consumerId).toList(),
                List.of(),
                r.audience().name(),
                r.provenance().tier().name()));
      }
    }
    return entries;
  }

  /**
   * Generic snapshot of any {@link ConsumerDeclaring} kind whose consumers are the inline
   * {@link ConsumerHook}s only (no executor-/surface-/stream-derived consumers). Tempdoc 560 §4.1:
   * because the consumers axis is now lifted to one shared position ({@link ConsumerDeclaring}), one
   * helper covers every such kind uniformly — the keystone gate polices them all the same way.
   */
  private static List<Entry> inlineConsumerEntries(
      String kind, List<? extends ConsumerDeclaring> declarations) {
    List<Entry> entries = new ArrayList<>();
    for (ConsumerDeclaring d : declarations) {
      Map<String, ConsumerHook> merged = new LinkedHashMap<>();
      for (ConsumerHook hook : d.consumers()) {
        merged.putIfAbsent(hook.consumerId(), hook);
      }
      entries.add(
          new Entry(
              d.id().value(),
              kind,
              merged.size(),
              List.copyOf(merged.keySet()),
              List.of(),
              d.audience().name(),
              d.provenance().tier().name()));
    }
    return entries;
  }

  /**
   * Build the rows for every LANGUAGE_MEDIATED Prompt declaration (§4.4). The core PromptCatalog is
   * currently the empty stub, so this yields no rows today — but the axis is structurally covered, so
   * a future prompt declared without a consumer fails the gate.
   */
  public static List<Entry> buildPromptEntries() {
    return inlineConsumerEntries("prompt", PromptCatalog.of("core", List.of()).definitions());
  }

  /**
   * Build the rows for every LANGUAGE_MEDIATED Workflow Manifest declaration (tempdoc 560 §4.4 / Fix A).
   * The keystone gate must police the Manifest types this substrate added, not only the primitive axes —
   * the core workflow catalog is the static source, so a future {@link io.justsearch.agent.api.registry.Workflow}
   * declared with zero consumers now fails the build (it previously shipped invisibly).
   */
  public static List<Entry> buildWorkflowEntries() {
    return inlineConsumerEntries(
        "workflow",
        io.justsearch.app.services.conversation.CoreWorkflowCatalog.catalog().definitions());
  }

  /**
   * Build the rows for every COMPOSITION Plugin Manifest declaration. No static core PluginCatalog exists
   * today (the only Plugin producer is the runtime MCP-host projection, which is consumer-backed by
   * construction), so this yields no rows — but the axis is structurally covered like the empty Prompt axis:
   * a future statically-declared zero-consumer Plugin fails the gate.
   */
  public static List<Entry> buildPluginEntries() {
    return inlineConsumerEntries(
        "plugin",
        io.justsearch.agent.api.registry.PluginCatalog.of("core", List.of()).definitions());
  }

  /** All declarations across the Operation + Resource + Prompt + Workflow + Plugin axes. */
  public static List<Entry> buildEntries() {
    List<Entry> all = new ArrayList<>();
    all.addAll(buildOperationEntries());
    all.addAll(buildResourceEntries());
    all.addAll(buildPromptEntries());
    all.addAll(buildWorkflowEntries());
    all.addAll(buildPluginEntries());
    return all;
  }

  /**
   * Serialize the snapshot to JSON ({@code {version, entries:[...], witness:{agentDelivered:[...]}}}).
   * The {@code witness.agentDelivered} block is the §5 runtime-witness datum the JVM-less Node gate
   * cannot compute (it requires running the real {@link AgentOperationEmitter}); it is the set of
   * operation ids the agent's prompt-construction channel actually offers.
   */
  public static String toJson(List<Entry> entries) {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", "1");
    root.put("entries", entries);
    Map<String, Object> witness = new LinkedHashMap<>();
    witness.put("agentDelivered", List.copyOf(agentWitnessDeliveredIds()));
    root.put("witness", witness);
    return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
  }

  /** Write the snapshot to {@code <repoRoot>/tmp/consumer-presence/registry-snapshot.json}. */
  public static Path writeDefault() throws IOException {
    Path out = repoRoot().resolve(DEFAULT_RELATIVE_PATH);
    Files.createDirectories(out.getParent());
    Files.writeString(out, toJson(buildEntries()));
    return out;
  }

  public static void main(String[] args) throws IOException {
    Path out =
        args.length > 0 ? Path.of(args[0]) : repoRoot().resolve(DEFAULT_RELATIVE_PATH);
    Files.createDirectories(out.getParent());
    Files.writeString(out, toJson(buildEntries()));
    System.out.println("Wrote registry snapshot: " + out); // NOPMD - CLI tool stdout output
  }

  /**
   * The runtime-witness for the AGENT consumer (tempdoc 560 §5; AGENT = prompt-construction): the set
   * of operation ids the real {@link AgentOperationEmitter} actually projects into the model's tool
   * list. An operation whose snapshot consumers include {@code "agent-loop"} but whose id is NOT in
   * this set is <em>over-claimed</em> — the static referencer gate sees a declared consumer, but the
   * delivery channel (the constructed tool list) never carries it (e.g. the op is audience-filtered).
   *
   * <p>This is the second, non-static half of the keystone: it runs the genuine emitter (not a
   * re-derivation of the same executor tags) over the same composed set {@link #buildOperationEntries()}
   * describes, and compares declared-vs-delivered. The emitter applies its own audience allow-list
   * ({@code USER}/{@code AGENT}), so OPERATOR-audience ops that merely carry {@link ExecutorTag#AGENT}
   * are correctly absent.
   */
  public static java.util.Set<String> agentWitnessDeliveredIds() {
    List<Operation> ops = composedOperations();
    var catalog = OperationCatalog.of("core", ops);
    // Tempdoc 876 B.6 — the healthy-stack assumption is STATED, not inherited from a null check. A
    // bare AgentOperationEmitter leaves conditionFiring null, which the emitter reads as "do not
    // filter on availability at all"; the explicit conditionId -> false probe says what the witness
    // actually means ("with a healthy stack, nothing firing, the offering is this"). Same set today,
    // but a future condition that fires by default now changes this artifact loudly instead of
    // silently, because it goes through the real evaluator rather than around it.
    var emitted =
        new AgentOperationEmitter()
            .withAvailabilityProbe(conditionId -> false)
            .emit(catalog, List.of());
    java.util.Set<String> deliveredWireNames = new java.util.LinkedHashSet<>();
    for (Map<String, Object> tool : emitted) {
      Object fn = tool.get("function");
      if (fn instanceof Map<?, ?> fnMap) {
        Object name = fnMap.get("name");
        if (name != null) {
          deliveredWireNames.add(name.toString());
        }
      }
    }
    java.util.Set<String> ids = new java.util.LinkedHashSet<>();
    for (Operation op : ops) {
      if (deliveredWireNames.contains(OperationCatalog.toWireName(op.id()))) {
        ids.add(op.id().value());
      }
    }
    return ids;
  }

  private static String executorConsumerId(ExecutorTag tag) {
    return switch (tag) {
      case AGENT -> "agent-loop";
      case UI -> "ui-host";
      case CLI -> "cli";
    };
  }

  private static Audience executorAudience(ExecutorTag tag) {
    return switch (tag) {
      case AGENT -> Audience.AGENT;
      case UI -> Audience.USER;
      case CLI -> Audience.OPERATOR;
    };
  }

  /** Walk up from the working directory to the repo root (the dir holding {@code governance/}). */
  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      if (Files.isDirectory(dir.resolve("governance")) && Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
