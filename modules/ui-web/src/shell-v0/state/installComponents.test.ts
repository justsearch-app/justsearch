/**
 * Tempdoc 840 Phase 5 — the composers behind the user-facing install.
 *
 * These pin what a render test cannot state as sharply: `unavailable` is not `declined`.
 */

import { describe, expect, it } from 'vitest';
import {
  NECESSITY_CONSEQUENCE,
  composeComponentGroups,
  composeComponentRow,
  composeComponentSummary,
  composeStageRows,
  friendlyInstallMessage,
  toNecessity,
  type InstallComponentEstimate,
} from './installComponents.js';
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

const EMBEDDING: InstallComponentEstimate = {
  id: 'embedding',
  label: 'Search embeddings',
  description: 'Turns your documents into vectors so search can find them by meaning.',
  tier: 'retrieval-core',
  necessity: 'required',
  declinable: false,
  declined: false,
  totalBytes: 1_200_000_000,
  downloadBytes: 1_200_000_000,
  state: 'to-download',
};

const RERANKER: InstallComponentEstimate = {
  id: 'reranker',
  label: 'Search reranker',
  description: 'Re-orders the top results so the best answer is first.',
  tier: 'retrieval-enrichment',
  necessity: 'improves-results',
  declinable: true,
  declined: false,
  totalBytes: 340_000_000,
  downloadBytes: 340_000_000,
  state: 'to-download',
};

const CUDA: InstallComponentEstimate = {
  id: 'cuda-runtime',
  label: 'GPU runtime libraries',
  description: 'CUDA libraries the GPU models run on.',
  tier: 'runtime',
  necessity: 'infrastructure',
  declinable: false,
  declined: false,
  totalBytes: 2_000_000_000,
  downloadBytes: 0,
  state: 'unavailable',
  unavailableReason: 'No CUDA-capable GPU was detected on this machine.',
};

const CHAT: InstallComponentEstimate = {
  id: 'chat',
  label: 'Chat model',
  description: 'Answers questions and writes summaries about your documents.',
  tier: 'llm',
  necessity: 'adds-feature',
  declinable: true,
  declined: true,
  totalBytes: 6_400_000_000,
  downloadBytes: 0,
  state: 'declined',
};

describe('composeComponentRow — opt-out, and unavailable is not declined', () => {
  it('starts every supported component SELECTED (opt-out, not opt-in)', () => {
    expect(composeComponentRow(RERANKER).selected).toBe(true);
    expect(composeComponentRow(EMBEDDING).selected).toBe(true);
  });

  it('offers a control only where the user may actually say no', () => {
    expect(composeComponentRow(RERANKER).togglable).toBe(true);
    expect(composeComponentRow(EMBEDDING).togglable).toBe(false);
    expect(composeComponentRow(CUDA).togglable).toBe(false);
  });

  it('renders unavailable hardware with a REASON and NO control — an unticked box implies a choice', () => {
    const row = composeComponentRow(CUDA);
    expect(row.state).toBe('unavailable');
    expect(row.togglable).toBe(false);
    expect(row.availability).toEqual({
      kind: 'unavailable',
      reason: 'No CUDA-capable GPU was detected on this machine.',
    });
    expect(row.stateText).toBe('Not supported here');
  });

  it('keeps the control on a DECLINED component so the choice can be undone', () => {
    const row = composeComponentRow(CHAT);
    expect(row.selected).toBe(false);
    expect(row.togglable).toBe(true);
    expect(row.availability.kind).toBe('available');
  });

  it('never marks a declinable component unavailable — the two states must not be one presentation', () => {
    expect(composeComponentRow(CHAT).availability.kind).not.toBe('unavailable');
  });

  it('falls back to a reason even when the planner sent an empty one (no blank explanation)', () => {
    const row = composeComponentRow({ ...CUDA, unavailableReason: '   ' });
    expect(row.availability.kind).toBe('unavailable');
    expect(row.availability.kind === 'unavailable' && row.availability.reason.length).toBeGreaterThan(0);
  });

  it('withholds a size rather than printing "0 B" when the registry declares none', () => {
    expect(composeComponentRow({ ...RERANKER, totalBytes: 0 }).sizeText).toBeNull();
  });

  it('treats an unknown necessity as REQUIRED — a category this build does not know is not switch-off-able', () => {
    expect(toNecessity('some-future-category')).toBe('required');
    expect(toNecessity(undefined)).toBe('required');
  });
});

