# Authentic JustSearch 0.3.0 upgrade fixtures

Captured on Linux on **2026-10-21**, using tag `v0.3.0`, commit
`76871d924` (`76871d924` is the release commit, not a reconstructed schema).
The three archives total **575,276 bytes** (about 562 KiB).

These are the inputs for task `t-20261020-c2e24a`, issue #739, and scenarios
U1/U2/U6–U14b/U18/U20/U21. They do not implement the upgrade fixes.

| Archive | Capture |
| --- | --- |
| `small.zip` | 30 indexed files in two watched roots, the five bundled help files, and an explicitly submitted file outside both roots. Release v13 queue with PENDING, PROCESSING, six-hour WRITE_FAILED retry, RETRY_EXHAUSTED, FAILED, parser timeout and I/O retry rows. The queued update of `alpha/file-00.txt` changes its content; `pending.txt` and `processing.txt` have not been indexed. |
| `migrating.zip` | The same installation after a real public migration-start request, paused and killed while state is MIGRATING. The serving generation remains present. The building generation may not yet have a commit: this captures the early interruption before the scheduled worker restart. |
| `switching.zip` | Release restarted from MIGRATING; paused migration advanced through the public `forceSwitching=true` endpoint. A public ingest acknowledges an UPSERT; the live release gRPC service acknowledges DELETE. The durable buffer also may contain a watcher SYNC_ROOT. Engine killed with SWITCHING persisted. |

Each ZIP contains `data/`, `home/ui/settings.json` (envelope schema 2), source
files, bundled help source files, and `fixture.json`. The manifest records
release identity, queue counts, state, and SHA-256 of every captured file.
The index has actual release Lucene segments, `index_fingerprint`, and
`index_fingerprint_inputs`; `jobs.db` has `PRAGMA user_version=13`.
SQLite WALs, where present, are retained; shared-memory/OS locks, runtime
snapshots, logs and telemetry are omitted. The archives were captured after
SIGKILL, not a successful Engine shutdown.

AI inference was disabled to keep the capture small and independent of native
inference. The repository model tree was mounted read-only solely for model
identity discovery. No model binaries are in these archives. The release's
embedding status on reopen is not characterized as healthy by this fixture.

## Reproduce the captures

Requirements: Linux, `bwrap` with user namespaces, Java 25, Python 3,
`js-gradle-slot`, and the repository's existing model directory (the primary
checkout's `models/` is discovered through Git's common directory).
Set TMPDIR to a private directory **outside `/tmp`**. From the repository root:

```sh
python3 scripts/fixtures/capture-upgrade-030.py
cp "$TMPDIR/release030-capture/fixtures/"* modules/indexer-worker/src/test/resources/upgrade-030/
```

The script runs the equivalent of:

```sh
git worktree add --detach "$TMPDIR/release030-capture/release" v0.3.0
# In that checkout; skip the Windows sidecar packaging finalizer.
js-gradle-slot ./gradlew :modules:ui:headlessDist :modules:indexer-worker:installDist \
  -x :modules:ui:bundleSidecarResources -PskipWebBuild=true --no-configuration-cache --continue
# On exit, including failure:
git worktree remove --force "$TMPDIR/release030-capture/release"
```

JVMs use `-Djava.io.tmpdir` under TMPDIR; JAVA_TOOL_OPTIONS is unset. The
headless app and its worker run with isolated JUSTSEARCH_HOME and
JUSTSEARCH_DATA_DIR. A mount namespace exposes those files as `/fixture`,
the detached checkout as `/release`, and models read-only as `/models`.
These synthetic prefixes avoid embedding the capturing machine's home path.

The concrete operations are POST `/api/settings/v2`, POST
`/api/indexing/roots` twice, and POST `/api/knowledge/ingest` for the explicit
file. After indexing drains, the process group is stopped with SIGSTOP.
`SeedRelease030Queue.java`, executed with the **release** worker classpath,
uses release `enqueue`, `pollPending` and typed `markFailed` operations to
prepare the adverse queue. Four write failures produce the actual six-hour
ladder step. To reach RETRY_EXHAUSTED without seven days of wall time, only
that row's `first_failed_at` is moved seven days back with SQL before asking
the release API to perform the final failure transition. This is controlled
fixture preparation, not an observed seven-day outage. The Engine group is
then killed with SIGKILL.

