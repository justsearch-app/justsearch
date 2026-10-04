# C1 hostile-lock recovery phase

Integrated221 at d4dfbb075 failed only the installed lock-boot scenario. Five intruder threads
held file locks for 10ms while 100 documents were submitted. A locked Lucene points write caused
a tragic writer failure and the Engine correctly exited. Continued injection attacked every
successor; subsequent forced hang exits exhausted the unchanged three-restart budget. This is
recorded in `tmp/lane-f-takeover/writer-junit-70119043-0769-4c84-a882-4209b12124a4/`, including
fixture-output.txt and the four incarnation logs. The full XML snapshot remains
`tmp/c1-integrated-results-221/manifest.json`; no assertion or failure was removed.

Design §16 now explicitly separates injection from recovery after an observed counted exit.
The harness captures the binding that accepts all 100 paths. Only a counted exit at or after
that submitting incarnation releases the intruder; the existing JUnit acknowledgement must
confirm the FileChannels are closed. Search and health must then succeed through an owned
binding within the unchanged 180-second search deadline. If the Engine stays healthy, the
attack continues for the whole scenario. Attack thread counts/hold times, positive real lock
acquisitions and production restart bounds remain unchanged.

The independent reviewer found two concrete holes in the first correction: an older persistent
lastExit could release a new attack, and positive partial acceptance could pass. Both are fixed:
compare the exit incarnation against the submitting binding and require exactly paths.length.
The four Node tests cover healthy ongoing attack, release after the relevant exit, stale exit,
and partial acceptance. Restored236 passes all four. Adverse237 removes both guards and fails
both new cases; original phase code also failed the fatal-recovery case in227. Existing supervisor
contract/fake-workload self-test238 passes all34 checks. The new sibling tests run from the
existing cross-platform CI supervisor self-test step.

Installed235 passes writer, migration, lock-boot, lock-ingest, processing and isolated ledger.
Both lock arms execute the new release branch: fatal_or_uncaught exit1 from incarnation1,
accepted100, running healthy incarnation2 with a matching search hit, restartCount1 and owned
stop with closed ports. Fixtures:

- boot: `tmp/lane-f-takeover/writer-junit-5494e011-9ff0-491e-a6db-90f3ebb7b7ef/`
- ingest: `tmp/lane-f-takeover/writer-junit-59ce0874-502e-4bdc-b938-f99d31362c94/`

These installed runs preceded the stale-exit and exact-acceptance guards. The final guarded
variant passes all five installed cases in239. Both final lock arms remained healthy under the
continuous attack, accepted100 and reported submitting incarnation1 without releasing locks.
Thus235 proves the fatal recovery branch before the final guard, and239 proves the final guarded
healthy branch. A final-guard installed fatal-branch witness remains required, not waived.
Final fixtures are `tmp/lane-f-takeover/writer-junit-6e7e5c63-c721-46dc-a47a-edbe38305362/` (boot)
and `tmp/lane-f-takeover/writer-junit-83d199f7-8e2c-4805-b102-b371a016abb4/` (ingest).
Logs and XML:
`tmp/c1-root-recovery-235.txt`, `tmp/c1-root-recovery-results-235/manifest.json`,
`tmp/c1-hostile-phase-restored-236.txt`, `tmp/c1-hostile-review-adverse-237.txt`,
`tmp/c1-supervisor-selftest-238.txt`.
Final installed log: `tmp/c1-hostile-guarded-installed-239.txt`.

This correction does not turn the advisory Windows hosted integration tier into a required
cross-platform gate. E's installed platform/recovery obligations remain unchanged.

## Final-guard hosted fatal witness — 2026-09-09

Hosted run34356123502 at6bf931408 supplies the previously missing final-guard fatal branch:
both boot and ingest accepted exactly100 documents, observed counted transient exit1 from the
submitted incarnation, released the injected fault, and found the indexed data after incarnation2
became ready. The fixture output retains the exit/incarnation evidence. Extracted summary:
`tmp/c1-hosted-proof-summary-269.json`; originals are retained under
`tmp/c1-hosted-artifacts-269/`. The hosted integration-test-results artifact10106694706 expires
2026-12-08T13:17:16Z and is accessible from
[the run](https://github.com/justsearch-app/justsearch/actions/runs/34356123502).

This closes the final-guard fatal-branch proof gap for6bf931408 only. That run is not final C1
acceptance: overall CI fails, and writer recovery passes only after two exit-classification
failures. One failed attempt also records a supervisor-state EPERM rename; another is forcibly
killed after its close deadline. The latest runtime-close and remaining ordered fixes still need
their final configured verification tiers.
