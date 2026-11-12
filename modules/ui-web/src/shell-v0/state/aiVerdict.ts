// SPDX-License-Identifier: Apache-2.0
/**
 * aiVerdict.ts — the ONE AI-engine lifecycle authority (tempdoc 663).
 *
 * `BrainSurface.deriveAiState()` used to reconcile ~5 overlapping state representations by a
 * hand-ordered precedence ladder: `installStatus`, local `busy[...]` flags,
 * `runtimeStatus.onnxFeatures[].modelActive`, the unified `aiStateStore` runtime, and a separate raw
 * inference snapshot. This module replaces that ladder with a single, pure, unit-testable derivation
 * — the AI-engine sibling of `verdict.ts`'s search-health authority (tempdoc 595).
 *
 * Deliberately NOT merged into `verdict.ts`'s `SystemHealthVerdict`/`ProvisionalCause`: 595 excludes
 * AI from the system-health alarm on purpose (AI is optional, offline-by-design on a fresh install is
 * not a system fault). This module keeps that exclusion while giving AI the SAME single-authority
 * treatment search already has — the original mistake 595 avoided repeating was conflating "don't
 * alarm" with "don't have a single authority." `AiStability` mirrors `verdict.ts`'s
 * `Stability`/`ProvisionalCause` SHAPE (settled | provisional·cause) but is its own closed union,
 * scoped to AI causes — `verdict.ts`'s `ProvisionalCause` lives inside the `verdict-derivation`
 * gate-guarded search-verdict seam, and its exhaustive switches are all search-scoped; adding AI-only
 * causes there would force them to handle cases that can never occur in their own output.
 *
 * Calm-by-default AND resilient to a starved poll (tempdoc 649/662): when no install/runtime data has
 * arrived yet, this never guesses a confident negative - it stays in the calm `connecting` state,
 * keyed off the shared `connection.reachable` signal `aiStateStore` already derives, not a bespoke
 * AI-specific staleness check.
 *
 * Design pass 2 (2026-07-01) - split into two functions so the OBSERVED half can be computed ONCE in
 * `aiStateStore.ts` (a store-level authority, like 594/595/596, not private to `BrainSurface`):
 *   - `computeAiEngineVerdict` - pure function of purely OBSERVED signals (install/runtime/reachable).
 *     Called from `aiStateStore.ts`'s `buildSnapshot()`, exposed as `AiState.aiEngine`.
 *   - `applyLocalIntent` - overlays surface-LOCAL optimistic UI intent (a just-clicked button, before
 *     the poll confirms) on top of the observed result. Deliberately kept OUT of the store: a different
 *     surface must not see BrainSurface's own click as if it were observed truth (the `setAiActivity`
 *     shared-signal precedent was considered and deliberately not reused here - see the design doc §R).
 */
import { isKnown } from './known.js';
import { formatBytes } from '../display/format.js';
import type { AiRuntime, InstallStatus, AiRuntimeStatus } from './aiStateStore.js';

/** Why the AI-engine state is provisional (real-now-but-in-flux). Closed union - every consumer
 * handles it totally. Sibling of `verdict.ts`'s `ProvisionalCause`, not a member of it. */
export type AiProvisionalCause =
  | 'checking' // no install/runtime data yet, but the backend is reachable
  | 'stale-poll' // the backend poll itself is currently stale (no first data yet, OR an
  // already-installed engine's runtime state can no longer be confirmed - Design pass 2)
  | 'installing'
  | 'starting'
  | 'switching-variant';

export type AiStability =
  | { readonly kind: 'settled' }
  | { readonly kind: 'provisional'; readonly cause: AiProvisionalCause };

export type AiEngineKind =
  | 'not_installed'
  // Sandbox round 8: an interrupted install left multi-GB of `.partial` bytes on disk. Distinct from
  // `not_installed` because `not_installed` is a SETTLED claim that nothing is there — rendered over
  // 1.2 GB of retained download it is simply false, and it contradicts the pause dialog's own promise
  // that the bytes stay and the next install resumes. Derived from the DISK-probed
  // `installStatus.resumableBytes`, never from the session-ephemeral `state: 'cancelled'`.
  | 'paused'
  | 'installing'
  | 'install_failed'
  | 'offline'
  | 'starting'
  | 'connecting'
  | 'online'
  // Tempdoc 737 §15 decision 1 (soft-off): the engine is HEALTHY but the user disabled chat, and a
  // background procedure (VDU) is holding it up to finish document understanding. Distinct from
  // 'online' so the UI never shows a green "chat ready" for a state where chat is intentionally off.
  | 'background'
  | 'indexing';

