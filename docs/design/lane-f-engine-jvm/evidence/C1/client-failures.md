# C1 Engine client failure cleanup

## Registration close aggregation

`EngineKnowledgeClient.closeTransport` attempts all five transport registration closes before
propagating the first RuntimeException or Error, retaining later failures as suppressed exceptions.
Base client close remains one-shot, so this must complete on the first call; retry cannot repair
unattempted transport owners. The two existing base registrations also retire through their
existing close path. No new executor or lifecycle state is introduced.

The regression wraps real registrations and injects two failures after their actual close. It
asserts all seven logical names are gone, both failures survive, a second close does not repeat
cleanup, and a replacement client can register and close against the same registry. Both an
ordinary first exception and a first Error are covered.

Run308 exposed the sequential-close failure, but its spy called close against copied registration
identity. Run309 still failed after aggregation because that fixture could not remove the real
map entry. These are not clean adverse proof. The corrected fixture delegates close to the actual
registered instance. Adverse310 then restores the original five sequential calls and both cases
fail because later names remain registered. Restored311 passes all six executor/client cases and
PMD; full build312 passes in23s with test Error Prone enabled. Base: da42316ab plus this item.

Logs: `tmp/c1-client-close-adverse-308.txt`, `tmp/c1-client-close-restored-309.txt`,
`tmp/c1-client-close-adverse-310.txt`, `tmp/c1-client-close-restored-311.txt`,
`tmp/c1-client-close-build-312.txt`. Preserved reports: `tmp/c1-client-close-results-308/`,
`tmp/c1-client-close-results-310/`, `tmp/c1-client-close-results-311/`. Run309's XML was replaced
before preservation; its log is retained and no successful proof relies on it.

Root reviewed this bounded correction. Final C1 review and integrated/live/hosted acceptance remain required.

## Late worker failure after caller completion

`withBudget` now logs a worker failure at ERROR if the caller's completion CAS has already won.
It reports only after worker/admission cleanup, and rethrows Error through OwnedCallTask to the
executor's uncaught-error path. Caller deadline/cancellation results remain the settled outcome.
The same final Error propagation also applies when the worker wins completion; it cannot quietly
poison an executor thread just because the caller also receives the Error.

The two new cases hold a real registered call executor's mocked search body beyond the caller
deadline, verify the caller receives DEADLINE_EXCEEDED while admission remains held, then release
an ordinary exception or Error. They capture the exact failure's log event at ERROR, require
admission zero before reporting, and observe the Error through a handler installed only on that
executor's own thread factory. The ordinary case also performs a subsequent healthy request.

Adverse313 on the previous production code fails both cases at the missing error-log wait.
Restored314 passes14 client/context cases and PMD. Adverse315 removes only the final Error
rethrow: the ordinary case passes and the Error case fails specifically at the uncaught-handler
wait, after successful ERROR logging. Restored316 reuses the unchanged passing314 source/test
outputs from Gradle cache; all14 cases are green. Full build317 passes in22s. Base7cd9ef55d plus
this item. Root inspected the caller CAS, actual-work cleanup and OwnedCallTask propagation.

Logs: `tmp/c1-client-late-adverse-313.txt`, `tmp/c1-client-late-restored-314.txt`,
`tmp/c1-client-error-adverse-315.txt`, `tmp/c1-client-late-final-316.txt`,
`tmp/c1-client-late-build-317.txt`. Preserved reports: `tmp/c1-client-late-results-313/`,
`tmp/c1-client-late-results-314/`, `tmp/c1-client-late-results-315/`,
`tmp/c1-client-late-results-316/`. Next is sandbox reader refusal and protocol correlation,
followed by the remaining ordered independent-review items.
