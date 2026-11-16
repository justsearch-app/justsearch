/**
 * Sandbox round 7 — install-consent composition unit tests.
 *
 * The defect these pin: the consent dialog said "several GB" (a hardcoded string) while the exact
 * total was already in component state, and claimed "you must accept the upstream model terms"
 * without ever showing, naming or linking them. So the assertions are about DERIVATION: the size
 * must come from `planPreview.totalDownloadBytes` (a fixed string cannot satisfy the byte-exact
 * expectations, nor track a changed preview), and every listed package must carry the registry's
 * own licence + termsUrl.
 *
 * Also pins the race behaviour: `refreshAll()` is fire-and-forget from `connectedCallback`, so the
 * dialog can open before either fetch resolves.
 */

import { describe, expect, it } from 'vitest';
import { composeInstallConsent } from './BrainSurface';
import type { AiInstallManifest } from '../../api/domains/packs.js';
import type { InstallPlanPreview } from '../utils/aiInstallPoll.js';

const manifest: AiInstallManifest = {
  schemaVersion: 2,
  purpose: 'test registry',
  packages: [
    {
      id: 'embedding',
      label: 'Embedding model',
      license: 'Apache-2.0',
      termsUrl: 'https://huggingface.co/Alibaba-NLP/gte-multilingual-base',
      tier: 'RETRIEVAL_CORE',
      variants: [
        {
          filename: 'model.onnx',
          sha256: 'AA',
          sizeBytes: 1_000,
          downloadUrl: 'https://example.invalid/model.onnx',
        },
      ],
      supportingFiles: [],
    },
    {
      id: 'chat',
      label: 'Chat model',
      license: 'Apache-2.0',
      termsUrl: 'https://huggingface.co/Qwen/Qwen3.5-9B',
      tier: 'LLM',
      variants: [],
      supportingFiles: [],
    },
    {
      id: 'legacy-untagged',
      label: 'Untagged package',
      license: null,
      termsUrl: null,
      variants: [],
      supportingFiles: [],
    },
  ],
};

const preview: InstallPlanPreview = {
  intent: 'full',
  totalDownloadBytes: 10_890_000_000,
  tiers: [
    { tier: 'retrieval-core', label: 'Core retrieval', includedByIntent: true },
    { tier: 'llm', label: 'Chat & AI answers', includedByIntent: true },
  ],
};

describe('composeInstallConsent', () => {
  it('states the exact download total from the plan preview, not a hardcoded size', () => {
    // 10_890_000_000 B formats to "10 GB".
    //
    // Sandbox round 8 corrected this assertion's rationale (not its value): it used to claim this was
    // "the SAME number the progress screen shows", which is false whenever a download is being
    // resumed. `totalDownloadBytes` is now `InstallPlan.remainingBytes()` — what the network will
    // TRANSFER — while the progress screen counts up to the file-size total, which still includes the
    // bytes already staged on disk. The two differ by exactly `resumableBytes`, which is why the
    // dialog now names that amount on its own line instead of leaving the gap unexplained.
    expect(composeInstallConsent(manifest, preview).downloadTotal).toBe('10 GB');
  });

  it('with bytes an earlier download left staged, states the most it downloads, not that it resumes them (7b G2\')', () => {
    const staged = composeInstallConsent(manifest, {
      ...preview,
      totalDownloadBytes: 9_750_000_000,
      resumableBytes: 1_140_000_000,
    });
    // Reuse of the staged bytes is not guaranteed: "Up to" the whole 10_890_000_000 B.
    expect(staged.upTo).toBe(true);
    expect(staged.consentTotal).toBe('10 GB');
    expect(staged).not.toHaveProperty('resumedTotal');
  });

  it('says no "Up to" without staged bytes (the ordinary first-run case)', () => {
    expect(composeInstallConsent(manifest, preview)).toMatchObject({ upTo: false, consentTotal: '10 GB' });
    expect(composeInstallConsent(manifest, { ...preview, resumableBytes: 0 }).upTo).toBe(false);
    expect(composeInstallConsent(manifest, null)).toMatchObject({ upTo: false, consentTotal: null });
  });

  it('tracks the preview instead of a fixed string (a different plan gives a different total)', () => {
    const smaller = composeInstallConsent(manifest, {
      ...preview,
      totalDownloadBytes: 734_003_200,
    });
    expect(smaller.downloadTotal).toBe('700 MB');
    expect(smaller.downloadTotal).not.toBe(
      composeInstallConsent(manifest, preview).downloadTotal,
    );
  });

  it('names each package with its licence and a linkable termsUrl', () => {
    const { packages, termsUnavailable } = composeInstallConsent(manifest, preview);
    expect(termsUnavailable).toBe(false);
    expect(packages.map((p) => p.label)).toEqual([
      'Embedding model',
      'Chat model',
      'Untagged package',
    ]);
    expect(packages[0]).toEqual({
      id: 'embedding',
      label: 'Embedding model',
      license: 'Apache-2.0',
      termsUrl: 'https://huggingface.co/Alibaba-NLP/gte-multilingual-base',
    });
    // Every package that declares terms upstream exposes them for a real <a href>.
    expect(packages[1]?.termsUrl).toBe('https://huggingface.co/Qwen/Qwen3.5-9B');
  });

  it('falls back to the package id when the registry declares no label', () => {
    const unlabelled = composeInstallConsent(
      { schemaVersion: 2, packages: [{ id: 'reranker', variants: [], supportingFiles: [] }] },
      preview,
    );
    expect(unlabelled.packages[0]?.label).toBe('reranker');
    expect(unlabelled.packages[0]?.license).toBeNull();
    expect(unlabelled.packages[0]?.termsUrl).toBeNull();
  });

  it('drops a package whose tier the active intent excludes (wire enum vs kebab tier id)', () => {
    const retrievalOnly = composeInstallConsent(manifest, {
      ...preview,
      intent: 'retrieval',
      tiers: [
        { tier: 'retrieval-core', includedByIntent: true },
        { tier: 'llm', includedByIntent: false },
      ],
    });
    expect(retrievalOnly.packages.map((p) => p.id)).toEqual(['embedding', 'legacy-untagged']);
  });

  it('never hides terms when the intent is unknown — an untagged package always stays listed', () => {
    const noTierInfo = composeInstallConsent(manifest, { ...preview, tiers: [] });
    expect(noTierInfo.packages.map((p) => p.id)).toEqual([
      'embedding',
      'chat',
      'legacy-untagged',
    ]);
  });

  it('race: preview not resolved yet → no size claimed at all, terms still listed', () => {
    const early = composeInstallConsent(manifest, null);
    expect(early.downloadTotal).toBeNull();
    expect(early.packages).toHaveLength(3);
    expect(early.termsUnavailable).toBe(false);
  });

  it('race: manifest not resolved yet → flagged unavailable rather than an empty terms list', () => {
    const early = composeInstallConsent(null, preview);
    expect(early.termsUnavailable).toBe(true);
    expect(early.packages).toEqual([]);
    expect(early.downloadTotal).toBe('10 GB');
  });

  it('a zero/absent byte total is treated as unknown, never rendered as "0 B"', () => {
    expect(composeInstallConsent(manifest, { intent: 'full' }).downloadTotal).toBeNull();
    expect(
      composeInstallConsent(manifest, { intent: 'full', totalDownloadBytes: 0 }).downloadTotal,
    ).toBeNull();
  });
});
