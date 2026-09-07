// SPDX-License-Identifier: Apache-2.0
/**
 * BrainSurface — Lit-side Brain rail surface (slice 452 phase 9).
 *
 * Self-mounting Surface with full functional parity to React BrainView:
 * install/cancel/repair AI, Simple/Detailed mode toggle, GPU runtime
 * variant activation, inference mode switching, pack import (Tauri),
 * LLM settings, policy banners, runtime status display.
 *
 * Visual presentation differs from React (no Tailwind / Framer Motion;
 * uses Lit + framework-agnostic CSS) — operationally equivalent.
 *
 * Side-effect registers `<jf-brain-surface>` for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
// Tempdoc 571 §11 / 578 — surfaceScrollLayoutStyles dropped: Brain is now a display:contents tab host.
import { activateOnKey } from '../utils/keyboardHandler.js';
// Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: Brain hosts the AI's learned-memory as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { present } from '../display/present.js';
import { formatBytes } from '../display/format.js';
import { projectFact } from '../display/facts.js';
import { takeMemberTabIntent, subscribeMemberTab } from '../router/memberTabIntent.js';
import '../components/OpButton.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import { OperationClient, OperationError, getOperationClient } from '../operations/OperationClient.js';
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import { isTauriRuntime } from '../../utils/tauriRuntime.js';
import { pickFolder } from '../../utils/folderPicker.js';
import {
  subscribeAiState,
  type AiState as UnifiedAiState,
  type InstallStatus,
  type AiRuntimeStatus,
  type PackImportStatus,
} from '../state/aiStateStore.js';
import {
  enqueueUiModePersistence,
  UI_MODE_INTENT_HEADER,
  getUiMode,
  getUiModeRevision,
  setUiMode,
  subscribeUiMode,
  type UiMode,
} from '../state/uiModeState.js';
// Tempdoc 657 — pre-install per-tier weight breakdown (GET /api/ai/install/plan-preview).
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';
import {
  aiEngineBody,
  aiEngineHeadline,
  aiEngineTone,
  applyLocalIntent,
  type AiEngineVerdict,
  type AiStability,
} from '../state/aiVerdict.js';
import { unavailableBecause, AVAILABLE } from '../state/availability.js';
// Tempdoc 840 Phase 5 — the ONE derivation behind the component list, the honest transfer line and
// the staged-progress rows. Composed here, rendered below; the composers are unit-tested directly.
import {
  composeComponentGroups,
  composeComponentSummary,
  composeStageRows,
  composeTransferLine,
  friendlyInstallMessage,
  searchReadyNotice,
  type ComponentRow,
} from '../state/installComponents.js';
import '../components/Control.js';
// Tempdoc 613 — coherence: the compat callout words its cause from the ONE canonical reindex
// vocabulary (the same `reasonFor`/CAUSE_ROWS the Chat degradation banner + 595 verdict use),
// so the same condition cannot be worded differently across surfaces.
import { INDEX_SCHEMA_MISMATCH, isReindexCause, reasonFor } from '../state/readinessNotice.js';
import { ENRICHMENT_IN_PROGRESS_LABEL } from '../state/enrichmentCoverage.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { formatStartupEstimate, humanizeSeconds, elapsedSecondsSince } from '../state/startupEstimate.js';
import { isAiInstallLive } from '../substrates/ai/aiInstallLiveness.js';
import { icon } from '../components/Icon.js';
// Tempdoc 586 §F-1a — reuse the existing pulse-dots primitive for the first-paint skeleton.
import '../components/chat/PulseDots.js';
import { confirmAsync } from '../components/ConfirmDialog.js';
import { ModalController } from '../primitives/modalController.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
// Sandbox round 7 — the install consent dialog names the real packages, licences and terms links
// from the registry the backend will actually install from, instead of the hardcoded "several GB".
import {
  getAiInstallManifest,
  type AiInstallManifest,
  type AiInstallModelPackage,
} from '../../api/domains/packs.js';
// Tempdoc 564 Phase B (4b): EffectivePolicy is the single generated wire-contract projection.
import type { EffectivePolicy } from '../../api/generated/schema-types/effective-policy.js';

// Tempdoc 663 Stage 3/5 — InstallStatus/AiRuntimeStatus/PackImportStatus moved to
// `utils/aiInstallPoll.ts` (re-exported by `state/aiStateStore.ts`), the shared, always-on poller
// that replaced this surface's one-shot `refreshAll()` fetches. Imported below, not redeclared.

/**
 * Tempdoc 518 Appendix F W3.2 — one row of /api/inference/transitions response.
 */
interface TransitionRecord {
  timestampMs: number;
  fromMode: string;
  toMode: string;
  reason: string;
  success: boolean;
  durationMs: number;
  wireCode?: string;
}

/**
 * Tempdoc 518 Appendix G Wave D.1 — one span row from /api/diagnostics/traces.
 * Mirrors the NdjsonSpanExporter line format.
 */
interface TraceSpan {
  trace_id?: string;
  span_id?: string;
  parent_span_id?: string;
  name?: string;
  start?: string;
  end?: string;
  duration_ms?: number;
  status?: string;
  attrs?: Record<string, unknown>;
}

interface TraceExplorerResponse {
  spans?: TraceSpan[];
  tracesAvailable?: boolean;
}

// Tempdoc 586 §3 — local InferenceStatus interface removed; `inference` is now
// typed as the shared store's snapshot (`UnifiedAiState['inference']`), which was
// extended with `generation` + `lastStartupDurationMs` (already on the wire).

// Tempdoc 586 §3 — local SystemStatus interface removed; `systemStatus` is now the
// shared store's status snapshot (`UnifiedAiState['status']` = generated StatusResponse,
// which carries the embedding/schema fields this surface reads).

interface UiSettings {
  mode?: UiMode;
}

interface LlmSettings {
  serverExecutable?: string | null;
  contextWindow?: number;
  maxTokens?: number;
  gpuLayers?: number;
  modelPath?: string | null;
  llamaLibPath?: string | null;
}

const NUM = new Intl.NumberFormat();

/**
 * Round-15 scope-mismatch fix — the enrichment card's SCOPE, stated on the card itself.
 *
 * The finding was not only that this surface showed the wrong number; it was that neither surface
 * declared which quantity it meant while both said "semantic search". The number is now the shared
 * index-wide blend, and this says so, so a reader can tell what the percent is a percent OF.
 *
 * A local literal on purpose (the `STAGE_LABELS` precedent in `TaskList.ts`): one consumer, and a
 * shared constant with no second consumer is a fork waiting to happen.
 */
const ENRICHMENT_SCOPE_NOTE = 'Overall enrichment across all stages';

// Tempdoc 663 — the local `friendlyModel()` formatter (model-label cleanup) was removed; its one call
// site now projects `core.ai.model` via `projectFact`, which reads `aiState.runtime.modelLabel` — the
// SAME friendly-formatting `aiStateStore.ts`'s own `friendlyModel()` already applies at the source, so
// this view no longer needs a second copy of that logic.

/**
 * Tempdoc 518 Appendix F W3.1 — restart-ETA badge.
 *
 * Returns the "Starting…" subtitle copy. When the runtime has reported a prior
 * successful-startup duration (lastStartupDurationMs >= 0 from /api/inference/status),
 * surface it as an ETA hint: "Usually takes ~Ns." Otherwise return the generic fallback.
 *
 * Tempdoc 601 — the number now comes from the ONE shared `formatStartupEstimate` helper (the
 * sole `<0 → unknown` + seconds-format decision), shared with the affordance-bar reason projector
 * (`projectAvailability`) so the estimate is not forked across surfaces. This keeps BrainSurface's
 * own "AI is initializing." sentence (label+sub structure) and shares only the NUMBER.
 *
 * Tempdoc 601 §20 — when a live load-start stamp is present, show the MEASURED elapsed too (a count-up,
 * never a countdown), mirroring the status pill's "Starting… Ns" so this deep-dive screen also reflects
 * the §18 "show both" mapping: "AI is initializing — 12s (usually ~6s)". Below the `>2s` gate (or with no
 * stamp) it falls back to the prior static copy, so existing call sites are unchanged.
 */
export function formatRestartEtaSub(
  lastStartupDurationMs: number | undefined | null,
  loadStartedAtMs?: number | null,
): string {
  const typical = formatStartupEstimate(lastStartupDurationMs); // "~6s" | null
  const elapsed = elapsedSecondsSince(loadStartedAtMs ?? null);
  if (elapsed > 2) {
    return typical
      ? `AI is initializing — ${humanizeSeconds(elapsed)} (usually ${typical})`
      : `AI is initializing — ${humanizeSeconds(elapsed)}`;
  }
  return typical === null ? 'AI is initializing.' : `AI is initializing. Usually takes ${typical}.`;
}

/**
 * Tempdoc 663 Design pass 2 (critical-review fix, 2026-07-01) — should the Runtime section's
 * GPU/VRAM/Tier grid dim (a previous reading, genuinely about to change)? Mirrors the ONE
 * `provisional` convention used identically in StatusDeck/HealthSurface/BrowseSurface/LibrarySurface
 * (`stability.kind === 'provisional'`) — but narrowed to only the causes that actually mean "this GPU
 * reading may be superseded shortly": `installing`/`starting`/`switching-variant`. Deliberately
 * EXCLUDES `checking`/`stale-poll` — those are about DATA FRESHNESS of a DIFFERENT fact (the
 * install/runtime status hasn't arrived or confirmed yet), not about these specific GPU values being
 * about to change. A retained `this.inference.gpu` snapshot from an earlier successful poll
 * (inferencePoll retains last-known-good) can coexist with `aiEngine.kind === 'connecting'` right
 * after a fresh mount — without this narrowing, the grid would dim for a reason unrelated to its own
 * purpose.
 */
export function isGpuReadingProvisional(stability: AiStability | undefined): boolean {
  return (
    stability?.kind === 'provisional' &&
    (stability.cause === 'installing' ||
      stability.cause === 'starting' ||
      stability.cause === 'switching-variant')
  );
}

/** The ONNX-feature shape this surface renders (a row of `AiRuntimeStatus.onnxFeatures`). */
type OnnxFeatureRow = NonNullable<AiRuntimeStatus['onnxFeatures']>[number];

/**
 * The observed execution provider for one ONNX feature, as one short right-hand label (tempdoc 805
 * G.3). Renders what the ORT session ACTUALLY runs on beside the intent-derived row, because
 * `status:'active'` + `modelActive:true` were both true on round 11's machine while every encoder
 * had silently fallen back to CPU.
 *
 * Unknown renders as the pre-805 intent wording — an absent observation is never a claim.
 */
export function observedEpLabel(f: OnnxFeatureRow): string {
  const ep = f.executionProvider;
  if (f.gpuFallback) {
    return f.fallbackReason ? `CPU (fallback: ${f.fallbackReason})` : 'CPU (GPU fallback)';
  }
  if (ep === 'cuda') return 'CUDA';
  if (ep === 'cpu') return 'CPU';
  // Tempdoc 806 B.2: the Worker-owned encoder rows (embed, splade) can be status:'unknown' before
  // the policy snapshot lands. "inactive" would be a confident negative about a state we cannot see.
  if (f.status === 'unknown') return 'unknown';
  return f.modelActive ? 'active' : 'inactive';
}

/**
 * Whether the Brain SIMPLE view owes the user a repair hint: an ONNX feature is observably running
 * on CPU after a GPU was configured AND the install status says a required file is missing. Both
 * halves are required — a fallback with nothing missing is not repairable by downloading, and a
 * missing file with no observed consequence is already covered by the pending-additions surface.
 */
export function shouldHintRepairForGpuFallback(
  runtimeStatus: AiRuntimeStatus | null,
  installStatus: InstallStatus | null,
): boolean {
  const anyFallback = runtimeStatus?.onnxFeatures?.some((f) => f.gpuFallback === true) ?? false;
  return anyFallback && installStatus?.repairNeeded === true;
}

/** A package automatic repair has provably stopped fixing, with what the user can do instead. */
export interface ManualFallbackPackage {
  packageId: string;
  label: string;
  attempts: number;
  url: string;
  targetPath: string;
  error: string;
}

/**
 * Which remedy — if any — the install state actually owes the user (tempdoc 824 §3.3c/§3.4).
 *
 * - `none` — nothing required is missing. Optional gaps live here too: an absent metadata sidecar
 *   no consumer reads is not a repair prompt.
 * - `repair` — a required file is missing AND no affected capability is observably running. The
 *   full-strength copy, and the FAIL-CLOSED default: with nothing observed, this is what shows.
 * - `repair-soft` — a required file is missing but every affected capability IS observably running.
 *   Round 16's product said "a required component is missing" while SPLADE served 1 660 CUDA
 *   inferences; the honest sentence names both halves.
 * - `manual` — three consecutive repair passes failed the same file at transport. An affordance
 *   that cannot succeed must not be presented as the remedy, so this one names the file, the URL
 *   and the destination instead.
 *
 * This is the same two-signal shape `shouldHintRepairForGpuFallback` above already uses:
 * bookkeeping alone never gets to assert a capability is broken.
 */
export type RepairRemedy =
  | { kind: 'none' }
  | { kind: 'repair' }
  | { kind: 'repair-soft' }
  | { kind: 'manual'; packages: ManualFallbackPackage[] };

export function deriveRepairRemedy(installStatus: InstallStatus | null): RepairRemedy {
  const packages = installStatus?.packages ?? [];
  const stuck = packages.filter((p) => (p.terminalReason ?? '') !== '');
  if (stuck.length > 0) {
    return {
      kind: 'manual',
      packages: stuck.map((p) => ({
        // `p.id` used to be read here as a fallback; the backend `PackageStatus` has never had an
        // `id` field (tempdoc 657), so the arm was always dead. Tempdoc 840 Phase 4 generates this
        // type from the Java DTO's schema, which is how the dead arm became visible.
        packageId: p.packageId ?? '',
        label: p.label ?? p.packageId ?? '',
        attempts: p.attempts ?? 0,
        url: p.url ?? '',
        targetPath: p.targetPath ?? '',
        error: p.error ?? '',
      })),
    };
  }
  if (installStatus?.repairNeeded !== true) return { kind: 'none' };
  // Only a package that actually failed can be attributed to the gap. When none did — the gap came
  // from the disk recompute after a restart, which cannot say which capability it belongs to — no
  // observation applies and the full-strength copy stands.
  const affected = packages.filter((p) => p.state === 'failed');
  const allRunning =
    affected.length > 0 && affected.every((p) => p.functionalStatus === 'active');
  return allRunning ? { kind: 'repair-soft' } : { kind: 'repair' };
}

/** Headline for the offline panel, given the remedy the install state owes. */
export function repairRemedyHeadline(remedy: RepairRemedy): string | null {
  switch (remedy.kind) {
    case 'manual':
      return 'Installed — needs a manual step';
    case 'repair':
    case 'repair-soft':
      return 'Installed — repair available';
    default:
      return null;
  }
}

/** Sub-text for the offline panel, given the remedy the install state owes. */
export function repairRemedySub(remedy: RepairRemedy): string | null {
  switch (remedy.kind) {
    case 'manual':
      return 'Automatic repair could not download a file — see AI install in Detailed for the direct link.';
    case 'repair':
      return 'A required component is missing — use Repair in Detailed.';
    case 'repair-soft':
      return 'Working, but an expected file is missing — Repair will restore it.';
    default:
      return null;
  }
}

/**
 * Tempdoc 941 — why a package in the install status is NOT installed, for the per-package list.
 *
 * `PackageStatus.skipReason` (the planner's authored prose) and `PackageStatus.skipCause` (its
 * typed `SkipCause` id) have both been on the wire since 840 Phase 2 / 941 round 19, and until now
 * the frontend read neither: a skipped package rendered in the Models list exactly like an
 * installed one — same name, a byte count for a download that never happened, and no statement
 * anywhere that it is missing or why. The user's own decline looked identical to a machine that
 * cannot run the package.
 *
 * The two fields are not redundant. `skipCause` is the CLASSIFICATION (what logic reads);
 * `skipReason` is the SENTENCE the planner authored for display — it names the actual constraint
 * ("Insufficient VRAM for chat-standard (6144 MB available, 7680 MB required)"), which no
 * cause-derived copy could reconstruct. So: cause picks the short badge, prose carries the detail.
 */
