// SPDX-License-Identifier: Apache-2.0
//
// Tempdoc 663 — unit coverage for `computeAiEngineVerdict` (the OBSERVED AI-engine lifecycle
// derivation, computed once in `aiStateStore.ts`) and `applyLocalIntent` (the small, surface-local
// optimistic-intent overlay `BrainSurface` applies on top). Design pass 2 (2026-07-01) split the
// original single ladder into these two functions; this file's structure mirrors that split.

import { describe, it, expect } from 'vitest';
import {
  computeAiEngineVerdict,
  applyLocalIntent,
  presentAiEngineVerdict,
  type AiEngineObservedInput,
  type AiEngineVerdict,
  type AiEngineKind,
} from './aiVerdict.js';
import { known, UNKNOWN, type Maybe } from './known.js';
import type { AiRuntime, InstallStatus, AiRuntimeStatus } from './aiStateStore.js';

function runtime(overrides: Partial<AiRuntime> = {}): AiRuntime {
  return {
    mode: 'offline',
    modelId: null,
    modelLabel: null,
    contextWindow: null,
    contextWindowDerived: null,
    gpu: null,
    installed: UNKNOWN as Maybe<boolean>,
    installing: UNKNOWN as Maybe<boolean>,
    loadStartedAtMs: null,
    ...overrides,
  };
}

function input(overrides: Partial<AiEngineObservedInput> = {}): AiEngineObservedInput {
  return {
    installStatus: null,
    runtimeStatus: null,
    runtime: runtime(),
    reachable: true,
    // Tempdoc 807 — the default is a LIVE observation, so every pre-807 case below keeps asserting the
    // behaviour it was written for; the liveness cases opt out explicitly.
    snapshotLive: true,
    ...overrides,
  };
}