export interface AiEngineVerdict {
  readonly kind: AiEngineKind;
  readonly stability: AiStability;
  /**
   * Set only when `kind === 'install_failed'` - the install service's own error text. It is detail for
   * support, shown once and labelled as such; what went wrong and what to do are said in words by
   * {@link aiEngineBody}, from {@link installFailureCode} (FC11).
   */
  readonly installFailure: string | null;
  /** Set only when `kind === 'install_failed'` and the install service named its error code. */
  readonly installFailureCode?: string;
  /**
   * Set only when `kind === 'indexing'`: true when the engine is parked specifically because
   * `chatEnabledSpec === false` (the user hasn't activated chat yet - "install is not enable"),
   * as opposed to a genuine in-flight GPU handoff to indexing work. Tempdoc 734 round 5 finding
   * 3 - a Sandbox round waited 5+ minutes for this state to resolve on its own because the sub-label
   * read the same either way ("Indexing embeddings...") whether backfill was genuinely still
   * consuming the GPU or the engine was simply waiting on a click that would never come by itself.
   * Computed identically in both the authored and legacy `runtime.mode` branches (`chatEnabledSpec`
   * is in scope in both - review pass found a genuinely current backend can land in the legacy
   * branch during the rare pre-reconciler-attach boot window, per `BootstrapProjections`'s own
   * javadoc, so this isn't only a deprecated-backend concern). On a truly pre-737 backend that
   * never sends `chatEnabledSpec` at all, the expression naturally evaluates to `false` (not
   * `true`, since `undefined !== false`), so it stays safe there too. `undefined` only when not
   * in the 'indexing' kind at all.
   */
  readonly awaitingChatEnable?: boolean;
  /**
   * Set only when `kind === 'paused'`: bytes an interrupted install left on disk, which resuming
   * keeps. Carried on the verdict so the ONE presentation projection (`aiEngineBody`) can name the
   * amount, rather than each surface re-reading `installStatus` and formatting its own number.
   */
  readonly resumableBytes?: number;
  /**
   * Observed per-model facts, beside the lifecycle `kind` and independent of it: an engine can be
   * `online` while one ONNX model (an embedder, the reranker) failed to load. Read off the same
   * `runtimeStatus.onnxFeatures` this function already reads, so a surface that needs them (Health's
   * AI models and Graphics card rows) consumes them here instead of re-reading the raw feature list.
   * Each is absent when it has nothing to say.
   *
   * `failedModels`: the features whose status is `failed`, with the backend's reason.
   */
  readonly failedModels?: readonly { readonly id: string; readonly reason: string | null }[];
  /**
   * Where the ACTIVE (loaded) ONNX models actually run. `cpuOnly`: every active model runs on CPU
   * without a GPU fallback; `gpuFallback`: at least one active model was configured for the GPU but
   * fell back to CPU. Absent when no ONNX model is active.
   */
  readonly activeModelPlacement?: { readonly cpuOnly: boolean; readonly gpuFallback: boolean };
  /**
   * Set only when `kind === 'not_installed'` and nobody has asked for AI: no install is running, failed
   * or left bytes to resume (those are other kinds), and neither chat nor semantic search is switched
   * on. AI is optional, so this is a settled choice, not a fault: surfaces read it to stay calm about
   * what only AI provides (see `awaitsUnrequestedAi` in readinessNotice.ts). Absent whenever the
   * person asked for AI.
   */
  readonly notRequested?: true;
}

/**
 * Purely OBSERVED inputs - install/runtime signals + reachability. No surface-local UI intent (see
 * `AiEngineLocalIntent` below) - this is what `aiStateStore.ts` computes once, from signals it already
 * owns, alongside `stability`/`verdict`/`runtime`.
 */
