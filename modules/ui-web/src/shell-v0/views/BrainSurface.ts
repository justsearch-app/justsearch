// SPDX-License-Identifier: Apache-2.0
/**
 * BrainSurface — Lit-side Brain rail surface (slice 452 phase 9).
 *
 * Self-mounting Surface with full functional parity to React BrainView:
 * install/cancel/repair AI, Advanced settings, GPU runtime
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
// Tempdoc 571 §11 / 578 — AI Brain ⊇ Memory: Brain hosts the AI's learned-memory as a tab.
import '../components/SurfaceTabs.js';
import type { SurfaceTabItem } from '../components/SurfaceTabs.js';
import { getSurface } from '../../api/registry/SurfaceCatalogClient.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { saveAbsoluteSettings, createSettingsAttempt, executeSettingsAttempt, requireSettingsWitness, SettingsAttemptError, type SettingsAttempt } from '../../api/settingsAttempt.js';
import type { SettingsV2 } from '../../api/generated/schema-types/settings-v2.js';
import { present } from '../display/present.js';
import { formatBytes } from '../display/format.js';
import { projectFact } from '../display/facts.js';
import { takeMemberTabIntent, subscribeMemberTab, requestMemberTab } from '../router/memberTabIntent.js';
import '../components/OpButton.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import '../components/OperationOutcomeDetail.js';
import type { OpSuccessEventDetail, OpErrorEventDetail } from '../components/OpButton.js';
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
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';
import {
  getUiMode,
  subscribeUiMode,
  type UiMode,
} from '../state/uiModeState.js';
// Tempdoc 657 — pre-install per-tier weight breakdown (GET /api/ai/install/plan-preview).
import { aiRuntimeReadStart, markAiReadOrder, refreshAiInstallNow, type InstallPlanPreview } from '../utils/aiInstallPoll.js';
import {
  aiEngineBody,
  aiEngineHeadline,
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
import { TransientController } from '../primitives/transientController.js';
import { composeBrainSentence, PICTURES_NOT_AVAILABLE_WITH_MODEL, type BrainFeature, type BrainFeatureId } from '../state/brainPage.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
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

// Tempdoc 586 §3 — local InferenceStatus interface removed; `inference` is now
// typed as the shared store's snapshot (`UnifiedAiState['inference']`), which was
// extended with `generation` + `lastStartupDurationMs` (already on the wire).

// Tempdoc 586 §3 — local SystemStatus interface removed; `systemStatus` is now the
// shared store's status snapshot (`UnifiedAiState['status']` = generated StatusResponse,
// which carries the embedding/schema fields this surface reads).

interface UiSettings {
  mode?: UiMode;
  chatEnabled?: boolean | null;
}

interface LlmSettings {
  serverExecutable?: string | null;
  contextWindow?: number | null;
  maxTokens?: number | null;
  gpuLayers?: number | null;
  modelPath?: string | null;
  llamaLibPath?: string | null;
}

interface ModelPresence {
  packages?: Array<{ id: string; complete: boolean; presentVariantFiles?: string[] }>;
}

/** One file of `GET /api/ai/install/downloaded-files`, relative to the models folder (design D4). */
interface DownloadedFileEntry { path?: string; sizeBytes?: number | null; reason?: string | null }

/** One part as Advanced settings - Downloaded files shows it; sizes are measured on disk. */
interface DownloadedPart {
  id?: string;
  label?: string;
  state?: string;
  sizeOnDiskBytes?: number | null;
  declined?: boolean;
  removable?: boolean;
  reason?: string | null;
  previewDigest?: string | null;
  unavailableAfter?: string;
  delete?: DownloadedFileEntry[];
  kept?: DownloadedFileEntry[];
}

/** The latest removal, reported from what is actually on disk. */
interface FileRemoval {
  packageId?: string;
  label?: string;
  state?: string;
  message?: string;
  deleted?: DownloadedFileEntry[];
  kept?: DownloadedFileEntry[];
}

interface DownloadedFilesView { parts?: DownloadedPart[]; removal?: FileRemoval | null }

/** Parts whose files "Remove files" can delete in this step; the others show why not. */
const REMOVABLE_PARTS = new Set(['reranker', 'chat']);
const REMOVING_FILES = 'Removing files - try again when it finishes';

function measuredSize(bytes: number | null | undefined): string {
  return bytes == null ? 'size unknown' : formatBytes(bytes);
}

const NUM = new Intl.NumberFormat();
const PICTURE_CHOICE_UNKNOWN = 'Saved picture reading choice is not known. Refresh settings before changing it.';

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
  return featureInUse(f) ? 'active' : 'inactive';
}

/**
 * Whether the runtime observes this ONNX feature working now: a loaded model whose status is not
 * 'inactive'. `status:'inactive', modelActive:true` is a disabled feature whose Worker session stays
 * loaded (RuntimeActivationService), so a loaded model alone is not "in use". 'unknown' is `null`.
 * The endpoint always sends a status; an absent one keeps the pre-redesign reading of modelActive.
 */
export function featureInUse(f: OnnxFeatureRow | undefined): boolean | null {
  if (f == null || f.status === 'unknown') return null;
  return f.status !== 'inactive' && f.modelActive === true;
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
      return 'Automatic repair could not download a file — use the direct links below.';
    case 'repair':
      return 'A required component is missing — use Repair below.';
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
 * Every `skipCause` the wire can carry, projected from the generated schema type rather than
 * restated here: `SSOT/schemas/ai-install-status.v1.json` states the closed set (from the backend
 * `SkipCause` enum, via `@WireEnumIds`), so this union tracks the backend automatically.
 */
type SkipCauseWire = NonNullable<NonNullable<AiInstallStatus['packages']>[number]['skipCause']>;

/**
 * The causes that carry a classification — the wire set minus `''`, the deliberate "unknown, never
 * a guess" form. These are exactly the keys the copy maps below must cover, so an additional backend
 * cause fails the `satisfies` check at compile time instead of silently rendering the neutral
 * fallback in the UI.
 */
type ClassifiedSkipCause = Exclude<SkipCauseWire, ''>;

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
  platform: 'Different operating system',
  intent: 'Not in this mode',
  'user-declined': 'Declined',
  'dev-only': 'Development only',
} satisfies Record<ClassifiedSkipCause, string>;

/**
 * The sentence to show when the record carries no authored prose. Keyed on the same typed cause,
 * so a skip whose cause IS known still says something, and one whose cause is not says only what
 * is known.
 */
const SKIP_FALLBACK_DETAIL: Readonly<Record<string, string>> = {
  hardware: 'This component was skipped because this machine cannot run it.',
  platform: 'This component is not available for this operating system.',
  intent: 'This component is not part of the install mode you chose.',
  'user-declined': 'You chose not to install this component.',
  'dev-only': 'This component ships for development stacks only and is never part of an install.',
} satisfies Record<ClassifiedSkipCause, string>;

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
      // Once the planner has component rows it owns the exact acquisition set, including
      // individual declines within a tier and files already present on disk.
      if (preview?.components) {
        const component = preview.components.find(c => c.id === p.id);
        return component != null && component.declined !== true
          && component.state === 'to-download';
      }
      const tier = normalizeTierId(p.tier);
      return tier === null || !excludedTiers.has(tier);
    })
    .map((p: AiInstallModelPackage) => ({
      id: p.id,
      label: p.label || p.id,
      license: p.license ?? null,
      termsUrl: p.termsUrl ?? null,
    }));

  return { downloadTotal, resumedTotal, packages, termsUnavailable: !manifest?.packages?.length };
}

/**
 * 574 A3 — map the legacy `.status-dot.<state>` class word onto the `jf-status-dot`
 * atom's (tone, live) projection. The bespoke per-state dot CSS (online glow / starting
 * + installing pulse) is replaced by the one status-dot atom; the in-progress states
 * (`starting`/`installing`) drive its `live` pulse.
 */
