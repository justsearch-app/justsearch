# C2 raw attachment test barrier correction

[Hosted CI34776942178](https://github.com/justsearch-app/justsearch/actions/runs/34776942178)
at2accba28b ran all13 jobs:12 passed, while app-ui failed
AgentSessionRegistryDetachTest.deliveryEvictionReleasesRawAttachBeforeRunRetirement.
The retained hosted log/XML show ExecutionException caused by the deliberate socket callback's
IllegalStateException. Runner availability and the ADR/register premises are no longer the
failure. No full hosted green or installed schema5 proof is claimed here.

## Root cause and correction

The test's ready callback released its latch during synchronous initial replay. It could then
publish the next frame before observe returned. The substrate queues that frame into initial
handoff, where callback failure correctly propagates as an attachment failure. The test intended
to exercise live delivery, whose failed observer is retired and releases the existing attach latch.

A private test-only delegating Handle now signals after the real observe call returns. The test
requires that barrier and the ready replay before publishing live. It proves the real observer
is gone and the run remains active before waiting for attach return. A separate blocked-primer
regression deterministically reproduces the earlier timing and requires its correct initial
attachment exception. Production behavior and the assertion intent are preserved; no retry,
longer timeout, swallowed exception or dependency was added.

Independent source diagnosis and final wrapper review are clear and confirmed this fixture
race with no production lifecycle defect. The final1317 run resolves the reviewer's earlier
1315 source-binding limit after assertion reordering. AgentSessionRegistry calls observe before its wait block; RunChannelObservation delegates
to the substrate's synchronous handoff, while normal publish owns live callback retirement.

## Verification

Windows/Java25 atf97f2b55d plus the one test source in [the manifest](raw-detach-barrier-verification.json):

-1314 failed compilation because an initial Mockito spy proposal was unavailable in app-agent.
  Root used the explicit delegating Handle instead of introducing a test dependency. The failed
  compile log is preserved; no tests ran in1314.
-1315 executes allthree focused tests, zero failures/errors/skips, PMD/format pass.
-Root then moved observer-eviction/run-active assertions before attach-return waiting so the
  negative cannot pass or fail merely because delivery never happened.
-Negative1316 removes only the production onDetached callback. The observer is evicted and the
  run remains active, but attach.get times out at the intended assertion. One case fails, zero
  errors/skips; finally retires the real observation so the helper exits. Production restores
  byte-for-byte. The terminal run callback remains intact throughout this negative.
-Final1317 executes allthree cases, zero failures/errors/skips, PMD/format green with the final
  assertion ordering and unchanged production source.

Command1315/1317:

```powershell
./gradlew.bat :modules:app-agent:spotlessApply :modules:app-agent:test --tests '*AgentSessionRegistry*Test' :modules:app-agent:spotlessCheck :modules:app-agent:pmdTest --console=plain
```

Negative1316 selects only the deliveryEvictionReleasesRawAttachBeforeRunRetirement method via
`:modules:app-agent:test --tests`, with `--console=plain`.

The manifest binds local logs/XML/zips/counts, final sources, negative restoration and downloaded
hosted app-ui attribution/XML to accessible tmp paths in the lane worktree. Retain through lane
acceptance plus30 days and export before removing the worktree. Fresh hosted success on the
corrected test remains required; this narrow correction does not close C2.
