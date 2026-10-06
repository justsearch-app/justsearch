/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import java.util.List;

/**
 * Producer-published runtime manifest (tempdoc 501).
 *
 * <p>A self-describing, versioned document the JustSearch backend writes to
 * {@code <dataDir>/runtime/manifest.json} and serves at {@code GET /api/runtime/manifest}.
 * The single source of truth for non-JVM consumers (Vite proxy, prod MCP,
 * dev-runner, Tauri shell, browser) answering "what is the currently-running
 * JustSearch instance and how do I reach it?"
 *
 * <p>Two transports, one document — the filesystem path is for tools that
 * don't yet know the port; the HTTP endpoint is for browsers and remote tools.
 * Change notification rides {@code /api/runtime/manifest/stream} (SSE) with
 * the same payload shape.
 *
 * <p>Readiness is phased: the first write happens at HTTP bind and carries
 * only head fields ({@code head.readyAt} populated, {@code worker} null);
 * a rewrite happens when the Worker connects, adding the {@code worker}
 * sub-record and {@code worker.readyAt}. Consumers choose their own bar
 * by checking which optional fields are non-null.
 *
 * <p>Identity over liveness: every process generates a fresh {@code
 * instanceId} (UUID) at boot. Consumers cache by identity — when
 * {@code instanceId} changes, caches invalidate by definition. Liveness is
 * a separate concern handled via the companion {@code manifest.lock} file
 * carrying PID.
 *
 * <p>Stability: stable (manifest contract). New fields are added with
 * {@code @JsonInclude(NON_NULL)}; schema bumps require incrementing
 * {@link #schemaVersion}.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeManifest(
    int schemaVersion,
    String instanceId,
    long pid,
    String startedAt,
    String dataDir,
    /**
     * Projection from {@code LifecycleProjection.project} over one component registry snapshot. Single string discriminator over the canonical
     * {@code LifecycleState} enum ({@code STARTING} | {@code READY} | {@code DEGRADED} |
     * {@code ERROR}). Consumers get the overall state without composing sub-records.
     * Null only during the head-only initial publish before the head is fully bound;
     * present on every subsequent rewrite.
     */
    String lifecycle,
    HeadInfo head,
    WorkerInfo worker,
    AiInfo ai,
    /**
     * Tempdoc 501 Phase 30 (§13.4.2 reachability axis): typed transports
     * list. Each entry names a transport this producer exposes (HTTP REST,
     * SSE, filesystem path, well-known mirror, MCP tool, probes) with a
     * {@code kind} discriminator, the URL or filesystem path, and an
     * audience tag. Replaces the per-field encoding inside {@link HeadInfo}
     * (where {@code apiBaseUrl} carried reachability while
     * {@code sessionToken} carried a credential — two axes in one record).
     * {@code HeadInfo.apiBaseUrl} stays for one schema cycle for
     * back-compat; new consumers should read {@code reachability}.
     * Schema-version stays at 1: {@code @JsonInclude(NON_NULL)} keeps the
     * field optional for older readers.
     */
    Reachability reachability,
    /**
     * Install/runtime mode (tempdoc 657). {@code intent} is the configured product shape
     * ({@code full-desktop} | {@code headless} | {@code mcp-lite}, from {@code -Djustsearch.mode});
     * {@code realized} is the coarse capability actually up ({@code full} | {@code retrieval-only} |
     * {@code degraded}), projected from registry-backed index and generative capability views. So the
     * advertised mode never outruns what is actually loaded. Nullable — a manifest written before the
     * first mode publish omits it; {@code @JsonInclude(NON_NULL)} keeps it optional and the schema
     * version stays 1 (older readers unaffected).
     */
    ModeInfo mode,
    /**
     * Tempdoc 654: the JustSearch Runtime Contract descriptor — the coarse contract version plus
     * the pinned versions of the contract's constituent surfaces (manifest schema, lifecycle
     * subset, MCP protocol + tool surface). Advertised here so the manifest is the one object an
     * external agent reads to learn "what is promised, at what version." A projection over
     * existing version single-sources (see {@link RuntimeContract#current()}); nullable and
     * {@code @JsonInclude(NON_NULL)}, so older readers are unaffected and no schema bump is needed.
     */
    RuntimeContract runtimeContract,
    /**
     * Realized chat-model identity (tempdoc 842 §2.5). {@code profileId} is the
     * {@code ChatModelProfile} the RUNNING engine claims ({@code null} = no profile claim, i.e. the
     * model came from a bare path); {@code modelFile} is the file name actually loaded; {@code
     * mmprojActive} says whether a multimodal projector is attached to that engine.
     *
     * <p>This is the same declared-vs-realized honesty 805 established for ONNX execution providers
     * and {@link ModeInfo} shipped for install mode: settings can say "compact" while the engine
     * runs the 9B, and a projector-dropping swap leaves vision silently off. Only a projection of
     * the running engine can say so.
     *
     * <p>Nullable — a manifest written before the first chat publish (or with no inference runtime
     * at all) omits it; {@code @JsonInclude(NON_NULL)} keeps it optional and the schema version
     * stays 1 (older readers unaffected), exactly as {@code mode} was added.
     */
    ChatInfo chat,
    /** Private filesystem-only ownership records. Public projections always omit this list. */
    List<ManagedChild> children,
    /** Private warm-handoff state. Public projections always omit it. */
    ShutdownHandoff shutdownHandoff,
    /**
     * Whether the processes this Engine starts end with it (Spec F §1.3): the status of the
     * Engine's own Windows job and, when it could not be established, why. Nullable and additive
     * (older readers ignore it; no schema bump), like {@code mode} and {@code chat}.
     */
    ContainmentInfo containment,
    /**
     * Which supervised incarnation this Engine is (Spec F §3.2): the supervisor's session and the
     * 1-based incarnation number, copied from {@code JUSTSEARCH_ENGINE_INCARNATION}. Null when the
     * Engine was not started by a supervisor that sets it. Nullable and additive, like {@code
     * containment}.
     */
    IncarnationInfo incarnation) {

  public static final int CURRENT_SCHEMA_VERSION = 2;

  /**
   * The manifest without containment and incarnation blocks, as written before either was
   * reported.
   */
  public RuntimeManifest(
      int schemaVersion,
      String instanceId,
      long pid,
      String startedAt,
      String dataDir,
      String lifecycle,
      HeadInfo head,
      WorkerInfo worker,
      AiInfo ai,
      Reachability reachability,
      ModeInfo mode,
      RuntimeContract runtimeContract,
      ChatInfo chat,
      List<ManagedChild> children,
      ShutdownHandoff shutdownHandoff) {
    this(schemaVersion, instanceId, pid, startedAt, dataDir, lifecycle, head, worker, ai,
        reachability, mode, runtimeContract, chat, children, shutdownHandoff, null, null);
  }

  public RuntimeManifest {
    if (schemaVersion <= 0) {
      throw new IllegalArgumentException("schemaVersion must be positive");
    }
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must be non-blank");
    }
    if (startedAt == null || startedAt.isBlank()) {
      throw new IllegalArgumentException("startedAt must be non-blank");
    }
    if (dataDir == null || dataDir.isBlank()) {
      throw new IllegalArgumentException("dataDir must be non-blank");
    }
    if (head == null) {
      throw new IllegalArgumentException("head must be non-null");
    }
  }

  /**
   * Head process surface — always non-null. Present from the first manifest
   * write (at HTTP bind).
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record HeadInfo(
      Integer apiPort, String apiBaseUrl, String sessionToken, String readyAt, String buildStamp) {
    public HeadInfo {
      if (apiPort != null && (apiPort <= 0 || apiPort > 65535)) {
        throw new IllegalArgumentException("apiPort out of range: " + apiPort);
      }
      if ((apiPort == null) != (apiBaseUrl == null) || (apiPort == null) != (readyAt == null)) {
        throw new IllegalArgumentException("apiPort, apiBaseUrl and readyAt must be set together");
      }
    }

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis): strips the
     * session token credential. HTTP / SSE / MCP / well-known transports
     * call this; the filesystem transport carries the full record.
     */
    public HeadInfo publicProjection() {
      return sessionToken == null ? this : new HeadInfo(apiPort, apiBaseUrl, null, readyAt, buildStamp);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShutdownHandoff(String state, String reason, String changedAt) {}

  /**
   * index component surface — nullable. Becomes non-null on the second manifest write,
   * after {@code connectKnowledgeServer} resolves (either successfully or with a
   * spawn failure).
   *
   * <p>Tempdoc 501 §12.1: the {@code state} discriminator carries the worker-projection's
   * tri-state surface — {@code "pending"} (Worker still booting, not yet known if it'll
   * connect), {@code "ready"} (connected and serving), {@code "failed"} (spawn or
   * connect failed; {@code spawnError} carries the human-readable reason). The previous
   * null-vs-populated distinction conflated "still trying" with "gave up" — the
   * discriminator separates them.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkerInfo(
      /** {@code "pending"} | {@code "ready"} | {@code "failed"}. Always present. */
      String state,
      String indexBasePath,
      String readyAt,
      /** Populated when {@link #state} is {@code "failed"}; null otherwise. */
      String spawnError) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). Worker carries
     * no credentials today; the public view is identity. Override here is
     * the structural commitment — adding a future credential-class field
     * must also add it to the redaction branch in this method.
     */
    public WorkerInfo publicProjection() {
      return this;
    }
  }

  /**
   * Inference (AI) runtime surface, projected from the generative component's read-only
   * capability view. Nullable before the component is registered.
   *
   * <p>The {@code phase} discriminator carries the upstream {@code CapabilityHealth} name
   * ({@code PENDING}, {@code READY}, {@code DEGRADED}, {@code OFFLINE}, {@code RECOVERING}).
   * {@code required} reports whether inference is configured for this stack at all —
   * consumers should treat {@code required=false} + {@code phase=OFFLINE} as the expected
   * state, not a failure.
   *
   * <p>Tempdoc 682 Item 2 — llama-server build pin surfacing. {@code serverBuildExpected} is
   * the pinned build tag ({@code bNNNN}) from the staging-time {@code runtime-version.txt}
   * marker next to the configured llama-server executable; {@code serverBuildActual} is the
   * build the running server reports via {@code GET /props} ({@code build_info}). Both are
   * nullable — unknown is a supported state (externally-started/adopted servers, dev setups,
   * no server yet). Divergent non-null values are the visible expected-vs-actual drift
   * signal; the producer also logs it loudly. No schema bump: nullable additive fields under
   * {@code @JsonInclude(NON_NULL)}.
   *
   * <p>Tempdoc 835 §9c.2 — {@code thinkingSupport} is the running server's reasoning-capability
   * verdict: {@code SUPPORTED} (launched with {@code --reasoning-budget} and healthy),
   * {@code UNSUPPORTED} (the build rejected the flag; relaunched without it — inference stays up,
   * thinking does not), {@code DISABLED} (switched off by configuration) or {@code UNKNOWN}
   * (nothing launched by us, e.g. an adopted server). Nullable and additive, same as the build
   * pin: this is where "what can this installation do" is already read from, so a surface can
   * disable a thinking control with a reason instead of promising what the build cannot do.
   *
   * <p>Tempdoc 883 decision 1 — {@code contextWindow} is the window this installation's engine was
   * launched with and WHY ({@code rung}, {@code reason} of {@code top-rung} / {@code override} /
   * {@code stepped-from:<planned top rung>}, {@code slots}, {@code kvType}, and the NVML free VRAM
   * recorded at plan time). The window is a derived resource, so "what did this machine end up
   * with" is a fact about the installation, not a setting anyone can read back out of config —
   * which is exactly what this manifest is for. Null when this process launched no server (nothing
   * started yet, or an adopted external instance whose window it did not choose). Nullable and
   * additive: no schema bump.
   *
   * <p>This is the INTENT. The window the server reports ({@code /props} {@code n_ctx}, published
   * on {@code /api/inference/status} as {@code llmContextTokens}) is the OBSERVATION, and stays
   * authoritative; never present one as the other.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AiInfo(
      String phase,
      boolean required,
      String pendingReason,
      String readyAt,
      String serverBuildExpected,
      String serverBuildActual,
      String thinkingSupport,
      OnlineAiRuntimeIntrospection.ContextWindow contextWindow) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). AI carries no
     * credentials today; the public view is identity. Same structural
     * commitment as {@link WorkerInfo#publicProjection}.
     */
    public AiInfo publicProjection() {
      return this;
    }
  }

  /**
   * Install/runtime mode surface (tempdoc 657) — nullable until the first mode publish.
   *
   * <p>{@code intent} is the producer-owned primitive: the configured product shape from
   * {@code -Djustsearch.mode} ({@code full-desktop} | {@code headless} | {@code mcp-lite}; defaults to
   * {@code full-desktop}). {@code realized} is a coarse projection of what is actually up —
   * {@code full} (retrieval + LLM ready), {@code retrieval-only} (retrieval up, LLM not required/offline),
   * or {@code degraded} — derived from registry-backed index and generative capability views. Consumers
   * branch on {@code realized}; when it diverges from {@code intent} the manifest tells the honest truth.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ModeInfo(String intent, String realized) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). Mode carries no credentials; the public
     * view is identity. Same structural commitment as {@link AiInfo#publicProjection}.
     */
    public ModeInfo publicProjection() {
      return this;
    }
  }

  /**
   * Realized chat-model identity (tempdoc 842 §2.5) — the honesty surface for "which chat model is
   * this stack actually talking to".
   *
   * <ul>
   *   <li>{@code profileId} — the {@code ChatModelProfile} id the running engine claims
   *       ({@code "standard"} | {@code "compact"} | ...). {@code null} is a real, distinct state:
   *       the engine loaded a bare path nobody attributed to a profile. It is NOT "standard".
   *   <li>{@code modelFile} — the file name the engine actually loaded (never the full path: the
   *       manifest's public projection is read by external agents and directory layout is not
   *       theirs to learn).
   *   <li>{@code mmprojActive} — {@code TRUE}/{@code FALSE} when the engine's projector state is
   *       known, {@code null} when it is not. Deliberately a boxed tri-state: the defect this
   *       block exists to expose is a swap that silently drops the projector, and "we don't know"
   *       must not render as "no projector" (or, worse, as "vision is fine").
   * </ul>
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ChatInfo(String profileId, String modelFile, Boolean mmprojActive) {

    /**
     * Public projection (tempdoc 501 §13.4.5 audience axis). Chat identity carries no credentials
     * and no filesystem layout — {@code modelFile} is a bare file name — so the public view is
     * identity, like {@link ModeInfo#publicProjection}.
     */
    public ChatInfo publicProjection() {
      return this;
    }
  }

  /**
   * The Engine's process containment (Spec F §1.3). {@code status} is {@code CONTAINED}, {@code
   * UNAVAILABLE} or {@code NOT_APPLICABLE}; {@code reason} names the failed call and its Windows
   * error ({@code <call>:<error>}) when UNAVAILABLE and is null otherwise.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ContainmentInfo(String status, String reason) {

    /** Carries no credentials and no filesystem layout, so the public view is identity. */
    public ContainmentInfo publicProjection() {
      return this;
    }
  }

  /**
   * One supervised Engine incarnation (Spec F §3.2): {@code session} is the supervisor's session
   * (the dev run id, or the shell's session), {@code n} counts the Engines it started, from 1.
   */
  public record IncarnationInfo(String session, int n) {
    /** The environment variable a supervisor sets at spawn: {@code <session>/<n>}. */
    public static final String ENVIRONMENT = "JUSTSEARCH_ENGINE_INCARNATION";

    private static final int MAX_SESSION = 64;

    public IncarnationInfo {
      if (session == null || session.isBlank() || session.length() > MAX_SESSION) {
        throw new IllegalArgumentException("session must be 1-" + MAX_SESSION + " characters");
      }
      if (n < 1) throw new IllegalArgumentException("n counts from 1");
    }

    /** Parses {@code <session>/<n>}; null when absent or malformed (the field is then omitted). */
    public static IncarnationInfo parse(String value) {
      if (value == null) return null;
      int slash = value.lastIndexOf('/');
      if (slash <= 0 || slash == value.length() - 1) return null;
      String session = value.substring(0, slash);
      String number = value.substring(slash + 1);
      if (session.isBlank() || session.length() > MAX_SESSION || !number.matches("[1-9][0-9]{0,8}")) {
        return null;
      }
      return new IncarnationInfo(session, Integer.parseInt(number));
    }

    /** Names no credential and no path, so the public view is identity. */
    public IncarnationInfo publicProjection() {
      return this;
    }
  }

  /**
   * Public projection of the whole manifest (tempdoc 501 §13.4.5 audience
   * axis). Each sub-record declares its own {@code publicProjection()}; this
   * method composes them. The type system enforces that new sensitive
   * fields declare their public projection at the record level — there is
   * no static helper to forget to update.
   *
   * <p>Used by every HTTP-class transport: REST controller, SSE controller,
   * MCP tool result, well-known mirror. The filesystem transport keeps the
   * full record (FS-permission gated).
   */
  public RuntimeManifest publicProjection() {
    HeadInfo publicHead = head == null ? null : head.publicProjection();
    WorkerInfo publicWorker = worker == null ? null : worker.publicProjection();
    AiInfo publicAi = ai == null ? null : ai.publicProjection();
    Reachability publicReach = reachability == null ? null : reachability.publicProjection();
    ModeInfo publicMode = mode == null ? null : mode.publicProjection();
    RuntimeContract publicContract =
        runtimeContract == null ? null : runtimeContract.publicProjection();
    ChatInfo publicChat = chat == null ? null : chat.publicProjection();
    ContainmentInfo publicContainment = containment == null ? null : containment.publicProjection();
    IncarnationInfo publicIncarnation = incarnation == null ? null : incarnation.publicProjection();
    if (publicHead == head
        && publicWorker == worker
        && publicAi == ai
        && publicReach == reachability
        && publicMode == mode
        && publicContract == runtimeContract
        && publicChat == chat
        && publicContainment == containment
        && publicIncarnation == incarnation
        && (children == null || children.isEmpty())
        && shutdownHandoff == null) {
      return this;
    }
    return new RuntimeManifest(
        schemaVersion, instanceId, pid, startedAt, dataDir, lifecycle,
        publicHead, publicWorker, publicAi, publicReach, publicMode, publicContract, publicChat,
        null, null, publicContainment, publicIncarnation);
  }
}
