// SPDX-License-Identifier: Apache-2.0
/**
 * installComponents.ts — tempdoc 840 Phase 5: the ONE derivation behind the user-facing side of the
 * AI model install.
 *
 * Before this module the install was a single undifferentiated ~7 GB wait: a percentage, a byte
 * counter, no rate, no ETA, and nothing naming what any of it was for. The backend has known far
 * more than that since Phases 0-4 — a standing per-component list on
 * `GET /api/ai/install/plan-preview` (`components[]`), an ordered stage plan with per-stage
 * capabilities on `GET /api/ai/install/status` (`stages[]` / `readyCapabilities`), and a measured
 * transfer rate + horizon. This module projects those wire facts into the shapes three surfaces
 * render (the Brain panel, the System Self-View, the task tray) so the SAME fact is never derived
 * twice with two different answers.
 *
 * Two properties are load-bearing and are why the composition lives here rather than inline in a
 * template:
 *
 *  1. **`-1` is UNKNOWN.** `AiInstallStatus.bytesPerSecond` / `remainingSeconds` carry `-1` as an
 *     explicit sentinel and never `0`; AI Brain's install flow (slice 7b, `installFlow.ts`) shows time
 *     left only for a positive estimate.
 *
 *  2. **`unavailable` is not `declined`.** Hardware this machine lacks is not a choice the user
 *     made, so it gets the typed `unavailable{reason}` availability and NO control, while a
 *     declinable component gets a real opt-OUT control. Rendering the two the same way would imply
 *     an option that does not exist.
 */

import { formatBytes } from '../display/format.js';
import { AVAILABLE, unavailableBecause, type Availability } from './availability.js';
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

/** `GET /api/ai/install/plan-preview` → `components[]` (app-api `InstallPlanPreview.ComponentEstimate`). */
export interface InstallComponentEstimate {
  id?: string;
  label?: string;
  description?: string;
  tier?: string;
  necessity?: string;
  declinable?: boolean;
  declined?: boolean;
  totalBytes?: number;
  downloadBytes?: number;
  state?: string;
  unavailableReason?: string;
}

/**
 * The four necessity categories (`Necessity.java`), in the order they are presented. The order is
 * the answer to "what do I lose if I say no?", loudest first — infrastructure last because it is the
 * only one that is not a user-facing capability at all.
 */
const NECESSITY_ORDER = [
  'required',
  'improves-results',
  'adds-feature',
  'infrastructure',
] as const;

export type Necessity = (typeof NECESSITY_ORDER)[number];

/** The category heading — the registry's own label, kept short enough for a group header. */
const NECESSITY_HEADING: Record<Necessity, string> = {
  required: 'Required',
  'improves-results': 'Improves results',
  'adds-feature': 'Adds a feature',
  infrastructure: 'Infrastructure',
};

/**
 * What the category MEANS, in consequence terms. This is the whole point of the necessity axis: a
 * user who turns off the reranker to save 340 MB gets measurably worse search, and no percentage or
 * byte counter can tell them that. The wording is the consequence, never the mechanism.
 *
 * A CLAUSE, not a sentence: it rides the group header line (`REQUIRED · search does not work without
 * this`) rather than occupying a line of its own. Seven components once cost ~18 lines of prose; the
 * consequence is the part that had to survive the cut, so it moved onto the heading instead.
 */
export const NECESSITY_CONSEQUENCE: Record<Necessity, string> = {
  required: 'search does not work without this',
  'improves-results': 'results are worse without these',
  'adds-feature': 'you lose chat and summaries',
  infrastructure: 'plumbing, not a capability you use directly',
};

/**
 * An unrecognized necessity id must not become silently switch-off-able — the same conservative
 * mapping `Necessity.fromId`'s callers apply on the backend (a category this build does not know
 * leaves the package mandatory).
 */
export function toNecessity(id: string | null | undefined): Necessity {
  const found = NECESSITY_ORDER.find((n) => n === id);
  return found ?? 'required';
}

/** `ComponentEstimate.state` — derived from the plan, so it cannot disagree with what an install does. */
type ComponentState =
  | 'installed'
  | 'to-download'
  | 'declined'
  | 'unavailable'
  | 'not-in-mode';

/**
 * State text is written ONLY where the state DEVIATES from installed — `installed` maps to `null`.
 *
 * "Installed" is the resting state of every row on a working machine, so printing it seven times
 * spends seven lines saying nothing: a label that never varies carries no information, and it crowded
 * out the facts that do vary (what the component costs, whether it is off). Silence now means
 * installed, and any text in that slot means something is different from normal.
 */
const COMPONENT_STATE_TEXT: Record<ComponentState, string | null> = {
  installed: null,
  'to-download': 'Will download',
  declined: 'Off',
  unavailable: 'Not supported here',
  'not-in-mode': 'Not in this mode',
};