describe('composeComponentGroups — grouped by consequence, in consequence order', () => {
  it('groups by necessity in order and drops empty categories', () => {
    const groups = composeComponentGroups([CHAT, CUDA, RERANKER, EMBEDDING]);
    expect(groups.map((g) => g.necessity)).toEqual([
      'required',
      'improves-results',
      'adds-feature',
      'infrastructure',
    ]);
    expect(groups[0]!.rows.map((r) => r.id)).toEqual(['embedding']);
  });

  it('states what is LOST, not what the category is called', () => {
    const groups = composeComponentGroups([RERANKER]);
    // A clause now, not a sentence (840 post-review C.3) — it rides the group heading line. Still the
    // CONSEQUENCE ("results are worse"), which is what this pinned; the category name is elsewhere.
    expect(groups[0]!.consequence).toBe('results are worse without these');
    expect(groups[0]!.heading).toBe('Improves results');
    expect(NECESSITY_CONSEQUENCE['adds-feature']).toContain('chat');
  });

  it('is empty (not a fabricated placeholder) when the preview has no components', () => {
    expect(composeComponentGroups(undefined)).toEqual([]);
    expect(composeComponentGroups([])).toEqual([]);
  });
});

/**
 * The four real `improves-results` members — the declinable enrichment tier, which is where the user
 * actually chooses. Their footprints span 23 MB to 616 MB; scaled against the 6.3 GB chat model they
 * all collapse into indistinguishable slivers (7.6% / 9.7% / 4.1% / 0.4%), which is the defect the
 * within-group scale exists to fix.
 */
const SPLADE: InstallComponentEstimate = { ...RERANKER, id: 'splade', totalBytes: 481_500_000 };
const NER: InstallComponentEstimate = { ...RERANKER, id: 'ner', totalBytes: 259_900_000 };
const CITATION: InstallComponentEstimate = {
  ...RERANKER,
  id: 'citation-scorer',
  totalBytes: 22_600_000,
};
const BIG_RERANKER: InstallComponentEstimate = { ...RERANKER, totalBytes: 616_300_000 };

/**
 * 840 post-review B — the size bar. A bar is a COMPARISON, and the two rules here are both about what
 * it may honestly claim: it is scaled against the row's PEERS (its own necessity group), and it is not
 * drawn at all where there are no peers to compare against.
 */
describe('group subtotal — present only where it AGGREGATES more than one component', () => {
  const subtotals = (cs: InstallComponentEstimate[]) =>
    Object.fromEntries(composeComponentGroups(cs).map((g) => [g.necessity, g.subtotal]));

  it('sums ONLY its own group members', () => {
    const s = subtotals([EMBEDDING, BIG_RERANKER, SPLADE, NER, CITATION, CUDA, CHAT]);
    // 616.3 + 481.5 + 259.9 + 22.6 MB = 1.3803 GB — the enrichment tier alone, not the 9 GB list.
    expect(s['improves-results']).toBe('4 components · 1.29 GB');
    // The three one-member groups get NO subtotal: their single row already states that figure, and
    // "1 component · 1.12 GB" directly above "1.12 GB" is the same number twice.
    expect(s['required']).toBeNull();
    expect(s['adds-feature']).toBeNull();
    expect(s['infrastructure']).toBeNull();
  });

  it('is withheld from a one-member group — the row below is already the subtotal', () => {
    for (const g of composeComponentGroups([EMBEDDING, CHAT, CUDA])) {
      expect(g.rows).toHaveLength(1);
      expect(g.subtotal).toBeNull();
    }
  });

  it('drops the footprint clause rather than printing "0 B" when no member declares a size', () => {
    const s = subtotals([
      { ...RERANKER, id: 'a', totalBytes: 0 },
      { ...RERANKER, id: 'b', totalBytes: undefined },
    ]);
    expect(s['improves-results']).toBe('2 components');
  });

  it('counts every member, installed or not — the cost of a CATEGORY, not of the current disk', () => {
    // Deliberately distinct from `composeComponentSummary`, which is a claim about what is on disk.
    const s = subtotals([BIG_RERANKER, { ...SPLADE, state: 'declined', declined: true }]);
    expect(s['improves-results']).toBe('2 components · 1.02 GB');
  });
});

/**
 * 840 post-review C.2 — "Installed" was printed on all seven rows: the resting state of a working
 * machine, repeated until it crowded out the facts that vary. Silence is now the installed state, so
 * ANY text in that slot means something deviates.
 */
describe('stateText — written only where the state DEVIATES from installed', () => {
  it('says nothing at all for an installed component', () => {
    expect(composeComponentRow({ ...EMBEDDING, state: 'installed' }).stateText).toBeNull();
  });

  it('names every deviation, so its presence is the signal', () => {
    expect(composeComponentRow({ ...EMBEDDING, state: 'to-download' }).stateText).toBe(
      'Will download',
    );
    expect(composeComponentRow(CHAT).stateText).toBe('Off');
    expect(composeComponentRow(CUDA).stateText).toBe('Not supported here');
    expect(composeComponentRow({ ...RERANKER, state: 'not-in-mode' }).stateText).toBe(
      'Not in this mode',
    );
  });

  it('keeps an UNRECOGNIZED state loud rather than silently reading as installed', () => {
    // `toComponentState` falls back to `to-download`, which must still produce text — a fallback that
    // silenced the row would claim "installed" for a state this build cannot interpret.
    expect(composeComponentRow({ ...EMBEDDING, state: 'some-future-state' }).stateText).toBe(
      'Will download',
    );
  });
});