export interface SkipExplanation {
  /** Short status word for the row, in place of the download size. */
  readonly badge: string;
  /** The full sentence, or null when the record carries no prose and none can be derived. */
  readonly detail: string | null;
}

/**
 * The badge per `SkipCause` id. An id this build does not recognise — and an EMPTY one, which the
 * backend uses deliberately for "unknown, never a guess" — gets the neutral word.
 *
 * Deliberately neutral rather than fail-closed-onto-hardware: `SkipCause.limitsInstall` fails
 * closed because it decides whether the install is SHORT, and being wrong there understates a
 * problem. Copy is the opposite direction — telling a user their machine cannot run something when
 * nothing said so is the prose-as-assumption defect 941 round 19 F3 removed from the completion
 * message. "Not installed" is the true thing when the cause is unknown.
 */
const SKIP_BADGE: Readonly<Record<string, string>> = {
  hardware: 'Unsupported here',
  intent: 'Not in this mode',
  'user-declined': 'Declined',
  'dev-only': 'Development only',
};

/**
 * The sentence to show when the record carries no authored prose. Keyed on the same typed cause,
 * so a skip whose cause IS known still says something, and one whose cause is not says only what
 * is known.
 */
const SKIP_FALLBACK_DETAIL: Readonly<Record<string, string>> = {
  hardware: 'This component was skipped because this machine cannot run it.',
  intent: 'This component is not part of the install mode you chose.',
  'user-declined': 'You chose not to install this component.',
  'dev-only': 'This component ships for development stacks only and is never part of an install.',
};

/**
 * Explain a per-package install status, or `null` when the package was not skipped.
 *
 * Pure and exported so the copy is testable without mounting the surface (the same shape as
 * {@link repairRemedyHeadline} / {@link repairRemedySub} above).
 */
export function skipExplanation(pkg: {
  state?: string | null;
  skipReason?: string | null;
  skipCause?: string | null;
}): SkipExplanation | null {
  if (pkg.state !== 'skipped') return null;
  const cause = (pkg.skipCause ?? '').trim();
  const prose = (pkg.skipReason ?? '').trim();
  return {
    badge: SKIP_BADGE[cause] ?? 'Not installed',
    detail: prose || SKIP_FALLBACK_DETAIL[cause] || null,
  };
}

/** One row of the install consent dialog's terms list — a package the install will pull. */
export interface InstallConsentPackage {
  id: string;
  label: string;
  /** SPDX identifier, or null when the registry declares none. */
  license: string | null;
  /** Upstream terms page, or null when the registry declares none. */
  termsUrl: string | null;
}

/** Everything the consent dialog states, derived only from data the app already has. */
export interface InstallConsentContent {
  /**
   * Formatted total the install will download, or null when the plan preview has not resolved yet
   * (`refreshAll()` is fire-and-forget from `connectedCallback`, so the dialog can open first).
   */
  downloadTotal: string | null;
  /**
   * Sandbox round 8 — formatted bytes an interrupted earlier download left on disk, which this
   * install resumes rather than re-fetches. `null` when there is no paused download (the common
   * first-run case) or the preview has not resolved. `downloadTotal` already EXCLUDES these bytes,
   * so the two numbers add up to the full model footprint rather than double-counting it.
   */
  resumedTotal: string | null;
  packages: InstallConsentPackage[];
  /** True when the manifest has not resolved (or declares no packages) — nothing to show. */
  termsUnavailable: boolean;
}

/** Registry tier ids are kebab-case (`retrieval-core`); the manifest serializes the enum constant
 *  (`RETRIEVAL_CORE`). One normalization so the two can be compared without a wire-shape assumption. */
function normalizeTierId(tier: string | null | undefined): string | null {
  if (!tier) return null;
  return tier.toLowerCase().replace(/_/g, '-');
}

/**
 * Sandbox round 7 — composes the "Download AI models?" consent from the registry manifest
 * (`GET /api/ai/install/manifest`) plus the plan preview (`GET /api/ai/install/plan-preview`),
 * replacing the hardcoded "several GB" prose and the unshown "you must accept the upstream model
 * terms".
 *
 * Degradation is deliberate and asymmetric: a missing preview yields `downloadTotal: null` (the
 * caller says the size is still being computed rather than printing a wrong number), while a
 * package whose tier the active intent excludes is dropped only when the preview says so
 * explicitly — an unknown tier, an untagged package, or a missing preview all keep the package
 * listed. Terms are never hidden by a fallback; only over-listed.
 */
export function composeInstallConsent(
  manifest: AiInstallManifest | null | undefined,
  preview: InstallPlanPreview | null | undefined,
): InstallConsentContent {
  const totalBytes = preview?.totalDownloadBytes;
  const downloadTotal =
    typeof totalBytes === 'number' && totalBytes > 0 ? formatBytes(totalBytes) : null;

  // Sandbox round 8 — the pause dialog promises the already-downloaded bytes stay and the next
  // install resumes from them; this dialog then quoted the FULL footprint as if they were gone.
  // The planner now reports them (`InstallPlan.resumableBytes`), so the consent states them too.
  const resumedBytes = preview?.resumableBytes;
  const resumedTotal =
    typeof resumedBytes === 'number' && resumedBytes > 0 ? formatBytes(resumedBytes) : null;

  const excludedTiers = new Set(
    (preview?.tiers ?? [])
      .filter((t) => t.includedByIntent === false)
      .map((t) => normalizeTierId(t.tier))
      .filter((t): t is string => t !== null),
  );

  const packages: InstallConsentPackage[] = (manifest?.packages ?? [])
    .filter((p: AiInstallModelPackage) => {
      const tier = normalizeTierId(p.tier);
      return tier === null || !excludedTiers.has(tier);
    })
    .map((p: AiInstallModelPackage) => ({
      id: p.id,
      label: p.label || p.id,
      license: p.license ?? null,
      termsUrl: p.termsUrl ?? null,
    }));

  return { downloadTotal, resumedTotal, packages, termsUnavailable: packages.length === 0 };
}

/**
 * 574 A3 — map the legacy `.status-dot.<state>` class word onto the `jf-status-dot`
 * atom's (tone, live) projection. The bespoke per-state dot CSS (online glow / starting
 * + installing pulse) is replaced by the one status-dot atom; the in-progress states
 * (`starting`/`installing`) drive its `live` pulse.
 */
function brainDotTone(dot: string): {
  tone: 'success' | 'warning' | 'error' | 'info' | 'neutral';
  live: boolean;
} {
  switch (dot) {
    // `aiEngineTone` (state/aiVerdict.ts) is the ONE tone authority for the AI-engine kinds; these
    // two words project it rather than re-stating it. `indexing` used to be collapsed into `online`'s
    // green here (and into an "AI Online" label) while chat was in fact unavailable — the contradiction
    // the 0.2.0 round caught as F-6b.
    case 'online':
      return { tone: aiEngineTone('online'), live: false };
    case 'indexing':
      return { tone: aiEngineTone('indexing'), live: false };
    case 'starting':
      return { tone: 'warning', live: true };
    case 'installing':
      return { tone: 'info', live: true };
    case 'offline':
    case 'notinstalled':
    default:
      return { tone: 'neutral', live: false };
  }
}