/** One rendered component row. Everything a template needs, already decided. */
export interface ComponentRow {
  readonly id: string;
  readonly label: string;
  readonly description: string;
  readonly necessity: Necessity;
  readonly state: ComponentState;
  /** Deviation text only — `null` when the component is simply installed (see COMPONENT_STATE_TEXT). */
  readonly stateText: string | null;
  /** Full footprint at the selected profile, or `null` when the registry declares no size. */
  readonly sizeText: string | null;
  /**
   * Opt-OUT, not opt-in: everything this machine supports starts SELECTED and the user unticks what
   * they do not want. Opt-in would leave most users on a degraded retrieval stack they never
   * knowingly chose.
   */
  readonly selected: boolean;
  /** True only for a component the user may actually turn off — no control is rendered otherwise. */
  readonly togglable: boolean;
  /**
   * The typed availability for the row's control. `unavailable{reason}` for hardware this machine
   * lacks (a reachable reason, no choice implied); `AVAILABLE` otherwise. Deliberately NOT `blocked`
   * — that kind is reserved for intent gates.
   */
  readonly availability: Availability;
}

/** One necessity group, with its heading, its consequence clause and what it costs. */
export interface ComponentGroup {
  readonly necessity: Necessity;
  readonly heading: string;
  readonly consequence: string;
  /**
   * "4 components · 1.38 GB" — what this group costs, stated directly.
   *
   * This is what the size bar was reaching for and could not deliver: the bar answers "which of these
   * is bigger", which needs at least two members, while "what does this category cost me?" is a
   * question every group has an answer to — including the three that hold exactly one component and
   * therefore render no bar at all.
   */
  /** The group's aggregate cost, or `null` for a one-member group (its row already says it). */
  readonly subtotal: string | null;
  readonly rows: readonly ComponentRow[];
}

function toComponentState(state: string | null | undefined): ComponentState {
  switch (state) {
    case 'installed':
    case 'to-download':
    case 'declined':
    case 'unavailable':
    case 'not-in-mode':
      return state;
    default:
      // An unrecognized state is not evidence the component is present. "Will download" is the
      // least-wrong reading of "wanted, whereabouts unknown" and never claims something is installed.
      return 'to-download';
  }
}

/**
 * Project one wire component onto its row.
 *
 * `unavailable` is the case this function exists for. It carries the planner's own reason (it
 * already writes user-facing prose naming the actual constraint) and NO control, because an unticked
 * box implies a choice; `declined` — a choice the user really made — keeps its control so it can be
 * undone.
 *
 */
export function composeComponentRow(c: InstallComponentEstimate): ComponentRow {
  const state = toComponentState(c.state);
  const necessity = toNecessity(c.necessity);
  const totalBytes = c.totalBytes ?? 0;
  const unavailable = state === 'unavailable';
  const reason =
    c.unavailableReason && c.unavailableReason.trim().length > 0
      ? c.unavailableReason.trim()
      : 'This component needs hardware this machine does not have.';
  return {
    id: c.id ?? '',
    label: c.label && c.label.length > 0 ? c.label : (c.id ?? 'component'),
    description: c.description ?? '',
    necessity,
    state,
    stateText: COMPONENT_STATE_TEXT[state],
    sizeText: totalBytes > 0 ? formatBytes(totalBytes) : null,
    selected: !(c.declined === true) && state !== 'declined',
    // `not-in-mode` is the intent axis, not the user's — offering a tick would promise a component
    // the active mode does not install whatever the tick says.
    togglable: c.declinable === true && !unavailable && state !== 'not-in-mode',
    availability: unavailable ? unavailableBecause(reason) : AVAILABLE,
  };
}

/**
 * Group the standing component list by necessity, in category order, dropping empty categories.
 *
 * Sourced from the PREVIEW deliberately: `status.packages` is run bookkeeping and comes back empty
 * on an idle machine, so a surface that asked it "what components are there" before the first
 * install would be told "none".
 */
export function composeComponentGroups(
  components: readonly InstallComponentEstimate[] | null | undefined,
): ComponentGroup[] {
  const all = components ?? [];
  const groups: ComponentGroup[] = [];
  for (const necessity of NECESSITY_ORDER) {
    const inGroup = all.filter((c) => toNecessity(c.necessity) === necessity);
    if (inGroup.length === 0) continue;
    groups.push({
      necessity,
      heading: NECESSITY_HEADING[necessity],
      consequence: NECESSITY_CONSEQUENCE[necessity],
      subtotal: composeGroupSubtotal(inGroup),
      rows: inGroup.map((c) => composeComponentRow(c)),
    });
  }
  return groups;
}

/**
 * "4 components · 1.38 GB" — the group's own cost line.
 *
 * Sums the group's declared footprints, counting the members whether or not they are installed:
 * unlike the section-wide {@link composeComponentSummary} (a claim about what is ON DISK now), this
 * answers "what does this category cost me?", which is a property of the category and not of the
 * current install state. The footprint clause is withdrawn rather than printed as "0 B" when no member
 * declares a size, so the count still stands alone.
 */