export class BrainSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    settings: { state: true },
    llm: { state: true },
    inference: { state: true },
    installStatus: { state: true },
    planPreview: { state: true },
    modelPresence: { state: true },
    manifest: { state: true },
    installConsentOpen: { state: true },
    runtimeStatus: { state: true },
    policy: { state: true },
    packStatus: { state: true },
    systemStatus: { state: true },
    expanded: { state: true },
    runtimeError: { state: true },
    bulkOutcomeKey: { state: true },
    bulkError: { state: true },
    busy: { state: true },
    refreshing: { state: true },
    activeTab: { state: true },
    featureInfo: { state: true },
    planLoading: { state: true },
    planError: { state: true },
    downloadedFiles: { state: true },
    removalConfirm: { state: true },
    removalError: { state: true },
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
  private settingsRead = false;
  declare llm: LlmSettings;
  // Tempdoc 586 §3 — `inference` + `systemStatus` are sourced from the shared
  // aiStateStore (the single observed-state authority, aiStateStore.ts §B7),
  // not a second poll BrainSurface runs itself. Typed as the store's snapshots.
  declare inference: UnifiedAiState['inference'];
  declare installStatus: InstallStatus | null;
  declare planPreview: InstallPlanPreview | null;
  declare modelPresence: ModelPresence | null;
  /** Registry manifest behind the consent dialog's package/licence/terms list. */
  declare manifest: AiInstallManifest | null;
  declare installConsentOpen: boolean;
  declare runtimeStatus: AiRuntimeStatus | null;
  declare policy: EffectivePolicy | null;
  declare packStatus: PackImportStatus | null;
  declare systemStatus: UnifiedAiState['status'];
  declare expanded: Record<string, boolean>;
  declare runtimeError: string | null;
  declare bulkOutcomeKey: string;
  declare bulkError: OpErrorEventDetail | null;
  declare busy: Record<string, boolean>;
  declare refreshing: boolean;
  /** Active composition tab id: 'runtime' (own body) or a member surface id. */
  declare activeTab: string;
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
  private rerankerSettings: SettingsV2 | null = null;
  private rerankerApplied: boolean | null = null;
  private rerankerUncertain = false;
  private refreshRevision = 0;
  private planRevision = 0;
  private planReads = 0;
  private choiceRevision = 0;
  private llmRevision = 0;
  private llmPending = 0;
  private queuedLlm: Partial<LlmSettings> | null = null;
  private readonly llmDrafts = new Map<keyof LlmSettings, string>();
  // Render transients reset on hide; the requests themselves still own these locks.
  private readonly pendingActions = new Set<string>();
  private featureOpener: HTMLElement | null = null;
  private consentFeature: string | null = null;
  /** Parts a "Download again" consent previews and, if accepted, downloads (design D4). */
  private consentInclude: string[] = [];
  declare downloadedFiles: DownloadedFilesView | null;
  /** The part whose "Remove files" confirmation is open. */
  declare removalConfirm: string | null;
  declare removalError: { id: string; message: string } | null;
  private removalPoll: number | null = null;
  private pictureUncertain = false;
  private pictureAttempt: SettingsAttempt | null = null;
  private pictureApplied: boolean | null = null;
  /** Read order of the last picture reading change; a runtime read started earlier may predate it. */
  private pictureChangeOrder = 0;
  private pictureTargetEnabled = false;
  declare featureInfo: BrainFeatureId | null;
  declare planLoading: boolean;
  declare planError: string | null;
  private readonly featureTransient = new TransientController(this, {
    layer: 'transient', id: 'brain-feature-info', managesDismiss: true,
    close: () => this.closeFeatureInfo(),
  });
  private readonly onWindowFocus = () => { void this.refreshAll(); };
  private memberTabUnsub: (() => void) | null = null;
  private pollSettings: number | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.settings = {};
    this.llm = {};
    this.inference = null;
    this.installStatus = null;
    this.planPreview = null;
    this.modelPresence = null;
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
    this.bulkOutcomeKey = '';
    this.bulkError = null;
    this.runtimeError = null;
    this.busy = {};
    this.refreshing = false;
    this.activeTab = 'runtime';
    this.featureInfo = null;
    this.planLoading = false;
    this.downloadedFiles = null;
    this.removalConfirm = null;
    this.removalError = null;
    this.planError = null;
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
    .component-toggle {
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
    .component-toggle[data-on='true'] {
      background: var(--accent-tint);
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
      background: var(--accent-on-tint);
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
    .body { padding: 1.75rem 2.5rem; gap: 1.5rem; min-width: 0; overflow-wrap: anywhere; }
    .brain-headline { margin: 0 0 0.75rem; font-size: var(--font-size-xl); font-weight: 400; }
    .brain-sentence { font-size: var(--font-size-lg); color: var(--text-secondary); line-height: 1.8; margin: 0 0 1rem; max-width: 65rem; }
    .brain-status-detail, .step-meta { font-size: var(--font-size-xs); color: var(--text-secondary); line-height: 1.5; }
    .brain-status-detail { margin-bottom: 0.5rem; }
    button.feature-phrase, button.step-info, .brain-links button, .feature-panel button {
      border: 0; border-radius: 0; padding: 0; background: transparent; color: inherit; font: inherit;
    }
    button.feature-phrase { display: inline; text-align: left; }
    button.feature-phrase:hover, button.step-info:hover { color: var(--text-tint); background: transparent; }
    button:focus-visible, a:focus-visible { outline: 2px solid var(--text-tint); outline-offset: 4px; }
    .feature-switch-wrap { display: inline-flex; flex-wrap: wrap; max-width: 100%; align-items: center; vertical-align: middle; margin-inline: 0.4rem; }
    button.feature-switch { border: 1px solid var(--border-subtle); border-radius: 1rem; width: 2rem; height: 1.15rem; padding: 0.1rem; background: var(--surface-tertiary); }
    button.feature-switch span { display: block; width: 0.8rem; height: 0.8rem; border-radius: 50%; background: var(--text-secondary); }
    button.feature-switch[aria-checked='true'] { background: var(--accent-tint); }
    button.feature-switch[aria-checked='true'] span { margin-left: auto; background: var(--accent-on-tint); }
    button.feature-switch[aria-disabled='true'] { opacity: 0.6; cursor: default; }
    .switch-reason { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
    .feature-switch-wrap:focus-within .switch-reason { position: static; width: auto; height: auto; clip-path: none; font-size: var(--font-size-xs); max-width: min(18rem, 100%); }
    .brain-path { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); column-gap: 0.75rem; row-gap: 2.5rem; padding: 1.5rem 0; }
    .brain-path > .path-step { grid-row: 2; }
    .path-step { position: relative; min-width: 0; display: flex; align-items: center; flex-direction: column; text-align: center; gap: 0.35rem; font-size: var(--font-size-sm); }
    .path-step::before { content: ''; position: absolute; height: 1px; background: var(--border-subtle); width: calc(100% + 0.75rem); top: 1.3rem; left: -50%; z-index: 0; }
    .files-step::before, .picture-branch .path-step::before { display: none; }
    .step-symbol { display: flex; align-items: center; justify-content: center; border: 1px solid var(--border-subtle); background: var(--surface-1); border-radius: 50%; width: 2.65rem; height: 2.65rem; margin-bottom: 0.5rem; position: relative; z-index: 1; }
    .step-symbol[data-active='true'] { border-color: var(--accent-tint); color: var(--text-tint); }
    button.step-info { display: flex; flex-direction: column; align-items: center; }
    .picture-branch { position: relative; grid-column: 2; grid-row: 1; justify-self: center; width: min(20rem, 200%); }
    .picture-branch::after { content: ''; left: 50%; height: 2.5rem; position: absolute; bottom: -2.5rem; border-left: 1px dashed var(--border-subtle); }
    .graphics-card { display: grid; grid-template-columns: max-content minmax(3rem, 1fr) minmax(8rem, max-content); align-items: center; gap: 1.5rem; font-size: var(--font-size-sm); }
    .graphics-card .progress-bar { background: var(--accent-tint); }
    .brain-links { align-items: center; font-size: var(--font-size-sm); color: var(--text-secondary); gap: 1.5rem; }
    .brain-links a { display: inline-flex; align-items: center; gap: 0.4rem; color: inherit; text-decoration: none; }
    .feature-panel { position: absolute; top: calc(100% + 0.75rem); right: 0; width: min(25rem, calc(100vw - 6rem)); max-height: min(24rem, 60vh, calc(100vh - 8rem)); overflow: auto; text-align: left; padding: 1rem; box-sizing: border-box; background: var(--surface-2); color: var(--text-primary); border: 1px solid var(--border-subtle); border-radius: 0.75rem; z-index: 1; box-shadow: 0 0.5rem 2rem #0004; }
    .feature-tradeoffs { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
    .feature-tradeoffs h3 { font-size: var(--font-size-xs); color: var(--text-secondary); font-weight: 400; }
    .feature-tradeoffs p { font-size: var(--font-size-sm); line-height: 1.5; }
    .brain-advanced { min-width: 0; }
    .advanced-heading { margin: 1.5rem 0 0; padding-bottom: 0.5rem; color: var(--text-secondary); font-size: var(--font-size-xs); font-weight: 400; }
    .advanced-row { display: grid; grid-template-columns: minmax(10rem, 28%) minmax(0, 1fr); align-items: center; gap: 1.5rem; padding: 0.9rem 0; border-top: 1px solid var(--border-subtle); font-size: var(--font-size-sm); }
    .advanced-row > div { min-width: 0; overflow-wrap: anywhere; }
    .advanced-row p, .advanced-note, .runtime-observation { margin: 0.25rem 0 0; color: var(--text-secondary); font-size: var(--font-size-xs); }
    .brain-advanced button:not(.component-toggle) { border: 0; border-radius: 0; background: transparent; padding: 0; color: var(--text-secondary); }
    .advanced-row button { margin-left: 0.75rem; }
    .slider-setting { display: flex; flex-wrap: wrap; gap: 0.75rem; align-items: center; }
    .slider-setting input[type='range'] { flex: 1 1 10rem; min-width: 0; width: 100%; accent-color: var(--accent-tint); }
    .slider-setting output { min-width: 8rem; color: var(--text-secondary); }
    .brain-advanced h4 { font-size: var(--font-size-xs); color: var(--text-secondary); font-weight: 400; }
    .brain-advanced .data-row { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 0.5rem; padding: 0.4rem 0; font-size: var(--font-size-sm); }
    .variant-info small { display: block; overflow-wrap: anywhere; }
    .variant-options, .field input { max-width: 100%; box-sizing: border-box; }
    @media (max-width: 650px) { .advanced-row { grid-template-columns: 1fr; gap: 0.5rem; } .component-row { grid-template-columns: minmax(0, 1fr) max-content; } .component-control { grid-column: 2; } }

    .section, .variant { border: 0; border-radius: 0; background: transparent; padding: 1rem 0; border-top: 1px solid var(--border-subtle); }
    jf-button::part(control) { border: 0; border-radius: 0; background: transparent; }
    jf-button.install-action::part(control), jf-button.consent-confirm::part(control) { background: var(--accent-tint); color: var(--accent-on-tint); border-radius: 1rem; }
    jf-button.repair-action::part(control) { color: var(--text-primary); font-weight: 600; }
    dialog.consent { box-sizing: border-box; width: min(32rem, calc(100vw - 2rem)); max-height: calc(100vh - 2rem); overflow: auto; }
    @media (max-width: 800px) {
      .body { padding: 1.25rem; }
      .brain-path { grid-template-columns: 1fr; padding-top: 0; gap: 1.5rem; }
      .brain-path > .path-step { grid-row: auto; }
      .files-step { order: 0; }
      .read-step { order: 1; }
      .picture-branch { order: 2; }
      [data-feature='meaning'] { order: 3; }
      [data-feature='reranker'] { order: 4; }
      [data-feature='chat'] { order: 5; }
      .path-step { align-items: flex-start; text-align: left; padding-left: 3.5rem; }
      .path-step::before { display: none; }
      .step-symbol { position: absolute; left: 0; top: 0; }
      button.step-info { align-items: flex-start; }
      .picture-branch { grid-column: auto; grid-row: auto; width: 100%; }
      .picture-branch::after { display: none; }
      .graphics-card { grid-template-columns: 1fr; gap: 0.5rem; }
      .feature-panel { width: min(25rem, 100%); left: 0; right: auto; }
      .feature-tradeoffs { grid-template-columns: 1fr; gap: 0; }
    }
    @media (forced-colors: active) {
      .step-symbol, button.feature-switch, .component-toggle, .progress { border: 1px solid CanvasText; }
      button.feature-switch[aria-checked='true'], .component-toggle[data-on='true'], .progress-bar { background: Highlight; forced-color-adjust: none; }
      button.feature-switch span, .component-switch-thumb { background: CanvasText; }
      .component-toggle[data-on='true'] .component-switch-thumb { background: HighlightText; }
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
    window.addEventListener('focus', this.onWindowFocus);
    this.startSettingsPolling();
    // Tempdoc 586 §3 — the shared aiStateStore is the single observed-state authority
    // for inference + system status (aiStateStore.ts §B7); mirror its snapshots here
    // instead of running a second poll for them.
    this.unsubAi = subscribeAiState((s) => {
      this._unifiedAiState = s;
      // Adopt only non-null snapshots: the store retains its last-known snapshot when
      // the connection goes stale (aiStateStore §B7), so a null here means "no data
      // yet" (pre-first-poll) and must not clobber an already-known value.
      if (s.inference && s.inference !== this.inference) {
        this.inference = s.inference;
        void this.refreshSettings();
      }
      if (s.status) this.systemStatus = s.status;
      // Tempdoc 663 Stage 3/5 — install/runtime/pack status now come from the shared, always-on
      // `aiInstallPoll` (via aiStateStore), replacing this surface's own one-shot fetch + the
      // conditionally-armed pollInstall/pollPack/pollRuntime timers (which only ever self-armed
      // AFTER a prior fetch had already succeeded — the structural cause of the "stuck on
      // Connecting… forever" bug, tempdoc 663 §O). Same non-null-adopt rule as inference/status.
      if (s.installStatus) {
        const previous = this.installStatus;
        this.installStatus = s.installStatus;
        if ((previous?.state !== s.installStatus.state && previous != null)
          || (previous?.phase === 'activation_required' && s.installStatus.phase !== 'activation_required')) {
          void this.refreshInstallPlanPreview();
          void this.refreshSettings();
        }
      }
      if (s.runtimeStatus) {
        const changed = s.runtimeStatus !== this.runtimeStatus;
        this.runtimeStatus = s.runtimeStatus;
        if (changed && !this.pendingActions.has('reranker-switch')) this.rerankerApplied = null;
        if (!this.pendingActions.has('picture-switch') && this.runtimeReadFollowsPictureChange()) this.pictureApplied = null;
        const reranker = s.runtimeStatus.onnxFeatures?.find(feature => feature.id === 'reranker');
        if (changed && !this.pendingActions.has('reranker-switch')
            && reranker?.status !== 'unknown' && reranker?.modelActive != null
            && this.settingsRead && (this.rerankerSettings?.rerankerEnabled == null
              || this.rerankerSettings.rerankerEnabled === (reranker.status === 'active' && reranker.modelActive))) {
          this.rerankerUncertain = false;
        }
      }
      if (s.packStatus) this.packStatus = s.packStatus;
      this.requestUpdate();
    });
  }

  /** Tempdoc 609 — settle transient state on hide (in-flight refresh / op errors / per-op busy locks)
   *  so a return doesn't show a stale spinner or a locked control. Statuses, expanded sections, and the
   *  active tab are recoverable and untouched. */
  protected override settleTransients(): void {
    this.refreshing = false;
    this.planError = null;
    this.runtimeError = null;
    this.bulkError = null;
    this.busy = {};
    // An un-answered consent prompt is transient too: a surface hidden mid-question must not come
    // back holding a modal (nor leak its scroll-lock).
    this.installConsentOpen = false;
    // An open "Remove files" confirmation and its refusal are answers to this visit only.
    this.removalConfirm = null;
    this.removalError = null;
    this.closeFeatureInfo();
  }

  /** 574 §22.G — drive the modal contract from the declarative `installConsentOpen` state. */
  protected override updated(changed: Map<string, unknown>): void {
    super.updated(changed);
    if (!changed.has('installConsentOpen')) return;
    if (this.installConsentOpen) this.consentModal.open();
    else {
      this.consentModal.close();
      if (this.consentInclude.length > 0) {
        // Closed without accepting: nothing was saved, so the page shows the saved plan again.
        this.consentInclude = [];
        void this.refreshInstallPlanPreview();
      }
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.stopAllPolling();
    window.removeEventListener('focus', this.onWindowFocus);
    ++this.refreshRevision;
    ++this.planRevision;
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

  private async refreshSettings(afterAnswerSave = false): Promise<void> {
    if ((this.pendingActions.has('inference-switch') || this.pendingActions.has('reranker-switch') || this.pendingActions.has('answer-pause-switch') || this.pendingActions.has('picture-switch')) && !afterAnswerSave) return;
    const revision = ++this.refreshRevision;
    const choice = this.choiceRevision;
    const llmRevision = this.llmRevision;
    const attempt = this.pictureAttempt;
    if (attempt) {
      try {
        const result = await executeSettingsAttempt(
          (path, init) => authorizedFetch(this.base() + path, init), attempt);
        if (!this.isConnected || revision !== this.refreshRevision || choice !== this.choiceRevision) return;
        this.pictureApplied = JSON.parse(attempt.body).pictureReadingEnabled as boolean;
        this.pictureChangeOrder = markAiReadOrder();
        this.rerankerSettings = { ...this.rerankerSettings, witness: result.witness };
        this.runtimeError = null;
      } catch (error) {
        if (!this.isConnected || revision !== this.refreshRevision || choice !== this.choiceRevision) return;
        if (!(error instanceof SettingsAttemptError)
            || ['SETTINGS_COMPLETION_UNKNOWN', 'SETTINGS_RECEIPT_INVALID'].includes(error.code)) return;
        this.pictureApplied = null;
        this.runtimeError = error.message;
      }
      this.pictureAttempt = null;
      this.pictureUncertain = false;
    }
    const settings = await this.fetchJson<SettingsV2>('/api/settings/v2');
    if (!this.isConnected || revision !== this.refreshRevision || choice !== this.choiceRevision || !settings) return;
    this.settingsRead = true;
    if (this.rerankerSettings?.witness?.acceptedRevision !== settings.witness?.acceptedRevision
        || this.rerankerSettings?.witness?.lastCommittedOperationKey !== settings.witness?.lastCommittedOperationKey) {
      this.rerankerApplied = null;
      this.pictureApplied = null;
    }
    // Another window or a later read can reveal a change this one never received a receipt for.
    if (this.rerankerSettings?.witness && settings.pictureReadingEnabled !== this.rerankerSettings.pictureReadingEnabled) {
      this.pictureChangeOrder = markAiReadOrder();
    }
    this.rerankerSettings = settings;
    this.settings = { ...(settings.ui ?? {}), mode: getUiMode() } as UiSettings;
    // Drafts own their values even after focus moves to another surface. Revisions
    // also cover edits and witnessed saves completed while this read was pending.
    if (llmRevision === this.llmRevision && this.llmPending === 0 && this.llmDrafts.size === 0) this.llm = (settings.llm ?? {}) as LlmSettings;
  }

  /** Only a runtime read started after the last picture change speaks for it; unknown order never does. */
  private runtimeReadFollowsPictureChange(): boolean {
    return (aiRuntimeReadStart(this.runtimeStatus) ?? 0) > this.pictureChangeOrder;
  }

  private async refreshAll(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    this.refreshing = true;
    try {
      await Promise.all([
        this.refreshSettings(), this.refreshInstallPlanPreview(), this.refreshDownloadedFiles(),
        this.fetchJson<EffectivePolicy>('/api/policy/effective').then(policy => {
          if (policy) this.policy = policy;
        }),
      ]);
    } finally { this.refreshing = false; }
  }

  private toggleSection(key: string): void {
    this.expanded = { ...this.expanded, [key]: !this.expanded[key] };
  }

  /** One observation gate for the page: retained or incomplete observations are not live facts. */
  private aiKnown(): boolean {
    return this._unifiedAiState?.snapshotLive === true
      && this._unifiedAiState.installObservationLive === true && this._unifiedAiState.runtimeObservationLive === true
      && this.installStatus != null
      && this._unifiedAiState?.aiEngine != null && this._unifiedAiState.aiEngine.kind !== 'connecting'
      && !(this._unifiedAiState.aiEngine.stability.kind === 'provisional'
        && ['checking', 'stale-poll'].includes(this._unifiedAiState.aiEngine.stability.cause));
  }

  private installKnown(): boolean {
    return this._unifiedAiState?.installObservationLive === true;
  }

  private hasInstalledAi(): boolean {
    return this.installStatus?.installedFully === true || this.installStatus?.repairNeeded === true
      || ['online', 'background', 'indexing', 'starting', 'offline'].includes(this._unifiedAiState?.aiEngine?.kind ?? '')
      || (this._unifiedAiState?.runtime?.installed?.known === true && this._unifiedAiState.runtime.installed.value);
  }

  private packagePresent(id: string): boolean {
    const files = this.modelPresence?.packages?.find(p => p.id === id);
    const onDisk = files ? files.complete || (id === 'chat' && (files.presentVariantFiles?.length ?? 0) > 0) : undefined;
    // Once no download runs, what is on disk now decides: a finished run's package rows can still
    // say "installed" for files removed since, and trusting them would switch a removed part on
    // without asking first (design D4).
    if (onDisk !== undefined && this.installStatus?.state !== 'running') return onDisk;
    const progress = this.installPlanPending() ? undefined : this.installStatus?.packages?.find(p => p.packageId === id);
    if (progress?.state === 'installed') return true;
    if (onDisk !== undefined) return onDisk;
    return this.planPreview?.components?.some(c => c.id === id && c.state === 'installed') === true;
  }

  /** The installer replaces the previous run's package records only after planning. */
  private installPlanPending(): boolean {
    return this.installStatus?.state === 'running'
      && (this.installStatus.phase === 'preflight' || this.installStatus.phase === 'plan');
  }

  private brainFeatures(): Record<BrainFeatureId, BrainFeature> {
    const live = this.aiKnown();
    const installed = this.hasInstalledAi();
    const features = {} as Record<BrainFeatureId, BrainFeature>;
    for (const [id, packageId, label] of [
      ['meaning', 'embedding', 'Understand meaning'],
      ['reranker', 'reranker', 'Best match first'],
      ['chat', 'chat', 'Answer questions'],
    ] as const) {
      const component = this.planPreview?.components?.find(c => c.id === packageId);
      const onnx = this.runtimeStatus?.onnxFeatures?.find(f => f.id === (id === 'meaning' ? 'embed' : id));
      const packageProgress = this.installPlanPending() ? undefined : this.installStatus?.packages?.find(p => p.packageId === packageId);
      const present = this.packagePresent(packageId) || (id === 'chat'
        ? this._unifiedAiState?.capabilities?.chat === true || this._unifiedAiState?.runtime?.mode === 'online'
        : onnx?.modelActive === true);
      const unavailable = !present && (component?.state === 'unavailable' || component?.state === 'not-in-mode');
      const selected = installed && id === 'reranker'
        ? this.rerankerApplied ?? (onnx?.status === 'active' && onnx.modelActive === true
          ? true : this.rerankerSettings?.rerankerEnabled ?? onnx?.status === 'active')
        : installed && id === 'chat' && (present || (component != null && component.declined !== true && component.state !== 'declined'))
        ? this.settings.chatEnabled ?? (this.systemStatus?.inference?.chatEnabledSpec
          ?? this._unifiedAiState?.status?.inference?.chatEnabledSpec
          ?? this._unifiedAiState?.capabilities?.chat === true)
        : !unavailable && component != null && component.declined !== true && component.state !== 'declined';
      const active = !live ? null : id === 'reranker' && this.rerankerApplied !== null ? this.rerankerApplied : id === 'chat' ? this._unifiedAiState?.capabilities?.chat ?? null
        : onnx?.status == null || onnx.status === 'unknown' ? null : onnx.status === 'active' && onnx.modelActive === true;
      const percent = packageProgress?.bytesTotal && packageProgress.bytesTotal > 0
        ? Math.floor(((packageProgress.bytesDownloaded ?? 0) / packageProgress.bytesTotal) * 100) : null;
      const stopped = this.installStatus?.state === 'cancelled';
      const running = this.installKnown() && this.installStatus?.state === 'running';
      const state = id === 'reranker' && this.busy['reranker-switch'] ? 'Changing…'
        : id === 'reranker' && this.rerankerUncertain ? 'Unknown — checking Best match first after an interrupted change'
        : running && packageProgress?.state === 'pending' ? 'Waiting to download'
        : running && packageProgress?.state === 'downloading' ? `Downloading${percent === null ? '' : ` ${percent}%`}`
        : !live ? 'Unknown — checking AI status'
        : stopped && packageProgress != null && !present && (packageProgress.bytesDownloaded ?? 0) > 0 ? `Cancelled · ${formatBytes(packageProgress.bytesDownloaded ?? 0)} kept`
        : unavailable ? `Unavailable on this computer: ${component!.unavailableReason || 'Not available in this install mode.'}`
        : !component && !present ? 'Unknown — package status unavailable'
        : !present ? 'Not downloaded'
        : active === null ? 'Unknown — last observed files are downloaded'
        : id === 'chat' && installed && this.settingsRead && !selected ? 'Off — background work can continue'
        : id === 'chat' && selected && active === false ? this.installStatus?.repairNeeded ? 'Unavailable — repair needed; answers are enabled' : 'Unavailable — answers are enabled'
        : id === 'reranker' && installed && active === true && this.rerankerSettings?.rerankerEnabled === false && this.rerankerApplied === null ? 'On — the saved Off choice is not applied'
        : id === 'reranker' && installed && active === false ? selected ? 'Unavailable — Best match first is enabled' : 'Off — files kept'
        : active ? 'On' : 'Off';
      const runtimeControl = installed && (id === 'reranker' || id === 'chat' && (present || (component != null && component.declined !== true && component.state !== 'declined')));
      const acquisitionControl = !runtimeControl && component?.declinable === true && (!installed || !present);
      const blockedReason = !live ? 'Checking AI status before changing features.' : unavailable ? component?.unavailableReason || state
        : (runtimeControl && (!this.settingsRead || id === 'reranker' && !this.rerankerSettings?.witness) ? id === 'reranker' ? 'Saved Best match first choice is not known. Refresh settings before changing it.' : 'Saved answer choice is not known. Refresh settings before changing answers.' : null)
        ?? this.planLockReason()
        ?? (this.busy['inference-switch'] ? 'Saving the answer choice' : null)
        ?? (installed && !component && !present ? 'Package status is unavailable. Refresh to check the files.' : null)
        ?? (id === 'chat' && installed && !selected && this.policy?.onlineAiEnabled === false ? 'Online AI is disabled by administrator policy.' : null)
        ?? (acquisitionControl && !present && this.policy?.downloadsEnabled === false ? 'Downloads are disabled by administrator policy.' : null);
      features[id] = {
        id, label, selected, installed: present, active, availability: unavailable ? 'unavailable' : component ? 'available' : 'unknown',
        state: runtimeControl && id === 'chat' ? !this.settingsRead ? `${state} · Saved answer choice is not known` : this.settings.chatEnabled == null ? `${state} · automatic (no saved answer choice)` : state : state,
        size: component?.totalBytes && component.totalBytes > 0 ? formatBytes(component.totalBytes) : null,
        switchable: !unavailable && (acquisitionControl || runtimeControl || (!live && !installed && id !== 'meaning')), blockedReason,
      };
    }
    const mmproj = live && this._unifiedAiState?.runtime?.mode === 'online'
      ? this.runtimeStatus?.active?.mmprojActive ?? null : null;
    const pictureOverride = this.rerankerSettings?.pictureReadingOverrideEnabled;
    const pictureChoice = pictureOverride != null ? mmproj ?? pictureOverride
      : this.pictureApplied ?? this.rerankerSettings?.pictureReadingEnabled
        ?? (mmproj ?? this.packagePresent('chat'));
    const pictureFiles = this.modelPresence?.packages?.find(p => p.id === 'chat')?.complete
      ?? this.planPreview?.components?.find(c => c.id === 'chat')?.state === 'installed';
    const pictureMismatch = mmproj === true && pictureChoice === false && this.pictureApplied === null;
    const pictureModelCannotRead = mmproj === false && this.pictureApplied === null && this.runtimeReadFollowsPictureChange();
    features.pictures = {
      id: 'pictures', label: 'Text in scans and images', selected: this.settingsRead && !!this.rerankerSettings?.witness && (pictureMismatch || pictureChoice),
      installed: features.chat.installed, active: live ? this.pictureApplied ?? mmproj : null,
      availability: features.chat.availability,
      state: !live ? 'Unknown — checking AI status' : !installed ? features.chat.availability === 'unavailable'
        ? 'Text in scans and images is unavailable on this computer'
        : 'Text in scans and images comes with the answer model’s files; it is not a separate download'
        : this.pendingActions.has('picture-switch') ? this.pictureTargetEnabled ? 'Starting picture reading…' : 'Stopping picture reading…'
        : this.pictureUncertain ? 'Unknown — checking picture reading after an interrupted change'
        : !this.settingsRead || !this.rerankerSettings?.witness ? PICTURE_CHOICE_UNKNOWN
        : pictureOverride === false ? 'Off — operator controlled'
        : pictureOverride === true ? mmproj === true ? 'On — operator controlled'
          : mmproj === false ? 'Unavailable — operator picture reader is not active' : 'Enabled by an operator override'
        : pictureMismatch ? 'On — the saved Off choice is not applied'
        : !pictureChoice ? 'Off - files and read text kept'
        : !pictureFiles ? 'Unavailable — picture reading files are missing'
        : pictureModelCannotRead ? PICTURES_NOT_AVAILABLE_WITH_MODEL
        : 'On',
      size: null, switchable: installed,
      blockedReason: !live ? 'Checking AI status before changing features.'
        : this.pictureUncertain ? 'Checking the interrupted picture reading change.'
        : pictureOverride != null ? 'Text in scans and images is controlled by an operator override; change that override first'
        : features.chat.availability === 'unavailable' ? 'Text in scans and images is unavailable on this computer'
        : !this.settingsRead || !this.rerankerSettings?.witness ? PICTURE_CHOICE_UNKNOWN
        : this.planLockReason(),
    };
    return features;
  }

  private renderBrainSentence(verdict: AiEngineVerdict, installed: boolean, features: Record<BrainFeatureId, BrainFeature>, readingPictures: boolean | null): TemplateResult {
    const parts = composeBrainSentence(verdict, installed, features, readingPictures, this.aiKnown());
    const picturePart = parts.find(part => part.feature === 'pictures');
    if (picturePart && features.pictures.state === PICTURE_CHOICE_UNKNOWN) {
      picturePart.text = `. ${PICTURE_CHOICE_UNKNOWN.slice(0, -1)}`;
    } else if (picturePart && installed && this.rerankerSettings?.pictureReadingEnabled === false
        && features.pictures.state === 'Off - files and read text kept') {
      picturePart.text = ". It doesn't read text in scans and images";
    }
    for (let i = 1; i < parts.length; ++i) {
      const punctuation = parts[i]!.text.match(/^[.,]/)?.[0];
      if (punctuation) {
        parts[i - 1]!.text += punctuation;
        parts[i]!.text = parts[i]!.text.slice(1);
      }
    }
    return html`${parts.map(part => {
      if (!part.feature) return part.text;
      const text = part.text.trimStart();
      return html`${part.text.slice(0, part.text.length - text.length)}<button type="button" class="feature-phrase" aria-expanded=${this.featureInfo === part.feature} aria-controls=${`brain-feature-panel-${part.feature}`} @click=${(event: Event) => this.openFeatureInfo(part.feature!, event)}>${text}</button>${this.renderFeatureSwitch(features[part.feature], 'sentence')}`;
    })}`;
  }

  private renderRepairAlert(): TemplateResult | typeof nothing {
    const remedy = deriveRepairRemedy(this.installStatus);
    if (remedy.kind === 'none') return nothing;
    if (remedy.kind === 'manual') return html`<div role="alert" data-testid="install-manual-fallback">
      Automatic repair could not download these files. Download each one and save it to the path shown, then restart AI.
      ${remedy.packages.map(p => html`<div><strong>${p.label}</strong> — ${p.attempts} attempts${p.error ? ` · ${p.error}` : ''}<div><code>${p.url}</code></div><div>→ <code>${p.targetPath}</code></div></div>`)}
    </div>`;
    return html`<div role="alert" data-testid=${shouldHintRepairForGpuFallback(this.runtimeStatus, this.installStatus) ? 'brain-gpu-fallback-hint' : 'install-repair-needed'}>
      ${shouldHintRepairForGpuFallback(this.runtimeStatus, this.installStatus) ? html`<span>Search features are running on CPU: a GPU component is missing from disk. </span>` : nothing}
      ${repairRemedySub(remedy)}
      <jf-button class="repair-action" variant="primary" label="Repair" .availability=${this.installStatus?.state === 'running' ? unavailableBecause('Already installing.') : this.policy?.downloadsEnabled === false ? unavailableBecause('Downloads are disabled by administrator policy.') : this.busy['install-repair'] ? unavailableBecause('Repair in progress.') : this.runtimeAvailability()}
        .onActivate=${() => void this.repairInstall()}>${icon({ name: 'refresh-cw', size: 14 })} Repair</jf-button>
    </div>`;
  }

  private renderFeatureSwitch(feature: BrainFeature, location: 'sentence' | 'path'): TemplateResult | typeof nothing {
    if (!feature.switchable) return nothing;
    const id = `brain-${location}-${feature.id}`;
    return html`<span class="feature-switch-wrap"><button type="button" id=${id} class="feature-switch" role="switch"
        aria-label=${`Use ${feature.label}`} aria-checked=${feature.selected ? 'true' : 'false'}
        aria-disabled=${feature.blockedReason ? 'true' : 'false'} aria-describedby=${feature.blockedReason ? `${id}-reason` : nothing}
        @click=${() => { if (!feature.blockedReason) void this.changeFeature(feature); }}
      ><span></span></button>${location === 'sentence' && feature.id === 'reranker' ? nothing : html`<button type="button" class="feature-phrase" aria-label=${`About ${feature.label}`}
        aria-expanded=${this.featureInfo === feature.id} aria-controls=${`brain-feature-panel-${feature.id}`}
        @click=${(event: Event) => this.openFeatureInfo(feature.id, event)}>${icon({ name: 'help-circle', size: 12 })}</button>`}${feature.blockedReason ? html`<span id=${`${id}-reason`} class="switch-reason">${feature.blockedReason}</span>` : nothing}</span>`;
  }

  private async changeFeature(feature: BrainFeature): Promise<void> {
    if (feature.blockedReason || this.planLockReason()) return;
    if (feature.id === 'pictures' && this.hasInstalledAi()) {
      const complete = this.modelPresence?.packages?.find(p => p.id === 'chat')?.complete
        ?? this.planPreview?.components?.find(c => c.id === 'chat')?.state === 'installed';
      if (!feature.selected && !complete) {
        // The saved decline is withdrawn only by the accepted install (design D4): a cancelled
        // consent leaves it, so Repair cannot restore files the user removed.
        await this.startInstall('pictures', this.declinedIds(['chat']));
      } else await this.setPictureReadingEnabled(!feature.selected);
      return;
    }
    if (feature.id === 'reranker' && this.hasInstalledAi()) {
      if (!feature.selected && !feature.installed) {
        await this.startInstall('reranker', this.declinedIds(['reranker']));
      } else await this.setRerankerEnabled(!feature.selected);
      return;
    }
    if (feature.id === 'chat' && this.hasInstalledAi() && (feature.installed || this.planPreview?.components?.some(c => c.id === 'chat' && c.declined !== true && c.state !== 'declined'))) {
      await this.setChatEnabled(!feature.selected);
      return;
    }
    await this.changeAcquisition(feature.id, feature.selected, feature.installed);
  }

  /**
   * Start AI / Resume Chat AI / Start Chat AI turn answers on the way the answers switch does: when
   * the answer model's files were removed (declined), they open the download consent and save
   * nothing until the accepted download starts (design D4); otherwise they switch answers on.
   */
  private async turnAnswersOn(): Promise<void> {
    const chat = this.brainFeatures().chat;
    if (this.hasInstalledAi() && !chat.installed && !chat.selected) await this.changeFeature(chat);
    else await this.setChatEnabled(true);
  }

  private async changeAcquisition(id: string, selected: boolean, installed: boolean): Promise<void> {
    if (this.hasInstalledAi() && !installed && !selected) {
      // Switching on a missing part after install asks first; its decline is withdrawn only when
      // the consented install starts (design D4).
      await this.startInstall(id, this.declinedIds([id]));
      return;
    }
    await this.setComponentDeclined(id, selected);
  }

  /** The subset of {@code ids} the user has declined, per the current plan preview. */
  private declinedIds(ids: string[]): string[] {
    return ids.filter(id => {
      const component = this.planPreview?.components?.find(c => c.id === id);
      return component?.declined === true || component?.state === 'declined';
    });
  }

  private openFeatureInfo(id: BrainFeatureId, event: Event): void {
    this.featureOpener = event.currentTarget as HTMLElement;
    this.featureInfo = id;
    this.featureTransient.open();
    void this.updateComplete.then(() => this.shadowRoot?.querySelector<HTMLElement>('.feature-panel')?.focus({ preventScroll: true }));
  }

  private closeFeatureInfo(): void {
    if (this.featureInfo === null) return;
    const id = this.featureInfo;
    this.featureInfo = null;
    this.featureTransient.close();
    const target = this.featureOpener?.isConnected ? this.featureOpener
      : this.shadowRoot?.querySelector<HTMLElement>(`[data-feature="${id}"] button`)
      ?? this.shadowRoot?.querySelector<HTMLElement>('.brain-headline');
    target?.focus({ preventScroll: true });
    this.featureOpener = null;
  }

  private renderFeaturePanel(feature: BrainFeature): TemplateResult | typeof nothing {
    if (this.featureInfo !== feature.id) return nothing;
    const benefits: Record<BrainFeatureId, string> = {
      meaning: 'Find files by what they mean, even when they use different words.',
      reranker: 'Reorders search results to put the best match first.',
      chat: 'Answers written from your files, with sources you can check.',
      pictures: 'Finds text in scans and pictures. Off keeps files and text already read; On resumes waiting files.',
    };
    return html`<div id=${`brain-feature-panel-${feature.id}`} class="feature-panel" tabindex="-1" role="region" aria-label=${`About ${feature.label}`}>
      <strong>${feature.label}</strong>
      <div class="feature-tradeoffs"><div><h3>You get</h3><p>+ ${benefits[feature.id]}</p></div>
        <div><h3>It costs</h3><p>${feature.size ? `${feature.size} registry file footprint. ` : ''}${feature.id === 'pictures' ? 'Included in the answer model’s files. ' : ''}Uses processing and graphics memory while working. Other heavy apps may slow down.</p></div></div>
      <button type="button" @click=${() => this.closeFeatureInfo()}>${icon({ name: 'x', size: 14 })} Close</button>
    </div>`;
  }

  private renderFeaturePath(): TemplateResult {
    const features = this.brainFeatures();
    const count = this._unifiedAiState?.index?.documentCount;
    const renderStep = (feature: BrainFeature, glyph: 'zap' | 'list' | 'message-square' | 'image') => html`
      <div class="path-step" data-feature=${feature.id}>
        <button type="button" class="step-info" @click=${(event: Event) => this.openFeatureInfo(feature.id, event)} aria-expanded=${this.featureInfo === feature.id} aria-controls=${`brain-feature-panel-${feature.id}`}>
          <span class="step-symbol" data-active=${feature.active === true}>${icon({ name: glyph, size: 20 })}</span>
          ${feature.label}
        </button>
        <div class="step-meta">${feature.id === 'chat' && feature.installed ? this._unifiedAiState?.runtime?.modelLabel ?? this.runtimeStatus?.active?.chatProfile ?? '' : ''}${feature.size ? ` · ${feature.size} registry footprint` : ''}${feature.id === 'meaning' ? ' · needed' : ''}</div>
        <div class="step-meta">${feature.state}</div>
        ${this.renderFeatureSwitch(feature, 'path')}
        ${this.renderFeaturePanel(feature)}
      </div>`;
    return html`<section class="brain-path" aria-label="How AI uses your files">
      <div class="path-step files-step"><span class="step-symbol">${icon({ name: 'copy', size: 20 })}</span><span>Your files</span><span class="step-meta">${!this.aiKnown() ? 'Unknown — checking AI status' : count?.known ? `${NUM.format(count.value)} files` : 'File count unknown'}</span></div>
      <div class="path-step read-step"><span class="step-symbol">${icon({ name: 'file-text', size: 20 })}</span><span>Read the text</span><span class="step-meta">${this.aiKnown() ? 'Built in' : 'Unknown — checking AI status'}</span></div>
      <div class="picture-branch">${renderStep(features.pictures, 'image')}</div>
      ${renderStep(features.meaning, 'zap')}
      ${renderStep(features.reranker, 'list')}
      ${renderStep(features.chat, 'message-square')}
    </section>`;
  }

  private renderGraphicsCard(): TemplateResult {
    const gpu = this.systemStatus?.gpu;
    const adapter = this.systemStatus?.operating?.gpu?.adapter;
    const fresh = this.aiKnown();
    const total = fresh ? gpu?.totalVramBytes : null;
    const used = fresh ? gpu?.usedVramBytes : null;
    const known = total != null && total > 0 && used != null && used >= 0 && used <= total;
    const devices = this.systemStatus?.gpu?.deviceCount;
    return html`<section class="graphics-card" aria-label="Graphics card memory">
      <span>Graphics card</span><div class="progress" role=${known ? 'meter' : 'img'} aria-label="Graphics memory used by all apps"
        aria-valuemin=${known ? 0 : nothing} aria-valuemax=${known ? total : nothing} aria-valuenow=${used ?? nothing} aria-valuetext=${known ? `${formatBytes(used!)} of ${formatBytes(total!)} in use by all apps${devices && devices > 1 ? ` · one of ${devices} graphics devices` : ''}` : nothing}>
        ${known ? html`<div class="progress-bar" style=${`width: ${(used! / total!) * 100}%`}></div>` : nothing}
      </div><span class="step-meta">${known ? `${formatBytes(used!)} of ${formatBytes(total!)} in use · all apps` : 'Memory use unknown'}${adapter ? ` · ${adapter}` : ''}${devices && devices > 1 ? ` · one of ${devices} graphics devices` : ''}</span>
    </section>`;
  }

  private packageDownloadSize(id: string): string {
    const bytes = this.planPreview?.components?.find(c => c.id === id)?.downloadBytes;
    return bytes != null && bytes >= 0 ? formatBytes(bytes) : 'size unknown';
  }

  private consentReady(): boolean {
    const content = composeInstallConsent(this.manifest, this.planPreview);
    return !this.planError && !this.planLoading && !content.termsUnavailable && this.planPreview?.totalDownloadBytes != null
      && this.planPreview.totalDownloadBytes >= 0
      && content.packages.every(p => {
        const bytes = this.planPreview?.components?.find(c => c.id === p.id)?.downloadBytes;
        return bytes != null && bytes >= 0;
      });
  }

  /**
   * Tempdoc 575 §17 Face C: is the install "running" but its backend owner gone quiet? Derived from
   * the ONE polled-state liveness authority ({@link isAiInstallLive}) — never inline — so the badge
   * cannot be re-implemented per site (the inflight-liveness gate registers this render site).
   */
  private installStalled(): boolean {
    return (
      this.installKnown() && this.installStatus?.state === 'running' &&
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

  // Inference status flows from aiStateStore; Health owns developer telemetry.
  // This page reconciles settings and acquisition preferences across windows.
  private startSettingsPolling(): void {
    if (this.pollSettings !== null) return;
    const timer = window.setTimeout(async () => {
      // Passive reads never invalidate an unfinished batch or take the choice lock.
      // Start the next delay only after both reads finish, so slow windows converge.
      if (!this.planReads && !this.planLockReason() && !this.busy['inference-switch']) {
        await Promise.all([this.refreshSettings(), this.refreshInstallPlanPreview(),
          this.refreshDownloadedFiles()]);
      }
      if (this.pollSettings === timer && this.isConnected) {
        this.pollSettings = null;
        this.startSettingsPolling();
      }
    }, 5000);
    this.pollSettings = timer;
  }

  private stopAllPolling(): void {
    if (this.pollSettings !== null) window.clearTimeout(this.pollSettings);
    this.pollSettings = null;
    if (this.removalPoll !== null) window.clearTimeout(this.removalPoll);
    this.removalPoll = null;
  }

  // ---------- Downloaded files: Remove files and Download again (design D4) ----------

  /** A removal runs here or in another window: every page route that adds or uses files waits. */
  private removalRunning(): boolean {
    return this.pendingActions.has('remove-files') || this.downloadedFiles?.removal?.state === 'running';
  }

  private async refreshDownloadedFiles(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    const view = await this.fetchJson<DownloadedFilesView>('/api/ai/install/downloaded-files');
    if (!this.isConnected) return;
    if (view) this.downloadedFiles = view;
    if (view?.removal?.state === 'running') this.scheduleRemovalPoll();
  }

  /** Follows a running removal (this window's or another's) until it reports what is left. */
  private scheduleRemovalPoll(): void {
    if (this.removalPoll !== null) return;
    this.removalPoll = window.setTimeout(async () => {
      this.removalPoll = null;
      await this.refreshDownloadedFiles();
      if (this.downloadedFiles?.removal?.state !== 'running') {
        await Promise.all([this.refreshInstallPlanPreview(), this.refreshSettings()]);
        void refreshAiInstallNow();
      }
    }, 1000);
  }

  private openRemoval(id: string): void {
    this.removalError = null;
    this.removalConfirm = id;
    void this.updateComplete.then(() => this.shadowRoot
      ?.querySelector<HTMLElement>(`#remove-files-title-${id}`)?.focus({ preventScroll: true }));
  }

  private closeRemoval(id: string): void {
    this.removalConfirm = null;
    void this.updateComplete.then(() => this.focusPartAction(id));
  }

  /** Focus returns to the row's own action, or to the outcome when the row changed. */
  private focusPartAction(id: string): void {
    const target = this.shadowRoot?.querySelector<HTMLElement>(`[data-remove="${id}"]`)
      ?? this.shadowRoot?.querySelector<HTMLElement>(`[data-download-again="${id}"]`)
      ?? this.shadowRoot?.querySelector<HTMLElement>('[data-testid="removal-outcome"]');
    target?.focus({ preventScroll: true });
  }

  private async confirmRemoval(part: DownloadedPart): Promise<void> {
    const id = part.id ?? '';
    if (this.removalRunning() || !part.previewDigest) return;
    this.removalConfirm = null;
    await this.withBusy('remove-files', async () => {
      this.removalError = null;
      try {
        await this.invokeOp('core.remove-ai-files', { packageId: id, previewDigest: part.previewDigest });
      } catch (error) {
        this.removalError = { id, message: error instanceof Error ? error.message : String(error) };
      }
      await this.refreshDownloadedFiles();
    });
    // The removal saved settings, so the next switch needs the witness it left behind.
    await Promise.all([this.refreshInstallPlanPreview(), this.refreshSettings()]);
    void refreshAiInstallNow();
    await this.updateComplete;
    this.focusPartAction(id);
  }

  /** Asks first, with the download size and licence; only the accepted install withdraws the decline. */
  private async downloadAgain(id: string): Promise<void> {
    if (this.planLockReason()) return;
    await this.startInstall(null, [id]);
  }

  private renderDownloadedFiles(): TemplateResult {
    const view = this.downloadedFiles;
    if (!view) return html`<p class="advanced-note">Checking the files on this computer…</p>`;
    const running = this.removalRunning();
    const rows = (view.parts ?? []).filter(p => p.state === 'downloaded' || p.state === 'incomplete'
      || (REMOVABLE_PARTS.has(p.id ?? '') && p.declined === true));
    const removal = view.removal;
    return html`<div class="downloaded-files" data-testid="downloaded-files">
      ${rows.length === 0 ? html`<p class="advanced-note">No downloaded model files.</p>`
        : rows.map(part => this.renderDownloadedPart(part, running))}
      ${running ? html`<p role="status">Removing files…</p>` : nothing}
      ${removal && removal.state !== 'running' ? this.renderRemovalOutcome(removal) : nothing}
    </div>`;
  }

  private renderDownloadedPart(part: DownloadedPart, running: boolean): TemplateResult {
    const id = part.id ?? '';
    const downloaded = part.state === 'downloaded';
    // Some files are left, but not every file the part needs: it is not usable (design D4).
    const incomplete = part.state === 'incomplete';
    const onDisk = downloaded || incomplete;
    const error = this.removalError?.id === id ? this.removalError.message : null;
    return html`<div class="advanced-row" data-part=${id}>
        <div>${part.label ?? id}<p>${downloaded ? `${measuredSize(part.sizeOnDiskBytes)} on disk`
          : incomplete ? `Incomplete, not usable · ${measuredSize(part.sizeOnDiskBytes)} on disk` : 'Not downloaded'}</p></div>
        <div>
          ${onDisk && part.removable ? html`<button type="button" data-remove=${id}
            aria-expanded=${this.removalConfirm === id ? 'true' : 'false'} ?disabled=${running}
            @click=${() => this.openRemoval(id)}>${icon({ name: 'trash-2', size: 14 })} Remove files</button>` : nothing}
          ${onDisk && !part.removable && part.reason ? html`<span class="removal-reason">${part.reason}</span>` : nothing}
          ${!downloaded && REMOVABLE_PARTS.has(id) && part.declined === true ? html`<button type="button"
            data-download-again=${id} ?disabled=${running} @click=${() => void this.downloadAgain(id)}>
            ${icon({ name: 'download', size: 14 })} Download again</button>` : nothing}
          ${error ? html`<p role="alert">${error}</p>` : nothing}
        </div>
      </div>
      ${this.removalConfirm === id ? this.renderRemovalConfirm(part) : nothing}`;
  }

  private renderRemovalConfirm(part: DownloadedPart): TemplateResult {
    const id = part.id ?? '';
    const deleted = part.delete ?? [];
    const kept = part.kept ?? [];
    const known = deleted.every(f => f.sizeBytes != null);
    const total = known ? formatBytes(deleted.reduce((sum, f) => sum + (f.sizeBytes ?? 0), 0)) : 'size unknown';
    return html`<div class="removal-confirm" role="group" aria-labelledby=${`remove-files-title-${id}`}
        data-testid="removal-confirm">
      <strong id=${`remove-files-title-${id}`} tabindex="-1">Remove ${part.label ?? id} files?</strong>
      <p>This deletes ${deleted.length} ${deleted.length === 1 ? 'file' : 'files'} (${total}) from this computer.
        ${part.unavailableAfter ? `${part.unavailableAfter} won't work until you download it again. ` : ''}Each
        file is checked first; a file JustSearch can't confirm it downloaded is kept. Your documents, the
        index and conversations are not touched.</p>
      <p>Deleted:</p>
      <ul class="removal-files">${deleted.map(f => html`<li><code>${f.path}</code> · ${measuredSize(f.sizeBytes)}</li>`)}</ul>
      ${kept.length > 0 ? html`<p>Kept:</p>
        <ul class="removal-files">${kept.map(f => html`<li><code>${f.path}</code> · ${measuredSize(f.sizeBytes)} · ${f.reason}</li>`)}</ul>` : nothing}
      <div class="row">
        <button type="button" class="confirm-remove" @click=${() => void this.confirmRemoval(part)}>Remove files</button>
        <button type="button" @click=${() => this.closeRemoval(id)}>Cancel</button>
      </div>
    </div>`;
  }

  private renderRemovalOutcome(removal: FileRemoval): TemplateResult {
    const headline = removal.state === 'removed' ? 'Files removed'
      : removal.state === 'interrupted' ? 'The removal was interrupted'
        : removal.state === 'refused' || removal.state === 'not-removed' ? 'Nothing was removed'
          : 'Some files were not removed';
    const deleted = removal.deleted ?? [];
    const kept = removal.kept ?? [];
    return html`<div role=${removal.state === 'removed' ? 'status' : 'alert'} tabindex="-1"
        data-testid="removal-outcome">
      <p>${removal.label ?? removal.packageId}: ${headline}. ${removal.message ?? ''}</p>
      ${deleted.length > 0 ? html`<p>Deleted:</p>
        <ul class="removal-files" data-testid="removal-deleted">${deleted.map(f => html`<li><code>${f.path}</code> · ${measuredSize(f.sizeBytes)}</li>`)}</ul>` : nothing}
      ${kept.length > 0 ? html`<p>Kept:</p>
        <ul class="removal-files" data-testid="removal-kept">${kept.map(f => html`<li><code>${f.path}</code> · ${f.reason ?? ''}</li>`)}</ul>` : nothing}
    </div>`;
  }

  private async withBusy<T>(key: string, fn: () => Promise<T>): Promise<T | null> {
    if (this.pendingActions.has(key)) return null;
    this.pendingActions.add(key);
    this.busy = { ...this.busy, [key]: true };
    try {
      return await fn();
    } catch (err) {
      const msg = err instanceof OperationError
        ? `${err.message}${err.errorCode && !err.message.includes(err.errorCode) ? ` (Code: ${err.errorCode})` : ''}`
        : err instanceof Error
          ? err.message
          : String(err);
      this.runtimeError = msg;
      return null;
    } finally {
      this.pendingActions.delete(key);
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

  private async refreshInference(): Promise<void> {
    await saveAbsoluteSettings(
      (path, init) => authorizedFetch(this.base() + path, init),
      {},
      { headers: { 'X-JustSearch-Refresh-Inference': 'true' }, timeoutMs: 180_000 },
    );
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
        title: opts.title,
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

  // ---------- Install actions ----------

  /**
   * Sandbox round 7 — the consent is rendered by this surface (see {@link renderInstallConsent})
   * rather than passed as a `message` string, because it carries the real package list, their SPDX
   * licences and CLICKABLE upstream terms links. `showConfirmDialog`'s message is `string`-typed all
   * the way up through the plugin-api host contract, so enriching it would change a capability
   * interface; `AdvisoryInboxDrawer`'s in-template anchors are the precedent followed here.
   */
  private planLockReason(): string | null {
    if (!this.aiKnown()) return 'Checking AI status before changing features.';
    if (this.removalRunning()) return REMOVING_FILES;
    if (this.installStatus?.state === 'running' || this.installStatus?.paused === true
      || this.installConsentOpen || this.pendingActions.has('install-start') || this.pendingActions.has('install-repair')) {
      return 'Changes apply after this download';
    }
    if ([...this.pendingActions].some(key => key.startsWith('component:'))) {
      return 'Saving the current download choices';
    }
    if (this.pendingActions.has('inference-switch')) return 'Saving the answer choice';
    if (this.pendingActions.has('reranker-switch')) return 'Changing…';
    if (this.pendingActions.has('picture-switch')) return 'Changing picture reading…';
    return null;
  }

  private async startInstall(feature: string | null = null, include: string[] = []): Promise<void> {
    // An unresolved preview can open a consent dialog, but cannot authorize a download.
    // Only mutations / an existing consent or download prevent opening it.
    if (this.installConsentOpen || this.installStatus?.state === 'running' || this.installStatus?.paused
      || this.pendingActions.size > 0 || this.removalRunning()) return;
    this.consentFeature = feature;
    this.consentInclude = include;
    // Lock before fetching: concurrent clicks cannot change the plan being consented to.
    this.installConsentOpen = true;
    await this.refreshInstallPlanPreview();
  }

  private async refreshInstallPlanPreview(): Promise<void> {
    if (!this.apiBase && this.apiBase !== '') return;
    ++this.planReads;
    try {
      const revision = ++this.planRevision;
      const choice = this.choiceRevision;
      this.planLoading = true;
      this.planError = null;
      const include = this.installConsentOpen ? this.consentInclude : [];
      const [preview, manifest, presence] = await Promise.all([
        this.fetchJson<InstallPlanPreview>(include.length > 0
          ? `/api/ai/install/plan-preview?include=${include.map(encodeURIComponent).join(',')}`
          : '/api/ai/install/plan-preview'),
        getAiInstallManifest(this.base()).catch(() => null),
        this.fetchJson<ModelPresence>('/api/ai/models/status'),
      ]);
      if (revision !== this.planRevision || choice !== this.choiceRevision) return;
      this.planPreview = preview;
      this.modelPresence = presence;
      this.manifest = manifest;
      this.planLoading = false;
      if (!preview || !manifest) this.planError = 'Download sizes and licences could not be loaded. Retry to review the download.';
    } finally { --this.planReads; }
  }

  private async setComponentDeclined(id: string, declined: boolean): Promise<void> {
    if (this.planLockReason()) return;
    ++this.choiceRevision;
    ++this.planRevision; // invalidate a preview requested before this choice
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
      if (data) void refreshAiInstallNow();
      else this.runtimeError = `Could not ${paused ? 'pause' : 'resume'} the download.`;
    });
  }

  private async confirmInstall(): Promise<void> {
    if (this.planLoading || !this.consentReady()) return;
    const feature = this.consentFeature;
    const include = this.consentInclude;
    // Download again accepts exactly the plan this consent showed; the Engine refuses a changed one.
    const planDigest = include.length > 0 ? this.planPreview?.planDigest ?? '' : '';
    this.consentInclude = [];
    this.installConsentOpen = false;
    await this.withBusy('install-start', async () => {
      this.runtimeError = null;
      const data = (await this.invokeOp('core.start-ai-install', { acceptTerms: true,
        ...(feature === 'reranker' ? { enableRerankerOnCompletion: true } : {}),
        ...(feature === 'pictures' ? { enablePictureReadingOnCompletion: true } : {}),
        // Answers are switched on by the Engine once the answer model is downloaded: switching on
        // now is refused while the model is missing, and nothing would retry it.
        ...(feature === 'chat' ? { enableChatOnCompletion: true } : {}),
        ...(include.length > 0 ? { includePackages: include, planDigest } : {}),
      })) as InstallStatus;
      if (data) void refreshAiInstallNow();
    });
  }

  /** Activate the accepted staged model candidate through the real HIGH-risk operation surface. */
  private async activateInstalledModels(): Promise<void> {
    await this.withBusy('install-activation', async () => {
      this.runtimeError = null;
      this.bulkError = null;
      this.bulkOutcomeKey = '';
      let data: unknown;
      try {
        data = await this.invokeOp('core.activate-installed-models', {
          source: 'installer_model_activation',
        });
      } catch (error) {
        if (!(error instanceof OperationError)) throw error;
        this.showBulkError(error);
        return;
      }
      if (data && typeof data === 'object' && 'operationKey' in data) {
        this.bulkOutcomeKey = String(data.operationKey ?? '');
      }
      await this.refreshAll();
    });
  }

  /**
   * Cancelling used to destroy every downloaded byte, so it (wrongly) needed no gate to be honest.
   * `DownloadExecutor.cancel()` now SUSPENDS the BITS job instead of removing it and `ResumableFetch`
   * keeps the `.partial` plus its identity sidecar, so the next install resumes via BITS or an HTTP
   * `Range` request (integrity-verified either way). The confirmation states that — pause, not discard.
   */
  private async cancelInstall(): Promise<void> {
    // DE2 — pausing keeps everything downloaded and the next install resumes, so it no longer asks
    // first; it says what happened instead (C1).
    await this.withBusy('install-cancel', async () => {
      const data = (await this.invokeOp('core.cancel-ai-install', {})) as InstallStatus & { alreadyCompleted?: boolean };
      if (data) {
        void refreshAiInstallNow();
        // The install finished first: nothing stopped, so the refreshed status is the whole outcome.
        if (data.alreadyCompleted !== true) {
          emitEphemeralToast({ message: 'Download paused. Everything downloaded so far is kept, and installing again resumes where it stopped.' });
        }
      }
    });
  }

  private async repairInstall(): Promise<void> {
    if (this.planLockReason()) return;
    await this.withBusy('install-repair', async () => {
      this.runtimeError = null;
      await this.refreshInstallPlanPreview();
      const content = composeInstallConsent(this.manifest, this.planPreview);
      const details = content.packages.map(p => `${p.label}: ${this.packageDownloadSize(p.id)}; ${p.license ?? 'licence not declared'}`).join('\n');
      const ok = await this.hostConfirm({
        title: 'Repair AI installation?',
        message: `This re-runs the install pipeline, re-downloading any missing or corrupt model files. Existing valid files are preserved.\nDownload: ${content.downloadTotal ?? 'size unknown'}.\n${details || 'Package sizes and licences are unknown.'}`,
        // DE2 — repairing downloads and accepts the listed terms; it destroys nothing, so not a danger dialog.
        variant: 'info',
        confirmLabel: 'Repair',
      });
      if (!ok) return;
      const data = (await this.invokeOp('core.repair-ai-install', { acceptTerms: true })) as InstallStatus;
      if (data) void refreshAiInstallNow();
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
      ++this.choiceRevision;
      await this.invokeOp('core.set-chat-enabled', { enabled });
      await this.refreshSettings(true);
    });
  }

  private async setPauseAnswersWhileIndexing(enabled: boolean): Promise<void> {
    await this.withBusy('answer-pause-switch', async () => {
      this.runtimeError = null;
      ++this.choiceRevision;
      const attempt = createSettingsAttempt({ pauseAnswersWhileIndexing: enabled },
        requireSettingsWitness(this.rerankerSettings?.witness));
      await executeSettingsAttempt(
        (path, init) => authorizedFetch(this.base() + path, init), attempt);
      await this.refreshSettings(true);
    });
  }

  private async setPictureReadingEnabled(enabled: boolean): Promise<void> {
    await this.withBusy('picture-switch', async () => {
      this.pictureTargetEnabled = enabled;
      this.runtimeError = null;
      ++this.choiceRevision;
      const attempt = createSettingsAttempt({ pictureReadingEnabled: enabled, pictureReadingSwitchIntent: true },
        requireSettingsWitness(this.rerankerSettings?.witness));
      try {
        const result = await executeSettingsAttempt(
          (path, init) => authorizedFetch(this.base() + path, init), attempt, { timeoutMs: 240_000 });
        this.pictureApplied = enabled;
        this.pictureChangeOrder = markAiReadOrder();
        this.rerankerSettings = { ...this.rerankerSettings, pictureReadingEnabled: enabled, witness: result.witness };
        this.pictureUncertain = false;
      } catch (error) {
        if (error instanceof SettingsAttemptError
            && ['SETTINGS_COMPLETION_UNKNOWN', 'SETTINGS_RECEIPT_INVALID'].includes(error.code)) {
          this.pictureAttempt = attempt;
          this.pictureUncertain = true;
        }
        throw error;
      }
      await this.refreshSettings(true);
    });
  }

  private async setRerankerEnabled(enabled: boolean): Promise<void> {
    await this.withBusy('reranker-switch', async () => {
      this.runtimeError = null;
      ++this.choiceRevision;
      const attempt = createSettingsAttempt({ rerankerEnabled: enabled, rerankerSwitchIntent: true }, requireSettingsWitness(this.rerankerSettings?.witness));
      let result;
      try {
        result = await executeSettingsAttempt(
          (path, init) => authorizedFetch(this.base() + path, init), attempt, { timeoutMs: 180_000 });
      } catch (error) {
        if (error instanceof SettingsAttemptError
            && ['SETTINGS_COMPLETION_UNKNOWN', 'SETTINGS_RECEIPT_INVALID'].includes(error.code)) {
          this.rerankerUncertain = true;
        }
        throw error;
      }
      this.rerankerUncertain = false;
      this.rerankerApplied = enabled ? null : false;
      this.rerankerSettings = { ...this.rerankerSettings, rerankerEnabled: enabled, witness: result.witness };
      await this.refreshSettings(true);
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
    if (this.llmPending > 0) {
      this.queuedLlm = { ...this.queuedLlm, ...updates };
      return;
    }
    const drafts = new Map(this.llmDrafts);
    let completed = false;
    ++this.llmRevision;
    ++this.llmPending;
    const context = updates.contextWindow;
    const normalized = { ...updates, ...(context == null ? {} : { contextWindow: context > 0 ? Math.max(512, context) : 0 }), ...(updates.maxTokens != null && updates.maxTokens > 0 ? { maxTokens: Math.max(16, updates.maxTokens) } : {}) };
    try {
      const result = await saveAbsoluteSettings((path, init) => authorizedFetch(this.base() + path, init), { llm: normalized });
      // A first completion may carry normalized values. Receipt-only replay still
      // confirms this narrow intent; it must not overwrite unrelated LLM fields.
      const committed: Partial<LlmSettings> = { ...normalized };
      for (const key of Object.keys(updates) as Array<keyof LlmSettings>) {
        const value = result.projection.llm?.[key];
        if (value !== undefined) Object.assign(committed, { [key]: value });
      }
      this.llm = { ...this.llm, ...committed };
      completed = true;
      this.runtimeError = null;
    } catch (err) {
      this.runtimeError = err instanceof Error ? err.message : String(err);
    } finally {
      ++this.llmRevision;
      --this.llmPending;
      for (const key of Object.keys(updates) as Array<keyof LlmSettings>) {
        if (this.llmDrafts.get(key) === drafts.get(key)) this.llmDrafts.delete(key);
      }
      this.requestUpdate();
      const queued = this.queuedLlm;
      this.queuedLlm = null;
      if (queued && completed) await this.patchLlm(queued);
    }
  }

  // ---------- Render: alerts + header ----------

  private showBulkError(error: OpErrorEventDetail): void {
    if (!this.isConnected) return;
    this.bulkOutcomeKey = '';
    this.bulkError = error;
  }

  private renderAlerts(): TemplateResult | typeof nothing {
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;
    if (!this.runtimeError && !downloadsDisabled && !onlineDisabled && !this.bulkOutcomeKey && !this.bulkError) return nothing;
    return html`
      ${this.bulkError ? html`<jf-error-alert tone="error" .onDismiss=${() => (this.bulkError = null)}>
        ${this.bulkError.message}
        ${this.bulkError.errorCode && !this.bulkError.message.endsWith(` (Code: ${this.bulkError.errorCode})`) ? html`<small style="display: block">Code: ${this.bulkError.errorCode}</small>` : nothing}
      </jf-error-alert>` : nothing}
      ${this.bulkOutcomeKey ? html`<jf-operation-outcome-detail
        operation-key=${this.bulkOutcomeKey} api-base=${this.apiBase}></jf-operation-outcome-detail>` : nothing}
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

  // ---------- Render: simple panel ----------

  /**
   * Tempdoc 663 Design pass 2 — the OBSERVED half (install/runtime/reachable) is now computed ONCE in
   * `aiStateStore.ts`, exposed as `AiState.aiEngine` (the fourth 594/595/596 sibling, store-level like
   * its siblings, not private to this component). This method is now a thin LOCAL overlay: it applies
   * this surface's own optimistic click-intent (`busy['install-start']`/`busy['inference-switch']`)
   * and the running status returned directly by install operations, before the store's next poll,
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
      installStarting: (this.installKnown() && this.installStatus?.state === 'running') || !!this.busy['install-start'],
    });
  }

  private renderSimplePanel(): TemplateResult {
    // Tempdoc 663 — the string `kind` drives this panel's dot/label/sub lookup below, unchanged
    // from before; only its SOURCE changed (the one `computeAiEngineVerdict` derivation, not the
    // old 5-source ladder).
    // A live install observation needs no runtime observation to show download progress.
    const known = (this.installKnown() && this.installStatus?.state === 'running') || this.aiKnown();
    const aiVerdict: AiEngineVerdict = known ? this.deriveAiEngineVerdict() : { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null };
    const aiState = aiVerdict.kind;
    const activationRequired = known && aiState !== 'installing' && this.installStatus?.phase === 'activation_required';
    const indexingActivity = this.indexingProgress().activity;
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    const onlineDisabled = this.policy?.onlineAiEnabled === false;
    const repairRemedy = deriveRepairRemedy(this.installStatus);
    const chatComponent = this.planPreview?.components?.find(c => c.id === 'chat');
    const chatUnavailableReason = chatComponent?.state === 'unavailable'
      ? chatComponent.unavailableReason || 'Chat is not supported here.' : null;

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
        sub: repairRemedySub(repairRemedy) ?? chatUnavailableReason ?? 'Start AI to enable chat and summaries.',
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
      online: {
        dot: 'online',
        label: aiEngineHeadline(aiVerdict),
        sub: this._unifiedAiState?.snapshotLive
          && this._unifiedAiState.status?.inference?.procedure === 'VDU_BATCH'
          && this._unifiedAiState.status?.inference?.engineReason === 'automatic-vdu'
          ? 'Reading pictures. Chat resumes after the current document.'
          : 'Chat and summaries ready.',
      },
      background: { dot: 'indexing', label: aiEngineHeadline(aiVerdict), sub: aiEngineBody(aiVerdict) },
      // Tempdoc 663 — indexing is now a distinct, named state (the original ladder had no explicit
      // branch for `runtime.mode === 'indexing'` and fell through to 'offline').
      // Tempdoc 734 round 5 finding 3 — `awaitingChatEnable` distinguishes "the engine is parked
      // because you haven't clicked Resume yet" from "genuinely still indexing"; both read the same
      // sub-label before this, and a Sandbox round waited 5+ minutes expecting the first case to
      // resolve on its own the way the second one does.
      indexing: {
        dot: 'indexing',
        label: indexingActivity.summary,
        sub: [indexingActivity.summary, indexingActivity.detail,
          aiVerdict.awaitingChatEnable ? 'Click Resume Chat AI below to start chat.' : null,
        ].filter(Boolean).join(' — '),
      },
      connecting: { dot: 'starting', label: 'Connecting…', sub: 'Checking AI status…' },
    };
    const sc = activationRequired
      ? {
          dot: 'starting',
          label: 'Downloaded — activation required',
          sub: 'Activate the downloaded models to rebuild affected indexes.',
        }
      : statusConfig[aiState] ?? statusConfig.offline!;

    const bytesDone = this.installStatus?.downloadedBytes ?? 0;
    const bytesTotal = this.installStatus?.totalBytes ?? 0;
    const pct = !this.installPlanPending() && bytesTotal > 0 ? Math.min(100, Math.floor((bytesDone / bytesTotal) * 100)) : null;

    // Tempdoc 663 Stage 3 — the primary action's availability is now typed (596), not a bare boolean.
    // Only the two states with a genuine, showable reason (a policy gate) use `unavailableBecause`; the
    // pure busy-only/unconditional cases use `blocked` (596 §10/C2 — a hard intent gate stays the
    // native-disabled-equivalent tier, not a soft "unavailable{reason}", since there is no reason beyond
    // "wait" to show).
    const primaryAction = (() => {
      if (activationRequired) {
        return {
          label: 'Activate downloaded models',
          iconName: 'check-circle-2' as const,
          onClick: () => void this.activateInstalledModels(),
          availability: this.busy['install-activation']
            ? ({ kind: 'blocked' } as const)
            : AVAILABLE,
          primary: true,
        };
      }
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
            onClick: () => void this.turnAnswersOn(),
            availability: chatUnavailableReason
              ? unavailableBecause(chatUnavailableReason)
              : onlineDisabled
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
            onClick: () => void this.turnAnswersOn(),
            availability: chatUnavailableReason
              ? unavailableBecause(chatUnavailableReason)
              : onlineDisabled
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
            onClick: () => void this.turnAnswersOn(),
            availability: chatUnavailableReason
              ? unavailableBecause(chatUnavailableReason)
              : onlineDisabled
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

    const features = this.brainFeatures();
    const readingPictures = this.systemStatus?.inference?.procedure === 'VDU_BATCH'
      ? this.aiKnown() ? true : null : false;
    const installed = this.hasInstalledAi();
    const processorOnly = this.runtimeStatus?.active?.gpuLayers === 0;
    const reindexRequired = this.systemStatus?.embedding?.compatState === 'BLOCKED_LEGACY'
      || this.systemStatus?.embedding?.compatState === 'BLOCKED_MISMATCH';
    const headline = !known ? 'Checking AI status…'
      : aiState === 'installing' ? sc.label
      : activationRequired ? 'Downloaded — activation required'
      : reindexRequired ? 'Rebuild search to use these models'
      : repairRemedyHeadline(repairRemedy) ? repairRemedyHeadline(repairRemedy)
      : aiState === 'not_installed' ? "AI isn't installed yet"
      : this.inference?.answersPausedWhileIndexing === true ? 'Answers paused while indexing'
      : aiState === 'online' && !readingPictures ? processorOnly ? 'AI is running on the processor' : 'AI is ready'
      : sc.label;
    const installSize = this.planPreview?.totalDownloadBytes;
    const actionLabel = primaryAction.label === 'Install AI' && typeof installSize === 'number' && installSize >= 0
      ? `Install AI · ${formatBytes(installSize)}` : primaryAction.label;
    const additionsLabel = `Install additions · ${typeof installSize === 'number' && installSize >= 0 ? formatBytes(installSize) : 'size unknown'}`;
    const additions = known && repairRemedy.kind === 'none' && installed && !activationRequired && aiState !== 'installing'
      && this.planPreview?.components?.some(c => c.state === 'to-download' && !c.declined) === true;
    return html`
      <div data-testid="brain-simple-panel">
        <h1 class="brain-headline" tabindex="-1">${headline}${aiState === 'installing' && pct !== null ? ` ${pct}%` : ''}</h1>
        <p class="brain-sentence">${this.inference?.answersPausedWhileIndexing === true && known ? html`Answers resume automatically when bulk file indexing ends. The answer model stays loaded.` : this.renderBrainSentence(aiVerdict, installed, features, readingPictures)}</p>
        <div class="brain-status-detail">${aiState === 'connecting' ? html`<jf-pulse-dots></jf-pulse-dots>` : nothing}${known ? (aiState === 'installing' || repairRemedy.kind === 'none' ? sc.sub : repairRemedy.kind === 'manual' ? repairRemedySub(repairRemedy) : nothing) : `Waiting for live install and AI observations. ${indexingActivity.summary}${indexingActivity.detail ? ` — ${indexingActivity.detail}` : ''}`}${this.installStalled() ? html` <span role="status">Download stalled — no recent progress.</span>` : nothing}</div>
        ${known && (!installed || aiState === 'installing' || activationRequired) ? html`<div class="status-row">
          <jf-button class=${primaryAction.primary && !installed ? 'install-action' : ''}
            variant=${primaryAction.primary && !installed ? 'primary' : 'ghost'}
            .availability=${primaryAction.availability}
            .onActivate=${primaryAction.onClick}
            label=${actionLabel}
            data-testid="brain-simple-action"
          >${icon({ name: primaryAction.iconName, size: 14 })} ${actionLabel}</jf-button>
          ${!installed ? html`<span class="brain-status-detail">You will see each licence before anything downloads.</span>` : nothing}
        </div>` : nothing}
        ${additions ? html`<div class="status-row">
          <jf-button variant="ghost" label=${additionsLabel}
            .availability=${downloadsDisabled ? unavailableBecause('Downloads are disabled by administrator policy.') : this.planLockReason() ? unavailableBecause(this.planLockReason()!) : AVAILABLE}
            .onActivate=${() => void this.startInstall()}>${icon({ name: 'download', size: 14 })} ${additionsLabel}</jf-button>
        </div>` : nothing}
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
                    variant="ghost"
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
        ${this.renderAlerts()}
        ${this.renderCompatibilityCallouts()}
        ${known && aiState === 'install_failed' ? html`<jf-error-alert tone="error">Install failed: ${this.deriveAiEngineVerdict().installFailure ?? this.installStatus?.lastError ?? this.installStatus?.message}</jf-error-alert>` : nothing}
        ${known ? this.renderRepairAlert() : nothing}
        ${this.renderFeaturePath()}
        ${this.renderGraphicsCard()}
        <div class="row brain-links">
          <button type="button" aria-expanded=${!!this.expanded.advanced} @click=${() => this.toggleSection('advanced')}>${icon({ name: 'settings', size: 14 })} Advanced settings</button>
          ${installed && aiState !== 'installing' && !activationRequired ? html`<jf-button variant="ghost" data-testid="brain-simple-action"
            label=${features.chat.selected ? 'Stop answers' : 'Start answers'}
            .availability=${features.chat.blockedReason ? unavailableBecause(features.chat.blockedReason) : AVAILABLE}
            .onActivate=${() => void this.changeFeature(features.chat)}>${icon({ name: features.chat.selected ? 'square' : 'play', size: 14 })} ${features.chat.selected ? 'Stop answers' : 'Start answers'}</jf-button>` : nothing}
          <a href="#justsearch://surface/core.health-surface" @click=${() => requestMemberTab('core.health-surface', 'ai-engine')}>${icon({ name: 'history', size: 14 })} Engine history in Health</a>
          <jf-button variant="ghost" label="Refresh status" .onActivate=${() => void this.refreshAll()}>${icon({ name: 'refresh-cw', size: 14 })}</jf-button>
        </div>

      </div>
    `;
  }

  // ---------- Render: mode-transition timeline (W3.2) ----------

  /**
   * Tempdoc 518 Appendix F W3.2 — Brain-panel timeline of recent mode transitions.
   * Renders the 8 newest from /api/inference/transitions. Hidden when the list is empty
   * so freshly-booted runtimes (no transitions yet) don't show an empty section.
   */
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
    const activity = this.indexingProgress().activity;
    const canonicalWordings = reindexCauses.map((code) => reasonFor(code, activity).wording);
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
                  ${activity.confirmed ? '' : `${activity.summary} — last observed: `}Rebuild the index to restore full search.
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
          @op-success=${(event: CustomEvent<OpSuccessEventDetail>) => {
            this.bulkError = null;
            this.bulkOutcomeKey = String(event.detail.structuredData?.operationKey ?? '');
            void this.refreshAll();
          }}
          @op-error=${(event: CustomEvent<OpErrorEventDetail>) => this.showBulkError(event.detail)}
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
  private indexingProgress() {
    return selectIndexingProgress(
      this.systemStatus,
      this._unifiedAiState?.indexingStatusLive ?? false,
      this._unifiedAiState?.episodeMaxPendingJobs ?? 0,
      this._unifiedAiState?.enrichSettleSamples ?? [],
    );
  }

  private renderEnrichmentProgress(): TemplateResult | typeof nothing {
    const emb = this.systemStatus?.embedding;
    // The compat callout above owns the BLOCKED_* states (it carries the reindex remedy); a second
    // card about the same condition would be two voices on one fact.
    if (emb?.compatState?.startsWith('BLOCKED')) return nothing;
    const progress = this.indexingProgress();
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
    // numbers were right, the present tense was not. An unconfirmed observation (expired or
    // explicitly stale) stops asserting progress: no spinner, no live-work colouring, and every
    // figure explicitly labelled as the last observation.
    if (!progress.activity.confirmed) {
      return html`
        <div class="section" data-testid="brain-enrichment-progress">
          <div style="display: flex; gap: 0.625rem; align-items: flex-start">
            ${icon({ name: 'zap', size: 18 })}
            <div style="flex: 1">
              <div style="font-weight: 600; color: var(--text-secondary)">
                Semantic search build — last known
              </div>
              <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.25rem">
                ${progress.activity.summary}${this._unifiedAiState?.snapshotLive ? '' : ` — ${reasonFor('binding.unreachable').wording}`} — these figures are the last observed
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
        style=${progress.activity.enrichmentDeferred ? '' : 'border-color: var(--accent-warning-30); background: var(--accent-warning-08)'}
      >
        <div style="display: flex; gap: 0.625rem; align-items: flex-start">
          ${icon({ name: 'zap', size: 18 })}
          <div style="flex: 1">
            <div style=${`font-weight: 600; color: var(${progress.activity.enrichmentDeferred ? '--text-secondary' : '--text-warning'})`}>
              ${progress.activity.enrichmentDeferred ? progress.activity.summary : ENRICHMENT_IN_PROGRESS_LABEL}
            </div>
            <!-- The subtitle names the SCOPE of the number above it. Before this it promised chunk
                 embeddings while the figure measured document vectors — one stage's number under
                 another stage's sentence. -->
            <div style="font-size: var(--font-size-sm); color: var(--text-secondary); margin-top: 0.25rem">
              ${progress.activity.detail ?? ''}
              ${ENRICHMENT_SCOPE_NOTE}: semantic vectors, passage vectors, keyword expansion and
              entity recognition.
            </div>
          </div>
          ${pct === null
            ? nothing
            : html`<div style="font-weight: 600; font-variant-numeric: tabular-nums">${pct}%</div>`}
        </div>
        ${pct === null || progress.activity.enrichmentDeferred
          ? nothing
          : html`<div class="progress" style="margin-top: 0.625rem">
              <div class="progress-bar" style="width: ${pct}%; background: var(--accent-warning)"></div>
            </div>`}
        <div style="font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: 0.5rem; display: flex; align-items: center; gap: 0.375rem">
          ${progress.activity.enrichmentDeferred ? nothing : icon({ name: 'loader-2', size: 12, spin: true })}
          ${NUM.format(pending)} ${progress.activity.enrichmentDeferred ? 'waiting' : 'pending'} across all stages
        </div>
      </div>
    `;
  }

  private renderSearchQualityFeatures(): TemplateResult {
    const features = this.runtimeStatus?.onnxFeatures ?? [];
    const activeCount = features.filter((f) => featureInUse(f) === true).length;
    // Tempdoc 807 A.3 — "N/N active" is a PRESENT-tense claim read off the retained runtime snapshot;
    // round 13 photographed "4/4 active" with the backend dead. Not live ⟹ the count is history, and
    // the per-feature green dot (which asserts "running right now") drops to neutral.
    const live = this.aiKnown();
    return html`<div data-testid="brain-observed-features"><h4>Observed search features · ${features.length > 0 ? `${activeCount}/${features.length} ${live ? 'active' : 'when last observed'}` : 'Unknown'}</h4>
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
                            : featureInUse(f) === true
                              ? 'success'
                              : 'neutral'}
                        style="margin-right: 0.5rem; vertical-align: middle"
                      ></jf-status-dot>
                      ${f.label ?? f.id ?? 'feature'}
                    </span>
                    <span style="color: var(--text-secondary); font-family: monospace; font-size: var(--font-size-xs)"
                      >${observedEpLabel(f)}${live ? '' : ' · last observed'}</span
                    >
                  </div>
                `,
              )}
        </div>
      </div>`;
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
    const components = this.planPreview?.components?.map(c => this.packagePresent(c.id ?? '') ? { ...c, state: 'installed' } : c);
    const groups = composeComponentGroups(components);
    if (groups.length === 0) return nothing;
    // The stock-take that replaced seven repetitions of "Installed" — one line saying how much is on
    // disk instead of seven saying nothing (`composeComponentSummary`).
    const summary = composeComponentSummary(components);
    return html`
      <div class="component-list" data-testid="install-component-list">
        ${summary
          ? html`<div class="component-summary" data-testid="install-component-summary">
              ${summary}
            </div>`
          : nothing}
        <div class="component-lede">Choose which optional files to download. Skipping keeps existing files.</div>
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
    const reason = this.planLockReason();
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
          ${this.packagePresent(r.id) ? html`<span class="component-state">${this.aiKnown() ? 'Downloaded' : 'Downloaded — last observed'}${this.aiKnown() && (r.id === 'chat' ? this.runtimeStatus?.active?.modelPath != null : this.runtimeStatus?.onnxFeatures?.some(f => f.id === (r.id === 'embedding' ? 'embed' : r.id) && featureInUse(f) === true)) ? ' · in use' : ''}</span>` : nothing}
          ${r.stateText
            ? html`<span class="component-state" data-testid="component-state-${r.id}"
                >${r.stateText}</span
              >`
            : nothing}
        </div>
        <span class="component-size">${r.sizeText ?? '—'}</span>
        <div class="component-control">
          ${r.togglable
            ? html`<button type="button"
                class="component-toggle"
                data-testid="component-toggle-${r.id}"
                data-on=${r.selected ? 'true' : 'false'}
                role="switch" aria-label=${`Include ${r.label}`} aria-checked=${String(r.selected)} aria-disabled=${String(!!reason)}
                aria-describedby=${reason ? `component-${r.id}-reason` : nothing}
                @click=${() => { if (!reason) void this.changeAcquisition(r.id, r.selected, r.state === 'installed'); }}
                ><span class="component-switch-thumb"></span></button>
                ${reason ? html`<span id=${`component-${r.id}-reason`} class="switch-reason">${reason}</span>` : nothing}`
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
    // Stage records are also retained until the new plan is published after runtime restore.
    if (this.installPlanPending() || (status?.state === 'running' && status.phase === 'restore_runtime')) return nothing;
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

  private renderPackageHistory(): TemplateResult {
    return html`          ${this.installStatus?.packages?.length
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
`;
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
              : html`The download size is not known yet. Wait for the preview before accepting.`}
          </p>
          ${consent.resumedTotal
            ? html`<p class="consent-lede">
                <strong>${consent.resumedTotal}</strong> from your earlier paused download is still on
                disk and will be resumed, not downloaded again.
              </p>`
            : nothing}
          <p class="consent-lede">
            ${consent.termsUnavailable
              ? 'The upstream model terms could not be listed right now. Retry to review them before downloading.'
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
                        <span>${this.packageDownloadSize(p.id)}</span>
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
            ${this.planLoading ? html`<p role="status">Loading download sizes and licences…</p>` : nothing}
            ${this.planError || (!this.planLoading && !this.consentReady()) ? html`<p role="status">${this.planError ?? 'Every download size must be known before accepting.'}</p><jf-button variant="ghost" label="Retry download preview" .onActivate=${() => void this.refreshInstallPlanPreview()}>Retry download preview</jf-button>` : nothing}
            <jf-button label="Cancel" .onActivate=${() => (this.installConsentOpen = false)}>
              Cancel
            </jf-button>
            <jf-button
              class="consent-confirm"
              variant="primary"
              label="Accept and download"
              .disabled=${!this.consentReady()}
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
    const live = this._unifiedAiState?.packObservationLive === true;
    return html`
      <div class="section">
        <h4>Import an offline package</h4>
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
                      : !live
                        ? unavailableBecause('Checking pack status before importing.')
                        : this.packStatus?.state === 'running'
                          ? unavailableBecause('A pack import is already in progress.')
                          : AVAILABLE}
                    .onActivate=${() => void this.importPack()}
                  >
                    ${icon({ name: 'hard-drive', size: 14 })} Import pack
                  </jf-button>
                </div>
                ${!live ? html`<p>Checking pack status…</p>` : nothing}
                ${this.packStatus
                  ? html`<div style="margin-top: 0.5rem; font-size: var(--font-size-xs); color: var(--text-secondary)">
                      ${live ? 'Pack status:' : 'Pack status — last observed:'} <code>${this.packStatus.state}</code>
                      ${this.packStatus.phase ? html` · ${this.packStatus.phase}` : nothing}
                      ${this.packStatus.message ? html` — ${this.packStatus.message}` : nothing}
                    </div>`
                  : nothing}
              `}
      </div>
    `;
  }

  // ---------- Render: install section advanced ----------

  private settingInput(key: 'modelPath' | 'serverExecutable' | 'llamaLibPath', label: string): TemplateResult {
    return html`<label class="field">${label}<input type="text" aria-label=${label}
      .value=${this.llmDrafts.get(key) ?? this.llm[key] ?? ''} ?disabled=${this.llmPending > 0}
      @input=${(event: Event) => this.editLlm(key, (event.target as HTMLInputElement).value)}
      @change=${(event: Event) => void this.patchLlm({ [key]: (event.target as HTMLInputElement).value })}></label>`;
  }

  private editLlm(key: keyof LlmSettings, value: string): void {
    ++this.llmRevision;
    this.llmDrafts.set(key, value);
    this.requestUpdate();
  }

  private renderSlider(key: 'contextWindow' | 'maxTokens' | 'gpuLayers', label: string, description: string, max: number): TemplateResult {
    const saved = this.llm[key];
    const draft = this.llmDrafts.get(key);
    const value = draft == null ? saved : Number(draft);
    const automatic = key === 'gpuLayers' ? value == null : key === 'contextWindow' && value === 0;
    const shown = automatic ? 'Automatic' : value == null ? 'Not configured' : key === 'gpuLayers' && value === 0 ? '0 layers · processor only' : `${NUM.format(value!)} ${key === 'gpuLayers' ? 'layers' : 'tokens'}`;
    return html`<div class="advanced-row">
      <div><label for=${`brain-${key}`}>${label}</label><p>${description}</p></div>
      <div class="slider-setting"><input id=${`brain-${key}`} type="range" min=${key === 'maxTokens' ? 16 : 0} max=${Math.max(max, saved ?? 0)} step="1"
        .value=${String(automatic && key === 'gpuLayers' ? Math.max(max, saved ?? 0) : value ?? 0)} aria-label=${label} aria-valuetext=${shown}
        @input=${(event: Event) => this.editLlm(key, (event.target as HTMLInputElement).value)}
        @change=${(event: Event) => void this.patchLlm({ [key]: Number((event.target as HTMLInputElement).value) })}>
        <output for=${`brain-${key}`}>${shown}</output>
        ${key === 'contextWindow' ? html`<jf-button variant="ghost" label=${`Automatic ${label.toLowerCase()}`} ?disabled=${this.llmPending > 0 || value === 0}
          .onActivate=${() => void this.patchLlm({ contextWindow: 0 })}>${icon({ name: 'refresh-cw', size: 12 })} Automatic</jf-button>` : nothing}
      </div>
    </div>`;
  }

  private runtimeAvailability() {
    return !this.aiKnown()
      ? unavailableBecause(this._unifiedAiState?.snapshotLive === false ? reasonFor('binding.unreachable').wording : 'Checking AI status before changing the engine.')
      : this.busy['inference-switch'] || this.busy.variant ? unavailableBecause('An engine change is in progress.') : AVAILABLE;
  }

  private renderRuntimeSection(): TemplateResult {
    const variants = this.runtimeStatus?.installedVariants ?? [];
    const activeId = this.runtimeStatus?.active?.activeVariantId;
    const live = this.aiKnown();
    const act = this.runtimeStatus?.activation;
    const availability = act?.state === 'running' ? unavailableBecause('Activating an engine variant.') : this.runtimeAvailability();
    const progress = this.indexingProgress();
    return html`<section aria-label="Graphics card settings">
      <h3 class="advanced-heading">Graphics card</h3>
      ${!live ? html`<p>${this._unifiedAiState?.snapshotLive === false ? reasonFor('binding.unreachable').wording : 'Checking AI status'} — these are the last observed readings.</p>` : nothing}
      ${this.renderSlider('gpuLayers', 'Graphics card use', 'More layers on the card is faster; 0 uses the processor.', 256)}
      <div class="advanced-row"><div>Graphics card support<p>Picked for your hardware</p></div><div>
        ${activeId ?? 'Built in'}${activeId ? live ? ' · active' : ' · active when last observed' : ''}
        <button type="button" aria-expanded=${!!this.expanded.variants} @click=${() => this.toggleSection('variants')}>${icon({ name: 'chevron-down', size: 14 })} Other options</button>
      </div></div>
      ${this.expanded.variants ? html`<div class="variant-options">
        ${variants.length === 0 ? html`<p>No additional installed runtime variants.</p>` : variants.map(v => html`<div class="variant">
          <span class="variant-info">${v.variantId}${v.variantId === activeId ? live ? ' · active' : ' · active when last observed' : ''}${v.exePath ? html`<small>${v.exePath}</small>` : nothing}</span>
          ${v.variantId ? html`<jf-button variant="ghost" label=${v.variantId === activeId ? 'Deactivate' : 'Activate'}
            .availability=${availability} .onActivate=${() => void (v.variantId === activeId ? this.deactivateVariant() : this.activateVariant(v.variantId!))}>
            ${icon({ name: 'refresh-cw', size: 14 })} ${v.variantId === activeId ? 'Deactivate' : 'Activate'}</jf-button>` : nothing}
        </div>`)}
        ${act?.state === 'running' ? html`<p role="status">Activating: ${act.phase ?? '…'}</p>` : nothing}
        ${act?.state === 'failed' ? html`<p role="alert">${act.message} ${act.errorCode}</p>` : nothing}
      </div>` : nothing}
      <div class="advanced-row"><div>Engine program<p>Runs the answer model</p></div><div>
        <span>${this.llm.serverExecutable || 'Built in'}</span>
        <button type="button" aria-expanded=${!!this.expanded.executable} @click=${() => this.toggleSection('executable')}>${icon({ name: 'folder', size: 14 })} Use another</button>
        ${this.expanded.executable ? this.settingInput('serverExecutable', 'Server executable') : nothing}
        ${this.expanded.executable ? this.settingInput('llamaLibPath', 'Llama library path') : nothing}
      </div></div>
      ${this.inference?.gpu ? html`<p class="runtime-observation" style=${isGpuReadingProvisional(this._unifiedAiState?.aiEngine?.stability) || !live ? 'opacity: 0.6' : ''}>
        CUDA: ${this.inference.gpu.cudaAvailable ? 'available' : 'no'} · ${projectFact('core.ai.gpu', this._unifiedAiState).value ?? 'Graphics memory unknown'}
        ${this.inference.tier ? ` · ${this.inference.tier.replace(/_/g, ' ')}` : ''}
      </p>` : nothing}
      <p class="runtime-observation">
        ${live && progress.activity.confirmed ? '' : `${progress.activity.summary} — last observed `}embed queue: ${progress.phase === 'unknown' ? '—' : NUM.format(progress.embeddingPending)}
        · VDU queue: ${progress.phase === 'unknown' ? '—' : NUM.format(progress.vduPending)}
      </p>
    </section>`;
  }

  private renderAdvanced(): TemplateResult | typeof nothing {
    if (!this.expanded.advanced) return nothing;
    const optionalGaps = this.installStatus?.optionalGaps ?? [];
    const installing = this.installStatus?.state === 'running';
    const downloadsDisabled = this.policy?.downloadsEnabled === false;
    return html`<div class="brain-advanced" aria-label="Advanced settings">
      <section aria-label="Answer settings"><h3 class="advanced-heading">Answers</h3>
        <div class="advanced-row"><div>Answer model<p>Writes the answers</p></div><div>
          ${this._unifiedAiState?.runtime?.modelLabel ?? this.runtimeStatus?.active?.chatProfile ?? 'Automatic'}${this.aiKnown() ? '' : ' · last observed'}
          ${this.brainFeatures().chat.size ?? ''}
          <button type="button" aria-expanded=${!!this.expanded.model} @click=${() => this.toggleSection('model')}>${icon({ name: 'folder', size: 14 })} Change</button>
          ${this.expanded.model ? this.settingInput('modelPath', 'Model path') : nothing}
        </div></div>
        <div class="advanced-row"><div>Pause answers while indexing<p>Wait while indexing files (bulk import, reindex); keep the answer model loaded</p></div><div>
          <button type="button" class="component-toggle" role="switch" aria-label="Pause answers while indexing"
            aria-checked=${this.rerankerSettings?.pauseAnswersWhileIndexing === true}
            ?disabled=${!this.settingsRead || !this.rerankerSettings?.witness || !!this.busy['answer-pause-switch']}
            @click=${() => this.setPauseAnswersWhileIndexing(this.rerankerSettings?.pauseAnswersWhileIndexing !== true)}><span></span></button>
          <span>${!this.settingsRead ? 'Checking saved choice…' : this.busy['answer-pause-switch'] ? 'Changing…'
            : this.rerankerSettings?.pauseAnswersWhileIndexing === true ? 'On' : 'Off'}</span>
        </div></div>
        ${this.renderSlider('contextWindow', 'Reading memory', 'How much text it reads at once', 131072)}
        ${this.renderSlider('maxTokens', 'Longest answer', 'Upper limit for one answer', 16384)}
      </section>
      ${this.renderRuntimeSection()}
      <section aria-label="Downloaded files"><h3 class="advanced-heading">Downloaded files</h3>
        <p class="advanced-note">Files on this computer, measured on disk. Remove files deletes a switched-off part's downloaded files; Download again asks first.</p>
        ${this.renderDownloadedFiles()}
        <p class="advanced-note">Download choices use registry sizes. Skipping a part keeps its existing files.</p>
        ${this.renderComponentList()}${this.renderTierBreakdown()}${this.renderPackageHistory()}${this.renderSearchQualityFeatures()}
        ${this.renderPackImport()}
      </section>
      <section aria-label="Maintenance"><h3 class="advanced-heading">Maintenance</h3>
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
          ${this.installStatus?.pendingRegistryAdditions?.length
            ? html`<div data-testid="install-pending-registry-additions">
                New AI components are available since this install:
                <code>${this.installStatus.pendingRegistryAdditions.join(', ')}</code> — run Install
                to add them.
              </div>`
            : nothing}

        <div class="row">
          <jf-button variant="ghost" label="Restart AI" .availability=${this.runtimeAvailability()}
            .onActivate=${() => void this.withBusy('inference-switch', () => this.refreshInference())}>${icon({ name: 'refresh-cw', size: 14 })} Restart AI</jf-button>
          <jf-button variant="ghost" label="Repair installation"
            .availability=${installing ? unavailableBecause('Already installing.') : downloadsDisabled ? unavailableBecause('Downloads are disabled by administrator policy.') : deriveRepairRemedy(this.installStatus).kind === 'manual' ? unavailableBecause('Automatic repair could not download these files. Use the direct links above.') : this.busy['install-repair'] ? unavailableBecause('Repair in progress.') : this.runtimeAvailability()}
            .onActivate=${() => void this.repairInstall()}>${icon({ name: 'refresh-cw', size: 14 })} Repair installation</jf-button>
          <jf-operation context="button" operation-id="core.rebuild-index" api-base=${this.apiBase}
            @op-success=${(event: CustomEvent<OpSuccessEventDetail>) => { this.bulkError = null; this.bulkOutcomeKey = String(event.detail.structuredData?.operationKey ?? ''); }}
            @op-error=${(event: CustomEvent<OpErrorEventDetail>) => this.showBulkError(event.detail)}></jf-operation>
        </div>
      </section>
    </div>`;
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

  private renderBrainBody(): TemplateResult {
    if (!this.apiBase && this.apiBase !== '') {
      return html`<div class="empty-state">No API connection. Start the JustSearch backend to configure AI.</div>`;
    }
    return html`
      <div class="body" @pointerdown=${(event: Event) => {
        if (this.featureInfo && !event.composedPath().some(node => node instanceof HTMLElement && (node.classList.contains('feature-panel') || node.getAttribute('aria-controls')?.startsWith('brain-feature-panel-')))) this.closeFeatureInfo();
      }}>
        ${this.renderSimplePanel()}
        ${this.renderEnrichmentProgress()}
        ${this.renderAdvanced()}
        ${this.renderInstallConsent()}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-brain-surface')) {
  customElements.define('jf-brain-surface', BrainSurface);
}