describe('computeAiEngineVerdict (observed axes only — no local intent)', () => {
  it('no data yet, backend reachable → calm "connecting", cause "checking" (no-data ≠ not-installed)', () => {
    const v = computeAiEngineVerdict(input({ reachable: true }));
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'checking' });
  });

  it('no data yet, backend NOT reachable → still calm "connecting", cause "stale-poll" (never a confident negative)', () => {
    const v = computeAiEngineVerdict(input({ reachable: false }));
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'stale-poll' });
  });

  it('install status known, not installed → "not_installed", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false };
    const v = computeAiEngineVerdict(input({ installStatus }));
    expect(v.kind).toBe('not_installed');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  // ── Sandbox round 8: a cancelled multi-GB download. The cancel dialog promises the bytes stay on
  //    disk and the next install resumes; the Brain surface then said "Not Installed — Install AI
  //    models to get started" over 1.2 GB of them. `not_installed` is a SETTLED negative, so this was
  //    not vagueness, it was a confident false claim contradicting the app's own promise. ──

  it('bytes staged on disk → "paused", not a settled "not_installed"', () => {
    const installStatus: InstallStatus = {
      state: 'idle',
      phase: 'idle',
      installedFully: false,
      resumableBytes: 1_140_000_000,
    };
    const v = computeAiEngineVerdict(input({ installStatus }));
    expect(v.kind).toBe('paused');
    expect(v.stability).toEqual({ kind: 'settled' });
    expect(v.resumableBytes).toBe(1_140_000_000);
  });

  it('paused is derived from the DISK-probed byte count, not from state:"cancelled"', () => {
    // The state a restart erases (`cancelled`) with no staged bytes must NOT read as paused...
    const forgotten: InstallStatus = { state: 'cancelled', phase: 'download', installedFully: false };
    expect(computeAiEngineVerdict(input({ installStatus: forgotten })).kind).toBe('not_installed');
    // ...while the state a restart RESTORES (`idle`) with staged bytes must — this is exactly the
    // returning-user case, and keying on `state` would invert both answers.
    const restarted: InstallStatus = {
      state: 'idle',
      phase: 'idle',
      installedFully: false,
      resumableBytes: 512,
    };
    expect(computeAiEngineVerdict(input({ installStatus: restarted })).kind).toBe('paused');
  });

  it('a running install outranks staged bytes (the download is live, not paused)', () => {
    const installStatus: InstallStatus = {
      state: 'running',
      phase: 'download',
      resumableBytes: 1_140_000_000,
    };
    expect(computeAiEngineVerdict(input({ installStatus })).kind).toBe('installing');
  });

  it('zero staged bytes leaves the honest "not_installed" untouched', () => {
    const installStatus: InstallStatus = {
      state: 'idle',
      phase: 'idle',
      installedFully: false,
      resumableBytes: 0,
    };
    expect(computeAiEngineVerdict(input({ installStatus })).kind).toBe('not_installed');
  });

  it('install running → "installing", provisional, regardless of other axes', () => {
    const installStatus: InstallStatus = { state: 'running', phase: 'downloading' };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'online' }) }),
    );
    expect(v.kind).toBe('installing');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'installing' });
  });

  it('install failed → "install_failed", settled, surfaces the error text (Investigation §E: previously unrepresented)', () => {
    const installStatus: InstallStatus = {
      state: 'failed',
      phase: 'idle',
      lastError: 'disk full',
    };
    const v = computeAiEngineVerdict(input({ installStatus }));
    expect(v.kind).toBe('install_failed');
    expect(v.stability).toEqual({ kind: 'settled' });
    expect(v.installFailure).toBe('disk full');
  });

  it('installed, runtime online → "online", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'online' }) }),
    );
    expect(v.kind).toBe('online');
  });

  it('runtime online is proof of an installed engine even if installStatus itself is stale/null', () => {
    const v = computeAiEngineVerdict(input({ installStatus: null, runtime: runtime({ mode: 'online' }) }));
    expect(v.kind).toBe('online');
  });

  it('installed, runtime indexing → "indexing", settled (Investigation §E: previously fell through to "offline")', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'indexing' }) }),
    );
    expect(v.kind).toBe('indexing');
  });

  it('legacy indexing + chatEnabledSpec false → awaitingChatEnable true (734 round 5 finding 3 fix, extended to the legacy branch: a current backend can land here during the pre-reconciler-attach boot window per BootstrapProjections)', () => {
    const v = computeAiEngineVerdict(
      input({ runtime: runtime({ mode: 'indexing' }), chatEnabledSpec: false }),
    );
    expect(v.kind).toBe('indexing');
    expect(v.awaitingChatEnable).toBe(true);
  });

  it('legacy indexing + chatEnabledSpec true → awaitingChatEnable false (genuinely still indexing, not awaiting a click)', () => {
    const v = computeAiEngineVerdict(
      input({ runtime: runtime({ mode: 'indexing' }), chatEnabledSpec: true }),
    );
    expect(v.kind).toBe('indexing');
    expect(v.awaitingChatEnable).toBe(false);
  });

  it('runtime starting (explicit live-load signal) → "starting", provisional cause "starting"', () => {
    const v = computeAiEngineVerdict(input({ runtime: runtime({ mode: 'starting' }) }));
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'starting' });
  });

  it('installed via onnxFeatures.modelActive (runtimeStatus-derived), mode transitioning → "starting", cause "starting"', () => {
    const runtimeStatus: AiRuntimeStatus = {
      // `id` (not `feature`) is the real wire field — AiRuntimeStatusResponse.OnnxFeatureStatus.
      // The FE type carried a phantom `feature`/`modelDescription` pair until tempdoc 805 G.3.
      onnxFeatures: [{ id: 'llm', modelActive: true }],
    };
    const v = computeAiEngineVerdict(
      input({ runtimeStatus, runtime: runtime({ mode: 'transitioning' }) }),
    );
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'starting' });
  });

  it('installed, runtime offline, reachable, no in-flight signal → "offline", settled', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(input({ installStatus, reachable: true }));
    expect(v.kind).toBe('offline');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('Design pass 2 — installed, runtime offline, NOT reachable → "offline" stays but becomes provisional (stale-poll), not a confident negative', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(input({ installStatus, reachable: false }));
    expect(v.kind).toBe('offline');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'stale-poll' });
  });

  it('known-unknown install data via runtime.installed=known(true) counts as "have install data"', () => {
    const v = computeAiEngineVerdict(input({ runtime: runtime({ installed: known(true) }) }));
    // installed=known(true) alone does not satisfy the `installed` boolean (that requires
    // installStatus.installedFully or onnxFeatures) — but it DOES count as "have install data",
    // so the calm "connecting" state must resolve to a settled "not_installed" rather than stay
    // stuck, mirroring the original ladder's haveInstallData check.
    expect(v.kind).toBe('not_installed');
  });
});