export interface AiEngineObservedInput {
  readonly installStatus: InstallStatus | null;
  readonly runtimeStatus: AiRuntimeStatus | null;
  readonly runtime: AiRuntime;
  /** `aiStateStore.connection.reachable` (tempdoc 649) - the ONE reachability signal, reused rather
   * than re-derived, so the calm "connecting"/stale-poll states degrade honestly under a starved poll. */
  readonly reachable: boolean;
  /**
   * Tempdoc 807 Part A (round-13 R13-F2) — `aiStateStore.isSnapshotLive(verdict)`, threaded in from the
   * single call site rather than re-derived here: "is what we last observed still a LIVE observation,
   * or only a past measurement?". Deliberately NOT a second liveness answer. It is strictly narrower
   * than `reachable` (both non-live verdict kinds are minted only when `reachableViaContact` is false),
   * so every engine claim this function withholds is one W1's snapshot-rendering surfaces are
   * simultaneously re-tensing to "last known" — the Brain card cannot say "Online" while the progress
   * card beside it says "last known".
   */
  readonly snapshotLive: boolean;
  /**
   * Tempdoc 737 §12b/§12c — the runtime-authority engine axis, projected additively onto the
   * `/api/status` inference block (`engineState`/`chatEnabledSpec`/`procedure`/`engineReason`). When
   * present, these are the authoritative signals and are PREFERRED over the legacy `runtime.mode`
   * (which is a coarser projection of the same backend Mode enum). All optional so an older backend
   * that predates the additive fields falls back to `runtime.mode` — this fallback retires with the
   * `phase`/`mode` aliases per §12d.
   */
  readonly engineState?: string;
  readonly chatEnabledSpec?: boolean;
  /** The Worker's semantic producer is switched on (`worker.enrichment.embeddingEnabled`): AI asked for. */
  readonly semanticEnabled?: boolean;
  readonly procedure?: string;
  readonly engineReason?: string;
}

/**
 * The ONE AI-engine lifecycle derivation, computed from purely OBSERVED signals. Pure function (no
 * DOM/IO) - unit-testable, mirroring `computeVerdict`'s style. Called ONCE, in `aiStateStore.ts`'s
 * `buildSnapshot()` (Design pass 2) - not privately by `BrainSurface` or any other consumer, which
 * instead read the result off `AiState.aiEngine` (optionally overlaid via `applyLocalIntent`).
 */
export function computeAiEngineVerdict(input: AiEngineObservedInput): AiEngineVerdict {
  const lifecycle = computeLifecycle(input);
  const features = input.runtimeStatus?.onnxFeatures ?? [];
  const failedModels = features
    .filter((f) => f.status === 'failed')
    .map((f) => ({ id: f.id ?? f.label ?? 'unknown', reason: f.reason ?? null }));
  const active = features.filter((f) => f.modelActive);
  return {
    ...lifecycle,
    ...(failedModels.length > 0 ? { failedModels } : {}),
    ...(active.length > 0
      ? {
          activeModelPlacement: {
            cpuOnly: active.every((f) => f.executionProvider === 'cpu' && !f.gpuFallback),
            gpuFallback: active.some((f) => f.gpuFallback === true),
          },
        }
      : {}),
  };
}

