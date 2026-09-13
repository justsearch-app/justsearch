import { z } from './runtime.generated.mjs';

export const CleanModeSchema = z.enum(['none', 'soft', 'hard']);
export const OutputModeSchema = z.enum(['full', 'compact']);
export const ReadyLevelSchema = z.enum(['ready_http', 'ready_worker']);

// ─── Ownership (271) ──────────────────────────────────────

const OwnerSchema = z
  .object({
    source: z.string(),
    agentSessionId: z.string().nullable(),
    confidence: z.string().optional(),
  })
  .passthrough();

const ResourceClaimsSchema = z
  .object({
    apiPort: z.number().int().optional(),
    uiPort: z.number().int().optional(),
    dataDir: z.string().optional(),
  })
  .passthrough();

const LeaseSchema = z
  .object({
    durationSec: z.number().int(),
    renewedAt: z.string(),
    expiresAt: z.string(),
    sequence: z.number().int(),
    // Tempdoc 735 G6: computed at projection time (buildOwnershipProjection) — seconds until
    // expiresAt, floored at 0. Additive: makes "how much campaign hold is left" legible without
    // every caller doing its own expiresAt-minus-now arithmetic.
    remainingSec: z.number().int().optional(),
  })
  .passthrough();

// Tempdoc 542 §B Layer 2: op-lease entries surfaced via quick_health so agents can see what
// critical work is in flight before considering a takeover.
const OpLeaseSchema = z
  .object({
    opId: z.string(),
    opClass: z.string(),
    criticality: z.string(),
    startedAt: z.string(),
    expectedDurationSec: z.number().int().optional(),
    expiresAt: z.string(),
    heartbeatAt: z.string().nullable().optional(),
    originProcess: z.string(),
    holder: z.record(z.unknown()).optional(),
    metadata: z.record(z.unknown()).optional(),
  })
  .passthrough();

const OwnershipProjectionSchema = z
  .object({
    holder: z.object({ source: z.string(), agentSessionId: z.string().nullable() }).passthrough(),
    takeoverPolicy: z.string().nullable(),
    launcherFamily: z.string().nullable(),
    mode: z.string().nullable(),
    lease: LeaseSchema.optional(),
    leaseFresh: z.boolean().optional(),
    callerIsOwner: z.boolean().optional(),
    // Tempdoc 542 §B Layer 2 — active op-leases (filtered to non-expired).
    opLeases: z.array(OpLeaseSchema).optional(),
    // Tempdoc 606 — single ownership-verdict authority projection.
    verdict: z.string().optional(),
    grade: z.string().optional(),
    recommendedAction: z.string().optional(),
    rebuildFirst: z.boolean().optional(),
    provenance: z.record(z.string(), z.unknown()).optional(),
    backendStale: z.boolean().optional(),
    runningHeadStamp: z.string().optional(),
    displacedNotice: z.string().optional(),
  })
  .passthrough();

export const ToolErrorSchema = z
  .object({
    code: z.string().optional(),
    message: z.string(),
    errorClass: z.string().optional(),
    retryAction: z.string().optional(),
    retryAttempt: z.number().int().optional(),
    stack: z.string().optional(),
  })
  .passthrough();

