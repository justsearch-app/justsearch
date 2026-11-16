// SPDX-License-Identifier: Apache-2.0
/**
 * installFlow.ts — slice 7b: the words of AI Brain's install flow (fm-11 consent, fm-12 progress,
 * fm-12b a stopped download), derived from what the Engine publishes.
 *
 * Parts carry the names of the page's steps. Sizes use a formatting context of their own (MB without
 * decimals; GB with two decimals for a part and one for a total), binary like the shared formatter,
 * which stays as it is for its other callers.
 *
 * A part is "Done" only from positive evidence: the Engine's model presence shows every final file of
 * the part placed, in an observation taken after the run reported the part's last file. The run's own
 * per-file "installed" flips between two files of one part, so it is never enough on its own.
 */
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

const MB = 1024 * 1024;
const GB = 1024 * MB;

/** Page step names per package; a package the pictures do not name keeps the Engine's label. */
const PART_NAMES: Record<string, { name: string; note?: string }> = {
  embedding: { name: 'Understand meaning' },
  splade: { name: 'Related words', note: 'helps Understand meaning' },
  reranker: { name: 'Best match first' },
  ner: { name: 'Names of people and places' },
  'citation-scorer': { name: 'Checking answers against sources' },
  chat: { name: 'Answer questions' },
};
const PART_ORDER = Object.keys(PART_NAMES);

export function partName(id: string | null | undefined, label?: string | null): { name: string; note: string | null } {
  const known = id ? PART_NAMES[id] : undefined;
  return known ? { name: known.name, note: known.note ?? null } : { name: label || id || 'Part', note: null };
}

