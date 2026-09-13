# C2-2 offline owner live capture

September12, Windows. Live754 passes against the installed Gradle distribution in
production-token mode. Production owner code is82e0e185d; launch revision28f7d4779
adds evidence/design only. This is not NSIS packaged-app acceptance or C2-2 completion.

## Instrument

Run from this worktree with its scripts/jseval on PYTHONPATH:

```text
python -m jseval.offline_procedure --base-url http://127.0.0.1:33221
  --corpus-dir <isolated-one-PDF-directory> --operations-db <owned-data>/operations.db
  --out <capture.json> --timeout 600
```

The per-boot mutation token comes only from JUSTSEARCH_SESSION_TOKEN and is not
written to evidence. Startup/activation/shutdown remain with the existing dev
runner/installed host owner. The instrument does not start or take over a stack.

Preflight requires an empty index, a standard online model, an existing read-only
operations database and exactly one PDF. It reuses jseval root admission/readiness
and the strict production document enumeration/preview/hash implementation. The
default application OCR, VDU policy, idle window and energy policy remain intact.

POST core.trigger-offline-processing returns acknowledgement only. Immediately
after acknowledgement, the accepted row must already exist for the unique
client/session identity. Bounded observation follows that row to completion and
requires one selected/processed unit, zero failed/remaining/blocked units and the
declared embedding handoff. It then requires the intended document identity,
matching indexed source bytes, a valid stored-text revision and nonempty text.
Empty passes and SUCCESS_EMPTY are not positive evidence. A missing or failed row
fails the instrument; its terminal metadata remains in the artifact.

SQLite readback is a local durable-row proof, not C2-6's future public query API.
Sampling RUNNING is recorded when observed but not required by a timing-sensitive
assertion. The real runner/store integration fixture separately holds cleanup and
proves the row and admission cannot complete early. This instrument does not claim
global enrichment drain, broad search quality or D1 generation carry-forward.

## Verification

- Unit748:95 cases pass, including17 instrument cases and78 shared production
  preview/capture cases. The parent corrected connection lifetime, immediate
  acceptance observation, finite timeout, corpus isolation, bounded observations,
  retained failure details and strict numeric checkpoint types before live use.
- Preparatory run746: fresh dev-runner distribution at09e86ab71, run
  a90f97da-962e-4530-8a07-25db670b7bba. Standard cuda12 activation succeeds. The real
  scanned-alpha.pdf fixture hashes to
  bbec73c1704bc7bccacdd9cc586e6113c96c6832f438c1160be71ccc6d453474.
  Default OCR is enabled; canonical root admission/readiness yields one indexed
  document and one pending VDU. No manual operation was invoked in this preparatory
  run. Normal owned shutdown returned202 and the Engine exited in629ms.
- Capture749 refuses before ingestion because ordinary development mode supplies
  an empty mutation token. This failed preflight is retained, not counted as live proof.
- Unit752 passes106 cases:28 instrument and78 shared preview cases. Independent
  review found and the parent fixed output collisions and bool/float pending-unit
  counts. Corpus, existing output and SQLite database/sidecar paths are protected;
  output creation is exclusive. Negative753 changes the validated output assignment
  to `out.resolve()` and exclusive `out.open("x", encoding="utf-8")` to mode `"w"`.
  All six selected output-collision regressions fail for overwritten bytes or a new
  watched file. The original source bytes were restored. Independent reread cleared
  both findings against the restored source and752/753 artifacts.
- Final parent read found that the query omitted failure_reason/result_json despite
  the evidence projection listing them. The query and schema probe now select both;
  a real SQLite regression preserves non-null failure and receipt values. Unit755
  passes107 cases. Corrected readback755 joins the same stopped live754 database,
  row and checkpoint. Original754's null receipt/failure fields are not evidence
  for those fields. This readback-only correction does not change the model run.
- Live754 runs on fresh data from production-mode run751,
  `95ed1a59-cbfa-4789-92d9-f4a4899fae61`, standard Qwen3.5-9B/cuda12 with its vision
  projector. Untokened mutation returns401; tokenized activation and operation work.
  The installed `modules/ui/build/install/ui/lib/*` classpath starts HeadlessApp,
  hot reload disabled, head stamp `50d1c26ec1b8a067`. No Worker JVM is launched.
  The initial index is empty, admission produces exactly one pending VDU, and the
  operation is already RUNNING on the first post-acknowledgement row read.
  Row1 starts at1789245322175 and completes at1789245332486, with selected1,
  processed1, failed0, remaining0, blocked0 and embeddingHandoff=not_needed.
  Engine log1516-1555 corroborates page rendering, two real model requests and200
  responses,47 extracted characters, and VDU_UPDATE_OUTCOME_SUCCESS_TEXT. Mode
  cleanup ends at log1623 before row completion. Thus an OCR preview alone is not
  the evidence for actual model execution.
  Strict preview readback identifies the intended source hash above, SUCCESS_FULL,
  no truncation, and matching text/content SHA256
  `d9170784b43231c34ed7c695ded4f6a06653a7d331849d62f5810227e68c76d3`.
  Independent parent readback confirms the fixture word alpha at the same revision.
  Independent reviewer reread also confirms the image-only fixture, model requests,
  committed update and cleanup-to-terminal ordering. Repository-resolved models,
  native resources and npm frontend mean this is not a self-contained package proof.
- Tokenized lifecycle shutdown returns202. The subsequent owned stop report shows
  the backend already exited, cleans the remaining registered UI listener, and
  confirms both ports closed without errors. The proof does not require a forceful
  Engine termination.

## Access and retention

Worktree artifacts: tmp/c2-2-offline-live-746-start.txt,
tmp/c2-2-offline-live-746-primary.json, tmp/c2-2-offline-live-747-start.txt,
tmp/c2-2-offline-instrument-748.txt, corpus tmp/c2-2-offline-live-746-corpus,
and isolated data directories tmp/c2-2-offline-live-{746,747}-data/.
Final evidence: tmp/c2-2-offline-instrument-752.txt,
tmp/c2-2-offline-instrument-negative-753.txt, tmp/c2-2-offline-live-754.json,
tmp/c2-2-offline-live-754-keyword.json, tmp/c2-2-offline-live-754-distribution.json,
tmp/c2-2-offline-live-754-model-events.jsonl and tmp/c2-2-offline-live-754-shutdown.json.
Final query correction: tmp/c2-2-offline-instrument-755.txt and
tmp/c2-2-offline-live-755-row-readback.json.
Full Engine log: tmp/c2-2-offline-live-751-data/logs/engine.log. Production751 start
and activation metadata and failed749 capture remain under their numbered prefixes.
The751 stop report is in the shared runner register at
F:/justsearch-public/tmp/dev-runner/runs/95ed1a59-cbfa-4789-92d9-f4a4899fae61/stop-report.json.
The746 stop report is in the shared runner register at
F:/justsearch-public/tmp/dev-runner/runs/a90f97da-962e-4530-8a07-25db670b7bba/stop-report.json.
Retain through lane acceptance plus30 days and export before worktree release.
