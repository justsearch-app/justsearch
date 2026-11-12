// @vitest-environment node
/**
 * projectFact() — the Display-value authority (tempdoc 594 §9.2). These tests pin the value
 * projection + the TERNARY presence (present / absent / unknown) over seeded observed-state, so a
 * factual chip can never render a fabricated or host-wrong value (594 §1, §11.3 #3).
 */
import { describe, it, expect } from 'vitest';
import { projectFact, isFact } from './facts';
import type { AiState } from '../state/aiStateStore';
import { known } from '../state/known';
import { formatCount } from './format';

/** projectFact only reads `aiState.status`, so a partial cast is sufficient + type-honest here. */
function withStatus(status: unknown): AiState {
  return { status } as unknown as AiState;
}

describe('projectFact — build-time constant (embedding dimension)', () => {
  it('is always present with the SSOT-generated dimension, regardless of aiState', () => {
    expect(projectFact('core.embed.dim', null)).toEqual({
      name: 'Embeddings',
      value: '768-d',
      presence: 'present',
    });
    expect(projectFact('core.embed.dim', withStatus(null)).value).toBe('768-d');
  });
});

describe('projectFact — GPU accelerator (587 host fact + provenance)', () => {
  it('UNKNOWN when status has not been polled', () => {
    expect(projectFact('core.gpu.accel', null).presence).toBe('unknown');
    expect(projectFact('core.gpu.accel', withStatus(null)).presence).toBe('unknown');
  });
  it('ABSENT (chip omitted) when the host has no GPU — never "GPU cuda12" on a CPU box', () => {
    expect(projectFact('core.gpu.accel', withStatus({ gpu: { available: false } })).presence).toBe(
      'absent',
    );
  });
  it('PRESENT "GPU CUDA" with provenance when CUDA is functional', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } }),
    );
    expect(f.presence).toBe('present');
    expect(f.value).toBe('CUDA');
    expect(f.provenance).toBe('via NVML · high confidence');
  });
  it('PRESENT "GPU" (name-only) when a GPU exists but CUDA is not functional', () => {
    const f = projectFact('core.gpu.accel', withStatus({ gpu: { available: true, cudaFunctional: false } }));
    expect(f.presence).toBe('present');
    expect(f.value).toBeNull();
  });
});

describe('projectFact — structured confidence (§11.3 #2)', () => {
  it('carries high confidence for an NVML-sourced GPU', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } }),
    );
    expect(f.confidence).toBe('high');
  });
  it('carries LOW confidence for an nvidia-smi-sourced GPU (so the chip can mark uncertainty)', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvidia-smi', confidence: 'LOW' } }),
    );
    expect(f.confidence).toBe('low');
  });
  it('non-GPU facts carry NO confidence field', () => {
    const f = projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: true } } }));
    expect(f.confidence).toBeUndefined();
  });
});

describe('projectFact — capability presence (SPLADE/NER/reranker)', () => {
  it('SPLADE present (name-only) / absent (omitted) / unknown', () => {
    expect(projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: true } } }))).toMatchObject(
      { presence: 'present', value: null },
    );
    expect(
      projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: false } } })).presence,
    ).toBe('absent');
    expect(projectFact('core.splade', withStatus({ worker: { enrichment: {} } })).presence).toBe('unknown');
  });
  it('reranker present when a model path is configured, absent otherwise', () => {
    expect(
      projectFact('core.reranker', withStatus({ worker: { gpu: { rerankerModelPath: '/m/rr.onnx' } } })).presence,
    ).toBe('present');
    expect(projectFact('core.reranker', withStatus({ worker: { gpu: {} } })).presence).toBe('absent');
  });
});

describe('projectFact — vector precision', () => {
  it('humanizes the wire format token', () => {
    expect(projectFact('core.vector.precision', withStatus({ worker: { vectorFormat: { vectorFormatActual: 'FLOAT32' } } }))).toMatchObject(
      { name: 'Vectors', value: 'Float32', presence: 'present' },
    );
  });
});

describe('projectFact — unknown id + isFact', () => {
  it('an unregistered id yields unknown presence, never a fabricated value', () => {
    expect(projectFact('core.nope', withStatus({}))).toEqual({ name: 'core.nope', value: null, presence: 'unknown' });
  });
  it('isFact recognizes the catalog', () => {
    expect(isFact('core.embed.dim')).toBe(true);
    expect(isFact('core.nope')).toBe(false);
  });
});

