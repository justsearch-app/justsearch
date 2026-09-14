# R10 full-suite fixture completion

Full853 at c94494525 represents2157 cases/298 suites before failing one assertion:
EngineGenerationCaptureTest's composed-services case expects0 active work, observes1.
Raw `tmp/c2-review-r10-full853.txt` and `tmp/c2-review-r10-full853-xml/` are preserved.
The thread dump `tmp/c2-review-r10-full853-threads856.txt` shows the suite subsequently
progressing through a real extraction bootstrap; the build completes failed in7m10s.

The fixture launched four port calls with separate implicit request owners, then
waited for completion only of its fifth, streaming request. The scan call closes
its handoff before returning, but the callback pump retains its owner until actual
exit (C1's existing lifetime contract). Waiting for the independent fifth request
cannot establish that the earlier pump has exited. This is a synchronization defect
in the test, not a reason to release production admission early.

All five calls now attach to one explicit request owner and the fixture waits for
that owner's completion. All UNAVAILABLE assertions, service resolution count5,
zero admission and zero foreground assertions remain. No timeout increase, polling,
exception suppression or production change is used. Focused857 executes12 cases,
zero failures/errors/skips; PMD and UI integration-test compilation pass. Exact
command/counts: review-r10-generation-verification.json. Raw
`tmp/c2-review-r10-generation857.txt` and `tmp/c2-review-r10-generation857-xml/`.

Retain raw artifacts through lane acceptance plus30 days and export before worktree
release. Another full run is required after this failure correction; R10 remains open.
