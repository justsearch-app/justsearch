/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.agent.api.registry.AliasRegistry;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.agent.api.registry.CatalogMatcher;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.AvailabilityExpression;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog of agent-tool Operations: mirrors the existing {@code SearchTool},
 * {@code BrowseTool}, {@code IngestTool}, {@code FileOperationsTool} as
 * Operation declarations per tempdoc 429 §"Migration" item 12 + §F.11 closure.
 *
 * <p>Bit-for-bit-preserves the existing {@code SafetyLevel → RiskTier} mapping
 * per §A.2:
 *
 * <ul>
 *   <li>READ_ONLY → LOW (search-index, browse-folders): {@link ConfirmStrategy.None},
 *       {@link AuditPolicy#NONE}, auto-retry idempotent
 *   <li>WRITE → MEDIUM (ingest-files): {@link ConfirmStrategy.Inline},
 *       {@link AuditPolicy#METADATA_ONLY}, no auto-retry
 *   <li>DESTRUCTIVE → HIGH (file-operations): {@link ConfirmStrategy.Inline},
 *       {@link AuditPolicy#METADATA_ONLY}, no auto-retry, undoSupported=true
 * </ul>
 *
 * <p>All four entries declare {@code executors = {AGENT}} (matches current
 * agent-only behavior); UI exposure is a future slice's concern.
 *
 * <p>Namespace is {@code "core"} (these are core JustSearch tools, not plugins).
 * Both {@code CoreOperationCatalog} and this catalog share the namespace; entry
 * IDs are disjoint.
 */
public final class AgentToolsOperationCatalog implements OperationCatalog {

  public static final String NAMESPACE = "core";

  private final AliasRegistry aliasRegistry;
  private final CatalogMatcher catalogMatcher;

  public AgentToolsOperationCatalog() {
    this(AliasRegistry.empty(), CatalogMatcher.defaultMatcher());
  }

  public AgentToolsOperationCatalog(AliasRegistry aliasRegistry, CatalogMatcher catalogMatcher) {
    this.aliasRegistry = aliasRegistry;
    this.catalogMatcher = catalogMatcher;
  }

  @Override
  public AliasRegistry aliasRegistry() {
    return aliasRegistry;
  }

  @Override
  public CatalogMatcher matcher() {
    return catalogMatcher;
  }

  public static final OperationRef SEARCH_INDEX = new OperationRef("core.search-index");

  /**
   * Tempdoc 868 §B.2 — the delegate's one content-bearing READ. Search returns budgeted excerpts;
   * this returns a document's extracted text a page at a time over the Worker's
   * {@code FetchDocumentSlice} RPC, so "read this file and summarize it" (the rank-1 recorded
   * intent, 0 clean completions before this) becomes literally executable. LOW risk, no confirm:
   * the readable universe is exactly the INDEXED documents — the Worker serves nothing else, which
   * is a stronger boundary than any path allowlist and needs no new consent posture.
   */
  public static final OperationRef READ_DOCUMENT = new OperationRef("core.read-document");

  public static final OperationRef BROWSE_FOLDERS = new OperationRef("core.browse-folders");
  public static final OperationRef INGEST_FILES = new OperationRef("core.ingest-files");
  public static final OperationRef FILE_OPERATIONS = new OperationRef("core.file-operations");

  /**
   * Tempdoc 561 P-E — the agent's learning producer. When the model learns a durable fact or user
   * preference, it calls {@code core_remember} to persist it to the single-authority memory record
   * (inspectable + forgettable via the Memory surface / {@code /api/memory}). LOW risk: a benign
   * local note, not a file/index mutation — read-only-ish; no confirmation, no audit.
   */
  public static final OperationRef REMEMBER = new OperationRef("core.remember");

  /**
   * Slice 491 §9.D Phase E (E3 + E17 probe fix): navigation tool in the agent's palette.
   * Re-declares the same OperationRef as {@code CoreOperationCatalog.NAVIGATE_TO_SURFACE};
   * the handler is registered in HeadAssembly after backendIntentRouter is initialized.
   * The LLM sees this as {@code core_navigate_to_surface} (wire-name transliteration).
   */
  public static final OperationRef NAVIGATE_TO_SURFACE =
      new OperationRef("core.navigate-to-surface");

  private final List<Operation> definitions =
      List.of(
          searchIndex(),
          readDocument(),
          browseFolders(),
          ingestFiles(),
          fileOperations(),
          navigateToSurface(),
          remember());

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public List<Operation> definitions() {
    return definitions;
  }

  private static Operation searchIndex() {
    return new Operation(
        SEARCH_INDEX,
        Presentation.forId(SEARCH_INDEX),
        // Tempdoc 868 §B.4 (from §A.2): `path_prefix` is DECLARED here because this Interface is
        // what AgentOperationEmitter projects to the model (AgentOperationEmitter.toOpenAiTool
        // reads op.intf().inputs()). SearchTool.execute honours the key — resolve-then-validate
        // against the indexed roots, via AgentToolPaths.RootsView#resolveRelative then #validate —
        // and the system prompt instructs the model to use it, but the
        // in-app delegate could never see it: across 37 recorded runs it was used 0 times. The
        // outward MCP surface (McpToolSurface) already declared it, so external agents had scoping
        // the delegate did not. `mode`/`pipeline` stay undeclared on purpose — they are internal
        // retrieval levers, not a capability the model should be steering.
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                + "\"limit\":{\"type\":\"integer\"},"
                + "\"path_prefix\":{\"type\":\"string\",\"description\":\"Restrict results to files"
                + " under this folder, as returned by core_browse_folders.\"}},"
                + "\"required\":[\"query\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.autoRetry(2, "core.search-index"),
            Set.of(),
            false),
        // Tempdoc 550 Preview face (F3): the first real producer of availability. Search is
        // offered to the agent only when the index is serving. The health model is
        // absence=healthy: "index.unavailable" fires ONLY when INDEX_SERVING is unhealthy
        // (LifecycleSnapshotTap). So Not(ConditionMatches("index.unavailable")) = "available
        // unless the index is not serving" — shown when ready, hidden when down, re-shown on
        // recovery. The emitter (AgentOperationEmitter) and the preview endpoint both evaluate
        // this live.
        //
        // Tempdoc 876 §B.2c corrects what this comment used to claim. "Reliably cleared when it
        // returns to READY" was true of reconcileDim in isolation and FALSE of the system: the
        // tap's only production caller was StatusLifecycleHandler.buildStatusMap, so the clear
        // happened only when something requested GET /api/status. A client that does not poll
        // inherited a stale assertion for the life of the process and was silently offered
        // neither this tool nor core.read-document (868 §C.3 recorded the model improvising a
        // nonsense call in its place). The guarantee now rests on
        // ReadinessReconciliationTrigger, which re-runs the readiness snapshot on worker and
        // inference capability transitions as well as on request — name THAT if this comment is
        // ever revisited, because it is the thing that makes the clear reliable. It is a second
        // TRIGGER, not total coverage: this condition is also asserted from the INDEX_SERVING
        // NOT_READY / "index.not_healthy" row (LifecycleSnapshotTap's MAPPING_TABLE), whose reason
        // code comes from the Worker-reported operational view rather than from a capability
        // transition, so that arm still moves only when a snapshot is taken. Belt and braces,
        // 876 §B.2b: within a SINGLE-AGENT run the offering may grow but never shrink, so a trigger
        // regression can no longer amputate a tool mid-conversation there. A run carrying agent
        // profiles re-derives its list per active profile and does not yet get that guarantee.
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches("index.unavailable"))),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(SEARCH_INDEX),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * Tempdoc 868 §B.2 — {@code core.read-document}. Same availability expression as {@link
   * #searchIndex()} and for the same reason: both are served by the Worker's index, so both are
   * offered only while the index is serving ({@code Not(ConditionMatches("index.unavailable"))} —
   * absence=healthy, re-shown on recovery). Declares {@code noRetry}, matching the intent that a
   * paged read should not be transparently repeated — and since tempdoc 879 wired the dispatcher
   * to this axis, that intent is now enforced: a failed read is surfaced, not silently replayed
   * behind a different offset.
   *
   * <p>Deliberately absent from the outward MCP surface ({@code McpToolSurface}), and the asymmetry
   * is the point: tempdoc 770 §4 withdrew a `fetch` tool there because an external MCP client is a
   * different consumer with its own context budget and its own retrieval loop, and the search/fetch
   * split it would need is the client's to make. The in-app delegate is the consumer whose 4096-token
   * window and inline-confirm lattice this tool was sized and gated for.
   */
  private static Operation readDocument() {
    return new Operation(
        READ_DOCUMENT,
        Presentation.forId(READ_DOCUMENT),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"path\":{\"type\":\"string\",\"description\":\"Path of the document to"
                + " read, as returned by core_browse_folders or a core_search_index result.\"},"
                + "\"offset_chars\":{\"type\":\"integer\",\"description\":\"Character offset to"
                + " start reading from (default 0). Use the offset the previous page's 'More:' line"
                + " gives you to continue. Paging costs one step per page and the header tells you"
                + " the document's total length — check it before deciding to continue.\"},"
                + "\"max_chars\":{\"type\":\"integer\",\"description\":\"Maximum characters to"
                + " return in this page. Capped server-side so one page always fits the context"
                + " window.\"}},"
                + "\"required\":[\"path\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        new OperationAvailability(
            Optional.of(
                new AvailabilityExpression.Not(
                    new AvailabilityExpression.ConditionMatches("index.unavailable"))),
            Optional.empty()),
        OperationLineage.empty(),
        Binding.of(READ_DOCUMENT),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation remember() {
    return new Operation(
        REMEMBER,
        new Presentation(
            new I18nKey("ops.remember.label"),
            new I18nKey("ops.remember.description"),
            Optional.empty(),
            Optional.empty()),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{"
                + "\"content\":{\"type\":\"string\",\"description\":\"The durable fact or user"
                + " preference to remember, in one concise sentence.\"},"
                + "\"kind\":{\"type\":\"string\",\"description\":\"Optional category, e.g."
                + " \\\"fact\\\" or \\\"preference\\\".\"}},\"required\":[\"content\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        // Tempdoc 879: `empty()` here means "the affected thing has no ResourceRef" — remember writes
        // the MemoryStore, and there is no Resource for agent memory — NOT "nothing is affected".
        // Those two meanings share one encoding today; minting a Resource just to populate a tooltip
        // would be the antipattern, so the distinction is recorded here instead.
        OperationLineage.empty(),
        Binding.of(REMEMBER),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation browseFolders() {
    return new Operation(
        BROWSE_FOLDERS,
        Presentation.forId(BROWSE_FOLDERS),
        // Tempdoc 655: list_files added so this declared schema matches the MCP-visible schema
        // for justsearch_browse (McpToolSurface) — the two were independently authored and had
        // drifted (list_files was accepted by MCP callers but never declared here). BrowseTool
        // does read it (BrowseTool.execute), switching to the file listing; it also auto-detects
        // when a folder has no subfolders.
        // Tempdoc 877 §2.1 — max_folders/max_files promoted: BrowseTool.execute honours both, and
        // the truncation message tells the model to "increase max_folders", an instruction it
        // could not follow while the key was undeclared. They are declared DESCRIPTION-ONLY — no
        // `type`, no `default` — and the reason is the §3.1(b) rule read correctly:
        //   · `type` is safe to declare on a key that was ALREADY declared, because the model was
        //     already being held to that shape. On a NEWLY declared key it is itself a NARROWING:
        //     `additionalProperties` is unset here, so before this promotion an undeclared
        //     `max_folders` was simply UNVALIDATED and reached the tool as-is. Adding
        //     `"type":"integer"` makes `{"max_folders":"50"}` — which small local models emit, and
        //     which ToolArgs.intArg exists to coerce — a hard BAD_REQUEST at
        //     OperationExecutorImpl's pre-dispatch validation, before BrowseTool ever runs. The
        //     narrowing is not hypothetical for the outward surface either: McpToolSurface declares
        //     `justsearch_browse` with only parent_path + list_files and routes it through
        //     callOperation("core.browse-folders", ...) — the same validator — so an MCP client is
        //     shown a schema without these keys while being held to one that types them.
        //   · No `default`: BrowseTool.DEFAULT_MAX_FOLDERS is config-derived
        //     (ConfigStore ... agent().browseDefaultMaxFolders()), so a literal in this static,
        //     model-visible schema states the wrong default on any machine that configured it.
        // The 200 ceiling rides the description for the same reason it is not a `maximum` keyword:
        // BrowseTool CLAMPS an over-large value, and a `maximum` would turn a harmless over-ask on
        // a read-only tool into a rejected call. Same shape as fileOperations() below, whose value
        // constraints all ride descriptions.
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"parent_path\":{\"type\":\"string\"},"
                + "\"list_files\":{\"type\":\"boolean\"},"
                + "\"max_folders\":{\"description\":\"Maximum folders to return;"
                + " capped at 200 server-side.\"},"
                + "\"max_files\":{\"description\":\"Maximum files to return when listing files;"
                + " capped at 200 server-side.\"}}}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            // A directory listing is idempotent: replaying it observes the filesystem again rather
            // than changing it, so a transient FS/index hiccup is exactly what auto-retry is for.
            // Since tempdoc 879 this declaration is what the dispatcher acts on, not decoration.
            RetryPolicy.autoRetry(2, "core.browse-folders"),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(BROWSE_FOLDERS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation ingestFiles() {
    return new Operation(
        INGEST_FILES,
        Presentation.forId(INGEST_FILES),
        Interface.of(
            // Tempdoc 811 (C-2a): `collection` is an OPTIONAL tag. Omitted/null → the containing indexed
            // root's collection, or `mcp-ingest` for out-of-root paths. Reserved app-internal names
            // are rejected server-side in IngestTool, not by this schema.
            "{\"type\":\"object\",\"properties\":{\"paths\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\"}},\"collection\":{\"type\":[\"string\",\"null\"]}},"
                + "\"required\":[\"paths\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
                RiskTier.MEDIUM,
                ConfirmStrategy.Inline.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(),
                false)
            // Tempdoc 560 §28 (4d): a coherent capability family. A durable allow-always grant for
            // "file-operations" auto-approves THIS (MEDIUM) member. Tempdoc 875 C.2: it does NOT
            // auto-approve the HIGH member `core.file-operations` — DurableGrantStore.isAllowed
            // refuses HIGH outright, so destructive work always costs a fresh, args-bound gesture.
            // Tempdoc 875 C.3: and even for this member the grant only covers invocations whose
            // `paths` canonicalize inside an indexed root (IndexedRootGrantScope); an out-of-root
            // ingest still runs, it just costs an approval that names the path (811 C-2a preserved).
            .withCapabilityFamily("file-operations")
            .withRecordKind(io.justsearch.agent.api.registry.OperationKind.INGEST)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(),
        // Tempdoc 879: lineage is not inert — the FE renders `affects` in the operation button and
        // hover preview — and ingest queues indexing work, so it affects the indexing-jobs Resource
        // exactly as core.rebuild-index declares. NOT core.indexed-roots: that Resource is the list
        // of WATCHED roots, changed only by the add/remove-watched-root gestures; ingest dispatches
        // a prepared recorded root plan and registers no watched roots.
        new OperationLineage(Set.of(new ResourceRef("core.indexing-jobs")), Set.of()),
        Binding.of(INGEST_FILES),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation fileOperations() {
    return new Operation(
        FILE_OPERATIONS,
        Presentation.forId(FILE_OPERATIONS, Optional.of("warning"), Optional.of("destructive")),
        // Tempdoc 877 §2.1 — the item shape FileOperationsTool.parseOperations actually parses,
        // promoted from an untyped `array` the model had to guess at; `conflict_strategy` and
        // `explanation` are promoted for the same reason (the tool honours both, but undeclared
        // they were pinned to FAIL and the literal "File operations" in the undo journal).
        // Item-level `required` is ["op"] ONLY, deliberately: this schema is ENFORCED before
        // dispatch (OperationExecutorImpl -> OperationInputSchemaValidator), and parseOperations
        // accepts `path` as an alias for `destination` because small models emit it — requiring
        // `destination` here would reject those calls before the alias could resolve. The
        // destination is still required, in parseOperations, which reports it per-operation.
        // Tempdoc 877 §3.1 — the rule these promotions follow, because this schema is BOTH shown to
        // the model AND enforced before dispatch: DECLARE the shape (which keys exist, their JSON
        // type, which are required), DESCRIBE the value constraints in prose, and ENFORCE them in
        // the tool. A value-constraint keyword — `enum`, `maximum`, `maxItems` — pre-empts the
        // tool's own check with a generic validator string and, worse, narrows behaviour the tool
        // deliberately accepts:
        //   · `op` has NO `enum`. parseOperations upper-cases it (FileOperationsTool.parseOperations)
        //     because small local models emit "mkdir"; FileOperationsToolTest#executeCaseInsensitiveOpType
        //     pins that as intended behaviour. An `enum` here would reject those calls at the
        //     dispatch boundary while that unit test — which calls execute() directly, bypassing the
        //     validator — stayed green: a live regression with a green suite over it. Same for
        //     `conflict_strategy`, upper-cased at the same altitude.
        //   · No `maxItems`. FileOperationsTool.execute already refuses an oversized batch with an
        //     actionable "...exceeds limit of 50. Split into smaller batches."
        // The allowed values and the limit therefore ride the DESCRIPTIONS, which is what the model
        // reads anyway. The limit is INTERPOLATED from the enforcing constant so it has one author;
        // contrast browseFolders() above, whose 200 has to stay a literal only because the constant
        // enforcing it (BrowseTool.MAX_MAX_FOLDERS) is private to that tool. Its per-call DEFAULT
        // is deliberately not stated at all: that one is config-derived, so any literal here would
        // bake one machine's configured value into a static, model-visible schema.
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"operations\":{\"type\":\"array\","
                + "\"description\":\"File operations to execute sequentially, at most "
                + FileOperationsTool.MAX_BATCH_SIZE
                + " per call.\","
                + "\"items\":{\"type\":\"object\",\"properties\":{"
                + "\"op\":{\"type\":\"string\",\"description\":\"Operation type: one of MOVE,"
                + " RENAME, MKDIR, COPY (case-insensitive).\"},"
                + "\"source\":{\"type\":\"string\",\"description\":\"Source file or folder path"
                + " (not needed for MKDIR).\"},"
                + "\"destination\":{\"type\":\"string\",\"description\":\"Destination file or"
                + " folder path. Required for every operation.\"}},"
                + "\"required\":[\"op\"]}},"
                + "\"conflict_strategy\":{\"type\":\"string\",\"default\":\"FAIL\","
                + "\"description\":\"How to handle destination conflicts (case-insensitive), one"
                + " of FAIL, SKIP, AUTO_SUFFIX: FAIL aborts the batch,"
                + " SKIP skips the conflicting operations, AUTO_SUFFIX renames to a unique name"
                + " like file (1).txt.\"},"
                + "\"explanation\":{\"type\":\"string\",\"description\":\"One line saying what"
                + " these operations accomplish. Recorded in the undo journal and shown in the"
                + " user's undo history.\"}},"
                + "\"required\":[\"operations\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.HIGH,
            ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(),
            Set.of(),
                true,
                Optional.of(new ResourceRef(
                    "core.advisory-operation-completed")))
            // Tempdoc 560 §28 (4d): same "file-operations" family as ingest (a HIGH-risk member) —
            // the family axis is real, the membership stands. Tempdoc 875 C.2: but NO durable grant
            // (family OR per-operation) can satisfy this operation's gate, because
            // DurableGrantStore.isAllowed refuses RiskTier.HIGH before consulting either grant set.
            // The family membership is therefore about coherence and revocation scope, not about
            // blanket destructive approval — which the product never asked for.
            .withCapabilityFamily("file-operations"),
        OperationAvailability.empty(),
        // Tempdoc 879: same reading as remember() — `empty()` means "the affected thing has no
        // ResourceRef" (file-operations mutates the FILE SYSTEM, which is not modelled as a
        // Resource), not "nothing is affected".
        OperationLineage.empty(),
        Binding.of(FILE_OPERATIONS),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * Slice 491 §9.D Phase E (E3 + E17): agent navigation tool. LOW risk (navigation is
   * presentation-layer); no confirm.
   *
   * <p>Tempdoc 560 WS4 (catalog collapse): this is now the <em>single canonical</em>
   * {@code core.navigate-to-surface} declaration. The duplicate that previously lived in
   * {@code CoreOperationCatalog} (executors {@code {UI, AGENT}}, audience {@code USER}) was
   * removed so core + agent-tools can install into the one {@link
   * io.justsearch.agent.api.registry.ContributionRegistry} without a ref collision. This entry
   * carries the <em>superset</em> executor set {@code {UI, AGENT}} + {@code USER} audience, so
   * the UI registry path (UIOperationEmitter filters {@code UI}) emits a byte-identical wire
   * entry while the agent loop (filters {@code AGENT}) still sees it. The handler is the same
   * {@code NavigateToSurfaceHandler} registered in HeadAssembly.
   */
  private static Operation navigateToSurface() {
    return new Operation(
        NAVIGATE_TO_SURFACE,
        Presentation.forId(NAVIGATE_TO_SURFACE),
        Interface.of(
            "{\"type\":\"object\",\"properties\":{\"surfaceId\":{\"type\":\"string\"}},"
                + "\"required\":[\"surfaceId\"]}",
            "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(NAVIGATE_TO_SURFACE),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.UI, ExecutorTag.AGENT),
        Audience.USER);
  }
}