describe('projectFact — status metric-facts (§17.2 migration off the bypass shells)', () => {
  const withState = (s: unknown): AiState => s as AiState;
  it('core.files formats the count, unknown when no index', () => {
    expect(
      projectFact('core.files', withState({ index: { ownDocumentCount: known(605) } })),
    ).toMatchObject({ name: 'Files', value: '605', presence: 'present' });
    expect(projectFact('core.files', withState({})).presence).toBe('unknown');
  });
  it('core.size formats bytes, unknown when no status', () => {
    expect(
      projectFact('core.size', withState({ status: { worker: { core: { indexSizeBytes: 53_477_376 } } } })).value,
    ).toBe('51.0 MB');
    expect(projectFact('core.size', withState({ status: null })).presence).toBe('unknown');
  });
  it('core.memory formats bytes', () => {
    expect(projectFact('core.memory', withState({ status: { memoryUsedBytes: 238_026_752 } })).value).toBe('227.0 MB');
  });
  it('metric-facts carry NO confidence/provenance (value-only — render plainly, §17.3)', () => {
    const f = projectFact('core.files', withState({ index: { ownDocumentCount: known(1) } }));
    expect(f.confidence).toBeUndefined();
    expect(f.provenance).toBeUndefined();
  });
});

describe('projectFact — derived context window (883 decision 1 / ADR-0047)', () => {
  const withRuntime = (runtime: unknown): AiState =>
    ({ inference: {}, runtime } as unknown as AiState);
  // The token count is locale-grouped through the shared formatter, so the expectations compose it
  // rather than hardcoding a separator — a `4,096`/`4.096` mismatch would fail on the format, not on
  // the behaviour under test (observed: this suite runs under a non-en grouping locale).
  const tokens = (n: number): string => `${formatCount(n)} tokens`;

  it('states the observed token count AND why that rung was chosen', () => {
    expect(
      projectFact(
        'core.ai.contextWindow',
        withRuntime({
          contextWindow: 32768,
          contextWindowDerived: { rung: 32768, reason: 'top-rung', slots: 2, kvType: 'q8_0' },
        }),
      ),
    ).toMatchObject({
      name: 'Context',
      value: `${tokens(32768)} (top-rung, 2 slots, q8_0)`,
      presence: 'present',
    });
  });

  it('a stepped-down launch says so — the case the parenthetical exists for', () => {
    expect(
      projectFact(
        'core.ai.contextWindow',
        withRuntime({
          contextWindow: 16384,
          contextWindowDerived: { rung: 16384, reason: 'stepped-from:32768', slots: 2, kvType: 'q8_0' },
        }),
      ).value,
    ).toBe(`${tokens(16384)} (stepped-from:32768, 2 slots, q8_0)`);
  });

  it('an ADOPTED/external engine publishes no derivation — the count renders bare, not "(null)"', () => {
    // The rung record exists only while a server THIS process launched is running (ADR-0047:
    // intent vs observation). The observed n_ctx is still a fact, so it must still render.
    expect(
      projectFact(
        'core.ai.contextWindow',
        withRuntime({ contextWindow: 8192, contextWindowDerived: null }),
      ).value,
    ).toBe(tokens(8192));
  });

  it('a partial record degrades to the fields it has, and singularizes one slot', () => {
    expect(
      projectFact(
        'core.ai.contextWindow',
        withRuntime({
          contextWindow: 4096,
          contextWindowDerived: { rung: 4096, reason: 'override', slots: 1, kvType: null },
        }),
      ).value,
    ).toBe(`${tokens(4096)} (override, 1 slot)`);
  });

  it('no observed window at all is ABSENT, even with a derivation present', () => {
    // The count is the observation and it is what the fact reports; a rung alone would be the
    // intent standing in for a measurement, which ADR-0047 forbids.
    expect(
      projectFact(
        'core.ai.contextWindow',
        withRuntime({
          contextWindow: null,
          contextWindowDerived: { rung: 32768, reason: 'top-rung', slots: 2, kvType: 'q8_0' },
        }),
      ).presence,
    ).toBe('absent');
  });
});
