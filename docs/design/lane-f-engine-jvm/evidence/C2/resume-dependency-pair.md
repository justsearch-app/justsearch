# Declared generation versus unrelated settings at recovery

2026-09-21, base `e923824af`. C2's original `inputs changed` requirement means a
declared recovery dependency changed, not any change to global settings. The
implemented typed generation refusal is `RECOVERY_GENERATION_MISMATCH`; changing
its spelling would not improve the contract. The streaming root plan freezes
root bindings, force/exclusions and target generation, not the whole settings file.

## Proof

- Focused2057 executes four both-store reopen cases: real registered INGEST/REINDEX
  dispatch with unchanged or changed serving generation. Each closes the old queue
  and operations store after actual producer exit, then creates new owners. Unchanged
  generation runs one winning resume and settles the original rows. Changed generation
  preserves IDs/contexts/preparations, refuses the parent with the typed mismatch,
  runs no successor producer, issues no claims and settles/ACKs the child. The old
  cancellation leaves enumeration open; refusal truthfully closes it FAILED. This
  does not substitute for the separate COMPLETE-enumeration refusal cases.
- Installed2056 extends ingest-after-effect-before-checkpoint: while the original
  child receipt is held before checkpoint, the real public settings route commits a
  highContrast change and witness0→1. The fixture waits for the durable settings row
  and file commitment, then kills the exact Engine. The successor completes the
  original ingest/child with the same receipt, one resumed attempt and exact ACK,
  retains the changed settings witness/value and one searchable document.

2055 first tried waiting for the public settings HTTP response while another owner
was held by the artificial barrier and timed out. It still exposed a real history
projection defect: `settings.apply-public` is not a valid catalog OperationRef,
so OperationHistoryProjection rejects the committed row and pending delivery retries.
2056's durable commitment assertion separates the desired settings side effect from
observer response timing; it does not waive that history defect. The correction below
fixes public history delivery while preserving NamespacedId validation and every row.

The selected correction projects only SETTINGS_APPLY + `settings.apply-public`
as the history-facing `core.apply-settings`. The persisted descriptor remains
unchanged, preserving exact same-key identity; every other reference still goes
through OperationRef validation. This adds no invokable catalog operation. The
new installed history assertion fails against the unchanged installed distribution
in2058 with `Operation-history snapshot serialization failed`/INVALID_STATE after
otherwise successful ingestion recovery. This is the runnable negative control.
The corrected distribution and projector/reopen regression pass2060/2061 below.
The five-second response delay in2055 is not attributed to the caught projection
exception; its exact timing owner was not established.

Final2060 executes16 history/projector/read cases with no failures/errors/skips;
PMD/format and installDist pass. Independent review is clear. Final2061 executes
all8 installed cases with no failures/errors/skips in2m37s, including the corrected
public history endpoint, one `core.apply-settings` entry for the settings key,
unchanged raw retry reference and cleared history_pending after durable delivery.
The positive after-effect fixture's Engine log contains neither repeated history
delivery failure nor completion-observer failure. Gradle's `--info` log explicitly
names the changed operation-fault-scenario input as an execution invalidator.
Raw evidence additionally includes `tmp/2060*`, `tmp/2061.txt`, `tmp/2061-counts.json`,
`tmp/2061-xml`, `tmp/2061-installed-artifacts.json` and post-run `tmp/2062-health.json`.
All8 installed fixtures report owned stop with closed ports. Canonical index, skills,
157 canonical document links, module graph and runtime-config matrix checks pass.
This closes the paired dependency/history item; C2-10 and C2-12/full stress remain.

2057:4 executed cases, zero failures/errors/skips; PMD/format pass.2056 is a direct
installed Node scenario, not a new full JUnit suite result. Raw evidence:
`tmp/2057.txt`, `tmp/2057-counts.json`, `tmp/2057-xml`,
`tmp/2055-unrelated-settings.txt`, `tmp/2056-unrelated-settings.txt`, and
`tmp/2058-history-negative.txt`, plus
`tmp/lane-f-takeover/writer-live-1789954822121/`. Both installed fixtures stopped with
closed ports. Retain through lane acceptance plus30 days; export before worktree release.

The bulk operation's captured H1/H2 plan remains the separate
[C2-10 connection](bulk-reindex-connection.md). These streaming INGEST/REINDEX checks
do not satisfy bulk migration's row/plan/history obligations.
