# C2-6 cut1: settings preparation and storage

2026-09-13. The preparation/storage foundation is implemented and independently reviewed;
the runner/owner integration and producer migration remain required. The
[verification manifest](settings-preparation-verification.json) pins source and raw evidence.

ConfigStoreRebuilder now prepares without publishing and propagates all preparation failures,
including exclude-pattern serialization. ConfigStore separates the snapshot swap from
notification, preserving its convenience update behavior. UiSettingsStore prepares a private
copy and bytes, binds that preparation to its store, and uses a forced strict atomic replace.
Legacy AtomicFileWrites callers retain their fallback; strict settings writes refuse it.
Cleanup failure is suppressed onto the primary write failure instead of replacing it.

The settings envelope is v3. Its witness is zero/no key or positive/canonical UUIDv7; raw
legacy settings and v1/v2 envelopes remain readable, with no revision fields allowed in a
legacy envelope. Future versions refuse. Incomplete modern envelopes cannot become raw
defaults; schema integer overflow refuses. The historical raw UiSettings schemaVersion0/1
field remains readable. Existing migration tests retain their meaning and now expect a v3 save.

Inspection fails closed on unknown accessibility, invalid contents or quarantine evidence.
A preserved corrupt sibling survives restart, preventing a missing file from becoming a false
zero witness. A newly present valid file can be inspected on the same store before notification,
which enables later exact-new classification after an ambiguous move. Transitional raw save
uses this same inspection and refuses positive/unknown witnesses. Recovery replacement and
notification are explicit separate seams; the old recovery-cleared test still proves the
callback, through that split. Cut2/3 must establish recovery authority and retire every raw
writer before C2-6 closure; this cut does not claim the full atomic row/file protocol.

Final1259 passes85 represented cases in16 suites, zero failures/errors/skips:76 app-services
cases execute and9 configuration cases restore from cache using the original restored inputs.
PMD and ui:installDist pass in18s. The command applies formatting; it is not a separate
spotlessCheck claim. Earlier1254/1256 pass results remain retained and do not replace the final
regressions. Negative1258 represents23 cases with6 intended failures: strict force/fallback
and force-failure ordering, private-copy protection, inaccessible-parent refusal, and quarantine
restart protection. Originals were restored byte-for-byte at its end. Two subsequent settings
fixes (legacy-witness rejection and raw-save inspection) are covered by Final1259.

Independent review reconciled every final and negative XML count and inspected failure messages;
no wrong-reason pass is used. The raw logs retain JVM and PMD warnings. Canonical configuration
documentation and generated matrix are refreshed; docs/index/embedding/link/matrix checks pass
in docs1262. No configuration authority, module edge, generated skill behavior or global colors
are introduced by this settings cut.

The live browser1260 campaign matches five installed/built jar fingerprints and passes its
functional reconnect/reload checks, but fails its new accessibility assertion on additional
existing contrast issues. It remains FAILED and is not settings fault-matrix proof. Full/stress,
runner/file fault injection, all-producer live tests, installed and hosted proof remain required.
Forced bytes plus atomic rename do not establish physical-power-loss durability.

Raw artifacts remain accessible through lane acceptance plus30days and must be exported before
worktree release. No owner input or permission is pending.