/** 840 post-review E — the one line that replaced seven "Installed"s. */
describe('composeComponentSummary — the stock-take that replaced the repetition', () => {
  const installed = (c: InstallComponentEstimate) => ({ ...c, state: 'installed' });

  it('counts what is installed and adds up what it costs on disk', () => {
    expect(composeComponentSummary([installed(EMBEDDING), installed(CHAT)])).toBe(
      '2 installed · 7.08 GB on disk',
    );
  });

  it('counts ONLY installed components — a pending download has not spent disk yet', () => {
    expect(composeComponentSummary([installed(EMBEDDING), RERANKER, CUDA, CHAT])).toBe(
      '1 installed · 1.12 GB on disk',
    );
  });

  it('stays silent on a machine with nothing installed, rather than reading "0 installed"', () => {
    expect(composeComponentSummary([EMBEDDING, RERANKER])).toBeNull();
    expect(composeComponentSummary([])).toBeNull();
    expect(composeComponentSummary(undefined)).toBeNull();
  });

  it('drops the footprint clause rather than printing "0 B on disk"', () => {
    expect(composeComponentSummary([installed({ ...EMBEDDING, totalBytes: 0 })])).toBe(
      '1 installed',
    );
  });
});

const RUNNING: AiInstallStatus = {
  state: 'running',
  currentStage: 'enrichment',
  readyCapabilities: ['retrieval-core', 'runtime'],
  stages: [
    {
      stage: 'core',
      label: 'Search core',
      state: 'completed',
      capabilities: ['retrieval-core', 'runtime'],
      totalBytes: 1_000,
      downloadedBytes: 1_000,
    },
    {
      stage: 'enrichment',
      label: 'Retrieval enrichment',
      state: 'running',
      capabilities: ['retrieval-enrichment'],
      totalBytes: 400,
      downloadedBytes: 100,
    },
    {
      stage: 'chat',
      label: 'Chat & AI answers',
      state: 'blocked',
      capabilities: ['llm'],
      totalBytes: 6_000,
      downloadedBytes: 0,
      blockedReason: 'Not enough disk space: 6.0 GB needed, 2.1 GB free.',
    },
  ],
};

describe('composeStageRows — staged progress', () => {
  it('marks the stage the run is actually in', () => {
    const rows = composeStageRows(RUNNING);
    expect(rows.map((r) => r.current)).toEqual([false, true, false]);
  });

  it('computes a percent only where a byte total exists', () => {
    const rows = composeStageRows(RUNNING);
    expect(rows[1]!.percent).toBe(25);
    expect(composeStageRows({ stages: [{ stage: 'x', label: 'X', state: 'pending' }] })[0]!.percent)
      .toBeNull();
  });

  it('surfaces a blocked stage as an ACTIONABLE reason, not a generic failure', () => {
    const blocked = composeStageRows(RUNNING)[2]!;
    expect(blocked.stateText).toBe('Cannot start');
    expect(blocked.blockedReason).toBe('Not enough disk space: 6.0 GB needed, 2.1 GB free.');
  });

});

describe('friendlyInstallMessage — U5: a path is not a name', () => {
  const packages: AiInstallStatus['packages'] = [
    { packageId: 'embedding', label: 'Search embeddings', state: 'downloading' },
    { packageId: 'chat', label: 'Chat model', state: 'pending' },
  ];

  it('replaces the target path with the in-flight component label', () => {
    expect(
      friendlyInstallMessage('Downloading onnx/gte-multilingual-base/model.onnx...', packages),
    ).toBe('Downloading Search embeddings…');
  });

  it('keeps the retry suffix the backend appends', () => {
    expect(
      friendlyInstallMessage(
        'Downloading onnx/gte-multilingual-base/model.onnx... (attempt 2 of 3)',
        packages,
      ),
    ).toBe('Downloading Search embeddings… (attempt 2 of 3)');
  });

  it('leaves the message untouched when no package is in flight — an ugly truth beats a wrong label', () => {
    expect(friendlyInstallMessage('Downloading onnx/model.onnx...', [])).toBe(
      'Downloading onnx/model.onnx...',
    );
    expect(friendlyInstallMessage('Downloading onnx/model.onnx...', undefined)).toBe(
      'Downloading onnx/model.onnx...',
    );
  });

  it('leaves every other phase message alone', () => {
    expect(friendlyInstallMessage('Restoring AI runtime...', packages)).toBe(
      'Restoring AI runtime...',
    );
    expect(friendlyInstallMessage(null, packages)).toBe('');
  });
});