/** The lifecycle half of {@link computeAiEngineVerdict}: the `kind`, its stability and kind-scoped details. */
function computeLifecycle(input: AiEngineObservedInput): AiEngineVerdict {
  const {
    installStatus,
    runtimeStatus,
    runtime,
    reachable,
    snapshotLive,
    engineState,
    chatEnabledSpec,
    procedure,
  } = input;

  if (installStatus?.state === 'running') {
    return {
      kind: 'installing',
      stability: { kind: 'provisional', cause: 'installing' },
      installFailure: null,
    };
  }

  if (installStatus?.state === 'failed') {
    return {
      kind: 'install_failed',
      stability: { kind: 'settled' },
      installFailure: installStatus.lastError || installStatus.message || null,
      ...(installStatus.errorCode ? { installFailureCode: installStatus.errorCode } : {}),
    };
  }

  // Tempdoc 807 Part A (round-13 R13-F2) — EVERY engine-axis arm below describes a running process,
  // and reads it off the RETAINED snapshot. So each one is evidence of what WAS true at the last
  // successful observation; presenting it in the PRESENT TENSE additionally requires that the
  // observation still be live. Round 13 killed both java processes and photographed a green
  // "Online / Chat and summaries ready." that was minted right here, from a retained
  // `engineState: 'Healthy'` — the value was right, its tense was not.
  //
  // `unconfirmed` is the honest answer for that state, and is not a new vocabulary: it is the same
  // calm `connecting`/`stale-poll` pair this function already mints when it cannot see the engine
  // (below), the pair whose own doc explicitly covers "an already-installed engine's runtime state can
  // no longer be confirmed", and the pair `BrainSurface` falls back to before the store has emitted.
  // It claims nothing about the engine, renders amber rather than green, and its primary action is
  // blocked rather than a dead "Shut Down AI"/"Start AI" click against a backend that is gone.
  //
  // Applied UNIFORMLY to every engine-axis arm (both the authored and the legacy block, plus the legacy
  // `transitioning` arm further down) rather than to the photographed arm alone: picking arms by
  // judgement is how the defect existed in the first place. The install arms — `installing`,
  // `install_failed`, `paused`, `not_installed` — are deliberately NOT gated: they describe DISK and
  // install history, which a dead backend process does not change, and blanking them would hide the
  // install CTA that is the only thing a user can act on there.
  const unconfirmed: AiEngineVerdict = {
    kind: 'connecting',
    stability: { kind: 'provisional', cause: 'stale-poll' },
    installFailure: null,
  };
  const observedNow = (v: AiEngineVerdict): AiEngineVerdict => (snapshotLive ? v : unconfirmed);

  // Engine axis (tempdoc 737 §12b/§12c). The runtime-authority `engineState` — when the backend
  // provides it — is the ONE signal for the engine's lifecycle; PREFER it over the legacy
  // `runtime.mode`. A running/healthy engine is itself proof of an installed, working engine,
  // independent of whether the install poll has reported back yet (the original ladder's reasoning).
  const authored = engineState !== undefined && engineState !== '';
  if (authored) {
    if (engineState === 'Healthy') {
      // Soft-off (§15 decision 1): a background procedure holds the engine up while the user has
      // chat DISABLED. Chat is intentionally NOT offered — surface it as 'background', never the
      // green 'online', so the UI is honest about chat being off while work continues. This precedence
      // is unchanged by 807: whenever the observation IS live, soft-off still wins over 'online'.
      if (chatEnabledSpec === false && !!procedure) {
        return observedNow({ kind: 'background', stability: { kind: 'settled' }, installFailure: null });
      }
      return observedNow({ kind: 'online', stability: { kind: 'settled' }, installFailure: null });
    }
    if (engineState === 'Starting' || engineState === 'Recovering') {
      return observedNow({
        kind: 'starting',
        stability: { kind: 'provisional', cause: 'starting' },
        installFailure: null,
      });
    }
    // engineState === 'Down' with the GPU yielded to indexing is the INDEXING state; any other
    // 'Down' reason falls through to the shared installed / connecting / offline logic below.
    if (engineState === 'Down' && input.engineReason === 'gpu-yielded-to-indexing') {
      return observedNow({
        kind: 'indexing',
        stability: { kind: 'settled' },
        installFailure: null,
        awaitingChatEnable: chatEnabledSpec === false,
      });
    }
  } else {
    // Legacy fallback (pre-authority backends). Retires with the phase/mode aliases (§12d).
    if (runtime.mode === 'online') {
      return observedNow({ kind: 'online', stability: { kind: 'settled' }, installFailure: null });
    }
    if (runtime.mode === 'indexing') {
      return observedNow({
        kind: 'indexing',
        stability: { kind: 'settled' },
        installFailure: null,
        awaitingChatEnable: chatEnabledSpec === false,
      });
    }
    if (runtime.mode === 'starting') {
      return observedNow({
        kind: 'starting',
        stability: { kind: 'provisional', cause: 'starting' },
        installFailure: null,
      });
    }
  }

  const installed =
    installStatus?.installedFully === true ||
    (runtimeStatus?.onnxFeatures?.some((f) => f.modelActive) ?? false);

  if (!installed) {
    const haveInstallData = installStatus !== null || runtimeStatus !== null || isKnown(runtime.installed);
    if (!haveInstallData) {
      // No-data != not-installed (§2.B). Resilient to a starved poll: while the backend is reachable
      // via any channel, this never flips to a confident "Not Installed" or "Offline" - only the
      // internal cause distinguishes "waiting for the first poll" from "the poll itself is stale."
      return {
        kind: 'connecting',
        stability: { kind: 'provisional', cause: reachable ? 'checking' : 'stale-poll' },
        installFailure: null,
      };
    }
    // Sandbox round 8: "Not Installed" is a CONFIDENT negative. Over bytes an interrupted install
    // left on disk it is a false one, and it contradicts the cancel dialog's promise that they are
    // kept. `resumableBytes` is disk-derived by the backend planner, so this survives the restart
    // that erases `state: 'cancelled'`.
    const staged = installStatus?.resumableBytes ?? 0;
    if (staged > 0) {
      return {
        kind: 'paused',
        stability: { kind: 'settled' },
        installFailure: null,
        resumableBytes: staged,
      };
    }
    return {
      kind: 'not_installed',
      stability: { kind: 'settled' },
      installFailure: null,
      ...(chatEnabledSpec === true || input.semanticEnabled === true ? {} : { notRequested: true as const }),
    };
  }

  // Legacy transitioning (pre-authority): the authored path already mapped Starting/Recovering above.
  // 807: an engine-axis claim like the rest, so it carries the same liveness gate.
  if (!authored && runtime.mode === 'transitioning') {
    return observedNow({
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'starting' },
      installFailure: null,
    });
  }

  // Design pass 2 - resilient to a starved poll (649/§K), extended to the already-installed axis: a
  // genuinely-installed engine whose runtime state we can no longer confirm (the poll has gone stale)
  // must not settle to a confident negative.
  //
  // Tempdoc 807 Part A corrects what this comment used to argue. It claimed only this ONE fallback
  // branch needed the check, because "the online/indexing/starting/transitioning branches above are
  // each themselves direct, specific evidence and correctly stay unconditional". That argument is the
  // campaign's signature TENSE error, stated in the one place that mints the sentence round 13
  // photographed: direct evidence is evidence of what WAS true at the moment it was observed, and
  // presenting it as a settled present-tense claim additionally requires that the observation still be
  // live. Those branches are now gated by `observedNow` (see the block above); this branch keeps its
  // own `!reachable` de-settling because it answers a different, weaker question (the poll may be
  // starved while the snapshot is still live — 649's calm "Catching up…" case).
  if (!reachable) {
    return {
      kind: 'offline',
      stability: { kind: 'provisional', cause: 'stale-poll' },
      installFailure: null,
    };
  }
  return { kind: 'offline', stability: { kind: 'settled' }, installFailure: null };
}

