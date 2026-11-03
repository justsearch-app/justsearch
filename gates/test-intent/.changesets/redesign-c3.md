---
schema: test-intent.v1
task: redesign-c3
author-role: builder
author-session: 01a1a093-f88e-7202-98e7-53ccd15f5ffb
---

# C3: one transient source witness

The intent is the owner-supplied C3 contract and challenger revision in this builder's request.
The new isolated test files are exempt from entries; their expectations are C3-1, C3-2,
C3-4, C3-6, C3-7 and preservation cases P1, P6, P7. Capability checks read back ctime,
sub-millisecond mtime and file keys; an unavailable capability is unverified (a skipped case),
never a pass. No run-evidence files or acceptance records are authored here.

```json
{
  "entries": [
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/BulkWriteChunkDeclarationTest.java",
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/VduNativePublicationFixture.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/HeldFileWriter.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/UntaggedScanCollectionRemovalTest.java",
        "modules/indexer-worker/src/test/java/io/justsearch/indexerworker/loop/WorkerUserExclusionsEngineTest.java"
      ],
      "class": "Adaptation",
      "moved": [
        "signature"
      ],
      "whatMoved": "Synthetic FileEnvelope construction now wraps FileFreshnessSnapshot. Paths, normalized keys, path hashes, file keys, sizes, millisecond mtimes, kinds and observation times keep their original inputs; FileTime.fromMillis is the same input in the full-precision representation and optional ctime is absent for these synthetic mocked-stale-resolver fixtures. Inputs, fault injection, observations and assertions are unchanged."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/FileFreshnessClassifyTest.java",
        "governance/logic-seams.v1.json#law-targetTests"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "A null-to-non-null or non-null-to-null file key change between captures counts as changed.",
          "location": "task redesign-c3 request"
        },
        {
          "kind": "owner-words",
          "quote": "It keeps the full FileTime, the file key and the optional Unix ctime.",
          "location": "task redesign-c3 request"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: docs/decisions searched for null file-key, FileFreshness, ctime and freshness; none concerning this witness. The former null-key-ignore law in the logic seam is superseded by C3-7 in the owner request. Other classifier expectations and targetTests remain unchanged; synthetic timestamps are re-encoded as FileTime."
    },
    {
      "items": [
        "modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/UnchangedSourceReadFailureTest.java"
      ],
      "class": "Obsolete",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "When the unchanged acknowledgment fails its final witness check, the job goes to stale/retry handling and does not fall through to extraction within the same attempt",
          "location": "task redesign-c3 request"
        },
        {
          "kind": "owner-words",
          "quote": "A source that vanished stays distinguished from one that cannot be read, including the existing root-presence requirement before treating it as deleted.",
          "location": "task redesign-c3 request"
        }
      ],
      "conflictSearch": "decision records searched for a conflict: docs/decisions searched for freshness; no conflicting source found. The former expectations of parser failure after mutation/deletion are superseded by C3-6. Real stale handling now asserts deferral on mutation, deletion transition on confirmed absence, and no extraction on either path. Hash read failure and planned-hash parser failure retain their retryable-failure assertions. Parameterized display names identify each overlay run."
    }
  ]
}
```

## Site classification

- Queue admission (`observeAdmissionSource`, atomic candidate admission) and maintenance preservation:
  converted to FileFreshnessSnapshot; the duplicate AdmissionWitness and ctime reader are deleted.
  The sharing check also mutates the source immediately before and immediately after its real
  admission hash, rejecting both observations; extractor callbacks cover post-hash and final-ack timing.
- SourceContentHash.admissionIdentity: converted through the existing shared snapshot. The raw
  sha256/sha256WithinRoot functions stream bytes without certifying currency; their certifying
  callers bracket them with the shared witness. Scan/queue paths that merely record planned hashes
  are admission inputs rather than current-result certification; extraction still validates them.
- JobBatchExtractor: captures via WorkerIngestionAuthority before the first hash, checks after
  hashing and before unchanged acknowledgment, preserves the extraction-policy/collection checks,
  and defers stale shortcuts without extraction. Post-extraction validation uses the same witness.
- JobBatchWriter and StaleSnapshotResolver: converted through FileEnvelope, which carries the
  original snapshot intact to the existing pre-write check. No second metadata representation.
- WorkerIngestService.unchangedWatcherSource and committedBootFileMatches: converted. The first
  is admission preservation around a hash, rather than the disabled raw watcher hasher. RegistryIdentity
  at ~311 identifies a registry file for loading, rather than certifying source content.
- SyncDirectoryOps root preservation and NativePolicyReplay normal readmission: converted;
  their persisted millisecond encodings and ancestor/root/collection evidence remain intact.
- WatcherReplay.observe: converted; matches only compares already-observed source hashes with
  the index, and does not itself read source bytes. Raw watcher hashing remains out of scope.
- KnowledgeServerMigrationOps.observeForCleanup: converted for the regular-file hash proof.
  observedKind still encodes persisted millisecond sourceState and nonregular/absence states;
  it no longer serves as the after-hash freshness comparison. Root-presence checks stay intact.
- Release030RecoveryState stores/loads sourceState without reading source bytes; not transient
  certification, unchanged. DocumentIdentityBootImport is explicitly out of scope.
- Persisted size/mtime discovery in SyncDirectoryOps and candidate reconciliation is out of scope.
  A same-size offline edit with restored mtime can still be missed without forced reconciliation.

Full times and ctime stay transient. No schema, ledger encoding, recovery sourceState encoding,
sourceState millisecond string, or AdmissionPolicy reason code changes. Ctime changes map to
existing CONTENT_CHANGED. Null key availability changes map to existing FILE_KEY_CHANGED.
Platforms without ctime keep full mtime, size and file-key checks. An external writer may still
change a file after the final observation; there is no filesystem fence excluding it.