describe('computeAiEngineVerdict — runtime-authority engine axis (tempdoc 737 §12b/§12c)', () => {
  it('engineState Healthy + chat enabled → "online" (preferred over runtime.mode)', () => {
    // runtime.mode deliberately DISAGREES (offline) to prove the authority is preferred.
    const v = computeAiEngineVerdict(
      input({ engineState: 'Healthy', chatEnabledSpec: true, runtime: runtime({ mode: 'offline' }) }),
    );
    expect(v.kind).toBe('online');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('soft-off: engineState Healthy + chatEnabledSpec=false + procedure active → "background", NOT "online"', () => {
    const v = computeAiEngineVerdict(
      input({
        engineState: 'Healthy',
        chatEnabledSpec: false,
        procedure: 'VDU_BATCH',
        runtime: runtime({ mode: 'online' }),
      }),
    );
    expect(v.kind).toBe('background');
    expect(v.kind).not.toBe('online');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('chat disabled but NO procedure running → plain "online" (not the background state)', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Healthy', chatEnabledSpec: false, procedure: '' }),
    );
    expect(v.kind).toBe('online');
  });

  it('engineState Down + gpu-yielded-to-indexing → "indexing"', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Down', engineReason: 'gpu-yielded-to-indexing' }),
    );
    expect(v.kind).toBe('indexing');
  });

  it('indexing + chatEnabledSpec false → awaitingChatEnable true (tempdoc 734 round 5 finding 3)', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Down', engineReason: 'gpu-yielded-to-indexing', chatEnabledSpec: false }),
    );
    expect(v.kind).toBe('indexing');
    expect(v.awaitingChatEnable).toBe(true);
  });

  it('indexing + chatEnabledSpec true → awaitingChatEnable false (genuinely still indexing, not awaiting a click)', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Down', engineReason: 'gpu-yielded-to-indexing', chatEnabledSpec: true }),
    );
    expect(v.kind).toBe('indexing');
    expect(v.awaitingChatEnable).toBe(false);
  });

  it('engineState Down (other reason) + installed + reachable → "offline" (falls through to shared logic)', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const v = computeAiEngineVerdict(
      input({ engineState: 'Down', engineReason: 'engine-down', installStatus, reachable: true }),
    );
    expect(v.kind).toBe('offline');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('engineState Starting → "starting", provisional', () => {
    const v = computeAiEngineVerdict(input({ engineState: 'Starting' }));
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'starting' });
  });

  it('engineState Recovering → "starting", provisional (crash-recovery reads as in-flux)', () => {
    const v = computeAiEngineVerdict(input({ engineState: 'Recovering' }));
    expect(v.kind).toBe('starting');
  });

  it('install axis still wins over the engine axis (installing beats a stale Healthy)', () => {
    const installStatus: InstallStatus = { state: 'running', phase: 'downloading' };
    const v = computeAiEngineVerdict(
      input({ installStatus, engineState: 'Healthy', chatEnabledSpec: true }),
    );
    expect(v.kind).toBe('installing');
  });

  it('absent engineState → legacy runtime.mode fallback (defensive for old backends)', () => {
    const v = computeAiEngineVerdict(input({ runtime: runtime({ mode: 'online' }) }));
    expect(v.kind).toBe('online');
  });

  it('background verdict presents an honest headline/body (chat is off, engine working)', () => {
    const p = presentAiEngineVerdict({
      kind: 'background',
      stability: { kind: 'settled' },
      installFailure: null,
    });
    expect(p.headline).toBe('Background processing');
    expect(p.body).toContain('chat is off');
    expect(p.tone).toBe('warning');
  });
});