/** Surface-LOCAL, transient UI intent - kept OUT of the store (Design pass 2 §R). */
export interface AiEngineLocalIntent {
  /** e.g. `busy['inference-switch']` - a just-clicked variant switch, before the poll confirms it. */
  readonly switching: boolean;
  /** e.g. `busy['install-start']` - a just-clicked install/retry, before the poll confirms it. */
  readonly installStarting: boolean;
}

/**
 * Overlays local, surface-specific optimistic intent on top of the store-computed observed verdict.
 * `BrainSurface` (the one surface with the buttons these flags come from) calls this on its own render
 * path; no other consumer of `AiState.aiEngine` should call it, since these flags are not observed
 * truth. Mirrors the original ladder's exact precedence for both flags (see `aiVerdict.test.ts`).
 */
export function applyLocalIntent(observed: AiEngineVerdict, intent: AiEngineLocalIntent): AiEngineVerdict {
  // Checked first, unconditionally - matches the original ladder's priority for this exact
  // OR-condition. A retry-click after a failure must show "Installing…" immediately, even though the
  // last-known poll still says `failed`; that is what the local intent flag is for.
  if (intent.installStarting) {
    return {
      kind: 'installing',
      stability: { kind: 'provisional', cause: 'installing' },
      installFailure: null,
    };
  }
  // `switching` only matters where the observed axis would otherwise land on a confident "offline" or a
  // bare "starting" (a mode-level `transitioning`, not yet attributed to a variant switch) - it never
  // overrides installing/failed/not_installed/connecting/online/indexing, mirroring the original
  // ladder's placement of this check last, after every other branch.
  if (
    intent.switching &&
    (observed.kind === 'offline' ||
      (observed.kind === 'starting' &&
        observed.stability.kind === 'provisional' &&
        observed.stability.cause === 'starting'))
  ) {
    return {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'switching-variant' },
      installFailure: null,
    };
  }
  return observed;
}