export const StartInputSchema = z
  .object({
    apiPort: z.number().int().min(0).optional(),
    uiPort: z.number().int().positive().optional(),
    dataDir: z.string().min(1).optional(),
    clean: CleanModeSchema.optional(),
    waitLevel: ReadyLevelSchema.optional().describe('Readiness level to wait for after start (default: ready_worker)'),
    skipBuild: z.boolean().optional().describe('Skip the Gradle build step and launch from the '
      + 'artifacts already on disk (default: false). Two consequences, both from tempdoc 844: the '
      + 'step that is skipped is `assemble + :modules:ui:installDist` (F4 — `assemble` alone left '
      + 'the launched dist untouched), so a Java edit you '
      + 'have not installed will NOT be in the running stack; and hot reload needs the '
      + 'worker-services classes dir to be paired with those jars, which skipping the build cannot '
      + 'establish. When they are not paired the dev-runner turns hot reload OFF for the run and '
      + 'records why in run.json, rather than putting a half-new classes dir on a half-old '
      + 'classpath (M3).'),
    startTimeoutMs: z.number().int().positive().optional().describe('Timeout for dev-runner start subprocess (default: 600000)'),
    waitTimeoutMs: z.number().int().positive().optional().describe('Timeout for readiness polling after start (default: 60000)'),
    takeover: z.enum(['deny', 'warn', 'force']).optional()
      .describe('Takeover policy if another agent owns the backend (default: deny)'),
    hotReload: z.boolean().optional()
      .describe('Enable hot-reload: JDWP agent + DevReloadManager on Worker, and the per-run JDWP '
        + 'port the reload tool pushes to. DEFAULT TRUE (tempdoc 844 §4.2 condition 3 — it was '
        + 'opt-in on 1 of 162 measured starts, so nobody could reach the capability, or its bugs). '
        + 'Pass false to opt out: the Worker then has no JDWP listener at all and reload will '
        + 'refuse with HOT_RELOAD_NOT_ENABLED rather than pretend.'),
    leaseDurationSec: z.number().int().optional()
      .describe('Tempdoc 735 G6: campaign-length ownership hold, in seconds — clamped server-side to '
        + '[30, 7200] (default: 30, i.e. current behavior). Declare this at start for a long measurement '
        + 'campaign so the shared-lease renewal loop can hold ownership through minutes of jseval/gradle '
        + 'activity without a Claude Code session touch, instead of lapsing on the default 30s passive-'
        + 'expiry window and inviting a takeover mid-run. Does not change explicit takeover semantics — '
        + 'force/warn still works normally; this only stretches the passive-expiry window.'),
    distFrom: z.string().optional()
      .describe('Tempdoc 606 Piece 4: launch the stack from THIS worktree\'s built dist (must be a '
        + 'sibling worktree under .claude/worktrees, or the main repo). The shared lease stays under '
        + 'the main repo, so a worktree agent can run its own code on the one shared stack. Resolves a '
        + 'rebuildFirst/provenance-mismatch verdict.'),
    chatProfile: z.enum(['compact', 'standard']).optional()
      .describe('Chat model profile delivered to the backend as JUSTSEARCH_CHAT_PROFILE (tempdoc 842). '
        + 'The dev default is "compact" even when this is omitted — ambient operator env still wins.'),
    sessionId: z.string().optional(),
  })
  .strict();

export const StopInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    force: z.boolean().optional(),
    clean: CleanModeSchema.optional().describe('Clean data dir after stop (default: none)'),
    sessionId: z.string().optional(),
  })
  .strict();

// Tempdoc 606 3c: block until the shared dev stack becomes acquirable (owner releases /
// goes abandoned / a critical op clears), then return — replacing the conflict→ask-user→
// manual-retry round-trip with a single waited call. Polls the ONE ownership verdict.
export const AcquireWhenFreeInputSchema = z
  .object({
    timeoutSec: z.number().int().positive().max(1800).optional()
      .describe('Max seconds to wait for the stack to become acquirable (default: 120).'),
    pollMs: z.number().int().min(500).max(30_000).optional()
      .describe('Poll interval in ms (default: 2000).'),
    sessionId: z.string().optional(),
  })
  .strict();

export const AcquireWhenFreeOutputSchema = z
  .object({
    ok: z.boolean(),
    acquirable: z.boolean(),
    verdict: z.string().optional(),
    grade: z.string().optional(),
    recommendedTakeover: z.enum(['deny', 'warn', 'force']).optional(),
    recommendedAction: z.string().optional(),
    waitedMs: z.number().optional(),
    ownership: OwnershipProjectionSchema.optional(),
    error: ToolErrorSchema.optional(),
  })
  .passthrough();

const DevRunnerErrorSchema = z
  .object({
    code: z.string(),
    message: z.string(),
    stack: z.string().optional(),
  })
  .passthrough();

export const DevRunnerStartJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string(),
        apiPort: z.number().int().positive(),
        uiPort: z.number().int().positive(),
        apiBaseUrl: z.string(),
        uiUrl: z.string(),
        dataDir: z.string(),
        pids: z
          .object({
            runnerPid: z.number().int().positive().optional(),
            backendRootPid: z.number().int().positive().optional(),
            frontendRootPid: z.number().int().positive().optional(),
          })
          .passthrough()
          .optional(),
        readiness: z
          .object({
            ready_http: z.boolean().optional(),
          })
          .passthrough()
          .optional(),
        owner: OwnerSchema.optional(),
        resourceClaims: ResourceClaimsSchema.optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const DevRunnerStopJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().nullable(),
        killedPids: z.array(z.number()).optional(),
        portsClosed: z.boolean().optional(),
        stopReportPath: z.string().nullable().optional(),
        note: z.string().optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const DevRunnerCleanupJsonSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().nullable(),
        portsClosed: z.boolean().optional(),
        note: z.string().optional(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        error: DevRunnerErrorSchema,
      })
      .passthrough(),
  ]);

export const DevRunnerStatusJsonSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      alive: z
        .object({
          runner: z.boolean().optional(),
          backendRoot: z.boolean().optional(),
          frontendRoot: z.boolean().optional(),
        })
        .passthrough()
        .optional(),
      ports: z
        .object({
          api: z
            .object({
              port: z.number().int().optional(),
              listening: z.boolean().optional(),
            })
            .passthrough()
            .optional(),
          ui: z
            .object({
              port: z.number().int().optional(),
              listening: z.boolean().optional(),
            })
            .passthrough()
            .optional(),
        })
        .passthrough()
        .optional(),
      readiness: z
        .object({
          ready_http: z.boolean().optional(),
        })
        .passthrough()
        .optional(),
      owner: OwnerSchema.optional(),
      resourceClaims: ResourceClaimsSchema.optional(),
      ownership: OwnershipProjectionSchema.optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }).nullable(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

/** Tempdoc 844 P1: the dev-runner `status` projection, now reached through
 *  `quick_health { detail: "full" }` rather than a second orientation tool. */
export const StatusOutputSchema = DevRunnerStatusJsonSchema;

export const TailLogKindSchema = z.enum([
  'backend_stdout',
  'backend_stderr',
  'frontend_stdout',
  'frontend_stderr',
  'stop_report',
]);

export const TailLogInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    kind: TailLogKindSchema,
    maxBytes: z.number().int().positive().max(1_000_000).optional(),
    maxLines: z.number().int().positive().max(10_000).optional(),
    grepPattern: z.string().optional().describe('Regex pattern to filter log lines'),
    sessionId: z.string().optional(),
  })
  .strict();

export const TailLogOutputSchema = z
  .union([
    z
      .object({
        ok: z.literal(true),
        runId: z.string().meta({ format: 'uuid' }),
        kind: TailLogKindSchema,
        path: z.string().min(1),
        truncated: z.boolean(),
        bytesRead: z.number().int().min(0),
        text: z.string(),
      })
      .passthrough(),
    z
      .object({
        ok: z.literal(false),
        runId: z.string().meta({ format: 'uuid' }),
        kind: TailLogKindSchema,
        error: ToolErrorSchema,
      })
      .passthrough(),
  ]);

export const FetchApiEndpointSchema = z.enum([
  'status',
  'health',
  'effective_config',
  'debug_state',
  'policy_effective',
  'inference_status',
  'gpu_capabilities',
  'ui_ready',
  'ai_runtime_status',
]);

/**
 * Tempdoc 844 B4b — `maxBytes` is a READ budget, not an output budget. Agents lowered it to shrink
 * the returned payload and got `response_too_large` instead; it now truncates with an explicit
 * notice, and the description says which knob actually shrinks output.
 */
const MAX_BYTES_DESCRIPTION =
  'Maximum bytes to READ from the backend response (default 2000000). Exceeding it truncates the '
  + 'body and returns an explicit RESPONSE_TRUNCATED notice — a truncated body does not parse as '
  + 'JSON, so this cannot be used to shrink a large response. To shrink OUTPUT use jsonPath, '
  + 'outputMode:"compact", or summaryOnly.';

/** Tempdoc 844 B4a/B4c — one description for the one projection implementation (two callers). */
const JSON_PATH_DESCRIPTION =
  'Dot-path projecting a subtree of the response, with array indices — e.g. "llm.model_path" or '
  + '"results[0].fields.path". On a miss the tool returns the available keys at the deepest level '
  + 'that did resolve and withholds the body, so a typo costs a hint, not the whole payload.';

export const FetchApiJsonInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    endpoint: FetchApiEndpointSchema,
    jsonPath: z.string().optional().describe(JSON_PATH_DESCRIPTION),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional().describe(MAX_BYTES_DESCRIPTION),
    sessionId: z.string().optional(),
  })
  .strict();

// Tempdoc 844 B4a/B4b: the fields that make a partial answer legible. Declared rather than left to
// passthrough (the 842 §2.7 lesson — an undeclared field is an undocumented field).
const TruncationFields = {
  truncated: z.boolean().optional(),
  bytesRead: z.number().int().min(0).optional(),
  maxBytesLimit: z.number().int().positive().optional(),
};
const JsonPathAvailableField = {
  jsonPathAvailable: z
    .object({
      kind: z.string(),
      keys: z.array(z.string()).optional(),
      keysTotal: z.number().int().optional(),
      length: z.number().int().optional(),
      hint: z.string().optional(),
    })
    .passthrough()
    .optional(),
};