For interrupted fixtures the script restarts that release, POSTs
`/api/indexing/migration/start`, then `/api/indexing/migration/pause`, and
kills it. After another release restart, POST
`/api/indexing/migration/cutover?forceSwitching=true`, ingest and DeleteById
produce the SWITCHING capture. No state-file edits synthesize either state.
The release build runs with configuration caching disabled because its captured
Gradle script closures can fail when restored. The script refuses an existing
scratch directory and first writes the archives into its `fixtures/` subfolder.
The explicit copy above installs reviewed captures into test resources. It retains small logs and
native extraction under TMPDIR, and removes the detached checkout and all
its build outputs. API tokens/credential files must never be included: the
capture excludes runtime state and rejects credential-like filenames,
private-key headers, recognizable tokens, and absolute user-home paths in
all remaining bytes. The captured dev-mode Engine generated no credential
files in the retained durable data.

## Java harness and characterization

`Release030Fixture.unpack(name, tempDirectory)` verifies every archived
checksum before relocating paths. It rewrites watched roots, queue paths,
switch-buffer payloads, path hashes and identity hashes together. To keep
Lucene paths, doc IDs and indexed terms consistent, it re-encodes the isolated
copy using **the archived release field catalog and original commit
metadata**, preserving document UIDs and content. This is an explicit
input re-encoding; the compressed captures retain the original segment
bytes. Non-stored chunk text is recovered from the parent and its saved
character offsets. Unchanged source bytes recover their indexed modification
time so unpacking alone does not turn U9 into a changed-file extraction.
Queue sources first receive their captured admission's `last_updated` as an
mtime upper bound, so queue-only retry sources are not given a synthetic
post-admission edit by unpacking. Indexed unchanged sources then recover their
more precise indexed timestamp.
Retry and migration-phase deadlines retain their absolute captured values;
time-sensitive follow-up tests should prepare those deadlines against their
chosen clock before boot. This helper intentionally rejects vector-bearing,
SPLADE-bearing or unexpected multi-valued captures rather than silently
losing fields. These captures are text-only.

The helper lives in indexer-worker because that module already owns real
KnowledgeServer boot, generation compatibility and SQLite upgrade tests.
It needs neither UI dependencies nor a new production module.

```sh
js-gradle-slot ./gradlew :modules:indexer-worker:test --tests '*Release030*Test' --continue
```

`Release030UpgradeCharacterizationTest` asserts today's known-bad behavior:
opening this release index starts migration; the captured ready PENDING,
PROCESSING and explicit-file jobs become DONE with MISSING_INGESTION_BOUNDARY
before extraction. Later fix PRs must invert those expectations. Model identity
caches are scoped to the captured hashes and restored exactly so missing
model binaries cannot introduce a different legitimate rebuild reason.
No test is disabled.

## On-demand #739 scale

```sh
scripts/fixtures/generate-upgrade-030.sh
```

Output is `build/upgrade-030-scale/`; an existing output is refused. The
script compiles the harness and runs its generator in one slotted Gradle
invocation. It starts with the small archive, its **real v13 DDL**, release
catalog and commit identity; no hand-authored SQL schema substitutes it.
It adds 200,001 distinct indexed parents under alpha and 700,000 PENDING
jobs under that same root, with actual independent source files, path
resolution rows, and identities for indexed parents. Existing adverse rows
are preserved. The original release fingerprint is checked after indexing;
SQLite integrity, version and queue size are checked after generation.
`scale.json` records the measured run. Nothing generated in build/ is tracked.
This is a synthetic scale expansion of the captured format, not 700k jobs
observed running on the release. Allow several GB of temporary disk.

Windows, desktop-shell execution, GPU inference, later upgrade fixes and
crash recovery correctness are outside this fixture PR's verified coverage.