// ---------------------------------------------------------------------------------------------
// Presentation projection (Design pass 3) - mirrors `verdict.ts`'s `verdictHeadline`/`verdictTone`/
// `presentVerdict` EXACTLY, extending `aiEngine` from a single-sourced VALUE (Design pass 2) to a
// single-sourced PRESENTATION (headline/tone/body/announce) - the same 557 Display-to-Presentation
// lineage `verdict.ts` already documents for the search axis, now given to the AI axis. Every consumer
// (the global footer pill via `computeStatusLabel`/`computeStatusTone`, the a11y announcer, the
// completion/failure toast) reads THIS, never `AiEngineKind` directly with its own switch - the same
// discipline that keeps 594/595/596 from re-forking.
//
// Wording/tone reused VERBATIM from `BrainSurface.ts`'s `statusConfig` label/sub table and
// `brainDotTone` (the one place this copy already existed) - not reinvented here.
import type { NoticeTone } from '../components/SystemNotice.js';

/**
 * The ONE human headline for the AI-engine verdict (the footer pill + a11y announcer read this).
 *
 * Wording note: `BrainSurface.ts`'s `statusConfig` table (its status card) used to keep its OWN copy of
 * this wording, saying "AI Online" for both `online`/`indexing` and carrying the online-vs-indexing
 * distinction only in its separate SUB-text ("Indexing embeddings…"). That second authority drifted
 * exactly as forks do — the card showed a green "AI Online" for a state where chat is unavailable, while
 * this function's footer pill said "Indexing" (0.2.0 F-6b). `statusConfig` now projects THIS function for
 * both kinds and keeps only its `sub` (the footer has no sub-text slot). The terser bare words
 * ("Online"/"Indexing") are the footer's pre-existing convention, now the one convention.
 * `not_installed`/`installing`/
 * `install_failed`/`connecting` had NO prior footer wording (the old code collapsed all of them to a
 * bare, inconsistently-cased "offline"/"Offline") — those four reuse `statusConfig`'s established text
 * verbatim, since there is no prior footer convention to preserve for them.
 */
export function aiEngineHeadline(v: AiEngineVerdict): string {
  switch (v.kind) {
    case 'not_installed':
      return 'Not Installed';
    case 'paused':
      return 'Download Paused';
    case 'installing':
      return 'Installing…';
    case 'install_failed':
      return 'Install Failed';
    case 'offline':
      return 'Offline';
    case 'starting':
      // Callers needing the live "Starting… Ns" count-up (the footer pill) special-case this kind
      // themselves, reading `runtime.loadStartedAtMs` directly - a static headline cannot carry a
      // ticking value. This plain label is still correct for non-live consumers (e.g. the a11y
      // announcer, which announces the moment of transition, not a live count).
      return 'Starting…';
    case 'connecting':
      return 'Connecting…';
    case 'online':
      return 'Online';
    case 'background':
      // Soft-off (tempdoc 737 §15 decision 1): engine up for background work, chat off.
      return 'Background processing';
    case 'indexing':
      return 'Indexing';
  }
}

/**
 * The ONE tone projection. Mirrors the footer's OWN pre-existing tone convention (`indexing`/`starting`
 * kept deliberately amber/"in-flux", distinct from settled `online`'s green — 595's own comment on the
 * code this replaces: "settled indexing/starting keep their prior amber in-flux tone"). `BrainSurface.ts`'s
 * `brainDotTone` used to collapse `online`/`indexing` to the same green dot on the theory that its
 * sub-text carried the distinction; in practice that read as "everything is fine" for a degraded state,
 * so those two dot words now project this function too (0.2.0 F-6b).
 */
export function aiEngineTone(kind: AiEngineKind): NoticeTone {
  switch (kind) {
    case 'online':
      return 'success';
    case 'indexing':
    case 'background':
    case 'starting':
    case 'connecting':
      return 'warning';
    case 'installing':
      return 'info';
    case 'install_failed':
      // Critical-review fix (2026-07-01) — this MUST agree with `core.ai-engine.failed`'s
      // `defaultSeverity: 'error'` (`messageClasses.ts`), the toast fired on reaching this state.
      // Grouping this with `offline`/`not_installed`'s neutral tone (as an earlier draft of this
      // function did) meant a failed install fired one urgent red toast, then settled into a calm,
      // neutral-toned pill for anyone who missed the toast — the SAME state disagreeing with itself
      // across two things this pass built together.
      return 'error';
    case 'offline':
    case 'not_installed':
    // `paused` is calm, not an alarm: nothing is broken and nothing was lost. It shares
    // `not_installed`'s neutral tone; what distinguishes them is the headline and body, which say
    // the download is paused and how much of it is already on disk.
    case 'paused':
    default:
      return 'neutral';
  }
}