function mb(bytes: number): string {
  return bytes > 0 && bytes < MB ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${Math.round(bytes / MB)} MB`;
}

/** One part's size: 956 MB, 1.19 GB. */
export function formatPartSize(bytes: number): string {
  return bytes >= GB ? `${(bytes / GB).toFixed(2)} GB` : mb(bytes);
}

/** A total: 2.6 GB, 341 MB. */
export function formatTotalSize(bytes: number): string {
  return bytes >= GB ? `${(bytes / GB).toFixed(1)} GB` : mb(bytes);
}

/** "1.3 of 2.6 GB", "120 of 956 MB": both amounts in the unit of the whole. */
export function formatAmountOf(done: number, total: number, kind: 'part' | 'total'): string {
  const d = Math.max(0, Math.min(done, total));
  if (total >= GB) {
    const digits = kind === 'part' ? 2 : 1;
    return `${(d / GB).toFixed(digits)} of ${(total / GB).toFixed(digits)} GB`;
  }
  if (total >= MB) return `${Math.round(d / MB)} of ${Math.round(total / MB)} MB`;
  return `${Math.round(d / 1024)} of ${Math.max(1, Math.round(total / 1024))} KB`;
}

/** "about 24 min left"; only for a positive estimate the Engine reports. */
export function formatTimeLeft(seconds: number | null | undefined): string | null {
  if (typeof seconds !== 'number' || !(seconds > 0)) return null;
  const minutes = Math.max(1, Math.round(seconds / 60));
  return minutes < 90 ? `about ${minutes} min left` : `about ${Math.round(minutes / 60)} h left`;
}

/** What the run is doing when it is not moving bytes (the Engine's phases); null while it downloads. */
export function phaseWords(phase: string | null | undefined): string | null {
  switch (phase) {
    case 'preflight':
    case 'plan':
      return 'Preparing the download';
    case 'apply':
    case 'restart_worker':
    case 'restore_runtime':
    case 'smoke_test':
    case 'activation_required':
      return 'Setting up the downloaded parts';
    default:
      return null;
  }
}

/**
 * RS5 (G3') — what a running install is doing when it is not moving bytes. The Engine marks a part
 * `verifying` while leaving the run's phase at `download`, so a verifying part speaks first.
 */
export function runActivity(status: AiInstallStatus | null | undefined): string | null {
  if (status?.state !== 'running') return null;
  if ((status.packages ?? []).some(p => p.state === 'verifying')) return 'Checking the downloaded files';
  return phaseWords(status.phase);
}

export interface ModelPresencePart { id: string; complete: boolean; presentVariantFiles?: string[] }

/** The shipped registry's packages that carry a model file (each has variants; cuda-runtime has none). */
const MODEL_PACKAGES = new Set([...PART_ORDER, 'chat-compact']);

/**
 * RS2 (G6') — a final model file of this package is on this computer: model presence lists a variant
 * file, or reports complete a package that carries a model (the Engine calls such a package complete
 * only with a variant file present). A package of supporting files only, such as the GPU runtime, is
 * complete without any model file and never counts.
 */
export function modelFilePresent(part: ModelPresencePart): boolean {
  return (part.presentVariantFiles?.length ?? 0) > 0 || (part.complete === true && MODEL_PACKAGES.has(part.id));
}

/** RS8 — the final model files model presence shows, per part (null: complete, its files not listed). */
export type ModelFiles = ReadonlyMap<string, ReadonlySet<string> | null>;

export function modelFiles(parts: readonly ModelPresencePart[]): ModelFiles {
  const files = new Map<string, ReadonlySet<string> | null>();
  for (const p of parts) {
    if (!modelFilePresent(p)) continue;
    files.set(p.id, (p.presentVariantFiles?.length ?? 0) > 0 ? new Set(p.presentVariantFiles) : null);
  }
  return files;
}

/** RS8 — a final model file seen earlier is still on this computer. */
export function modelFileKept(before: ModelFiles, now: ModelFiles): boolean {
  for (const [id, files] of before) {
    const still = now.get(id);
    if (still === undefined) continue;
    if (files === null || still === null || [...files].some(f => still.has(f))) return true;
  }
  return false;
}

/** When the page saw each part reported installed, and when its last model-presence request began. */
export interface DoneEvidence {
  installedSeenAt: ReadonlyMap<string, number>;
  presenceRequestedAt: number | null;
  presence: readonly ModelPresencePart[] | null;
}

export interface PartRow {
  id: string;
  name: string;
  /** Done / "a of b" / Waiting / Stopped… / a stage's blocked reason. */
  words: string;
  done: boolean;
  /** 0..100 for a part with bytes under way, else null. */
  percent: number | null;
}

type Pkg = NonNullable<AiInstallStatus['packages']>[number];

/** Every final file of the part placed, seen after the run reported its last file. */
export function partDone(pkg: Pkg, evidence: DoneEvidence): boolean {
  const id = pkg.packageId ?? '';
  const seen = evidence.installedSeenAt.get(id);
  if (pkg.state !== 'installed' || seen === undefined || evidence.presenceRequestedAt === null) return false;
  if (evidence.presenceRequestedAt < seen) return false;
  return evidence.presence?.some(p => p.id === id && p.complete === true) === true;
}

/** The run's parts in the page's step order; parts the plan skipped are not part of this install. */
export function runParts(status: AiInstallStatus | null | undefined): Pkg[] {
  const parts = (status?.packages ?? []).filter(p => p.state !== 'skipped' && (p.packageId ?? '') !== '');
  const rank = (p: Pkg) => {
    const i = PART_ORDER.indexOf(p.packageId ?? '');
    return i < 0 ? PART_ORDER.length : i;
  };
  return parts.map((p, i) => ({ p, i })).sort((a, b) => rank(a.p) - rank(b.p) || a.i - b.i).map(x => x.p);
}

export function composePartRows(
  status: AiInstallStatus | null | undefined,
  evidence: DoneEvidence,
  view: 'running' | 'stopped',
): PartRow[] {
  const blocked = new Map((status?.stages ?? []).filter(s => (s.blockedReason ?? '').trim() !== '')
    .map(s => [s.stage ?? '', (s.blockedReason ?? '').trim()]));
  return runParts(status).map(p => {
    const { name } = partName(p.packageId, p.label);
    const done = partDone(p, evidence);
    const total = p.bytesTotal ?? 0;
    const got = Math.max(0, Math.min(p.bytesDownloaded ?? 0, total));
    const amount = total > 0 ? formatAmountOf(got, total, 'part') : null;
    const percent = !done && total > 0 && got > 0 ? Math.min(100, Math.floor((got / total) * 100)) : null;
    let words: string;
    if (done) words = 'Done';
    else if (view === 'running') {
      words = p.state === 'pending' ? blocked.get(p.stage ?? '') ?? 'Waiting'
        : p.state === 'failed' ? 'Stopped'
        : amount ?? 'Waiting';
    } else {
      words = p.state === 'pending' ? blocked.get(p.stage ?? '') ?? 'Waiting'
        : got > 0 && amount ? `Stopped (had ${amount})` : 'Stopped';
    }
    return { id: p.packageId ?? '', name, words, done, percent };
  });
}

/** A failed run is shown as a stopped download while some part of it is not on this computer. */
export function stoppedRunId(status: AiInstallStatus | null | undefined, evidence: DoneEvidence): string | null {
  if (status?.state !== 'failed') return null;
  const parts = runParts(status);
  if (parts.length === 0 || parts.every(p => partDone(p, evidence))) return null;
  return `${status.startedAtEpochMs ?? 0}:${status.state}`;
}

/**
 * G5', fm-12b — the stopped view and the top bar's status entry follow one rule. A failed run is a
 * stopped download while a part of it is missing: one the run did not report placed, or one the AI
 * Brain page, from model presence, shows as not done. "Cancel install" closes that run's stopped view
 * in this window (until a reload); a later run is a different run and shows again.
 */
let dismissedRun: string | null = null;
let pageStoppedRun: string | null = null;
const stoppedListeners = new Set<() => void>();
const notifyStopped = () => { for (const listener of [...stoppedListeners]) listener(); };

export function subscribeStoppedRun(listener: () => void): () => void {
  stoppedListeners.add(listener);
  return () => stoppedListeners.delete(listener);
}
export function dismissedStoppedRun(): string | null { return dismissedRun; }
export function dismissStoppedRun(id: string): void {
  if (dismissedRun === id) return;
  dismissedRun = id;
  notifyStopped();
}
/** The stopped run the AI Brain page shows from its own evidence, or null. */
export function reportPageStoppedRun(id: string | null): void {
  if (pageStoppedRun === id) return;
  pageStoppedRun = id;
  notifyStopped();
}
/** Test isolation: the window's dismissal and the page's report start empty. */
export function __resetStoppedRunForTest(): void { dismissedRun = null; pageStoppedRun = null; stoppedListeners.clear(); }

/**
 * A failed run as the top bar sees it: 'stopped' while its stopped view stands, 'dismissed' after
 * "Cancel install" closed it, null for any other run (a failure after every part was placed stays a
 * failed install).
 */
export function stoppedInstall(status: AiInstallStatus | null | undefined): 'stopped' | 'dismissed' | null {
  if (status?.state !== 'failed') return null;
  const id = `${status.startedAtEpochMs ?? 0}:${status.state}`;
  if (dismissedRun === id) return 'dismissed';
  const parts = runParts(status);
  const missing = parts.length > 0 && parts.some(p => p.state !== 'installed');
  return missing || pageStoppedRun === id ? 'stopped' : null;
}