/**
 * Tempdoc 807 Part A (round-13 R13-F2) — the headline defect. With BOTH java processes dead, the Brain
 * surface reported a green "Online / Chat and summaries ready." indefinitely, across two reproductions.
 * It was minted here: `engineState` is read off the RETAINED snapshot, so after the backend dies it
 * still says 'Healthy', and this function turned that past measurement into a SETTLED present-tense
 * capability claim. Each case below has an anti-regression twin proving a live observation is untouched.
 */
describe('computeAiEngineVerdict — a retained observation is not a present-tense claim (807 Part A)', () => {
  const UNCONFIRMED = { kind: 'provisional', cause: 'stale-poll' } as const;

  it('THE round-13 sentence: engineState Healthy + snapshot NOT live → never a settled "online"', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Healthy', chatEnabledSpec: true, snapshotLive: false, reachable: false }),
    );
    expect(v.kind).not.toBe('online');
    expect(v.stability).not.toEqual({ kind: 'settled' });
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual(UNCONFIRMED);
  });

  it('THE round-13 sentence, at the presentation layer: no "Online", no "Chat and summaries ready.", no green', () => {
    const p = presentAiEngineVerdict(
      computeAiEngineVerdict(
        input({ engineState: 'Healthy', chatEnabledSpec: true, snapshotLive: false, reachable: false }),
      ),
    );
    expect(p.headline).not.toBe('Online');
    expect(p.body).not.toBe('Chat and summaries ready.');
    expect(p.tone).not.toBe('success');
    expect(p.headline).toBe('Connecting…');
  });

  it('ANTI-REGRESSION: engineState Healthy + snapshot live → still EXACTLY settled "online"', () => {
    const v = computeAiEngineVerdict(
      input({ engineState: 'Healthy', chatEnabledSpec: true, snapshotLive: true }),
    );
    expect(v).toEqual({ kind: 'online', stability: { kind: 'settled' }, installFailure: null });
    expect(presentAiEngineVerdict(v).body).toBe('Chat and summaries ready.');
  });

  it('soft-off precedence is intact: Healthy + chat off + procedure, snapshot LIVE → "background", not "online" (737 §15 decision 1)', () => {
    const v = computeAiEngineVerdict(
      input({
        engineState: 'Healthy',
        chatEnabledSpec: false,
        procedure: 'VDU_BATCH',
        snapshotLive: true,
      }),
    );
    expect(v.kind).toBe('background');
    expect(v.stability).toEqual({ kind: 'settled' });
  });

  it('the soft-off arm is an engine claim too: chat off + procedure, snapshot NOT live → unconfirmed, and still never "online"', () => {
    const v = computeAiEngineVerdict(
      input({
        engineState: 'Healthy',
        chatEnabledSpec: false,
        procedure: 'VDU_BATCH',
        snapshotLive: false,
        reachable: false,
      }),
    );
    expect(v.kind).not.toBe('online');
    expect(v.kind).toBe('connecting');
    expect(v.stability).toEqual(UNCONFIRMED);
  });

  // The sibling arms audited alongside the photographed one: each mints a claim about a RUNNING
  // process from the same retained snapshot, so each carries the same gate, with the same twin.
  it.each([
    ['authored indexing (Down + gpu-yielded)', { engineState: 'Down', engineReason: 'gpu-yielded-to-indexing' }, 'indexing'],
    ['authored starting', { engineState: 'Starting' }, 'starting'],
    ['authored recovering', { engineState: 'Recovering' }, 'starting'],
    ['legacy runtime.mode online', { runtime: runtime({ mode: 'online' }) }, 'online'],
    ['legacy runtime.mode indexing', { runtime: runtime({ mode: 'indexing' }) }, 'indexing'],
    ['legacy runtime.mode starting', { runtime: runtime({ mode: 'starting' }) }, 'starting'],
  ] as const)(
    '%s: live → %s (anti-regression); not live → unconfirmed',
    (_name, overrides, liveKind) => {
      expect(computeAiEngineVerdict(input({ ...overrides, snapshotLive: true })).kind).toBe(liveKind);
      const dead = computeAiEngineVerdict(
        input({ ...overrides, snapshotLive: false, reachable: false }),
      );
      expect(dead.kind).toBe('connecting');
      expect(dead.stability).toEqual(UNCONFIRMED);
    },
  );

  it('legacy transitioning (installed engine, pre-authority backend) carries the gate too', () => {
    const installStatus: InstallStatus = { state: 'idle', phase: 'idle', installedFully: true };
    const live = computeAiEngineVerdict(
      input({ installStatus, runtime: runtime({ mode: 'transitioning' }), snapshotLive: true }),
    );
    expect(live.kind).toBe('starting');
    const dead = computeAiEngineVerdict(
      input({
        installStatus,
        runtime: runtime({ mode: 'transitioning' }),
        snapshotLive: false,
        reachable: false,
      }),
    );
    expect(dead.kind).toBe('connecting');
    expect(dead.stability).toEqual(UNCONFIRMED);
  });

  // The install axis is NOT gated: it describes disk and install history, which a dead backend process
  // does not change, and blanking it would hide the only action a user can take there.
  it('BOUNDARY — the install axis is untouched by liveness (a dead backend does not un-install anything)', () => {
    const running: InstallStatus = { state: 'running', phase: 'downloading' };
    expect(
      computeAiEngineVerdict(input({ installStatus: running, engineState: 'Healthy', snapshotLive: false })).kind,
    ).toBe('installing');
    const failed: InstallStatus = { state: 'failed', phase: 'idle', lastError: 'disk full' };
    expect(computeAiEngineVerdict(input({ installStatus: failed, snapshotLive: false })).kind).toBe(
      'install_failed',
    );
    const notInstalled: InstallStatus = { state: 'idle', phase: 'idle', installedFully: false };
    expect(
      computeAiEngineVerdict(input({ installStatus: notInstalled, snapshotLive: false })).kind,
    ).toBe('not_installed');
  });
});