## Run-evidence arguments for the independent verifier

Repair checks (only assertion failures establish fail-before):

```bash
env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot node scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/redesign-c3-repair.json \
  --before fb9b1ffdc0 \
  --overlay modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java \
  --overlay modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/AdmissionSourceWitnessTest.java \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#unstableShortcutDefersWithoutExtractionAndRetryPublishesNewBytes \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#writerRejectsRestoredMtimeRewriteAndRetryPublishesNewBytes \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#subMillisecondMtimeIsComparedIndependentlyOfCtime \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#fileKeyAvailabilityTransitionsAreChanges \
  --check modules/worker-services/src/test/java/io/justsearch/indexerworker/loop/SourceWitnessCertificationTest.java#unavailableAttributesAfterHashNeverAcknowledgeUnchanged \
  --check modules/indexer-worker/src/test/java/io/justsearch/indexerworker/queue/AdmissionSourceWitnessTest.java#queueAndExtractorUseIdenticalWitnesses
```

Preservation checks are `SourceWitnessCertificationTest#unmodifiedShortcutReadsContentOnlyOnce`
and `#replacementWithSameSizeAndMtimeIsChanged`, plus the requested complete worker-services,
indexer-worker and worker-core suites on base and candidate. Do not put preservation checks in
an assertion-fail-before run. `unmodifiedShortcutReadsContentOnlyOnce` prints source-file
readAttributes/getAttribute API calls (including SourceAccess checks) and pins exactly one content
hash call with no extraction. Each shared capture uses one basic read and one optional unix:ctime
attribute probe, compared with one basic read in the old extractor witness and two probes in the
old queue witness.

The measured unchanged extractor attempt has two witness captures before and three after: admission,
post-hash validation (new), and final acknowledgment validation. The witness-only budget is one
basic stat per capture before, versus one basic stat plus one ctime probe per capture after: two
witness probes before and six after. Counting the source file's direct BasicFileAttributes reads
and getAttribute calls throughout that attempt (including SourceAccess and placeholder probes,
counting getAttribute's internal readAttributes(String) delegation only once) gives:

| Probe | Base | Candidate |
| --- | ---: | ---: |
| Basic attributes | 4 | 5 |
| Unix ctime | 0 | 3 |
| Other attributes (placeholder probes) | 3 | 3 |
| Total | 7 | 11 |
| Content hash calls | 1 | 1 |
| Extraction calls | 0 | 0 |

The local focused C3 cases have no skips on this filesystem. All nine repair cases fail by
JUnit assertion on fb9b1ffdc0 and pass on the candidate; stable-source and replacement controls
pass on both. The before tree is a shared clone under the supplied temporary folder; each tree
uses its own build/test resources. Shared check resources are the Gradle cache and slot scheduler.
No existing owner service or daemon was stopped. `.agents/preflight` is absent.

## Baseline environment exceptions

These failures occurred on fb9b1ffdc0 and both candidate full runs; they are outside C3:

- `LinuxRootMountInfoTest#missingDanglingAndAmbiguousUuidMappingsAreUnknown`:
  `NoSuchFileException: /dev/disk/by-uuid` (the explicit disk-backed fixture requires UUID aliases).
- `WatcherUnavailabilityScopeTest#aNewerUnavailableIncarnationCannotBeClearedByAnOlderReconciliation`:
  expected true, observed false.
- `WatcherUnavailabilityScopeTest#endedUnavailableIncarnationIsRecoverableOnlyByItsOwnReconciliation`:
  attributed root loss expected to preserve runtime-wide certainty, observed false.
- `WatcherUnavailabilityScopeTest#inaccessibleRootWhoseAbsenceIsUnknownIsStillRootScoped`:
  unreadable root expected to retain root-scoped uncertainty, observed false.

The full base run otherwise had only the nine expected C3 assertion failures. The final candidate
indexer run had 2,504 reported cases and no failures (39 existing skips). Worker-core had 652
reported cases and no failures in the initial run (15 existing skips), reused as UP-TO-DATE by the
final run. Worker-services finished with exactly the four baseline failures above; the final
pre-push command passed, including PMD and UnreferencedCodeTest. Existing platform/resource skips are not claimed as passes; the
focused C3 cases have zero skips. No Windows runtime check is claimed; null-ctime comparisons
exercise the fallback representation on Linux.

## Builder check commands

- `env -u DISPLAY -u WAYLAND_DISPLAY js-gradle-slot ./gradlew -PskipWebBuild=true :modules:worker-services:test :modules:indexer-worker:test :modules:worker-core:test --continue`:
  completed; failed only for the four baseline environment exceptions above.
- Focused worker cases `SourceWitnessCertificationTest`, `UnchangedSourceReadFailureTest`, and
  `FileFreshnessClassifyTest`: 18 cases passed with zero skips. The final complete indexer run
  also passed `AdmissionSourceWitnessTest`, including its before/after admission-hash mutations.
- Shared base clone, overlaid isolated C3 checks: eight worker assertions and the queue witness
  assertion failed as intended; stable-source and replacement controls passed, zero C3 skips.
- `env -u DISPLAY -u WAYLAND_DISPLAY node scripts/dev/java-prepush.mjs --unreferenced`:
  passed (includes the existing excluded real-drive integration test named by that script).
- `git diff --check` and changeset digest parsing: passed.

No run-evidence files or acceptance records were created. These are builder observations for
handover; independent verification remains with the orchestrator.

## Acceptance records