export class BrainSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    settings: { state: true },
    llm: { state: true },
    inference: { state: true },
    installStatus: { state: true },
    planPreview: { state: true },
    manifest: { state: true },
    installConsentOpen: { state: true },
    runtimeStatus: { state: true },
    policy: { state: true },
    packStatus: { state: true },
    systemStatus: { state: true },
    expanded: { state: true },
    runtimeError: { state: true },
    busy: { state: true },
    refreshing: { state: true },
    // Tempdoc 518 Appendix F W3.2 — mode-transition timeline.
    transitions: { state: true },
    // Tempdoc 518 Appendix G Wave D.1 — trace explorer state.
    recentSpans: { state: true },
    tracesAvailable: { state: true },
    activeTab: { state: true },
  };

  declare apiBase: string;
  /**
   * Tempdoc 508-followup §ε2 — host API for migrating off direct
   * platform / dialog / operation-client imports. Optional for
   * back-compat with mount paths that don't inject it yet; the
   * private helpers below transparently fall back to the direct
   * imports when {@code host_} is undefined.
   */
  declare host_: PluginHostApi | undefined;
  declare settings: UiSettings;
  declare llm: LlmSettings;
  // Tempdoc 586 §3 — `inference` + `systemStatus` are sourced from the shared
  // aiStateStore (the single observed-state authority, aiStateStore.ts §B7),
  // not a second poll BrainSurface runs itself. Typed as the store's snapshots.
  declare inference: UnifiedAiState['inference'];
  declare installStatus: InstallStatus | null;
  declare planPreview: InstallPlanPreview | null;
  /** Registry manifest behind the consent dialog's package/licence/terms list. */
  declare manifest: AiInstallManifest | null;
  declare installConsentOpen: boolean;
  declare runtimeStatus: AiRuntimeStatus | null;
  declare policy: EffectivePolicy | null;
  declare packStatus: PackImportStatus | null;
  declare systemStatus: UnifiedAiState['status'];
  declare expanded: Record<string, boolean>;
  declare runtimeError: string | null;
  declare busy: Record<string, boolean>;
  declare refreshing: boolean;
  /** Active composition tab id: 'runtime' (own body) or a member surface id. */
  declare activeTab: string;
  declare transitions: TransitionRecord[];
  /** Tempdoc 518 Appendix G Wave D.1 — recent spans for the in-product trace explorer. */
  declare recentSpans: TraceSpan[];
  declare tracesAvailable: boolean;

  /** 574 §22.G — the full modal contract (native `<dialog>` + scroll-lock + focus-restore) for the
   *  install consent dialog, composed rather than hand-wired. */
  private readonly consentModal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector<HTMLDialogElement>('dialog.consent'),
    onOpened: () => {
      requestAnimationFrame(() => {
        (this.shadowRoot?.querySelector('jf-button.consent-confirm') as HTMLElement | null)?.focus();
      });
    },
  });

  private clientRef: OperationClient | null = null;
  private _unifiedAiState: UnifiedAiState | null = null;
  private unsubAi: (() => void) | null = null;
  private uiModeUnsub: (() => void) | null = null;
  private modeSaveRevision = 0;
  private memberTabUnsub: (() => void) | null = null;
  private pollDiagnostics: number | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.settings = {};
    this.llm = {};
    this.inference = null;
    this.installStatus = null;
    this.planPreview = null;
    this.manifest = null;
    this.installConsentOpen = false;
    this.runtimeStatus = null;
    this.policy = null;
    this.packStatus = null;
    this.systemStatus = null;
    // Detailed sections start with Runtime open + Models
    // collapsed by default. Install AI / Search Quality Features collapsed
    // unless install is in flight.
    this.expanded = { runtime: true };
    this.runtimeError = null;
    this.busy = {};
    this.refreshing = false;
    this.transitions = [];
    this.recentSpans = [];
    this.tracesAvailable = false;
    this.activeTab = 'runtime';
  }

  static styles = [
    css`
    /* Tempdoc 571 §11 / 578 — Brain is a host surface: display:contents pass-through (layout-purity)
       delegating layout to <jf-surface-tabs>. Its own "AI Brain" body scrolls inside .brain-scroll;
       the Memory member carries its own SurfaceLayout. */
    :host {
      display: contents;
    }
    .brain-scroll {
      height: 100%;
      overflow-y: auto;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    .header {
      position: sticky;
      top: 0;
      z-index: 1;
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding: 1rem 1.5rem;
      background: var(--surface-1);
      border-bottom: 1px solid var(--border-subtle);
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-lg);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .header .subtitle {
      margin: 0.125rem 0 0 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .body {
      padding: 1rem 1.5rem;
      display: flex;
      flex-direction: column;
      gap: 1rem;
    }
    /* 574 B1 — generic action buttons are the jf-button atom now; the .icon-btn /
       .primary / .danger fork is deleted. The base button{} + .mode-toggle rules below
       stay for the bespoke segmented mode-toggle + inline-styled disclosure affordances. */
    button {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      background: var(--surface-primary);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      padding: 0.4rem 0.75rem;
      cursor: pointer;
      font-size: var(--font-size-sm);
      transition: background var(--duration-fast) var(--ease-standard);
    }
    button:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .section {
      padding: 1rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .section h3 {
      margin: 0 0 0.5rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.05em;
      text-transform: uppercase;
      color: var(--text-secondary);
    }
    .status-row {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      flex-wrap: wrap;
      row-gap: 0.5rem;
    }
    /* Tempdoc 840 post-review G — the primary action belongs BESIDE the status it acts on. It used to
       sit after the component list, ~600px below "AI Offline", so the state and the one thing to do
       about it were never on screen together. */
    .status-action {
      margin-left: auto;
    }
    .progress {
      height: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 9999px;
      overflow: hidden;
    }
    .progress-bar {
      height: 100%;
      background: linear-gradient(90deg, var(--accent-tint), var(--accent-tint));
      transition: width var(--duration-normal) var(--ease-standard);
    }
    .grid {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 0.375rem 1rem;
      font-size: var(--font-size-sm);
    }
    .grid .key {
      color: var(--text-secondary);
    }
    .grid .val {
      color: var(--text-primary);
      text-align: right;
      font-family: monospace;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .row {
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    .actions-right {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    label.field {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    input[type='text'],
    input[type='number'] {
      padding: 0.375rem 0.5rem;
      background: var(--surface-tertiary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      font-family: monospace;
    }
    .empty-state {
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .mode-toggle {
      display: inline-flex;
      gap: 0.25rem;
      padding: 0.25rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
    }
    .mode-toggle button {
      border: none;
      background: transparent;
      padding: 0.25rem 0.625rem;
      font-size: var(--font-size-xs);
    }
    .mode-toggle button.active {
      background: var(--accent-tint);
      color: var(--accent-on-tint);
    }
    .variant {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 0.625rem 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      margin-bottom: 0.5rem;
    }
    .variant.active {
      border-color: var(--accent-success);
      background: var(--accent-success-08);
    }
    .variant-info {
      flex: 1;
      min-width: 0;
    }
    .variant-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
    }
    .variant-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .jf-icon-spin {
      animation: jf-spin 1s linear infinite;
    }
    code {
      font-family: monospace;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      background: var(--surface-tertiary);
      padding: 0.125rem 0.25rem;
      border-radius: 0.25rem;
    }
    /* Install consent dialog — native <dialog> (browser inert + focus-trap + Top Layer), driven by
       ModalController so the scroll-lock/focus-restore half cannot be forgotten. */
    dialog.consent {
      border: 1px solid var(--border-subtle);
      border-radius: 0.75rem;
      max-width: 32rem;
      width: 100%;
      padding: 0;
      background: var(--surface-1);
      color: var(--text-primary);
    }
    dialog.consent::backdrop {
      background: rgba(0, 0, 0, 0.55);
    }
    .consent-card {
      padding: 1.25rem;
    }
    .consent-title {
      font-size: var(--font-size-md);
      font-weight: 600;
      margin: 0 0 0.75rem 0;
    }
    .consent-lede {
      margin: 0 0 0.75rem 0;
      font-size: var(--font-size-sm);
      line-height: 1.5;
      color: var(--text-secondary);
    }
    .consent-terms {
      list-style: none;
      margin: 0 0 1rem 0;
      padding: 0;
      max-height: 15rem;
      overflow-y: auto;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    .consent-terms li {
      display: flex;
      align-items: baseline;
      gap: 0.5rem;
      padding: 0.4rem 0.625rem;
      font-size: var(--font-size-sm);
      border-bottom: 1px solid var(--border-subtle);
    }
    .consent-terms li:last-child {
      border-bottom: none;
    }
    .consent-pkg {
      flex: 1;
      min-width: 0;
    }
    .consent-license {
      font-family: monospace;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .consent-terms a {
      color: var(--text-tint);
      white-space: nowrap;
    }
    .consent-actions {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    /* Tempdoc 840 Phase 5 / post-review design pass — the component list.
       A hand-authored GRID, not a flex row: flex let the name absorb every spare pixel, so on a wide
       panel ~1100px of void opened between a component's name and its size, and the size/state pair
       drifted further right the wider the window got. Grid plus a bounded max-width makes the figures
       a real COLUMN whose position does not depend on the length of any name. */
    .component-list {
      margin-top: 1rem;
      /* A measure, not a stretch: past ~46rem the eye stops being able to associate a row's name with
         its number, which is the entire job of this list. */
      max-width: 46rem;
      display: flex;
      flex-direction: column;
      gap: 0.875rem;
    }
    .component-lede {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .component-summary {
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      font-variant-numeric: tabular-nums;
    }
    /* A group is a CONTAINER — the left rule spans its rows, so the necessity heading visibly governs
       what follows instead of reading as one more row in a flat list. */
    .component-group {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      border-left: 1px solid var(--border-subtle);
      padding-left: 0.75rem;
    }
    .component-group-head {
      display: flex;
      align-items: baseline;
      column-gap: 0.25rem;
      row-gap: 0.15rem;
      flex-wrap: wrap;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--text-tertiary);
    }
    /* The consequence is a CLAUSE on the heading line now (840 post-review C.3): same line, lower
       emphasis, no case transform — it is prose, not a label. */
    .component-consequence {
      font-weight: 400;
      letter-spacing: normal;
      text-transform: none;
      color: var(--text-secondary);
    }
    /* What the category costs — the question the bar could only answer where a comparison existed
       (840 post-review B.3). Right-aligned on the heading line, so it reads as the group's total. */
    .component-subtotal {
      margin-left: auto;
      font-weight: 400;
      letter-spacing: normal;
      text-transform: none;
      color: var(--text-secondary);
      font-variant-numeric: tabular-nums;
    }
    /* name | figure | control-slot. The THIRD track is always present, even on a row with no
       toggle — that is what keeps the figures in one column: previously the control was a flex
       sibling AFTER the meta block, so a row without one slid its number right by the toggle's width. */
    .component-row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 4.25rem 3rem;
      align-items: center;
      column-gap: 0.75rem;
      row-gap: 0.125rem;
      padding: 0.4rem 0;
      border-bottom: 1px solid var(--border-subtle);
    }
    .component-row:last-child {
      border-bottom: none;
    }
    .component-name {
      grid-column: 1;
      min-width: 0;
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
      flex-wrap: wrap;
    }
    .component-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
    }
    /* Only ever rendered when the state DEVIATES from installed, so its presence is the signal. */
    .component-state {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .component-size {
      grid-column: 2;
      justify-self: end;
      font-family: monospace;
      font-size: var(--font-size-xs);
      color: var(--text-primary);
      white-space: nowrap;
      font-variant-numeric: tabular-nums;
    }
    /* Reserved on EVERY row, occupied on some. An empty cell is the point. */
    .component-control {
      grid-column: 3;
      justify-self: end;
    }
    .component-desc {
      grid-column: 1 / -1;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    /* The switch: track + thumb, state carried by the thumb's POSITION and the track's fill — not by
       hue — so it survives colour-blindness and the accent-is-not-text rule. */
    .component-toggle::part(control) {
      display: inline-flex;
      align-items: center;
      width: 2.25rem;
      padding: 0.125rem;
      border: 1px solid var(--border-subtle);
      border-radius: 9999px;
      background: var(--surface-tertiary);
      color: var(--text-primary);
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .component-toggle[data-on='true']::part(control) {
      background: var(--text-muted);
      justify-content: flex-end;
    }
    .component-switch-thumb {
      display: block;
      width: 0.75rem;
      height: 0.75rem;
      border-radius: 9999px;
      background: var(--text-primary);
    }
    .component-toggle[data-on='true'] .component-switch-thumb {
      background: var(--surface-secondary);
    }
    /* Staged acquisition — which stage is running, and what it is waiting on. */
    .stage-block {
      margin-top: 0.75rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .stage-transfer {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: monospace;
    }
    .stage-ready {
      font-size: var(--font-size-xs);
      color: var(--text-success);
    }
    .stage-head {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.25rem;
    }
    .stage-label {
      color: var(--text-primary);
    }
    .stage-blocked {
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-warning);
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 923 — Brain, Settings, and the top-bar toggle are projections of the same persisted
    // ui.mode authority. Mirroring the shared store makes changes propagate immediately; `advanced`
    // remains the wire value while Detailed is its one user-facing name.
    this.uiModeUnsub = subscribeUiMode((mode) => {
      if (this.settings.mode !== mode) this.settings = { ...this.settings, mode };
      this.requestUpdate();
    });
    // Tempdoc 571 §11 / 578 — if reached via a member deep-link (core.memory-surface → redirected
    // here), open that member's tab. Drain a pending intent (mounting now) AND subscribe (member
    // deep-link while THIS host is already active still switches the tab).
    const requested = takeMemberTabIntent('core.brain-surface');
    if (requested) this.activeTab = requested;
    this.memberTabUnsub = subscribeMemberTab((hostId, memberId) => {
      if (hostId !== 'core.brain-surface') return false;
      this.activeTab = memberId;
      return true;
    });
    void this.refreshAll();
    this.startDiagnosticsPolling();
    // Tempdoc 586 §3 — the shared aiStateStore is the single observed-state authority
    // for inference + system status (aiStateStore.ts §B7); mirror its snapshots here
    // instead of running a second poll for them.
    this.unsubAi = subscribeAiState((s) => {
      this._unifiedAiState = s;
      // Adopt only non-null snapshots: the store retains its last-known snapshot when
      // the connection goes stale (aiStateStore §B7), so a null here means "no data
      // yet" (pre-first-poll) and must not clobber an already-known value.
      if (s.inference) this.inference = s.inference;
      if (s.status) this.systemStatus = s.status;
      // Tempdoc 663 Stage 3/5 — install/runtime/pack status now come from the shared, always-on
      // `aiInstallPoll` (via aiStateStore), replacing this surface's own one-shot fetch + the
      // conditionally-armed pollInstall/pollPack/pollRuntime timers (which only ever self-armed
      // AFTER a prior fetch had already succeeded — the structural cause of the "stuck on
      // Connecting… forever" bug, tempdoc 663 §O). Same non-null-adopt rule as inference/status.
      if (s.installStatus) this.installStatus = s.installStatus;
      if (s.runtimeStatus) this.runtimeStatus = s.runtimeStatus;
      if (s.packStatus) this.packStatus = s.packStatus;
      this.requestUpdate();
    });
  }

  /** Tempdoc 609 — settle transient state on hide (in-flight refresh / op errors / per-op busy locks)
   *  so a return doesn't show a stale spinner or a locked control. Statuses, expanded sections, and the
   *  active tab are recoverable and untouched. */
  protected override settleTransients(): void {
    this.refreshing = false;
    this.runtimeError = null;
    this.busy = {};
    // An un-answered consent prompt is transient too: a surface hidden mid-question must not come
    // back holding a modal (nor leak its scroll-lock).
    this.installConsentOpen = false;
  }

  /** 574 §22.G — drive the modal contract from the declarative `installConsentOpen` state. */
  protected override updated(changed: Map<string, unknown>): void {
    super.updated(changed);
    if (!changed.has('installConsentOpen')) return;
    if (this.installConsentOpen) this.consentModal.open();
    else this.consentModal.close();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stopAllPolling();
    this.unsubAi?.();
    this.uiModeUnsub?.();
    this.uiModeUnsub = null;
    this.memberTabUnsub?.();
    this.memberTabUnsub = null;
  }

  private client(): OperationClient {
    if (!this.clientRef) {
      const apiBase =
        this.apiBase ||
        (typeof globalThis !== 'undefined' &&
        (globalThis as { location?: { origin?: string } }).location?.origin
          ? (globalThis as { location: { origin: string } }).location.origin
          : '');
      this.clientRef = getOperationClient(apiBase);
    }
    return this.clientRef;
  }

  private base(): string {
    return this.apiBase || '';
  }

  private async fetchJson<T>(path: string, init?: RequestInit): Promise<T | null> {
    try {
      const res = await authorizedFetch(this.base() + path, init);
      if (!res.ok) return null;
      return (await res.json()) as T;
    } catch {
      return null;
    }
  }

  private async refreshAll(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    this.refreshing = true;
    try {
      // Tempdoc 586 §3 / 663 Stage 3 — inference + system status AND install/runtime/pack status
      // are NOT fetched here; all five come from the shared aiStateStore subscription
      // (connectedCallback), which is always-on and self-healing. Only settings/policy — which have
      // no shared poller and are genuinely one-shot facts — are fetched on mount.
      const [settings, policy, preview, manifest] = await Promise.all([
        this.fetchJson<{ ui?: UiSettings; llm?: LlmSettings }>('/api/settings/v2'),
        this.fetchJson<EffectivePolicy>('/api/policy/effective'),
        // Tempdoc 657 — honest per-tier download weight, computed side-effect-free by the planner.
        this.fetchJson<InstallPlanPreview>('/api/ai/install/plan-preview'),
        // Sandbox round 7 — the registry the install runs from: package labels, SPDX licences and
        // upstream terms URLs for the consent dialog. Same swallow-and-degrade contract as the rest
        // of this mount fetch (the dialog states the terms are unavailable rather than inventing them).
        getAiInstallManifest(this.base()).catch(() => null),
      ]);
      if (settings) {
        // The boot appearance restore owns persisted-mode seeding. Brain is a projection, not a
        // second bootstrap writer: a slower settings response must not overwrite a newer top-bar
        // or Settings choice made while this refresh was in flight.
        this.settings = { ...(settings.ui ?? {}), mode: getUiMode() };
        this.llm = settings.llm ?? {};
      }
      if (policy) this.policy = policy;
      if (preview) this.planPreview = preview;
      if (manifest) this.manifest = manifest;
    } finally {
      this.refreshing = false;
    }
  }

  private toggleSection(key: string): void {
    this.expanded = { ...this.expanded, [key]: !this.expanded[key] };
  }

  /**
   * Tempdoc 575 §17 Face C: is the install "running" but its backend owner gone quiet? Derived from
   * the ONE polled-state liveness authority ({@link isAiInstallLive}) — never inline — so the badge
   * cannot be re-implemented per site (the inflight-liveness gate registers this render site).
   */
  private installStalled(): boolean {
    return (
      this.installStatus?.state === 'running' &&
      !isAiInstallLive(this.installStatus.updatedAtEpochMs ?? 0)
    );
  }

  // Tempdoc 663 Stage 3/5 — maybeStartInstallPolling/maybeStartPackPolling/maybeStartRuntimePolling
  // removed. They only self-armed AFTER a prior fetch had already succeeded with `state:'running'`,
  // so a failed/slow FIRST fetch never retried — the structural cause of the live-reproduced
  // "stuck on Connecting… forever" bug (tempdoc 663 §O). Install/runtime/pack status now come from
  // the shared, always-on `aiInstallPoll` (via aiStateStore's subscription above), which retries
  // unconditionally regardless of prior success/failure — on an adaptive cadence since tempdoc 840
  // Phase 5 (fast while something is in flight or still unknown, slower when settled and idle).

  // Tempdoc 586 §3 — inference status now flows from the shared aiStateStore; this
  // loop polls only the brain-specific transition timeline + trace explorer, which
  // have no shared poller. (Renamed from startInferencePolling.)
  private startDiagnosticsPolling(): void {
    if (this.pollDiagnostics !== null) return;
    this.pollDiagnostics = window.setInterval(async () => {
      // Tempdoc 518 Appendix F W3.2 — poll the transition timeline.
      // Cheap read (ring buffer snapshot); refreshes whenever a transition fires.
      const t = await this.fetchJson<{ transitions?: TransitionRecord[] }>(
        '/api/inference/transitions?limit=8',
      );
      if (t && Array.isArray(t.transitions)) {
        this.transitions = t.transitions;
      }
      // Tempdoc 518 Appendix G Wave D.1 — recent spans for the trace explorer panel.
      // Best-effort: the endpoint reports tracesAvailable=false when no traces.ndjson exists on
      // disk (a file check, NOT a tracing-level check), in which case we suppress the panel below.
      const traces = await this.fetchJson<TraceExplorerResponse>(
        '/api/diagnostics/traces?limit=10',
      );
      if (traces && Array.isArray(traces.spans)) {
        this.recentSpans = traces.spans;
        this.tracesAvailable = !!traces.tracesAvailable;
      }
    }, 5000);
  }

  private stopAllPolling(): void {
    if (this.pollDiagnostics !== null) window.clearInterval(this.pollDiagnostics);
    this.pollDiagnostics = null;
  }

  private async withBusy<T>(key: string, fn: () => Promise<T>): Promise<T | null> {
    if (this.busy[key]) return null;
    this.busy = { ...this.busy, [key]: true };
    try {
      return await fn();
    } catch (err) {
      const msg = err instanceof OperationError
        ? err.message
        : err instanceof Error
          ? err.message
          : String(err);
      this.runtimeError = msg;
      return null;
    } finally {
      this.busy = { ...this.busy, [key]: false };
    }
  }

  private async invokeOp(
    operationId: string,
    args: Record<string, unknown> = {},
  ): Promise<unknown> {
    // Tempdoc 550 C3: HIGH-risk ops hit a TYPED_CONFIRM gate (BUTTON → TRUSTED tier;
    // TRUSTED × HIGH). The button gesture is the consent; invokeWithConsent recovers the
    // backend's 428 by approving the backend-issued pending by id and re-invoking with the
    // minted capsule — no client-side mint for an arbitrary op. LOW/MEDIUM are AUTO at
    // TRUSTED tier (CoreTrustEvaluator) → consented:false, a single plain invoke.
    const consented = getOperation(operationId)?.policy?.risk === 'HIGH';
    // Tempdoc 508-followup §ε2 — prefer host.data.invokeOperation
    // when the surface was mounted with a host_. The legacy
    // OperationClient fallback supports test harnesses and any
    // mount paths that pre-date host injection.
    if (this.host_) {
      const result = await this.host_.data.invokeOperation(operationId, args, { consented });
      return result.structuredData;
    }
    const result = await this.client().invokeWithConsent(operationId, { args }, { consented });
    return result.structuredData;
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware confirm dialog. Falls back
   * to the direct confirmAsync when host_ is absent.
   */
  private async hostConfirm(opts: {
    title: string;
    message: string;
    variant?: 'info' | 'warning' | 'danger';
    confirmLabel?: string;
    cancelLabel?: string;
    typedConfirmWord?: string;
  }): Promise<boolean> {
    if (this.host_) {
      return this.host_.ui.showConfirmDialog(opts.message, {
        ...(opts.confirmLabel !== undefined ? { confirmLabel: opts.confirmLabel } : {}),
        ...(opts.cancelLabel !== undefined ? { cancelLabel: opts.cancelLabel } : {}),
        destructive: opts.variant === 'danger',
        ...(opts.typedConfirmWord !== undefined
          ? { typedConfirmWord: opts.typedConfirmWord }
          : {}),
      });
    }
    return confirmAsync(opts);
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware folder picker. Falls back
   * to the direct picker if host_ is absent.
   */
  private async hostPickFolder(title?: string): Promise<string | null> {
    if (this.host_) {
      return this.host_.platform.pickFolder();
    }
    return pickFolder(title !== undefined ? { title } : undefined);
  }

  /**
   * Tempdoc 508-followup §ε2 — host-aware Tauri-runtime check.
   * `host.platform.capabilities` advertises the same capabilities
   * detected by isTauriRuntime; `file-picker` is the closest 1:1
   * substitute for "are we in a Tauri shell?".
   */
  private hostHasFilePicker(): boolean {
    if (this.host_) {
      return this.host_.platform.capabilities.has('file-picker');
    }
    return isTauriRuntime();
  }

  // ---------- Mode toggle ----------

  private async setMode(mode: UiMode): Promise<void> {
    if (getUiMode() === mode) return;
    const previous = getUiMode();
    const localRevision = ++this.modeSaveRevision;
    this.settings = { ...this.settings, mode };
    setUiMode(mode);
    const globalRevision = getUiModeRevision();
    this.busy = { ...this.busy, mode: true };

    // The shared uiMode authority serializes writes from Brain, Settings, and the top bar. A local
    // queue was insufficient: a later top-bar click could otherwise persist before this request.
    let failure: string | null = null;
    try {
      const response = await enqueueUiModePersistence((signal, intent) =>
        authorizedFetch(this.base() + '/api/settings/v2', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            [UI_MODE_INTENT_HEADER]: intent,
          },
          body: JSON.stringify({ ui: { mode } }),
          signal,
        }),
      );
      if (!response.ok) failure = `Couldn't save detail level (HTTP ${response.status}).`;
    } catch (err) {
      failure = err instanceof Error ? err.message : String(err);
    }

    // Only the newest Brain-authored request owns Brain's busy/error lifecycle. Rollback additionally
    // requires this intent still to be the newest GLOBAL choice; another control must never be undone.
    if (localRevision === this.modeSaveRevision) {
      if (
        failure !== null
        && globalRevision === getUiModeRevision()
        && getUiMode() === mode
      ) {
        this.runtimeError = failure;
        this.settings = { ...this.settings, mode: previous };
        setUiMode(previous);
      } else if (failure === null) {
        this.runtimeError = null;
      }
      this.busy = { ...this.busy, mode: false };
    }
  }

  // ---------- Install actions ----------

  /**
   * Sandbox round 7 — the consent is rendered by this surface (see {@link renderInstallConsent})
   * rather than passed as a `message` string, because it carries the real package list, their SPDX
   * licences and CLICKABLE upstream terms links. `showConfirmDialog`'s message is `string`-typed all
   * the way up through the plugin-api host contract, so enriching it would change a capability
   * interface; `AdvisoryInboxDrawer`'s in-template anchors are the precedent followed here.
   */
  private async startInstall(): Promise<void> {
    // Sandbox round 8 — `planPreview` is a MOUNT-time value: `refreshAll()` runs from
    // `connectedCallback`, the manual refresh, and one op-success handler, and nothing else re-asked.
    // Between mount and this click a download can finish files or be paused with bytes retained, both
    // of which change the total the dialog is about to state. Re-ask first rather than quoting a
    // number the backend has already superseded; if the re-ask fails, the last-known preview (or the
    // "still being calculated" fallback) stands, exactly as before.
    await this.refreshInstallPlanPreview();
    this.installConsentOpen = true;
  }

  /** Re-reads the plan preview alone — the one consent input whose value goes stale while mounted. */
  private async refreshInstallPlanPreview(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    const preview = await this.fetchJson<InstallPlanPreview>('/api/ai/install/plan-preview');
    if (preview) this.planPreview = preview;
  }

  /**
   * Tempdoc 840 Phase 5 — record (or withdraw) the user's decision not to install one component.
   *
   * Opt-OUT: the wire call only ever fires from a row that is already selected-by-default, so the
   * DELETE arm exists to undo a standing decline, not to opt in to something the user never chose.
   * The preview is re-read afterwards because it — not this surface — owns `declined` and the
   * recomputed sizes; guessing locally would put a second authority on the row.
   */
  private async setComponentDeclined(id: string, declined: boolean): Promise<void> {
    await this.withBusy(`component:${id}`, async () => {
      this.runtimeError = null;
      const res = await authorizedFetch(
        `${this.base()}/api/ai/install/packages/${encodeURIComponent(id)}/decline`,
        { method: declined ? 'POST' : 'DELETE' },
      );
      if (!res.ok) {
        this.runtimeError = `Could not record that choice (HTTP ${res.status}).`;
        return;
      }
      await this.refreshInstallPlanPreview();
    });
  }

  /**
   * Pause / resume an in-flight run. NOT a cancel: the run keeps its op-lease and its place in the
   * set, so this is the affordance for "stop using my bandwidth for a minute", which cancel — which
   * ends the run — could never be.
   */
  private async setInstallPaused(paused: boolean): Promise<void> {
    await this.withBusy(paused ? 'install-pause' : 'install-resume', async () => {
      this.runtimeError = null;
      const data = await this.fetchJson<InstallStatus>(
        paused ? '/api/ai/install/pause' : '/api/ai/install/resume',
        { method: 'POST' },
      );
      // Both endpoints answer with the post-call status, so a null here is a REFUSAL (or an
      // unreachable backend) — say so rather than leaving a button that looks like it worked.
      if (data) this.installStatus = data;
      else this.runtimeError = `Could not ${paused ? 'pause' : 'resume'} the download.`;
    });
  }

  private async confirmInstall(): Promise<void> {
    this.installConsentOpen = false;
    await this.withBusy('install-start', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.start-ai-install', { acceptTerms: true })) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  /**
   * Cancelling used to destroy every downloaded byte, so it (wrongly) needed no gate to be honest.
   * `DownloadExecutor.cancel()` now SUSPENDS the BITS job instead of removing it and `ResumableFetch`
   * keeps the `.partial` plus its identity sidecar, so the next install resumes via BITS or an HTTP
   * `Range` request (integrity-verified either way). The confirmation states that — pause, not discard.
   */
  private async cancelInstall(): Promise<void> {
    const ok = await this.hostConfirm({
      title: 'Pause the download?',
      message:
        'Cancelling pauses the download. Everything already downloaded stays on disk and the next install resumes from where it stopped instead of starting over. Files that finished downloading stay installed.',
      variant: 'warning',
      confirmLabel: 'Pause download',
      cancelLabel: 'Keep downloading',
    });
    if (!ok) return;
    await this.withBusy('install-cancel', async () => {
      const data = (await this.invokeOp('core.cancel-ai-install', {})) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  private async repairInstall(): Promise<void> {
    const ok = await this.hostConfirm({
      title: 'Repair AI installation?',
      message:
        'This re-runs the install pipeline, re-downloading any missing or corrupt model files. Existing valid files are preserved.',
      variant: 'warning',
      confirmLabel: 'Repair',
    });
    if (!ok) return;
    await this.withBusy('install-repair', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.repair-ai-install', { acceptTerms: true })) as InstallStatus;
      if (data) this.installStatus = data;
    });
  }

  // Tempdoc 508-followup §ε2 + parallel 508 reconcile: `forceRebuildIndex`
  // method removed during merge. The "Force rebuild" button now mounts
  // via `<jf-operation operation-id="core.bulk-reindex">` (parallel 508
  // migration), and the typed-REBUILD confirm ceremony lives on the
  // wire's `ConfirmStrategy.Inline` rather than in a Lit handler.
  // typedConfirmWord on `ConfirmDialogOptions` (added in followup-ε2)
  // remains useful for surfaces that haven't migrated to `<jf-operation>`.

  // ---------- Inference mode ----------

  /**
   * Tempdoc 737 §12b: the chat-engine buttons write the user's INTENT via `core.set-chat-enabled`
   * ({@code enabled:true|false}) — an intent write with no preconditions — instead of the superseded
   * `core.switch-inference-mode`. The reconciler converges the engine toward spec; a soft-off
   * background procedure may keep the engine up with a visible reason. `enabled:true` is always a
   * legal escape action from every state (offline / indexing / background), so there is no dead button.
   */
  private async setChatEnabled(enabled: boolean): Promise<void> {
    await this.withBusy('inference-switch', async () => {
      this.runtimeError = null;
      await this.invokeOp('core.set-chat-enabled', { enabled });
      // Tempdoc 586 §3 — one-shot post-action refresh (not a poll) for immediate
      // feedback; the shared store's 5s poll reconciles too. Typed as the store snapshot.
      const fresh = await this.fetchJson<NonNullable<UnifiedAiState['inference']>>(
        '/api/inference/status',
      );
      if (fresh) this.inference = fresh;
    });
  }

  // ---------- Runtime variants ----------

  private async activateVariant(variantId: string): Promise<void> {
    await this.withBusy('variant', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.activate-runtime-variant', { variantId })) as AiRuntimeStatus;
      if (data) {
        this.runtimeStatus = data;
      }
    });
  }

  private async deactivateVariant(): Promise<void> {
    await this.withBusy('variant', async () => {
      const data = (await this.invokeOp('core.deactivate-runtime-variant', {})) as AiRuntimeStatus;
      if (data) {
        this.runtimeStatus = data;
      }
    });
  }

  // ---------- Pack import (Tauri-only) ----------

  private async preflightPack(): Promise<void> {
    const path = await this.hostPickFolder('Select AI Pack folder or .json manifest');
    if (!path) return;
    await this.withBusy('pack-preflight', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.preflight-ai-pack', { path })) as Record<string, unknown>;
      if (data) {
        this.runtimeError =
          (data['ok'] as boolean) === true
            ? null
            : `Pack preflight: ${data['message'] ?? 'failed'}`;
      }
    });
  }

  private async importPack(): Promise<void> {
    const path = await this.hostPickFolder('Select AI Pack folder');
    if (!path) return;
    await this.withBusy('pack-import', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.import-ai-pack', { path })) as PackImportStatus;
      if (data) {
        this.packStatus = data;
      }
    });
  }

  // ---------- LLM settings persist ----------

  private async patchLlm(updates: Partial<LlmSettings>): Promise<void> {
    this.llm = { ...this.llm, ...updates };
    await authorizedFetch(this.base() + '/api/settings/v2', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ llm: updates }),
    });
  }

  // ---------- Render: alerts + header ----------

  private renderAlerts(): TemplateResult | typeof nothing {
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;
    if (!this.runtimeError && !downloadsDisabled && !onlineDisabled) return nothing;
    return html`
      ${this.runtimeError
        ? html`<jf-error-alert
            tone="error"
            .onDismiss=${() => (this.runtimeError = null)}
          >
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            ${this.runtimeError}
          </jf-error-alert>`
        : nothing}
      ${downloadsDisabled
        ? html`<jf-error-alert tone="warning">
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            Downloads are disabled by administrator policy. Use AI Pack import instead of "Install
            AI".
          </jf-error-alert>`
        : nothing}
      ${onlineDisabled
        ? html`<jf-error-alert tone="warning">
            <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
            Online AI is disabled by administrator policy. Assets can still be installed for
            staging.
          </jf-error-alert>`
        : nothing}
    `;
  }

  private renderHeader(): TemplateResult {
    const mode = getUiMode();
    return html`
      <div class="header">
        <div>
          <h2>AI Brain</h2>
          <p class="subtitle">Configure local language models</p>
        </div>
        <div class="row">
          <div class="mode-toggle" role="group" aria-label="Detail level">
            <button
              type="button"
              class=${mode === 'simple' ? 'active' : ''}
              aria-pressed=${mode === 'simple' ? 'true' : 'false'}
              @click=${() => this.setMode('simple')}
            >
              Simple
            </button>
            <button
              type="button"
              class=${mode === 'advanced' ? 'active' : ''}
              aria-pressed=${mode === 'advanced' ? 'true' : 'false'}
              @click=${() => this.setMode('advanced')}
            >
              Detailed
            </button>
          </div>
          <jf-button
            variant="ghost"
            size="icon"
            label="Refresh status"
            .availability=${this.refreshing ? unavailableBecause('Refreshing…', true) : undefined}
            .onActivate=${() => void this.refreshAll()}
          >
            ${icon({ name: 'refresh-cw', size: 14, spin: this.refreshing })}
          </jf-button>
        </div>
      </div>
    `;
  }

  // ---------- Render: simple panel ----------

  /**
   * Tempdoc 663 Design pass 2 — the OBSERVED half (install/runtime/reachable) is now computed ONCE in
   * `aiStateStore.ts`, exposed as `AiState.aiEngine` (the fourth 594/595/596 sibling, store-level like
   * its siblings, not private to this component). This method is now a thin LOCAL overlay: it applies
   * only this surface's own optimistic click-intent (`busy['install-start']`/`busy['inference-switch']`)
   * on top of that shared observed result — `applyLocalIntent` (state/aiVerdict.ts). The fallback (before
   * the store has ever emitted) mirrors what `computeAiEngineVerdict` itself would compute for "no data,
   * not reachable yet".
   */
  private deriveAiEngineVerdict(): AiEngineVerdict {
    const observed: AiEngineVerdict = this._unifiedAiState?.aiEngine ?? {
      kind: 'connecting',
      stability: { kind: 'provisional', cause: 'stale-poll' },
      installFailure: null,
    };
    return applyLocalIntent(observed, {
      switching: !!this.busy['inference-switch'],
      installStarting: !!this.busy['install-start'],
    });
  }

  private renderSimplePanel(): TemplateResult {
    // Tempdoc 663 — the string `kind` drives this panel's dot/label/sub lookup below, unchanged
    // from before; only its SOURCE changed (the one `computeAiEngineVerdict` derivation, not the
    // old 5-source ladder).
    const aiVerdict = this.deriveAiEngineVerdict();
    const aiState = aiVerdict.kind;
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;
    const repairRemedy = deriveRepairRemedy(this.installStatus);

    const statusConfig: Record<string, { dot: string; label: string; sub: string }> = {
      not_installed: {
        dot: 'notinstalled',
        label: 'Not Installed',
        sub: 'Install AI models to get started.',
      },
      // Sandbox round 8 — this row exists because the one above was rendered over 1.2 GB of retained
      // download. Label and sub PROJECT `aiEngineHeadline`/`aiEngineBody` (the one authority, which
      // carries the byte count) rather than restating them here; that is the fork discipline the
      // `online`/`indexing` rows below already follow.
      paused: {
        dot: 'notinstalled',
        label: aiEngineHeadline(aiVerdict),
        sub: aiEngineBody(aiVerdict),
      },
      installing: {
        dot: 'installing',
        label: this.installStatus?.paused === true ? 'Paused' : 'Installing…',
        // Tempdoc 840 Phase 5 (U5) — the backend's own message names the download's target PATH
        // ("Downloading onnx/gte-multilingual-base/model.onnx..."), which reads as machine noise next
        // to a component list of friendly labels. Map it onto the component's label; the raw phase
        // remains the fallback when there is no in-flight package to name.
        sub:
          friendlyInstallMessage(this.installStatus?.message, this.installStatus?.packages) ||
          (this.installStatus?.phase
            ? `Phase: ${this.installStatus.phase.replace(/_/g, ' ')}`
            : 'Downloading models'),
      },
      // Tempdoc 663 §E — install failure was previously unrepresented as a lifecycle state (folded
      // silently into a generic 'offline'/'not_installed' render, with only a separate dismissable
      // `runtimeError` banner hinting at it). Now a real, named state.
      install_failed: {
        dot: 'offline',
        label: 'Install Failed',
        sub: this.installStatus?.lastError || this.installStatus?.message || 'Installation failed — try again.',
      },
      offline: {
        dot: 'offline',
        // Tempdoc 805 G.3: `repairNeeded` is a claim about DISK (a required file is missing), while
        // `installedFully` is a claim about install HISTORY — round 11 had the first true and the
        // second (correctly) also true, and the label said neither. When something is genuinely
        // missing, name the action; "Installed with limitations" alone tells a user nothing to do.
        // Tempdoc 824 §3.3c/§3.4: which sentence is true also depends on whether the capability is
        // observably running and on whether Repair can still succeed — `deriveRepairRemedy` is the
        // one place that decides, so this panel and the Detailed one cannot name different remedies.
        label:
          repairRemedyHeadline(repairRemedy) ??
          (this.installStatus?.installedFully === false
            ? 'Installed with limitations'
            : 'AI Offline'),
        sub: repairRemedySub(repairRemedy) ?? 'Start AI to enable chat and summaries.',
      },
      starting: {
        dot: 'starting',
        label: 'Starting…',
        // Tempdoc 518 Appendix F W3.1 — restart-ETA badge. When the runtime has reported a
        // prior successful-startup duration, show it as an ETA hint; otherwise fall back to
        // the generic "AI is initializing." copy. Tempdoc 601 §20 — also pass the live load-start
        // stamp so this surface shows the measured elapsed too (the §18 "show both" mapping).
        sub: formatRestartEtaSub(
          this.inference?.lastStartupDurationMs,
          this._unifiedAiState?.runtime?.loadStartedAtMs,
        ),
      },
      // The `label` for these two is a PROJECTION of `aiEngineHeadline` (state/aiVerdict.ts) — the one
      // authority the footer pill already reads — not a second, hand-maintained copy. The fork it
      // replaces said "AI Online" for BOTH kinds, so this panel rendered a green "AI Online" headline
      // while the footer said "Indexing" and /api/health reported `inference.offline` (0.2.0 F-6b).
      // Only the `sub` stays BrainSurface's own: the footer has no sub-text slot.
      // Each row is only ever selected when `aiState` equals its key, so `aiVerdict` is exact here.
      online: { dot: 'online', label: aiEngineHeadline(aiVerdict), sub: 'Chat and summaries ready.' },
      // Tempdoc 663 — indexing is now a distinct, named state (the original ladder had no explicit
      // branch for `runtime.mode === 'indexing'` and fell through to 'offline').
      // Tempdoc 734 round 5 finding 3 — `awaitingChatEnable` distinguishes "the engine is parked
      // because you haven't clicked Resume yet" from "genuinely still indexing"; both read the same
      // sub-label before this, and a Sandbox round waited 5+ minutes expecting the first case to
      // resolve on its own the way the second one does.
      indexing: {
        dot: 'indexing',
        label: aiEngineHeadline(aiVerdict),
        sub: aiVerdict.awaitingChatEnable
          ? "Ready — click Resume Chat AI below to start."
          : 'Indexing embeddings…',
      },
      connecting: { dot: 'starting', label: 'Connecting…', sub: 'Checking AI status…' },
    };
    const sc = statusConfig[aiState] ?? statusConfig.offline!;

    const bytesDone = this.installStatus?.downloadedBytes ?? 0;
    const bytesTotal = this.installStatus?.totalBytes ?? 0;
    const pct = bytesTotal > 0 ? Math.min(100, Math.floor((bytesDone / bytesTotal) * 100)) : null;

    // Tempdoc 663 Stage 3 — the primary action's availability is now typed (596), not a bare boolean.
    // Only the two states with a genuine, showable reason (a policy gate) use `unavailableBecause`; the
    // pure busy-only/unconditional cases use `blocked` (596 §10/C2 — a hard intent gate stays the
    // native-disabled-equivalent tier, not a soft "unavailable{reason}", since there is no reason beyond
    // "wait" to show).
    const primaryAction = (() => {
      switch (aiState) {
        // `paused` shares the install action but not its label: "Install AI" over a half-downloaded
        // 10 GB reads as "start over", which is the very fear the pause dialog set out to remove.
        case 'paused':
        case 'not_installed':
        case 'install_failed':
          return {
            label: aiState === 'paused' ? 'Resume Download' : 'Install AI',
            iconName: 'hard-drive' as const,
            onClick: () => void this.startInstall(),
            availability: downloadsDisabled
              ? unavailableBecause('Downloads are disabled by administrator policy.')
              : this.busy['install-start']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        case 'installing':
          return {
            label: 'Cancel',
            iconName: 'x' as const,
            onClick: () => void this.cancelInstall(),
            availability: this.busy['install-cancel'] ? ({ kind: 'blocked' } as const) : AVAILABLE,
            primary: false,
          };
        case 'offline':
          return {
            label: 'Start AI',
            iconName: 'check-circle-2' as const,
            onClick: () => void this.setChatEnabled(true),
            availability: onlineDisabled
              ? unavailableBecause('Online AI is disabled by administrator policy.')
              : this.busy['inference-switch']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        case 'starting':
          return {
            label: 'Cancel',
            iconName: 'x' as const,
            onClick: () => void this.setChatEnabled(false),
            availability: AVAILABLE,
            primary: false,
          };
        case 'connecting':
          return {
            label: 'Checking…',
            iconName: 'x' as const,
            onClick: () => {},
            availability: { kind: 'blocked' } as const,
            primary: false,
          };
        // `indexing` (engine down, GPU yielded) needs a way back to chat. The intent write
        // `core.set-chat-enabled {enabled:true}` has no precondition, so this is always legal
        // (0.2.0 F-6; mirrors `IndexingOverlay`'s "Go Online" escape hatch).
        case 'indexing':
          return {
            label: 'Resume Chat AI',
            iconName: 'check-circle-2' as const,
            onClick: () => void this.setChatEnabled(true),
            availability: onlineDisabled
              ? unavailableBecause('Online AI is disabled by administrator policy.')
              : this.busy['inference-switch']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        // Soft-off background (tempdoc 737 §15 decision 1): the engine is up finishing background
        // work while chat is disabled. The primary action is NEVER dead — offer to start chat
        // (enabled:true is legal and converges during/after the procedure per backend semantics).
        case 'background':
          return {
            label: 'Start Chat AI',
            iconName: 'check-circle-2' as const,
            onClick: () => void this.setChatEnabled(true),
            availability: onlineDisabled
              ? unavailableBecause('Online AI is disabled by administrator policy.')
              : this.busy['inference-switch']
                ? ({ kind: 'blocked' } as const)
                : AVAILABLE,
            primary: true,
          };
        case 'online':
        default:
          return {
            label: 'Shut Down AI',
            iconName: 'x' as const,
            onClick: () => void this.setChatEnabled(false),
            availability: this.busy['inference-switch'] ? ({ kind: 'blocked' } as const) : AVAILABLE,
            primary: false,
          };
      }
    })();

    return html`
      <div class="section" data-testid="brain-simple-panel">
        <div class="status-row">
          ${(() => {
            const d = brainDotTone(sc.dot);
            return html`<jf-status-dot
              size="lg"
              tone=${d.tone}
              ?live=${d.live}
            ></jf-status-dot>`;
          })()}
          <div>
            <div style="font-size: var(--font-size-md); font-weight: 500">
              ${sc.label}${this.installStalled()
                ? html`<span
                    title="No progress recently — the owner may be stuck. The backstop will reclaim it to a terminal state."
                    style="margin-left: 0.5rem; padding: 0.05rem 0.4rem; border-radius: 0.25rem; font-size: var(--font-size-xs); color: var(--text-warning); border: 1px solid var(--accent-warning)"
                    >stalled</span
                  >`
                : nothing}
            </div>
            <div style="font-size: var(--font-size-xs); color: var(--text-secondary)">${sc.sub}</div>
          </div>
          <div class="status-action">
            <jf-button
              variant=${primaryAction.primary ? 'primary' : 'secondary'}
              .availability=${primaryAction.availability}
              .onActivate=${primaryAction.onClick}
              label=${primaryAction.label}
              data-testid="brain-simple-action"
              style="min-width: 11rem"
            >
              ${icon({ name: primaryAction.iconName, size: 14 })} ${primaryAction.label}
            </jf-button>
          </div>
        </div>

        ${aiState === 'installing'
          ? html`
              <div style="margin-top: 1rem">
                ${pct !== null
                  ? html`
                      <div class="progress">
                        <div class="progress-bar" style="width: ${pct}%"></div>
                      </div>
                      <div
                        style="margin-top: 0.5rem; display:flex; justify-content:space-between; font-size: var(--font-size-xs); color: var(--text-secondary)"
                      >
                        <span>${this.installStatus?.phase?.replace(/_/g, ' ') ?? 'preparing'}</span>
                        <span>${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}</span>
                      </div>
                    `
                  : nothing}
                ${this.renderStagedProgress()}
                ${this.installStatus?.packages?.some((p) => p.resumed)
                  ? html`<div
                      style="margin-top: 0.25rem; font-size: var(--font-size-xs); color: var(--text-secondary)"
                    >
                      Resumed from your earlier download — the bytes already on disk were kept.
                    </div>`
                  : nothing}
                ${/* Tempdoc 840 Phase 5 — pause is NOT cancel: the run keeps its lease and its place,
                      so "stop using my bandwidth for a minute" finally has an affordance that does not
                      end the run. */ ''}
                <div class="row" style="margin-top: 0.75rem">
                  <jf-button
                    label=${this.installStatus?.paused === true
                      ? 'Resume download'
                      : 'Pause download'}
                    data-testid="install-pause-toggle"
                    .availability=${this.busy['install-pause'] || this.busy['install-resume']
                      ? ({ kind: 'blocked' } as const)
                      : AVAILABLE}
                    .onActivate=${() =>
                      void this.setInstallPaused(this.installStatus?.paused !== true)}
                  >
                    ${this.installStatus?.paused === true ? 'Resume download' : 'Pause download'}
                  </jf-button>
                </div>
              </div>
            `
          : nothing}
        ${this.renderComponentList()}
        ${aiState === 'online'
          ? html`
              <div style="margin-top: 1rem; padding: 0.75rem; background: var(--surface-tertiary); border-radius: 0.375rem">
                <div class="grid">
                  ${(() => {
                    // Tempdoc 663 — Model/Context/GPU are now projected via the shared Fact
                    // authority (594) instead of formatted inline; Tier stays inline (out of this
                    // stage's scope — no catalog row exists for it, and it's a single, single-use
                    // read with no second consumer, so AHA says leave it).
                    const model = projectFact('core.ai.model', this._unifiedAiState);
                    const ctx = projectFact('core.ai.contextWindow', this._unifiedAiState);
                    const gpu = projectFact('core.ai.gpu', this._unifiedAiState);
                    return html`
                      ${model.presence === 'present'
                        ? html`<span class="key">${model.name}</span
                            ><span class="val" title=${model.provenance ?? ''}>${model.value}</span
                            >`
                        : nothing}
                      ${this.inference?.tier
                        ? html`<span class="key">Tier</span
                            ><span class="val">${this.inference.tier.replace(/_/g, ' ')}</span>`
                        : nothing}
                      ${ctx.presence === 'present'
                        ? html`<span class="key">${ctx.name}</span><span class="val">${ctx.value}</span>`
                        : nothing}
                      ${gpu.presence === 'present'
                        ? html`<span class="key">${gpu.name}</span><span class="val">${gpu.value}</span>`
                        : nothing}
                    `;
                  })()}
                </div>
              </div>
            `
          : nothing}

        ${aiVerdict.kind === 'install_failed' && aiVerdict.installFailure
          ? html`<jf-error-alert tone="error" style="margin-top: 0.75rem"
              >Install failed: ${aiVerdict.installFailure}</jf-error-alert
            >`
          : nothing}
        ${shouldHintRepairForGpuFallback(this.runtimeStatus, this.installStatus)
          ? html`<jf-error-alert
              tone="warning"
              data-testid="brain-gpu-fallback-hint"
              style="margin-top: 0.75rem"
              >Search features are running on CPU: a GPU component is missing from disk. Use Repair
              in Detailed to download it.</jf-error-alert
            >`
          : nothing}
      </div>
    `;
  }

  // ---------- Render: mode-transition timeline (W3.2) ----------

  /**
   * Tempdoc 518 Appendix F W3.2 — Brain-panel timeline of recent mode transitions.
   * Renders the 8 newest from /api/inference/transitions. Hidden when the list is empty
   * so freshly-booted runtimes (no transitions yet) don't show an empty section.
   */
  /**
   * Tempdoc 518 Appendix G Wave D.2 — sparkline of inference generation over the recent
   * transition window. SVG-based, no external charting library. Each transition is a tick
   * mark on the timeline (failures red, successes teal); the current generation value is
   * the right-edge label. When generation is 0 or there are no transitions yet, returns
   * nothing — there's nothing useful to chart.
   */
  private renderGenerationSparkline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    const currentGen = typeof this.inference?.generation === 'number' ? this.inference.generation : 0;
    if (!rows || rows.length === 0 || currentGen === 0) return nothing;
    // Tempdoc 518 Wave A-E defect Fix-4: render the actual step-function y-line, not just
    // dots. Each successful transition bumps generation by 1; the line shows the count's
    // evolution. Failure dots stay on the line (failure does NOT bump generation, so the
    // step stays flat at that x — visible as a red dot on a horizontal segment).
    const ascending = [...rows].sort((a, b) => a.timestampMs - b.timestampMs);
    const first = ascending[0]!.timestampMs;
    const last = ascending[ascending.length - 1]!.timestampMs;
    const tsSpan = Math.max(1, last - first);
    const W = 200;
    const H = 32;
    const padX = 4;
    const padY = 4;
    const yMax = Math.max(1, currentGen);
    const xOf = (ts: number) => padX + ((ts - first) / tsSpan) * (W - padX * 2);
    const yOf = (gen: number) => H - padY - (gen / yMax) * (H - padY * 2);

    // Build the step function. Walk transitions in chronological order; each success bumps
    // the running gen by 1 (matches the runner's generation counter contract). Each row
    // contributes two points (horizontal then vertical jump on success) and stamps the
    // gen-at-that-row for dot positioning.
    const dotData: Array<{ ts: number; gen: number; row: TransitionRecord }> = [];
    const points: Array<{ x: number; y: number }> = [];
    let gen = 0;
    points.push({ x: padX, y: yOf(gen) });
    for (const row of ascending) {
      const px = xOf(row.timestampMs);
      points.push({ x: px, y: yOf(gen) }); // horizontal segment up to this transition
      if (row.success) {
        gen += 1;
        points.push({ x: px, y: yOf(gen) }); // vertical jump
      }
      dotData.push({ ts: row.timestampMs, gen, row });
    }
    // Final horizontal segment extending to the right edge (current generation plateau).
    points.push({ x: W - padX, y: yOf(gen) });
    const pointsStr = points.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');

    return html`
      <div
        data-testid="brain-generation-sparkline"
        style="margin-top: 0.75rem; display: flex; align-items: center; gap: 0.5rem; font-size: var(--font-size-xs)"
      >
        ${/* Round-14 finding 10 — "gen" was unqualified and collided with a DIFFERENT in-product
              "generation": /api/inference/status `generation` is an int engine-instance counter,
              while /api/knowledge/status `servingSearchGenerationId` is a string index id. Beside a
              model filename the abbreviation also read as "1st-generation model" (a version claim).
              Naming the axis is the whole fix — the value is unchanged. */ ''}
        <span style="color: var(--text-secondary)">engine generation:</span>
        <span style="font-weight: 600; font-variant-numeric: tabular-nums">${currentGen}</span>
        <svg width="${W}" height="${H}" style="display: block">
          <polyline
            data-testid="brain-generation-sparkline-line"
            points="${pointsStr}"
            fill="none"
            stroke="var(--accent-tint)"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          ${dotData.map(
            (d) => html`<circle
              cx="${xOf(d.ts)}"
              cy="${yOf(d.gen)}"
              r="3"
              fill="${d.row.success ? 'var(--accent-tint)' : 'var(--accent-danger)'}"
            >
              <title>${new Date(d.row.timestampMs).toLocaleTimeString()} · gen=${d.gen} · ${d.row.fromMode.toLowerCase()} → ${d.row.toMode.toLowerCase()} · ${d.row.reason.toLowerCase().replace(/_/g, ' ')} · ${d.row.durationMs}ms${d.row.wireCode ? ` · ${d.row.wireCode}` : ''}</title>
            </circle>`,
          )}
        </svg>
      </div>
    `;
  }

  private renderTransitionTimeline(): TemplateResult | typeof nothing {
    const rows = this.transitions;
    if (!rows || rows.length === 0) return nothing;
    return html`
      ${this.renderGenerationSparkline()}
      <details
        class="transitions"
        data-testid="brain-transitions-timeline"
        style="margin-top: 0.875rem; font-size: var(--font-size-xs)"
      >
        ${/* Round-14 finding 10 — "mode" is ambiguous on this very window: the same panel carries a
              Simple | Detailed disclosure mode and the search surface has rungs. These rows are the
              INFERENCE state machine's transitions; say so. */ ''}
        <summary style="cursor: pointer; color: var(--text-secondary)">
          Recent inference transitions (${rows.length})
        </summary>
        <ul
          style="margin: 0.5rem 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.25rem"
        >
          ${rows.map(
            (r) => html`
              <li
                data-testid="brain-transition-row"
                data-success=${String(r.success)}
                style="display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums"
              >
                <span
                  style="display:inline-block; width:0.5rem; height:0.5rem; border-radius:50%; background:${r.success
                    ? 'var(--accent-tint)'
                    : 'var(--accent-danger)'}; flex-shrink:0; align-self: center"
                ></span>
                <span style="color: var(--text-secondary)"
                  >${new Date(r.timestampMs).toLocaleTimeString()}</span
                >
                <span>${r.fromMode.toLowerCase()} → ${r.toMode.toLowerCase()}</span>
                <span style="color: var(--text-secondary)"
                  >· ${r.reason.toLowerCase().replace(/_/g, ' ')}</span
                >
                <span style="color: var(--text-secondary)">· ${r.durationMs}ms</span>
                ${r.wireCode
                  ? html`<span style="color: var(--text-danger)">· ${r.wireCode}</span>`
                  : nothing}
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  /**
   * Tempdoc 518 Appendix G Wave D.1 — in-product trace explorer panel.
   * Lists the 10 most recent spans from /api/diagnostics/traces. Hidden when the endpoint reports
   * `tracesAvailable: false` — which DiagnosticsController derives purely from whether
   * `<dataDir>/telemetry/traces.ndjson` is a regular file, NOT from the tracing level: with
   * JUSTSEARCH_HEAD_TRACING_LEVEL=none but a leftover traces file on disk, the panel still renders
   * (verified empirically). Detailed-mode-only for that reason among others.
   * Clicking a row copies its trace_id to the clipboard so it can be looked up in
   * otel-desktop-viewer or grep'd against traces.ndjson.
   */
  private renderTraceExplorer(): TemplateResult | typeof nothing {
    if (!this.tracesAvailable || !this.recentSpans || this.recentSpans.length === 0) {
      return nothing;
    }
    return html`
      <details
        class="traces"
        data-testid="brain-trace-explorer"
        style="margin-top: 0.875rem; font-size: var(--font-size-xs)"
      >
        <summary style="cursor: pointer; color: var(--text-secondary)">
          Recent spans (${this.recentSpans.length})
          <span style="color: var(--text-secondary); font-size: var(--font-size-xs)">
            · click a row to copy trace ID
          </span>
        </summary>
        <ul
          style="margin: 0.5rem 0 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 0.25rem"
        >
          ${this.recentSpans.map(
            (s) => html`
              <li
                data-testid="brain-trace-row"
                role="button"
                tabindex="0"
                style="display:flex; gap:0.5rem; align-items: baseline; font-variant-numeric: tabular-nums; cursor: pointer"
                @click=${() => {
                  if (s.trace_id) {
                    void navigator.clipboard?.writeText(s.trace_id);
                  }
                }}
                @keydown=${(e: KeyboardEvent) =>
                  activateOnKey(e, () => {
                    if (s.trace_id) void navigator.clipboard?.writeText(s.trace_id);
                  })}
                title=${s.trace_id ? `trace_id=${s.trace_id} (click to copy)` : ''}
              >
                <span
                  style="display:inline-block; width:0.5rem; height:0.5rem; border-radius:50%; background:${s.status === 'ERROR'
                    ? 'var(--accent-danger)'
                    : 'var(--accent-tint)'}; flex-shrink:0; align-self: center"
                ></span>
                <span style="color: var(--text-secondary)">
                  ${s.start ? new Date(s.start).toLocaleTimeString() : '—'}
                </span>
                <span style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 18rem">
                  ${s.name ?? '(unnamed)'}
                </span>
                <span style="color: var(--text-secondary)">
                  · ${typeof s.duration_ms === 'number' ? `${Math.round(s.duration_ms)}ms` : '—'}
                </span>
              </li>
            `,
          )}
        </ul>
      </details>
    `;
  }

  // ---------- Render: compatibility callouts + embedding progress ----------

  private renderCompatibilityCallouts(): TemplateResult | typeof nothing {
    const emb = this.systemStatus?.embedding;
    const schema = this.systemStatus?.schema;
    const embeddingBlocked =
      emb?.compatState === 'BLOCKED_LEGACY' || emb?.compatState === 'BLOCKED_MISMATCH';
    const isLegacy = emb?.compatState === 'BLOCKED_LEGACY';
    // Review 2026-08 (FE review-fix bundle, item 1): this read `=== 'INCOMPATIBLE'`, a literal the
    // producer never emits — `IndexStatusOps.safeSchemaCompatState()` (worker-services) returns only
    // COMPATIBLE / BLOCKED_LEGACY / BLOCKED_MISMATCH / UNAVAILABLE, and `indexing.proto`'s
    // `CompatibilityStatus.schema_compat_state` documents exactly that vocabulary (plus REBUILDING).
    // The gate was therefore dead, and with it the §D1 schema-mismatch remedy this callout carries.
    // Gate on the same blocked pair the backend maps to reason codes in
    // `StatusLifecycleHandler.compatBlockedReason` (BLOCKED_MISMATCH → `index.schema_mismatch`,
    // BLOCKED_LEGACY → `index.blocked_legacy`), so surface and wire agree by construction.
    const schemaBlocked =
      schema?.compatState === 'BLOCKED_LEGACY' || schema?.compatState === 'BLOCKED_MISMATCH';
    if (!embeddingBlocked && !schemaBlocked) return nothing;
    // Tempdoc 613 — coherence. The user-facing CAUSE wording projects the ONE canonical reindex
    // vocabulary (`reasonFor`/CAUSE_ROWS — identical to the Chat degradation banner and the 595
    // verdict), so the same condition can no longer read three different ways across surfaces. The
    // reindex code(s) are READ from the shared verdict already on the aiStateStore snapshot (the
    // backend derived them onto `retrieval.reasonCodes`), so there is NO FE compatState→code remap to
    // drift. The legacy/mismatch distinction, fingerprint hashes, and schema reason stay as
    // config-altitude technical DETAIL beneath the canonical lead.
    // Tempdoc 804 §D1: `index.schema_mismatch` left the degrading REINDEX_CAUSE_CODES bucket (it is
    // advisory — zero query-path consumers), but this callout still renders for a schema BLOCKED_*
    // state, so it must keep projecting that code's canonical wording rather than falling through to
    // the generic "Rebuild the index to restore full search." lead, which would over-claim here.
    const reindexCauses = [
      ...new Set(
        (this._unifiedAiState?.verdict?.reasons ?? []).filter(
          (code: string) => isReindexCause(code) || code === INDEX_SCHEMA_MISMATCH,
        ),
      ),
    ];
    const canonicalWordings = reindexCauses.map((code) => reasonFor(code).wording);
    return html`
      <div class="section" style="border-color: var(--accent-warning-45); background: var(--accent-warning-08)">
        <div style="display: flex; gap: 0.625rem; align-items: flex-start">
          ${icon({ name: 'x-circle', size: 18 })}
          <div style="flex: 1">
            ${canonicalWordings.length > 0
              ? canonicalWordings.map(
                  (wording) =>
                    html`<div style="font-weight: 600; color: var(--text-warning)">
                      ${wording}
                    </div>`,
                )
              : html`<div style="font-weight: 600; color: var(--text-warning)">
                  Rebuild the index to restore full search.
                </div>`}
            ${embeddingBlocked
              ? html`
                  <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.375rem">
                    ${isLegacy
                      ? 'Embedding model fingerprint missing.'
                      : 'Embedding model mismatch detected.'}
                  </div>
                  ${emb?.fingerprintStored && emb?.fingerprintCurrent
                    ? html`<div
                        style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: 0.25rem; font-family: monospace"
                      >
                        Stored: ${emb.fingerprintStored.substring(0, 12)}… → Current:
                        ${emb.fingerprintCurrent.substring(0, 12)}…
                      </div>`
                    : nothing}
                `
              : nothing}
            ${schemaBlocked
              ? html`
                  <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.375rem">
                    Schema incompatible${schema?.reindexRequiredReason
                      ? html` — ${schema.reindexRequiredReason}`
                      : nothing}.
                  </div>
                `
              : nothing}
          </div>
        </div>
        <!-- Tempdoc 511 Phase 9: <jf-operation> aggregate component dispatches via the
             (Operation, button) strategy; the rebuild op is the canonical reindex remedy
             (matching reasonFor's remedy). BrainSurface is operator-tier. -->
        <jf-operation
          context="button"
          operation-id="core.rebuild-index"
          api-base=${this.apiBase}
          @op-success=${() => void this.refreshAll()}
          style="width: 100%; margin-top: 0.75rem"
        ></jf-operation>
      </div>
    `;
  }

  /**
   * The enrichment progress card — round-15 scope-mismatch fix.
   *
   * IT USED TO READ ONE SIGNAL. `systemStatus.embedding` is DOCUMENT-level semantic-vector coverage:
   * 1 of the 4 signals enrichment actually consists of. The round photographed the consequence in a
   * single frame — this card at 96.8% while the Tasks card read 19%, its subtitle promising "chunk
   * embeddings" beside a number ~67 points away from actual chunk coverage, and then the card
   * VANISHING at 100% of its one signal while overall enrichment sat at 46%, leaving the surface
   * dedicated to AI status reading fully idle mid-run.
   *
   * IT NOW READS THE SHARED PROJECTION (813 §3b), the same `selectIndexingProgress` the Tasks card
   * and the status-bar chip render from — chosen over keeping a stage-scoped bar because two numbers
   * both called "semantic search" is the defect itself, and §3b already names one derivation
   * authority for index-wide progress. Consequences that are the point: the percent is the
   * unit-weighted blend over every applicable stage, so it cannot disagree with the other surfaces;
   * and the card persists until the WHOLE blend settles, so a per-stage 100% can no longer read as
   * done. The per-stage breakdown stays available in the Tasks card's disclosure (§20's two layers).
   */
  private renderEnrichmentProgress(): TemplateResult | typeof nothing {
    const emb = this.systemStatus?.embedding;
    // The compat callout above owns the BLOCKED_* states (it carries the reindex remedy); a second
    // card about the same condition would be two voices on one fact.
    if (emb?.compatState?.startsWith('BLOCKED')) return nothing;
    const live = !this._unifiedAiState || this._unifiedAiState.snapshotLive;
    const progress = selectIndexingProgress(
      this.systemStatus,
      live,
      this._unifiedAiState?.episodeMaxPendingJobs ?? 0,
      this._unifiedAiState?.enrichSettleSamples ?? [],
    );
    // Only the enrichment phase is this card's subject: `indexing` is the Tasks card's countdown,
    // `blocked` is the install CTA's business (nothing is progressing, so a progress bar would be a
    // fabrication), and `ready`/`unknown` have no progress to report.
    if (progress.phase !== 'enriching') return nothing;
    const pending = progress.enrichingPending;
    // Never a fabricated number: the projection withholds the percent when it has no faithful
    // denominator, and the bar is withheld with it rather than rendered at a made-up 0.
    const pct = progress.enrichingPercent;
    // Tempdoc 807 A.3 — these are fields off the RETAINED snapshot. With the backend gone they kept
    // animating a confident "Building semantic search 2.0% · 5,084 pending" (round 13, R13-F2): the
    // numbers were right, the present tense was not. Not live ⟹ stop asserting progress — no spinner,
    // no live-work colouring, every figure explicitly labelled as the last observation.
    if (!live) {
      return html`
        <div class="section" data-testid="brain-enrichment-progress">
          <div style="display: flex; gap: 0.625rem; align-items: flex-start">
            ${icon({ name: 'zap', size: 18 })}
            <div style="flex: 1">
              <div style="font-weight: 600; color: var(--text-secondary)">
                Semantic search build — last known
              </div>
              <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.25rem">
                ${reasonFor('binding.unreachable').wording} — these figures are the last observed
                values, not live progress.
              </div>
            </div>
            ${pct === null
              ? nothing
              : html`<div
                  style="font-weight: 600; font-variant-numeric: tabular-nums; color: var(--text-muted)"
                >
                  ${pct}%
                </div>`}
          </div>
          ${pct === null
            ? nothing
            : html`<div class="progress" style="margin-top: 0.625rem">
                <div class="progress-bar" style="width: ${pct}%; background: var(--text-muted)"></div>
              </div>`}
          <div style="font-size: var(--font-size-xs); color: var(--text-muted); margin-top: 0.5rem">
            ${ENRICHMENT_SCOPE_NOTE} · ${NUM.format(pending)} pending when last observed
          </div>
        </div>
      `;
    }
    return html`
      <div
        class="section"
        data-testid="brain-enrichment-progress"
        style="border-color: var(--accent-warning-30); background: var(--accent-warning-08)"
      >
        <div style="display: flex; gap: 0.625rem; align-items: flex-start">
          ${icon({ name: 'zap', size: 18 })}
          <div style="flex: 1">
            <div style="font-weight: 600; color: var(--text-warning)">
              ${ENRICHMENT_IN_PROGRESS_LABEL}
            </div>
            <!-- The subtitle names the SCOPE of the number above it. Before this it promised chunk
                 embeddings while the figure measured document vectors — one stage's number under
                 another stage's sentence. -->
            <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.25rem">
              ${ENRICHMENT_SCOPE_NOTE}: semantic vectors, passage vectors, keyword expansion and
              entity recognition.
            </div>
          </div>
          ${pct === null
            ? nothing
            : html`<div style="font-weight: 600; font-variant-numeric: tabular-nums">${pct}%</div>`}
        </div>
        ${pct === null
          ? nothing
          : html`<div class="progress" style="margin-top: 0.625rem">
              <div class="progress-bar" style="width: ${pct}%; background: var(--accent-warning)"></div>
            </div>`}
        <div style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: 0.5rem; display: flex; align-items: center; gap: 0.375rem">
          ${icon({ name: 'loader-2', size: 12, spin: true })}
          ${NUM.format(pending)} pending across all stages
        </div>
      </div>
    `;
  }

  // ---------- Render: collapsible accordion section helper ----------

  private renderAccordion(
    key: string,
    title: string,
    badgeText: string | null,
    body: () => TemplateResult,
  ): TemplateResult {
    const open = this.expanded[key] === true;
    return html`
      <div class="section" style="padding: 0">
        <button
          style="width: 100%; padding: 0.875rem 1rem; background: transparent; border: none; color: inherit; cursor: pointer; display: flex; align-items: center; gap: 0.5rem; font-size: var(--font-size-sm); font-weight: 500"
          @click=${() => this.toggleSection(key)}
        >
          <span style="display: inline-flex; transition: transform var(--duration-fast) var(--ease-standard); ${open ? 'transform: rotate(0deg)' : 'transform: rotate(-90deg)'}"
            >${icon({ name: 'chevron-down', size: 14 })}</span
          >
          <span>${title}</span>
          ${badgeText
            ? html`<span
                style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-left: 0.5rem"
                >${badgeText}</span
              >`
            : nothing}
        </button>
        ${open
          ? html`<div style="padding: 0 1rem 1rem 1rem; border-top: 1px solid var(--border-subtle)">
              ${body()}
            </div>`
          : nothing}
      </div>
    `;
  }

  private renderSearchQualityFeatures(): TemplateResult {
    const features = this.runtimeStatus?.onnxFeatures ?? [];
    const activeCount = features.filter((f) => f.modelActive).length;
    // Tempdoc 807 A.3 — "N/N active" is a PRESENT-tense claim read off the retained runtime snapshot;
    // round 13 photographed "4/4 active" with the backend dead. Not live ⟹ the count is history, and
    // the per-feature green dot (which asserts "running right now") drops to neutral.
    const live = !this._unifiedAiState || this._unifiedAiState.snapshotLive;
    return this.renderAccordion(
      'search-quality',
      'Search Quality Features',
      features.length > 0
        ? activeCount > 0
          ? live
            ? `${activeCount}/${features.length} active`
            : `${activeCount}/${features.length} when last observed`
          : 'optional'
        : null,
      () => html`
        <div style="margin-top: 0.625rem; font-size: var(--font-size-sm)">
          ${features.length === 0
            ? html`<div style="color: var(--text-secondary); padding: 0.5rem 0">
                No ONNX feature data yet — runtime status pending.
              </div>`
            : features.map(
                (f) => html`
                  <div class="data-row">
                    <span>
                      <jf-status-dot
                        tone=${!live
                          ? 'neutral'
                          : f.gpuFallback
                            ? 'warning'
                            : f.modelActive
                              ? 'success'
                              : 'neutral'}
                        style="margin-right: 0.5rem; vertical-align: middle"
                      ></jf-status-dot>
                      ${f.label ?? f.id ?? 'feature'}
                    </span>
                    <span style="color: var(--text-secondary); font-family: monospace; font-size: var(--font-size-xs)"
                      >${observedEpLabel(f)}</span
                    >
                  </div>
                `,
              )}
        </div>
      `,
    );
  }

  /**
   * Tempdoc 657 — honest first-run weight, grouped by capability tier so the optional heavy LLM pack
   * is visibly separate from the (already-bundled) retrieval stack. Sourced from the side-effect-free
   * plan-preview; renders nothing until it loads. Tiers the active mode excludes are shown dimmed so
   * the reason for their absence is legible rather than a silent gap.
   */
  private renderTierBreakdown(): TemplateResult | typeof nothing {
    const tiers = this.planPreview?.tiers;
    if (!tiers?.length) return nothing;
    const intent = this.planPreview?.intent ?? 'this';
    return html`
      <div style="margin-bottom: 0.5rem">
        ${tiers.map((t) => {
          const total = t.totalBytes ?? 0;
          const download = t.downloadBytes ?? 0;
          const statusText = !t.includedByIntent
            ? `not in ${intent} mode`
            : download > 0
              ? 'to download'
              : 'installed';
          return html`
            <div class="data-row" style=${t.includedByIntent ? '' : 'opacity: 0.55'}>
              <span>${t.label || t.tier || 'tier'}</span>
              <span style="color: var(--text-secondary); font-family: monospace">
                ${total ? formatBytes(total) : '—'} · ${statusText}
              </span>
            </div>
          `;
        })}
      </div>
    `;
  }

  /**
   * Tempdoc 840 Phase 5 (Task 1) — the component list: what each piece of the ~7 GB IS, what it
   * costs, where it stands, and — the point of the whole phase — what you lose by saying no.
   *
   * Grouped by NECESSITY rather than by capability tier because necessity is the only axis a user can
   * act on. The rows are sourced from the plan preview's standing `components[]`, so the list is
   * complete before any install has run.
   */
  private renderComponentList(): TemplateResult | typeof nothing {
    const groups = composeComponentGroups(this.planPreview?.components);
    if (groups.length === 0) return nothing;
    // The stock-take that replaced seven repetitions of "Installed" — one line saying how much is on
    // disk instead of seven saying nothing (`composeComponentSummary`).
    const summary = composeComponentSummary(this.planPreview?.components);
    return html`
      <div class="component-list" data-testid="install-component-list">
        ${summary
          ? html`<div class="component-summary" data-testid="install-component-summary">
              ${summary}
            </div>`
          : nothing}
        <div class="component-lede">
          Everything your machine supports is on. Turn off what you do not want.
        </div>
        ${groups.map(
          (g) => html`
            <div class="component-group" data-testid="component-group-${g.necessity}">
              ${/* A HEADER, not another row: uppercase small caps carrying the group's left rule, with
                    the consequence as a clause on the same line. A toned badge here read as a seventh
                    list item and spent colour on a category name, which is not act-now / in-motion /
                    broken. */ ''}
              <div class="component-group-head">
                <span>${g.heading}</span>
                <span class="component-consequence">· ${g.consequence}</span>
                ${g.subtotal === null
                  ? nothing
                  : html`<span
                      class="component-subtotal"
                      data-testid="component-subtotal-${g.necessity}"
                      >${g.subtotal}</span
                    >`}
              </div>
              ${g.rows.map((r) => this.renderComponentRow(r))}
            </div>
          `,
        )}
      </div>
    `;
  }

  /**
   * One component row, as a four-track grid: name · size bar · figure · control slot.
   *
   * The `unavailable` arm is the reason this is its own method: hardware this machine does not have is
   * NOT a choice, so it renders its reason and NO control — an unticked box would imply an option that
   * does not exist. The reason itself is read off the row's typed `availability`, so the string exists
   * in exactly one place.
   *
   * The control cell is emitted on EVERY row, empty where there is nothing to offer. That is what
   * makes the size figures a column: with the control as a trailing flex sibling, a row without one
   * pulled its figure right by the toggle's width, so the numbers zig-zagged down the panel.
   */
  private renderComponentRow(r: ComponentRow): TemplateResult {
    const unavailableReason =
      r.availability.kind === 'unavailable' ? r.availability.reason : null;
    const busy = !!this.busy[`component:${r.id}`];
    return html`
      <div
        class="component-row"
        data-testid="component-row-${r.id}"
        data-state=${r.state}
        style=${r.selected && r.state !== 'unavailable' && r.state !== 'not-in-mode'
          ? ''
          : 'opacity: 0.62'}
      >
        <div class="component-name">
          <span class="component-label">${r.label}</span>
          ${/* Rendered ONLY when the state deviates from installed — `stateText` is null otherwise, so
                the absence of text IS "installed" (840 post-review C.2). */ ''}
          ${r.stateText
            ? html`<span class="component-state" data-testid="component-state-${r.id}"
                >${r.stateText}</span
              >`
            : nothing}
        </div>
        <span class="component-size">${r.sizeText ?? '—'}</span>
        <div class="component-control">
          ${r.togglable
            ? html`<jf-control
                class="component-toggle"
                data-testid="component-toggle-${r.id}"
                data-on=${r.selected ? 'true' : 'false'}
                .availability=${busy ? ({ kind: 'blocked' } as const) : AVAILABLE}
                label=${r.label}
                .pressed=${r.selected}
                .onActivate=${() => void this.setComponentDeclined(r.id, r.selected)}
                ><span class="component-switch-thumb"></span
              ></jf-control>`
            : nothing}
        </div>
        ${r.description ? html`<div class="component-desc">${r.description}</div>` : nothing}
        ${unavailableReason
          ? html`<div class="component-desc" data-testid="component-unavailable-${r.id}">
              ${unavailableReason}
            </div>`
          : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 840 Phase 5 (Task 2) — honest progress: the measured rate and horizon, the staged plan,
   * and the moment search becomes usable while the rest is still downloading.
   *
   * Every segment appears only when there is an honest basis for it — `-1` on the wire means UNKNOWN
   * and the segment is WITHDRAWN, never rendered as "0 B/s" or "0s left" (`composeTransferLine`).
   */
  private renderStagedProgress(): TemplateResult | typeof nothing {
    const status = this.installStatus;
    const stages = composeStageRows(status);
    const transfer = composeTransferLine(status);
    const ready = searchReadyNotice(status);
    if (stages.length === 0 && transfer === null && ready === null) return nothing;
    return html`
      <div class="stage-block" data-testid="install-staged-progress">
        ${transfer
          ? html`<div class="stage-transfer" data-testid="install-transfer-line">${transfer}</div>`
          : nothing}
        ${ready
          ? html`<div class="stage-ready" data-testid="install-search-ready">${ready}</div>`
          : nothing}
        ${stages.map(
          (s) => html`
            <div class="stage-row" data-testid="install-stage-${s.id}">
              <div class="stage-head">
                <span class="stage-label">${s.current ? '▶ ' : ''}${s.label}</span>
                <span class="stage-state"
                  >${s.stateText}${s.bytesText ? html` · ${s.bytesText}` : nothing}</span
                >
              </div>
              ${s.percent === null
                ? nothing
                : html`<div class="progress">
                    <div class="progress-bar" style="width: ${s.percent}%"></div>
                  </div>`}
              ${s.blockedReason
                ? html`<div class="stage-blocked" data-testid="install-stage-blocked-${s.id}">
                    ${s.blockedReason}
                  </div>`
                : nothing}
            </div>
          `,
        )}
      </div>
    `;
  }

  private renderModels(): TemplateResult {
    const features = this.runtimeStatus?.onnxFeatures ?? [];
    const active = features.filter((f) => f.modelActive).length;
    // Tempdoc 807 A.3 — "N loaded" is the same present-tense claim as the Search Quality count.
    const live = !this._unifiedAiState || this._unifiedAiState.snapshotLive;
    return this.renderAccordion(
      'models',
      'Models',
      active > 0 ? (live ? `${active} loaded` : `${active} when last observed`) : null,
      () => html`
        <div style="margin-top: 0.625rem; font-size: var(--font-size-sm)">
          ${/* Tempdoc 840 Phase 5 — the same component list the Simple panel shows (Simple and
                Detailed are mutually exclusive, so it is never on screen twice). */ ''}
          ${this.renderComponentList()} ${this.renderTierBreakdown()}
          ${this.installStatus?.packages?.length
            ? this.installStatus.packages.map((p) => {
                // Tempdoc 941 — a skipped package says so, and says why. Its byte count is the
                // size of a download that never ran, so showing it in place of the reason made a
                // component that is absent read exactly like one that is present.
                const skip = skipExplanation(p);
                return html`
                  <div class="data-row" data-testid="package-row-${p.packageId ?? ''}">
                    <span>${p.label || p.packageId || 'package'}</span>
                    <span style="color: var(--text-secondary); font-family: monospace"
                      >${skip
                        ? skip.badge
                        : p.bytesTotal
                          ? formatBytes(p.bytesTotal)
                          : '—'}</span
                    >
                  </div>
                  ${skip?.detail
                    ? html`<div
                        class="package-skip-detail"
                        style="font-size: var(--font-size-xs); color: var(--text-secondary)"
                      >
                        ${skip.detail}
                      </div>`
                    : nothing}
                `;
              })
            : this.planPreview
              ? nothing
              : html`<div style="color: var(--text-secondary); padding: 0.5rem 0">
                  No model packages yet — install AI to populate this list.
                </div>`}
          ${this.llm.modelPath
            ? html`
                <div class="data-row">
                  <span>LLM model</span>
                  <span style="color: var(--text-secondary); font-family: monospace; font-size: var(--font-size-xs)"
                    >${this.llm.modelPath}</span
                  >
                </div>
              `
            : nothing}
        </div>
      `,
    );
  }

  // ---------- Render: install consent ----------

  /**
   * Sandbox round 7 — the consent screen states what the app already knows: the exact total from the
   * plan preview, and every package it will install with its SPDX licence and a clickable link to the
   * upstream terms the copy asks you to accept.
   *
   * Sandbox round 8 — the preview is re-read on the way in ({@link startInstall}) rather than trusted
   * from mount, and when an earlier download was paused the retained bytes get their own line. The
   * quoted total is what the network will TRANSFER (retained bytes excluded); the progress screen's
   * denominator is the file-size total those bytes count up to, so the two differ by exactly the
   * resumed amount this dialog now names.
   *
   * The manifest still arrives from the fire-and-forget `refreshAll()`, so it can be missing when
   * the dialog opens. Neither gap is papered over with a plausible-looking number or an empty list:
   * an unresolved preview says the size is still being computed, and an unresolved manifest says the
   * terms could not be listed (and that continuing still accepts them) rather than implying there
   * are none.
   */
  private renderInstallConsent(): TemplateResult {
    const consent = composeInstallConsent(this.manifest, this.planPreview);
    return html`
      <dialog
        class="consent"
        data-testid="install-consent-dialog"
        aria-labelledby="install-consent-title"
        @cancel=${(e: Event) => {
          e.preventDefault();
          this.installConsentOpen = false;
        }}
        @click=${(e: Event) => {
          if (e.target === e.currentTarget) this.installConsentOpen = false;
        }}
      >
        <div class="consent-card">
          <h3 id="install-consent-title" class="consent-title">Download AI models?</h3>
          <p class="consent-lede">
            ${consent.downloadTotal
              ? html`This downloads <strong>${consent.downloadTotal}</strong> of model files into AI
                  Home.`
              : html`This downloads the recommended model files into AI Home. The exact size is still
                  being calculated — it is shown on the progress screen once the download starts.`}
          </p>
          ${consent.resumedTotal
            ? html`<p class="consent-lede">
                <strong>${consent.resumedTotal}</strong> from your earlier paused download is still on
                disk and will be resumed, not downloaded again.
              </p>`
            : nothing}
          <p class="consent-lede">
            ${consent.termsUnavailable
              ? 'The upstream model terms could not be listed right now. Downloading still accepts the licence each package is published under; reopen this dialog once the connection is back to read them first.'
              : 'Each package below is published by its upstream author under the licence shown. Downloading accepts those terms.'}
          </p>
          ${consent.termsUnavailable
            ? nothing
            : html`
                <ul class="consent-terms">
                  ${consent.packages.map(
                    (p) => html`
                      <li>
                        <span class="consent-pkg">${p.label}</span>
                        <span class="consent-license">${p.license ?? 'licence not declared'}</span>
                        ${p.termsUrl
                          ? html`<a href=${p.termsUrl} target="_blank" rel="noopener noreferrer"
                              >Terms ↗</a
                            >`
                          : nothing}
                      </li>
                    `,
                  )}
                </ul>
              `}
          <div class="consent-actions">
            <jf-button label="Cancel" .onActivate=${() => (this.installConsentOpen = false)}>
              Cancel
            </jf-button>
            <jf-button
              class="consent-confirm"
              variant="primary"
              label="Accept and download"
              .onActivate=${() => void this.confirmInstall()}
            >
              Accept and download
            </jf-button>
          </div>
        </div>
      </dialog>
    `;
  }

  // ---------- Render: pack import (Tauri-only advanced) ----------

  private renderPackImport(): TemplateResult | typeof nothing {
    const tauri = this.hostHasFilePicker();
    const allowlist = this.policy?.packAllowlistConfigured ?? false;
    return html`
      <div class="section">
        <h3>${icon({ name: 'folder', size: 12 })} Offline pack import</h3>
        ${!tauri
          ? html`<jf-error-alert tone="warning">
              <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
              Pack import requires the desktop app (Tauri). Browser mode unavailable.
            </jf-error-alert>`
          : !allowlist
            ? html`<jf-error-alert tone="warning">
                <span slot="icon">${icon({ name: 'alert-circle', size: 14 })}</span>
                No pack allowlist is configured. Set
                <code>allowlistedPackManifestSha256</code> in policy to enable imports.
              </jf-error-alert>`
            : html`
                <div class="row">
                  <jf-button
                    label="Preflight"
                    ?disabled=${!!this.busy['pack-preflight']}
                    .onActivate=${() => void this.preflightPack()}
                  >
                    ${icon({ name: 'check-circle-2', size: 14 })} Preflight
                  </jf-button>
                  <jf-button
                    variant="primary"
                    label="Import pack"
                    .availability=${this.busy['pack-import']
                      ? { kind: 'blocked' }
                      : this.packStatus?.state === 'running'
                        ? unavailableBecause('A pack import is already in progress.')
                        : AVAILABLE}
                    .onActivate=${() => void this.importPack()}
                  >
                    ${icon({ name: 'hard-drive', size: 14 })} Import pack
                  </jf-button>
                </div>
                ${this.packStatus
                  ? html`<div style="margin-top: 0.5rem; font-size: var(--font-size-xs); color: var(--text-secondary)">
                      Pack status: <code>${this.packStatus.state}</code>
                      ${this.packStatus.phase ? html` · ${this.packStatus.phase}` : nothing}
                      ${this.packStatus.message ? html` — ${this.packStatus.message}` : nothing}
                    </div>`
                  : nothing}
              `}
      </div>
    `;
  }

  // ---------- Render: install section advanced ----------

  private renderInstallSection(): TemplateResult {
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    // Tempdoc 663 — consume the one AI-engine verdict instead of re-reading `installStatus.state`
    // directly (the ai-verdict-derivation gate). Also picks up the instant `busy['install-start']`
    // feedback the Simple panel already gets, closing the same gap here.
    const installing = this.deriveAiEngineVerdict().kind === 'installing';
    // Tempdoc 806 B.2 (round-12): ONE condition, ONE named remedy. The SIMPLE panel points at this
    // panel by name for `repairNeeded` ("A required component is missing — use Repair in Detailed",
    // :1310) while this panel offered Install as the primary CTA for the same condition — the user
    // was sent here for Repair and shown Install. When a required file is missing on disk, Repair is
    // the primary affordance here too; Install stays available (it is a superset), just not primary.
    // Tempdoc 824 §3.3c/§3.4 — the same derivation the Simple panel reads, so "use Repair in
    // Detailed" cannot land on a panel that has stopped believing Repair is the remedy. Repair is
    // the primary affordance only while it can still succeed: once a file has failed three
    // consecutive passes at transport, presenting Repair as THE action is the round-16 defect.
    const repairRemedy = deriveRepairRemedy(this.installStatus);
    const repairNeeded = repairRemedy.kind === 'repair' || repairRemedy.kind === 'repair-soft';
    const manualFallback = repairRemedy.kind === 'manual' ? repairRemedy.packages : [];
    const optionalGaps = this.installStatus?.optionalGaps ?? [];
    return html`
      <div class="section">
        <h3>${icon({ name: 'hard-drive', size: 12 })} AI install</h3>
        ${repairNeeded
          ? html`<div
              data-testid="install-repair-needed"
              style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.5rem"
            >
              ${repairRemedy.kind === 'repair-soft'
                ? 'Working, but an expected file is missing — Repair will restore it.'
                : 'A required component is missing — use Repair.'}
            </div>`
          : nothing}
        ${manualFallback.length > 0
          ? html`<div
              data-testid="install-manual-fallback"
              style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.5rem"
            >
              Automatic repair could not download these files. Download each one and save it to the
              path shown, then restart AI.
              ${manualFallback.map(
                (p) => html`<div>
                  <strong>${p.label}</strong> — ${p.attempts} attempts${p.error
                    ? html` · ${p.error}`
                    : nothing}
                  <div><code>${p.url}</code></div>
                  <div>→ <code>${p.targetPath}</code></div>
                </div>`,
              )}
            </div>`
          : nothing}
        ${optionalGaps.length > 0
          ? html`<div
              data-testid="install-optional-gaps"
              style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.5rem"
            >
              Optional files not installed (no capability depends on them):
              <code
                >${optionalGaps
                  .map((g) => `${g.packageId ?? ''}/${g.fileName ?? ''}`)
                  .join(', ')}</code
              >
            </div>`
          : nothing}
        <div style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-bottom: 0.5rem">
          State: <code>${this.installStatus?.state ?? 'idle'}</code>${this.installStatus?.phase
            ? html` · phase: <code>${this.installStatus.phase}</code>`
            : nothing}
          ${this.installStatus?.installedFully !== undefined
            ? html` · installedFully: <code>${String(this.installStatus.installedFully)}</code>`
            : nothing}
          ${/* Tempdoc 804 §B8 — a newer version's added artifacts are their own state ("extra AI
                components are available"), not a retroactive un-install of a complete one. */ ''}
          ${this.renderStagedProgress()}
          ${this.installStatus?.pendingRegistryAdditions?.length
            ? html`<div data-testid="install-pending-registry-additions">
                New AI components are available since this install:
                <code>${this.installStatus.pendingRegistryAdditions.join(', ')}</code> — run Install
                to add them.
              </div>`
            : nothing}
        </div>
        <div class="row">
          <jf-button
            variant=${repairNeeded || manualFallback.length > 0 ? 'secondary' : 'primary'}
            label="Install"
            .availability=${installing
              ? unavailableBecause('Already installing.')
              : downloadsDisabled
                ? unavailableBecause('Downloads are disabled by administrator policy.')
                : this.busy['install-start']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
            .onActivate=${() => void this.startInstall()}
          >
            Install
          </jf-button>
          <jf-button
            label="Cancel"
            ?disabled=${!installing || !!this.busy['install-cancel']}
            .onActivate=${() => void this.cancelInstall()}
          >
            Cancel
          </jf-button>
          <jf-button
            variant=${repairNeeded ? 'primary' : 'secondary'}
            label="Repair"
            .availability=${installing
              ? unavailableBecause('Already installing.')
              : downloadsDisabled
                ? unavailableBecause('Downloads are disabled by administrator policy.')
                : this.busy['install-repair']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
            .onActivate=${() => void this.repairInstall()}
          >
            Repair
          </jf-button>
        </div>
      </div>
    `;
  }

  // ---------- Render: runtime / variants / inference / LLM settings ----------

  private renderRuntimeSection(): TemplateResult {
    const variants = this.runtimeStatus?.variants ?? [];
    const activeId = this.runtimeStatus?.activation?.activeVariantId ?? null;
    const actState = this.runtimeStatus?.activation?.state ?? 'idle';
    const activating = actState === 'running' || !!this.busy['variant'];
    const provisional = isGpuReadingProvisional(this._unifiedAiState?.aiEngine.stability);
    // Tempdoc 807 A.3 — the whole Runtime card is a readout of the retained snapshot (CUDA, VRAM,
    // tier, which variant is active), and every control in it POSTs to a backend that must be alive.
    // Not live ⟹ the readout is labelled as history and the controls become unavailable-with-a-reason
    // (the 596 soft block: focusable, reason reachable) rather than silently clickable-and-failing.
    const live = !this._unifiedAiState || this._unifiedAiState.snapshotLive;
    // The reason is IMPORTED from the one cause vocabulary (`binding.unreachable` — the same row the
    // verdict and the disconnection banner word themselves from), never re-authored here.
    // NOT transient (round-13 review): `transient` makes `jf-control` QUEUE the intent and auto-fire
    // it on reconnect, so an offline click on Online/Indexing/Reload/Activate/Deactivate would land a
    // burst of conflicting RUNTIME MUTATIONS the moment the backend returns. These controls must be
    // refused with their reason now, not deferred — the user can re-click once the card is live again.
    const offlineGate = unavailableBecause(reasonFor('binding.unreachable').wording);

    return html`
      <div class="section">
        <h3>${icon({ name: 'hard-drive', size: 12 })} Runtime</h3>

        ${live
          ? nothing
          : html`<div style="font-size: var(--font-size-xs); color: var(--text-muted); margin-bottom: 0.5rem">
              ${reasonFor('binding.unreachable').wording} — the values below are the last observed
              readings, not live.
            </div>`}

        ${this.inference?.gpu
          ? html`
              <div class="grid" style="margin-bottom: 0.75rem; ${provisional || !live ? 'opacity: 0.6' : ''}">
                <span class="key">CUDA</span
                ><span class="val">${this.inference.gpu.cudaAvailable ? 'available' : 'no'}</span>
                ${(() => {
                  // Tempdoc 663 — same `core.ai.gpu` fact as renderSimplePanel's grid; the VRAM
                  // description is one authority, projected wherever it renders.
                  const gpu = projectFact('core.ai.gpu', this._unifiedAiState);
                  return gpu.presence === 'present'
                    ? html`<span class="key">VRAM</span><span class="val">${gpu.value}</span>`
                    : nothing;
                })()}
                ${this.inference.tier
                  ? html`<span class="key">Tier</span
                      ><span class="val">${this.inference.tier.replace(/_/g, ' ')}</span>`
                  : nothing}
              </div>
            `
          : nothing}

        ${variants.length > 0
          ? html`
              <div style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem">
                GPU runtime variants
              </div>
              ${variants.map(
                (v) => html`
                  <div class="variant ${v.id === activeId ? 'active' : ''}">
                    <div class="variant-info">
                      <div class="variant-label">${v.label ?? v.id}</div>
                      <div class="variant-meta">
                        ${v.description ?? ''}
                        ${v.requiredVramBytes
                          ? html` · requires ${formatBytes(v.requiredVramBytes)} VRAM`
                          : nothing}
                        ${v.available === false && v.reason
                          ? html` · <span style="color: var(--text-warning)">${v.reason}</span>`
                          : nothing}
                      </div>
                    </div>
                    ${v.id === activeId
                      ? html`<jf-button
                          label="Deactivate"
                          .availability=${!live
                            ? offlineGate
                            : activating
                              ? { kind: 'blocked' }
                              : AVAILABLE}
                          .onActivate=${() => void this.deactivateVariant()}
                        >
                          Deactivate
                        </jf-button>`
                      : html`<jf-button
                          variant="primary"
                          label="Activate"
                          .availability=${!live
                            ? offlineGate
                            : activating || v.available === false
                              ? { kind: 'blocked' }
                              : AVAILABLE}
                          .onActivate=${() => void this.activateVariant(v.id)}
                        >
                          Activate
                        </jf-button>`}
                  </div>
                `,
              )}
              ${actState === 'running'
                ? html`<div style="font-size: var(--font-size-xs); color: var(--text-secondary)">
                    Activating: ${this.runtimeStatus?.activation?.phase ?? '…'}
                  </div>`
                : nothing}
            `
          : nothing}

        <!-- Inference mode -->
        <div style="margin-top: 1rem">
          <div
            style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem"
          >
            Inference mode
          </div>
          <div class="row">
            <jf-button
              variant=${this.inference?.mode === 'online' ? 'primary' : 'secondary'}
              label="Online"
              .availability=${!live
                ? offlineGate
                : this.policy?.onlineAiEnabled === false
                  ? unavailableBecause('Online AI is disabled by administrator policy.')
                  : this.busy['inference-switch']
                    ? { kind: 'blocked' }
                    : AVAILABLE}
              .onActivate=${() => void this.setChatEnabled(true)}
            >
              Online
            </jf-button>
            <jf-button
              variant=${this.inference?.mode === 'indexing' ? 'primary' : 'secondary'}
              label="Indexing"
              .availability=${!live
                ? offlineGate
                : this.busy['inference-switch']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
              .onActivate=${() => void this.setChatEnabled(false)}
            >
              Indexing
            </jf-button>
            <jf-button
              label="Reload"
              .availability=${!live
                ? offlineGate
                : this.busy['inference-switch']
                  ? { kind: 'blocked' }
                  : AVAILABLE}
              .onActivate=${() =>
                this.withBusy('inference-switch', () => this.invokeOp('core.reload-inference'))}
            >
              Reload
            </jf-button>
            ${this.inference?.embeddingQueueSize !== undefined
              ? html`<span style="font-size: var(--font-size-xs); color: var(--text-secondary); align-self: center">
                  embed queue: ${NUM.format(this.inference.embeddingQueueSize)}
                  ${this.inference.vduQueueSize !== undefined
                    ? html` · VDU queue: ${NUM.format(this.inference.vduQueueSize)}`
                    : nothing}
                  ${live ? nothing : html` (last observed)`}
                </span>`
              : nothing}
          </div>
        </div>

        <!-- LLM settings -->
        <div style="margin-top: 1rem">
          <div
            style="font-size: var(--font-size-xs); text-transform: uppercase; color: var(--text-secondary); margin-bottom: 0.375rem"
          >
            LLM settings
          </div>
          <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem">
            <label class="field">
              Model path
              <input
                type="text"
                .value=${this.llm.modelPath ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({ modelPath: (e.target as HTMLInputElement).value || null })}
              />
            </label>
            <label class="field">
              Server executable
              <input
                type="text"
                .value=${this.llm.serverExecutable ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({
                    serverExecutable: (e.target as HTMLInputElement).value || null,
                  })}
              />
            </label>
            <label class="field">
              Context window
              <input
                type="number"
                min="0"
                .value=${String(this.llm.contextWindow ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({
                    contextWindow: Number((e.target as HTMLInputElement).value) || 0,
                  })}
              />
            </label>
            <label class="field">
              Max tokens
              <input
                type="number"
                min="0"
                .value=${String(this.llm.maxTokens ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({ maxTokens: Number((e.target as HTMLInputElement).value) || 0 })}
              />
            </label>
            <label class="field">
              GPU layers
              <input
                type="number"
                min="0"
                .value=${String(this.llm.gpuLayers ?? 0)}
                @change=${(e: Event) =>
                  void this.patchLlm({ gpuLayers: Number((e.target as HTMLInputElement).value) || 0 })}
              />
            </label>
            <label class="field">
              Llama lib path
              <input
                type="text"
                .value=${this.llm.llamaLibPath ?? ''}
                @change=${(e: Event) =>
                  void this.patchLlm({ llamaLibPath: (e.target as HTMLInputElement).value || null })}
              />
            </label>
          </div>
        </div>
      </div>
    `;
  }

  // ---------- Top render ----------

  override render(): TemplateResult {
    // Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: tab 0 is Brain's own runtime-config body (slotted so
    // this surface's shadow CSS styles it); the remaining tabs are the declared members (Memory).
    const members = getSurface('core.brain-surface')?.members ?? [];
    const items: SurfaceTabItem[] = [
      { id: 'runtime', label: 'AI Brain', altitude: 'PRODUCT', slot: 'tab-runtime' },
      ...members.map((mid) => ({
        id: mid,
        label: present({ kind: 'surface', id: mid }).label,
        altitude: getSurface(mid)?.altitude,
        surfaceId: mid,
      })),
    ];
    return html`
      <jf-surface-tabs
        tablist-label="AI Brain views"
        api-base=${this.apiBase}
        .host_=${this.host_}
        active-id=${this.activeTab}
        .items=${items}
        @tab-change=${(e: CustomEvent<{ id: string }>) => (this.activeTab = e.detail.id)}
      >
        <div slot="tab-runtime" class="brain-scroll">${this.renderBrainBody()}</div>
      </jf-surface-tabs>
    `;
  }

  // Tempdoc 586 §F-1a — first-paint skeleton shown until the initial status lands
  // (inference from the shared store, the rest from refreshAll), instead of a bare panel.
  private renderLoadingSkeleton(): TemplateResult {
    return html`
      <div
        style="display: flex; align-items: center; gap: 0.625rem; padding: 1rem; color: var(--text-secondary); font-size: var(--font-size-sm)"
      >
        <jf-pulse-dots></jf-pulse-dots>
        <span>Checking AI status…</span>
      </div>
    `;
  }

  private renderBrainBody(): TemplateResult {
    if (!this.apiBase && this.apiBase !== '') {
      return html`<div class="empty-state">No API connection. Start the JustSearch backend to configure AI.</div>`;
    }
    const mode = getUiMode();
    // Tempdoc 586 §F-1a — true cold start (no snapshot yet from store or refreshAll).
    const loading =
      this.inference == null && this.installStatus == null && this.runtimeStatus == null;
    return html`
      ${this.renderHeader()}
      <div class="body">
        ${this.renderAlerts()}
        ${loading ? this.renderLoadingSkeleton() : nothing}
        ${!loading && mode === 'simple' ? this.renderSimplePanel() : nothing}
        ${!loading && mode === 'advanced'
          ? html`
              <button
                style="display: inline-flex; align-items: center; gap: 0.4rem; align-self: flex-start; background: transparent; border: none; color: var(--text-secondary); cursor: pointer; font-size: var(--font-size-sm); padding: 0"
                @click=${() => this.setMode('simple')}
              >
                ${icon({ name: 'chevron-down', size: 14 })}
                <span style="display: inline-flex; transform: rotate(90deg)"></span>
                Simple view
              </button>
              ${this.renderCompatibilityCallouts()}
              ${this.renderEnrichmentProgress()}
              ${this.renderAccordion(
                'install',
                'Install AI',
                this.installStatus?.state ?? 'idle',
                () => html`<div style="padding-top: 0.625rem">${this.renderInstallSection()}</div>`,
              )}
              ${this.renderSearchQualityFeatures()}
              ${this.renderAccordion(
                'runtime',
                'Runtime',
                this.inference?.mode ? `Mode: ${this.inference.mode}` : null,
                () =>
                  html`<div style="padding-top: 0.625rem">${this.renderRuntimeSection()}</div>`,
              )}
              ${this.renderModels()}
              ${this.renderPackImport()}
              <!-- Developer telemetry: Detailed-only. Both panels expose runtime internals (mode
                   transitions, span/trace IDs) that have no meaning on the first-run Simple panel,
                   where they previously rendered. -->
              ${this.renderTransitionTimeline()}
              ${this.renderTraceExplorer()}
            `
          : nothing}
        ${this.renderInstallConsent()}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-brain-surface')) {
  customElements.define('jf-brain-surface', BrainSurface);
}