describe('computeAiEngineVerdict — observed per-model facts beside the lifecycle kind', () => {
  it('names each failed model with its reason, even while the engine is online', () => {
    const runtimeStatus: AiRuntimeStatus = {
      onnxFeatures: [
        { id: 'embed', status: 'active', modelActive: true, executionProvider: 'cuda' },
        { id: 'rerank', status: 'failed', reason: 'model file missing', modelActive: false },
      ],
    };
    const v = computeAiEngineVerdict(input({ runtimeStatus, runtime: runtime({ mode: 'online' }) }));
    expect(v.kind).toBe('online');
    expect(v.failedModels).toEqual([{ id: 'rerank', reason: 'model file missing' }]);
  });

  it('says where the active models run: CPU-only, or a GPU fallback', () => {
    const cpu = computeAiEngineVerdict(input({ runtimeStatus: { onnxFeatures: [
      { id: 'embed', modelActive: true, executionProvider: 'cpu' },
      { id: 'idle', modelActive: false, executionProvider: 'cuda' },
    ] } }));
    expect(cpu.activeModelPlacement).toEqual({ cpuOnly: true, gpuFallback: false });
    const fallback = computeAiEngineVerdict(input({ runtimeStatus: { onnxFeatures: [
      { id: 'embed', modelActive: true, executionProvider: 'cpu', gpuFallback: true },
    ] } }));
    expect(fallback.activeModelPlacement).toEqual({ cpuOnly: false, gpuFallback: true });
  });

  it('carries neither fact when no model failed and none is active', () => {
    const v = computeAiEngineVerdict(input({ runtimeStatus: { onnxFeatures: [{ id: 'embed', status: 'disabled' }] } }));
    expect(v.failedModels).toBeUndefined();
    expect(v.activeModelPlacement).toBeUndefined();
  });
});