function composeGroupSubtotal(inGroup: readonly InstallComponentEstimate[]): string | null {
  // A one-member group needs no subtotal: the single row below already states that figure, and
  // printing "1 component · 615.2 MB" directly above "615.2 MB" is the same number twice — noise in
  // a list whose whole revision was about removing text that repeats what is already on screen. A
  // subtotal earns its place only where it AGGREGATES something.
  if (inGroup.length < 2) return null;
  const count = `${inGroup.length} components`;
  const bytes = inGroup.reduce((sum, c) => sum + Math.max(0, c.totalBytes ?? 0), 0);
  return bytes > 0 ? `${count} · ${formatBytes(bytes)}` : count;
}

/**
 * The one-line stock-take that replaced seven repetitions of "Installed": how many components are on
 * disk, and what they cost together.
 *
 * This is the trade the per-row "Installed" labels never stated. The footprint counts ONLY installed
 * components, because "on disk" is a claim about disk — a pending download has not spent anything yet.
 * Returns `null` when nothing is installed (there is no stock to take, and "0 installed" beside a list
 * of pending downloads reads as breakage rather than as a fresh machine).
 */
export function composeComponentSummary(
  components: readonly InstallComponentEstimate[] | null | undefined,
): string | null {
  const installed = (components ?? []).filter((c) => toComponentState(c.state) === 'installed');
  if (installed.length === 0) return null;
  const bytes = installed.reduce((sum, c) => sum + Math.max(0, c.totalBytes ?? 0), 0);
  const count = `${installed.length} installed`;
  return bytes > 0 ? `${count} · ${formatBytes(bytes)} on disk` : count;
}

/** `AiInstallStatus.StageStatus.state` — the `StagedAcquisition.StageState` vocabulary plus `pending`. */
type StageState =
  | 'pending'
  | 'running'
  | 'completed'
  | 'skipped'
  | 'failed'
  | 'cancelled'
  | 'blocked';

const STAGE_STATE_TEXT: Record<StageState, string> = {
  pending: 'Waiting',
  running: 'Downloading',
  completed: 'Done',
  skipped: 'Nothing to do',
  failed: 'Failed',
  cancelled: 'Stopped',
  blocked: 'Cannot start',
};

function toStageState(state: string | null | undefined): StageState {
  switch (state) {
    case 'pending':
    case 'running':
    case 'completed':
    case 'skipped':
    case 'failed':
    case 'cancelled':
    case 'blocked':
      return state;
    default:
      return 'pending';
  }
}

/** One rendered stage row of the staged acquisition. */
export interface StageRow {
  readonly id: string;
  readonly label: string;
  readonly state: StageState;
  readonly stateText: string;
  /** True for the stage the run is currently in (`status.currentStage`). */
  readonly current: boolean;
  /** 0..100, or `null` when the stage declares no byte total (nothing honest to draw). */
  readonly percent: number | null;
  readonly bytesText: string | null;
  /**
   * Why a stage was refused BEFORE it started — today, not enough disk space. Actionable by the user,
   * and distinct from a failure: nothing was attempted, so nothing broke.
   */
  readonly blockedReason: string | null;
  readonly capabilities: readonly string[];
}

export function composeStageRows(status: AiInstallStatus | null | undefined): StageRow[] {
  const current = status?.currentStage ?? '';
  return (status?.stages ?? []).map((s) => {
    const total = s.totalBytes ?? 0;
    const done = s.downloadedBytes ?? 0;
    const state = toStageState(s.state);
    const blocked = s.blockedReason && s.blockedReason.trim().length > 0 ? s.blockedReason.trim() : null;
    return {
      id: s.stage ?? '',
      label: s.label && s.label.length > 0 ? s.label : (s.stage ?? 'stage'),
      state,
      stateText: STAGE_STATE_TEXT[state],
      current: !!s.stage && s.stage === current,
      percent: total > 0 ? Math.min(100, Math.max(0, Math.floor((done / total) * 100))) : null,
      bytesText: total > 0 ? `${formatBytes(done)} / ${formatBytes(total)}` : null,
      blockedReason: blocked,
      capabilities: s.capabilities ?? [],
    };
  });
}

/**
 * U5 — the install phase message names a filesystem path
 * (`Downloading onnx/gte-multilingual-base/model.onnx...`) because the backend's
 * `AcquisitionScheduler.Item.id()` IS the download's target path, while the component list beside it
 * has friendly labels. Map the path onto the label of the package the run currently has in flight.
 *
 * The mapping goes through the package the backend marked `downloading` rather than by parsing the
 * path, because `updatePackageState(item.packageId(), "downloading")` is set on the same callback
 * that writes this message — so the two cannot disagree. With no in-flight package to name, the
 * original message stands: a wrong friendly label is worse than an ugly true one.
 */
export function friendlyInstallMessage(
  message: string | null | undefined,
  packages: AiInstallStatus['packages'] | null | undefined,
): string {
  const raw = message ?? '';
  const match = /^Downloading\s+(.+?)\.\.\.(.*)$/s.exec(raw);
  if (!match) return raw;
  const active = (packages ?? []).find(
    (p) => p.state === 'downloading' || p.state === 'verifying',
  );
  const label = active?.label;
  if (!label || label.length === 0) return raw;
  return `Downloading ${label}…${match[2] ?? ''}`;
}
