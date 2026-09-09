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

Root reviewed this bounded correction. Late worker failure reporting is the next separate item;
final C1 review and integrated/live/hosted acceptance remain required.