/** The ONE consequence sentence for the AI-engine verdict (mirrors `statusConfig`'s `sub` text; the
 * `starting` kind's live ETA/elapsed sub-text is caller-specific and not reproduced here). */
export function aiEngineBody(v: AiEngineVerdict): string {
  switch (v.kind) {
    case 'not_installed':
      return 'Install AI models to get started.';
    case 'paused':
      // The number is the whole point: the pause dialog promised the bytes would stay, so the idle
      // surface has to be able to show that they did. Falls back to the unquantified claim only if
      // the backend somehow reported a paused state without a byte count.
      return v.resumableBytes && v.resumableBytes > 0
        ? `${formatBytes(v.resumableBytes)} already downloaded is kept on disk — resuming continues from there.`
        : 'An earlier download is paused — resuming continues from where it stopped.';
    case 'installing':
      return 'Downloading models.';
    case 'install_failed':
      return installFailureWords(v.installFailureCode);
    case 'offline':
      return 'Start AI to enable chat and summaries.';
    case 'starting':
      return 'AI is initializing.';
    case 'connecting':
      return 'Checking AI status…';
    case 'online':
      return 'Chat and summaries ready.';
    case 'background':
      // §15 decision 1 copy — honest about chat being off while the engine finishes background work.
      return 'AI engine is finishing document understanding — chat is off.';
    case 'indexing':
      return 'Indexing embeddings…';
  }
}

/**
 * FC11 — a failed AI install in words: what went wrong and what to do, by the install service's error
 * code (AiInstallService). The service's own message (a path, an exception) is not words for the
 * person; surfaces show it once, labelled as detail for support (`installFailure`).
 */
function installFailureWords(code: string | undefined): string {
  switch (code) {
    case 'ALL_DOWNLOADS_FAILED':
    case 'REQUIRED_DOWNLOADS_FAILED':
      return 'The AI model files could not be downloaded or saved. Check the internet connection and the free space on the disk, then install again.';
    case 'INSTALL_IO_ERROR':
    case 'IO_ERROR':
      return 'The folder for AI models could not be created or written to. Check the free space on the disk and that the folder can be written to, then install again.';
    case 'MANIFEST_UNAVAILABLE':
      return 'The list of AI models to install could not be read. Install again later.';
    case 'DOWNLOADS_DISABLED':
      return 'Downloads are turned off by your administrator, so AI models cannot be installed on this computer.';
    case 'SMOKE_TEST_FAILED':
      return 'The AI models were installed but did not start correctly. Install again to repair them.';
    default:
      return 'The AI install stopped before it finished. Install again to continue.';
  }
}

export interface AiEngineVerdictPresentation {
  readonly tone: NoticeTone;
  readonly headline: string;
  readonly body: string;
  /** The screen-reader announcement of an AI-ENGINE VERDICT CHANGE - mirrors `presentVerdict`'s
   * `announce` exactly (595 §15.1 / E1's pattern, a second instance for the AI axis). `alert`
   * (assertive) only for the one error-toned kind (`install_failed`), else `status` (polite). */
  readonly announce: { readonly text: string; readonly politeness: 'status' | 'alert' };
}

/**
 * The ONE AI-engine verdict-presentation projection (Design pass 3, mirroring `presentVerdict`
 * structurally). Every surface consumes this - the footer pill (headline + tone, via
 * `computeStatusLabel`/`computeStatusTone`), the a11y announcer (`announce`), and (indirectly, via the
 * `AiEngineKind` transition it drives) the completion/failure toast tracker.
 */
export function presentAiEngineVerdict(v: AiEngineVerdict): AiEngineVerdictPresentation {
  const headline = aiEngineHeadline(v);
  const tone = aiEngineTone(v.kind);
  return {
    tone,
    headline,
    body: aiEngineBody(v),
    announce: { text: headline, politeness: v.kind === 'install_failed' ? 'alert' : 'status' },
  };
}