export const FetchApiJsonOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      endpoint: FetchApiEndpointSchema,
      url: z.string().min(1),
      statusCode: z.number().int().nullable(),
      json: z.any().optional(),
      textTail: z.string().optional(),
      ...TruncationFields,
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      endpoint: FetchApiEndpointSchema,
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      json: z.any().optional(),
      textTail: z.string().optional(),
      ...TruncationFields,
      ...JsonPathAvailableField,
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const SearchQueryInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    query: z.string().min(1),
    cursor: z.string().optional().describe('Pagination cursor from a previous search response'),
    limit: z.number().int().positive().max(200).optional(),
    mode: z.string().optional(),
    querySyntax: z.enum(['SIMPLE', 'LUCENE']).optional(),
    verbose: z.boolean().optional(),
    summaryOnly: z.boolean().optional().describe('Return only totalHits and tookMs, omitting result details'),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().max(60_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional().describe(MAX_BYTES_DESCRIPTION),
    sessionId: z.string().optional(),
  })
  .strict();

export const SearchQueryOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      query: z.string(),
      url: z.string().min(1),
      statusCode: z.number().int(),
      totalHits: z.number(),
      tookMs: z.number(),
      results: z.array(z.any()),
      nextCursor: z.string().optional(),
      facets: z.any().optional(),
      correctionApplied: z.boolean().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      query: z.string(),
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      ...TruncationFields,
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const IngestInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    paths: z.array(z.string().min(1)).min(1),
    timeoutMs: z.number().int().positive().max(120_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional().describe(MAX_BYTES_DESCRIPTION),
    sessionId: z.string().optional(),
  })
  .strict();

export const IngestOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      url: z.string().min(1),
      statusCode: z.number().int(),
      accepted: z.number().int(),
      error: z.string().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      url: z.string().min(1).optional(),
      statusCode: z.number().int().nullable(),
      ...TruncationFields,
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Generic API Call ---

export const ApiCallMethodSchema = z.enum(['GET', 'POST', 'DELETE']);

export const ApiCallInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    method: ApiCallMethodSchema.default('GET'),
    path: z.string().min(1),
    body: z.any().optional(),
    // Tempdoc 844 B4c: shares fetch_api_json's projection implementation.
    jsonPath: z.string().optional().describe(JSON_PATH_DESCRIPTION),
    outputMode: OutputModeSchema.optional().describe('Output detail level (default: compact)'),
    timeoutMs: z.number().int().positive().max(60_000).optional(),
    maxBytes: z.number().int().positive().max(5_000_000).optional().describe(MAX_BYTES_DESCRIPTION),
    sessionId: z.string().optional(),
  })
  .strict();

// ─── AI Runtime Activate ───────────────────────────────────

export const AiActivateInputSchema = z
  .object({
    runId: z.string().meta({ format: 'uuid' }).optional().describe('Run ID (omit to use active run)'),
    apiPort: z.number().int().positive().optional().describe('API port (alternative to runId for untracked instances)'),
    variantId: z.string().min(1).default('cuda12'),
    // Tempdoc 842 §2.4: activation is when llama-server spawns, so it is the natural chat-model
    // profile switch point. Forwarded to POST /api/ai/runtime/activate when set; backend support
    // lands in a parallel Java slice — sending the field is forward-compatible either way.
    chatProfile: z.enum(['compact', 'standard']).optional(),
    timeoutMs: z.number().int().positive().max(120_000).default(60_000),
    pollIntervalMs: z.number().int().positive().max(10_000).default(2_000),
    sessionId: z.string().optional(),
  })
  .strict();