describe('applyLocalIntent (surface-local optimistic overlay — BrainSurface only)', () => {
  const settledOffline: AiEngineVerdict = {
    kind: 'offline',
    stability: { kind: 'settled' },
    installFailure: null,
  };

  it('installStarting (local click intent) alone → "installing" immediately, before the poll confirms', () => {
    // Mirrors the original ladder's `busy['install-start']` OR-condition: a click must not wait for
    // the next poll tick to show feedback.
    const v = applyLocalIntent(
      { kind: 'connecting', stability: { kind: 'provisional', cause: 'checking' }, installFailure: null },
      { installStarting: true, switching: false },
    );
    expect(v.kind).toBe('installing');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'installing' });
  });

  it('installStarting takes priority over a stale "failed" observed verdict (retry-click shows "Installing…" at once)', () => {
    const observed: AiEngineVerdict = {
      kind: 'install_failed',
      stability: { kind: 'settled' },
      installFailure: 'disk full',
    };
    const v = applyLocalIntent(observed, { installStarting: true, switching: false });
    expect(v.kind).toBe('installing');
    expect(v.installFailure).toBeNull();
  });

  it('switching intent flag, observed "offline" → "starting", cause "switching-variant"', () => {
    const v = applyLocalIntent(settledOffline, { installStarting: false, switching: true });
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'switching-variant' });
  });

  it('switching intent flag, observed "starting"/cause "starting" (a bare transitioning mode) → cause becomes "switching-variant"', () => {
    const observed: AiEngineVerdict = {
      kind: 'starting',
      stability: { kind: 'provisional', cause: 'starting' },
      installFailure: null,
    };
    const v = applyLocalIntent(observed, { installStarting: false, switching: true });
    expect(v.kind).toBe('starting');
    expect(v.stability).toEqual({ kind: 'provisional', cause: 'switching-variant' });
  });

  it('switching intent flag never overrides a confident non-offline observed kind (e.g. "online")', () => {
    const observed: AiEngineVerdict = { kind: 'online', stability: { kind: 'settled' }, installFailure: null };
    const v = applyLocalIntent(observed, { installStarting: false, switching: true });
    expect(v).toEqual(observed);
  });

  it('no local intent → returns the observed value unchanged', () => {
    const v = applyLocalIntent(settledOffline, { installStarting: false, switching: false });
    expect(v).toEqual(settledOffline);
  });
});

describe('presentAiEngineVerdict (Design pass 3 — the presentation projection)', () => {
  const verdictFor = (kind: AiEngineKind, installFailure: string | null = null): AiEngineVerdict => ({
    kind,
    stability: { kind: 'settled' },
    installFailure,
  });

  // Reuses the exact wording already established in BrainSurface.ts's `statusConfig` for the four
  // kinds that had no prior footer wording, and the footer's OWN pre-existing terse wording (preserved
  // deliberately, not `statusConfig`'s "AI Online"/"AI Offline") for the four kinds it already covered.
  it.each([
    ['not_installed', 'Not Installed', 'neutral'],
    ['paused', 'Download Paused', 'neutral'],
    ['installing', 'Installing…', 'info'],
    // Critical-review fix (2026-07-01) — 'error', not 'neutral': must agree with the
    // `core.ai-engine.failed` toast's `defaultSeverity: 'error'` (messageClasses.ts).
    // FC11a — the status panel's and AI Brain's one sentence for it.
    ['install_failed', 'AI install failed', 'error'],
    ['offline', 'Offline', 'neutral'],
    ['starting', 'Starting…', 'warning'],
    ['connecting', 'Connecting…', 'warning'],
    ['online', 'Online', 'success'],
    ['indexing', 'Indexing', 'warning'],
  ] as const)('kind %s → headline %s, tone %s', (kind, headline, tone) => {
    const p = presentAiEngineVerdict(verdictFor(kind));
    expect(p.headline).toBe(headline);
    expect(p.tone).toBe(tone);
  });

  it('paused states the retained amount in the body — the pause promise made checkable', () => {
    const p = presentAiEngineVerdict({
      kind: 'paused',
      stability: { kind: 'settled' },
      installFailure: null,
      resumableBytes: 1_140_000_000,
    });
    expect(p.headline).toBe('Download Paused');
    expect(p.body).toBe(
      '1.06 GB already downloaded is kept on disk — resuming continues from there.',
    );
  });

  it('paused without a byte count still says the download is paused, never invents a number', () => {
    const p = presentAiEngineVerdict(verdictFor('paused'));
    expect(p.body).toBe('An earlier download is paused — resuming continues from where it stopped.');
  });

  it('install_failed says in words what went wrong and what to do; the service\'s own text is not the body (FC11)', () => {
    const p = presentAiEngineVerdict(verdictFor('install_failed', 'disk full'));
    expect(p.body).toBe('The AI install stopped before it finished. Install again to continue.');
    expect(p.body).not.toContain('disk full');
  });
});
