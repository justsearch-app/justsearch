# Installed recovery verification after queue ownership changes

2026-09-14, based on pushed112d2e05d. This record is active repair work, not a claim
that installed recovery or C2 is complete.

Hosted CI34818093845 at d892321c6 reports overall success because integration-tests is
advisory (.github/workflows/ci.yml:1290). Its101 integration attempts include seven
failures and42 skips. The downloaded artifact remains accessible under
F:/justsearch-public/.claude/worktrees/lane-f-pr1-verify/tmp/hosted-integration1561/.
The processing/operation cases retain actual Engine death, durable PROCESSING observation,
recovered DONE state, searchable source and unchanged operations rows. They fail at the
old log assertion: the actual queue now reports positive unowned processing jobs, while
the fixture expected stuck jobs. Align the positive-count assertion with its producer;
retain all death, incarnation, queue, operation-row, search and cleanup checks.

Local installed1568 runs all six scenarios at112d2e05d plus that assertion correction.
Writer, lock-boot, processing and operation-resume pass. Migration and lock-ingest fail;
no skips. Processing and operation now reach PROCESSING_REPLAY_PASS, with operation also
proving OPERATION_RETRY_NO_DUPLICATES_PASS. Thus the corrected assertion is verified,
but the full installed suite is red. Node syntax and focused ESLint pass. XML/logs are
retained under tmp/hosted-recovery-repro1568*; quick1569 reports ABSENT, no foreign runs
or inference orphan after the fixture-owned cleanup.

## Open failures being repaired

- Migration fixture writer-junit-957c6d5f-a488-49f6-a71c-ca60be78f96f:Green verification
refuses the missing embedding_model_sha256 for the locally installed model. Earlier in
that successor, the migration enumerator admits A and extraction skips it as unchanged.
The promotion timeout reflects a FAILED migration, not slow successful work. Preserve
the fingerprint verification and diagnose generation/commit provenance.
- Lock-ingest fixture writer-junit-b235d176-e79b-4c1b-8110-d9b8da53d2f3:SQLITE_BUSY at
enqueueEntries' write after its membership read returns accepted0. All100 paths remain
required; do not weaken acceptance. Investigate write-transaction acquisition before
preservation reads versus a bounded retry with confirmed rollback.
- Hosted lock-boot writer-junit-fa7cec90-687d-4adc-b158-25bbc3a1729b:Engine exits during
initial discovery before run.json. Its log-only directory is mistaken for a registered
run, producing secondary cleanup Run not found. Local1568 does not reproduce that startup
failure. The victim/initial exception is absent from the artifact; ownership and initial
supervision must be investigated before claiming it fixed.

Raw evidence retains the tested head/distribution stamp and exact run identities. Retain
through lane final reconciliation plus30 days, at least2026-10-14. These failures stay in
active C2/installed repair work and are not owner-gated or accepted deferrals.