export const AiActivateOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      runId: z.string().meta({ format: 'uuid' }),
      variantId: z.string(),
      activationState: z.string(),
      phase: z.string(),
      message: z.string(),
      durationMs: z.number(),
      // Tempdoc 842 §2.4/§2.7: passthrough of the realized chat profile, when the runtime status
      // response carries it (defensive — the field does not exist until the Java slice lands).
      chatProfile: z.string().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      runId: z.string().meta({ format: 'uuid' }),
      variantId: z.string(),
      activationState: z.string().optional(),
      phase: z.string().optional(),
      message: z.string().optional(),
      durationMs: z.number().optional(),
      chatProfile: z.string().optional(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// ─── Quick Health ──────────────────────────────────────────

export const QuickHealthInputSchema = z
  .object({
    probe: z.boolean().optional().describe('HTTP-probe running backend (default: true)'),
    // Tempdoc 844 P1: the former justsearch.dev.status tool, folded in as a detail level.
    detail: z.enum(['summary', 'full']).optional()
      .describe('"summary" (default) reads run state from disk + optional HTTP probes. "full" additionally '
        + 'spawns the dev-runner status subprocess and returns its process/port/readiness payload under '
        + '`detail` — the projection the retired justsearch.dev.status tool returned.'),
    sessionId: z.string().optional(),
  })
  .strict();

// Tempdoc 637 Layer A: one-look freshness verdict per source (build artifact, index warmth, FE
// binding, lockfile drift). Declared here to match what quick_health actually emits (~server.mjs
// buildArtifact/indexWarmth/feBinding/locks) — previously undeclared and surviving only via
// passthrough (842 §2.7).
const FreshnessSourceSchema = z
  .object({
    state: z.string(),
    reason: z.string().optional(),
    remedy: z.string().optional(),
    note: z.string().optional(),
  })
  .passthrough();

const FreshnessSchema = z
  .object({
    buildArtifact: FreshnessSourceSchema,
    indexWarmth: FreshnessSourceSchema,
    feBinding: FreshnessSourceSchema,
    locks: FreshnessSourceSchema,
  })
  .passthrough();

// Tempdoc 842 §2.5/§2.7: realized chat model identity, projected from the runtime — passthrough
// fields since they don't exist on the status response until the parallel Java slice lands.
const QuickHealthModelSchema = z
  .object({
    chatProfile: z.string().optional(),
    modelPath: z.string().optional(),
  })
  .passthrough();

// Tempdoc 844 B3: one observed-but-unowned listener. `attribution` is itself a tri-state —
// "unowned" (provably not the active run) vs "unknown" (could not be attributed), never merged.
//
// Tempdoc 844 D3 adds `source`, which says HOW this entry is known, and `state`, which says what
// was actually verified about a declared one. The pairing is the honesty contract:
//   source:'observed'   — a port answered; nothing else is known (the P5 fallback).
//   source:'registered' — a producer (`jseval`) declared it in tmp/dev-runner/foreign; `state` is
//                         then 'live' (port answered), 'unreachable' (port silent, pid alive),
//                         'stale' (port silent, pid gone — a record its producer never retired) or
//                         'unreadable' (the record file could not be parsed). A registered record
//                         is NEVER reported as live on the strength of the record alone.
// `port`/`probePath` are nullable only for 'unreadable', where there is no trustworthy port to name.
const ForeignRunSchema = z
  .object({
    port: z.number().int().positive().nullable(),
    kind: z.enum(['backend', 'inference']),
    probePath: z.string().nullable(),
    attribution: z.enum(['unowned', 'unknown']),
    source: z.enum(['observed', 'registered']),
    state: z.enum(['live', 'unreachable', 'stale', 'unreadable']).optional(),
  })
  .passthrough();

const ProbeObservationSchema = z
  .object({
    state: z.enum(['REACHABLE', 'REFUSED', 'TIMED_OUT', 'ERROR']),
    statusCode: z.number().int().nullable().optional(),
    error: ToolErrorSchema.optional(),
  })
  .passthrough();

export const QuickHealthOutputSchema = z
  .object({
    // Tempdoc 925: false is reserved for proven absence/stoppage. Unknown record/probe state is
    // null so a consumer cannot translate an unreadable register into "the stack is free".
    running: z.boolean().nullable(),
    runState: z.enum(['ACTIVE', 'ABSENT', 'UNKNOWN']),
    runStateError: ToolErrorSchema.optional(),
    runId: z.string().meta({ format: 'uuid' }).nullable(),
    apiPort: z.number().int().positive().nullable(),
    uiPort: z.number().int().positive().nullable(),
    httpReady: z.boolean().nullable(),
    workerReady: z.boolean().nullable(),
    aiActive: z.boolean().nullable(),
    probes: z.object({
      api: ProbeObservationSchema.optional(),
      worker: ProbeObservationSchema.optional(),
      inference: ProbeObservationSchema.optional(),
    }).optional(),
    // Tempdoc 844 B3: backends observed but NOT owned by this dev-runner. The tri-state is
    // load-bearing — `null` = probing was off or the probe failed (I did not look), `[]` = I looked
    // and found nothing, a non-empty array = these are running and none of them is my run.
    foreignRuns: z.array(ForeignRunSchema).nullable(),
    foreignRunsNotice: z.string().optional(),
    inferenceOrphan: z.boolean().nullable().optional(),
    ownership: OwnershipProjectionSchema.optional(),
    freshness: FreshnessSchema.optional(),
    model: QuickHealthModelSchema.optional(),
    // Tempdoc 844 P1: present only for detail:"full". Either the dev-runner status projection or an
    // explicit ok:false carrying why it could not be read — never silently absent on request.
    detail: StatusOutputSchema.optional(),
  })
  .passthrough();

// ─── Reload (hot-reload) ──────────────────────────────────

export const ReloadInputSchema = z.object({
  module: z.string().optional()
    .describe('Gradle module to compile. Defaults to — and tempdoc 844 M5 now restricts it to — the '
      + 'one module the run recorded as its hot-reload classes dir (worker-services). Any other '
      + 'value is refused: the identity check only ever sees the recorded dir, so pushing a '
      + 'different module reported success while that module kept loading from its stale jar.'),
  debugPort: z.number().int().positive().optional()
    .describe('Tempdoc 844 R3: override the JDWP port recorded in the run record. Diagnostics '
      + 'only — the port normally comes from run.json (the dev-runner picks it per run), and the '
      + 'target VM must still prove its identity, so an override cannot be used to push into '
      + 'another tree\'s stack.'),
  skipCompile: z.boolean().optional()
    .describe('Skip Gradle compile, only push + signal (default: false)'),
  takeover: z.enum(['deny', 'warn', 'force']).optional()
    .describe('Tempdoc 844 R2: reload MUTATES a run, so it is ownership-gated like start/stop. '
      + 'Default "deny" refuses with OWNER_CONFLICT when another agent owns the stack. This '
      + 'authorizes the push; it does NOT transfer the lease.'),
  sessionId: z.string().optional(),
}).strict();

// ─── Preflight ─────────────────────────────────────────────

export const PreflightInputSchema = z.object({
  // Tempdoc 844 B1: preflight used to check the INVOKING checkout's dists while start launched from
  // distFrom's — preflight passed, start then failed. Same value, same resolver as start.
  distFrom: z.string().optional()
    .describe('Check the dists in the checkout `start` will launch from: the main repo, a path to a '
      + 'sibling worktree under .claude/worktrees, or a bare worktree name (resolved against '
      + '.claude/worktrees/<name>). Omit to check the invoking checkout, as before.'),
  sessionId: z.string().optional(),
}).strict();

export const PreflightOutputSchema = z
  .object({
    ready: z.boolean(),
    // Compatibility booleans remain for older harnesses. `checkStates` is authoritative because
    // false cannot distinguish a verified failure from an observation error.
    // Lane F stage A item A13 removed `workerDist` from both maps: the Worker distribution it
    // probed no longer exists, and `headDist` covers the one distribution the Engine launches from.
    checks: z.object({
      headDist: z.boolean(),
      noStaleRun: z.boolean(),
      modelsDir: z.boolean(),
      noInferenceOrphan: z.boolean(),
      // Tempdoc 618 §3: is the llama-server runtime resolvable for `ai_activate`?
      llamaVariantResolvable: z.boolean(),
    }),
    checkStates: z.object({
      headDist: z.enum(['PASS', 'FAIL', 'UNKNOWN', 'SKIPPED']),
      noStaleRun: z.enum(['PASS', 'FAIL', 'UNKNOWN', 'SKIPPED']),
      modelsDir: z.enum(['PASS', 'FAIL', 'UNKNOWN', 'SKIPPED']),
      noInferenceOrphan: z.enum(['PASS', 'FAIL', 'UNKNOWN', 'SKIPPED']),
      llamaVariantResolvable: z.enum(['PASS', 'FAIL', 'UNKNOWN', 'SKIPPED']),
    }),
    // Tempdoc 844 B1: which checkout the dist checks actually looked at, so the answer is
    // self-describing rather than implicitly "wherever this server happens to run".
    distCheckedRoot: z.string().optional(),
    distFrom: z.string().nullable().optional(),
    distFromResolvedVia: z.string().optional(),
    details: z.record(z.string(), z.string()).optional(),
  })
  .passthrough();
