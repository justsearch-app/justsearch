# Folder-protection evidence repair

Task t-20261019-5ec1a1; builder session 01a15776-d065-7d12-8b21-f9e5b2e63fdb.
The existing rejection record is unchanged. No author acceptance is appended.

## Implementation

The Engine protects each real directory before enumerating it, repairs regular files too,
and skips symbolic links/other reparse entries before opening their directory streams.
POSIX directories become 0700 and files 0600, preserving their bytes. Permission failures
warn once per protected root; skipped links warn once per link. No parent is repaired.

Windows uses the existing FFM backend with the same protected owner+SYSTEM DACL for
both files and directories. Repair opens the entry with OPEN_REPARSE_POINT and checks
attributes on that handle before setting its DACL. MAXIMUM_ALLOWED suppresses automatic
child propagation by SetSecurityInfo, so only the explicit no-follow walk selects children
([Microsoft API documentation](https://learn.microsoft.com/en-us/windows/win32/api/aclapi/nf-aclapi-setsecurityinfo)).
The native setter receives no owner/group/SACL changes. Win32 BOOL failures use captured
GetLastError; SetSecurityInfo's returned error code is preserved directly. Java 25 compiles
the class on Linux; this does not establish runtime behavior on a Windows host.

The shell warns once and leaves an existing linked runtime and its existing ledger
subdirectory's permissions unchanged. It still writes exit ledgers, real EngineHost
supervisor state, and shell_actuator shutdown requests. New directories receive private
creation permissions; existing link targets receive no repair.

## Compatible harness, not fabricated production

The original before comparisons use 4975ceedf, as requested. The computed origin/main merge-base
in this checkout is c00a0cb24; these are not described as the same revision.
No hand-written patch or production overlay is used. The prior patch files and receipts
using them or bc2a09ede have been retired.

The new helper and Windows backend do not exist at the before-state. Instead of inventing
those classes there, RuntimeDirectoryPermissionsTest is a compatible caller harness:
its real RuntimeManifestPublisher exists at both revisions. The Windows test discovers
the backend type optionally at runtime and uses a fake native setter when available.
It asserts repair of every existing directory/history/keystore/pending entry and exclusion
of links, junction descendants, parents and outside paths. On the before-state, the real
publisher makes no such repair calls, so the existing-file assertion fails. The independent
POSIX case starts with an already-0700 root and broad descendants; assertAll observes
those descendants remaining broad before and becoming 0700/0600 after. This distinguishes
recursive repair from merely locking the outer folder.

The shell policy check retains every literal expected SDDL entry and flag. Its compatible
harness reads the production constant declarations rather than referring to symbols absent
at the before-state. The test module therefore compiles when transplanted without any
production patch. Only the actual writer tests are selected for before comparisons;
construction/API checks are supplementary candidate checks, not invented old-policy failures.

A linked folder's successful writes/unlock and interrupted-keystore recovery are preservation
controls: they pass at 4975ceedf. Their New entries explicitly have repaired=false. The added
shell warning is separately a repair control: its child process uses all three real writers,
then the parent asserts exactly one warning; the before-state emits none. The child is waited
for and reaped by Command::output.

## Deliberately changed assertions

OwnerOnlyDirectoryTest previously expected an old history file to retain `rw-rw-r--`.
It now expects `rw-------`, as required by recursive file protection; the old token-bearing
bytes and parent-mode assertions remain. The old shell test asserted that a linked runtime
write returned Err and that the target remained empty. Those assertions are retired by the
linked-folder continuation choice and replaced by actual successful ledger/state/shutdown
writes with unchanged target/parent modes, plus a separate one-warning assertion. The shell
policy check retains the old literal expected values; only its observation harness moved.
Runtime reparse-warning verification is wrapped in assertDoesNotThrow so the before-state's
missing warning is an assertion failure, rather than an uncaught Mockito exception.

## Execution procedure

JAVA_TOOL_OPTIONS is unset. The unchanged token-bootstrap-execution-adapter.cjs supplies
only execution policy: it verifies the producer holds one js-gradle-slot, isolates Java
temporary files and Engine HOME/DATA directories beneath the assigned temporary folder,
and uses detached shared clones under the before root. It does not alter production code,
assertions or test results. All temporary before clones are removed by the producer.

Each receipt is written by scripts/governance/gates/test-intent/run-evidence.mjs, never
edited by hand. The whole invocation is enclosed by js-gradle-slot, for example:

```sh
js-gradle-slot node --require ./gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs \
  scripts/governance/gates/test-intent/run-evidence.mjs \
  --out gates/test-intent/evidence/private-folders-repair-v2.json \
  --before 4975ceedf --before-root "$TMPDIR" \
  --check modules/ui/src/test/java/io/justsearch/ui/runtime/RuntimeDirectoryPermissionsTest.java#windowsRepairIncludesExistingFilesAndSkipsEveryReparsePoint
```

The changeset lists the exact check IDs in each receipt. The preservation producer's real
exit is expected to be 1 when its passing before controls are classified `passed`, because
the generic producer uniformly expects before assertions. That is not reported as a script
pass. No compile/environment/uncaught-exception result is used as repair evidence.

## Platform checks

A standalone Java probe compiled from the actual helper/backend sources repaired synthetic
runtime and encryption trees under a unique /dev/shm folder. Existing files became 0600,
real directories 0700, contents stayed intact, and the data parent, symlink target and its
file retained their modes. `sudo -n -u nobody` was denied all ten attempts to list/read/create/
rename/delete inside the two protected roots. The probe's two child processes exited and
were waited for; its data was removed. No Engine was started and ~/.config/justsearch
remained absent. Windows distinct-account execution remains unverified.

## Observed results

`js-gradle-slot ./gradlew spotlessApply :modules:configuration:test :modules:app-api:test
:modules:app-services:test :modules:ui:test --continue -PskipWebBuild=true` exited 0:
BUILD SUCCESSFUL in 11m 38s. JUnit reports contained 6,824 cases: 6,805 passed and
19 existing skips, with no failures or errors. The privacy checks were not skipped.

`cargo test --manifest-path modules/shell/src-tauri/Cargo.toml --lib exit_ledger::tests::`
passed all 19 selected tests. The standard whole-crate run exited 101 with the seven
known updater_probe handoff failures and one transient ETXTBSY in the untouched deleted-
executable identity fixture. That fixture passed its isolated rerun. The whole-crate
serial rerun (`cargo test --manifest-path modules/shell/src-tauri/Cargo.toml --
--test-threads=1`) exited 101 with exactly the seven known updater_probe handoff failures;
154 library tests and 125 conformance tests passed. No test was weakened or skipped.

`javac --release 25` on WindowsOwnerOnlyDirectory.java exited 0 on Linux. No Windows
native execution or real distinct-account Windows result is claimed.

The whole-producer repair replay exited 0: all 13 selected candidate checks passed;
all 13 failed by assertion at 4975ceedf. The receipt lists no overlays and no patches.
The preservation replay exited 1 (the producer requires before assertions): all four
candidate checks passed and the same four passed at 4975ceedf. They are explicitly
classified as preservation, not repair. The supplemental API replay exited 0: nine
Java helper/backend cases and the shell descriptor case passed. All receipts were
written by the unchanged producer and their check-file digests match the final files.
All shared before clones were removed. The existing rejection record and every
previous token-bootstrap artifact remain byte-identical to HEAD. No Engine process
was started; ~/.config/justsearch was confirmed absent after these runs.

## Hard-link and iterator follow-up after 46816f593

The owner delegated the hard-link repair exception under the existing autonomy wording.
The changeset quotes that choice as an agent choice and cites P6/P7. Existing acceptance
and rejection records, prior receipts, and token-bootstrap artifacts are unchanged.

On Unix, a regular entry's `unix:nlink` is read with NOFOLLOW_LINKS before the permission
setter. More than one link leaves that inode unchanged and warns once for that path per
process. Windows uses nNumberOfLinks (unsigned DWORD at offset 40) from the already-opened
handle's BY_HANDLE_FILE_INFORMATION, before extracting/applying the DACL. The existing
FFM path still closes the handle on rejection. The Linux-runnable Windows guard check
covers two links, the high unsigned count, single-link files, real directories and reparse
points. It is a supplemental new API check; it does not execute Win32 on Linux.

DirectoryIteratorException from hasNext/next is now caught by the repair failure handler.
It warns once for the protected root, preserves existing entries and lets startup continue.
The new publisher unit check injects an actual failing DirectoryStream iterator and checks
construction, manifest publication, one warning and preservation of an old history marker.
The hard-link publisher unit check uses a real hard link outside the data folder and
checks the outside file's 0664 mode, bytes, same-inode relationship, unchanged parent/target
modes and one warning over repeated publication, while the runtime and manifest are private.
Neither test requires production scaffolding or new APIs at the before-state 46816f593.

Only the changed test files need fresh receipts: the two new publisher expectations compare
against 46816f593; the seven unchanged runtime repair controls and one unchanged runtime
preservation control are replayed against 4975ceedf. Entries for unaffected checks continue
to reference the original receipts. The Windows test file is replayed for its new guard API
check and unchanged descriptor/mask/unavailable-native oracles. No pre-existing assertion
or test selection was weakened. Each whole-producer job runs under js-gradle-slot; the execution-policy adapter uses
shared before clones and isolates Java HOME/DATA/temp.

The focused follow-up command was `js-gradle-slot ./gradlew spotlessApply
:modules:configuration:test :modules:app-services:test --tests
io.justsearch.app.services.encryption.EncryptionKeystorePermissionsTest :modules:ui:test
--tests io.justsearch.ui.runtime.RuntimeDirectoryPermissionsTest --continue
-PskipWebBuild=true`, with JAVA_TOOL_OPTIONS unset. It exited 0 (BUILD SUCCESSFUL in 25s):
342 configuration cases, five encryption-folder cases and ten runtime-folder cases passed;
none failed or skipped. No broad suite was rerun. All pre-existing assertions are retained.

Follow-up receipt results: `private-folders-hardlinks-iterator-v3.json` was produced with
exit 0: both candidate checks passed, and both failed by assertion at 46816f593. The hard-
link failure reports expected 0664 permissions but actual 0600 on the outside inode; the
iterator failure reports the unexpected DirectoryIteratorException wrapping the injected
IOException. `private-folders-runtime-repair-v3.json` exited 0: all seven unchanged checks
passed now and failed by assertion at 4975ceedf. Neither receipt has patches or overlays.
The unchanged linked-runtime preservation replay exited 1 under the generic producer
because its check passed on both revisions, correctly recorded as preservation.
`private-folders-windows-api-v3.json` exited 0 with all four cases passing. This includes
the new native-information guard API check; Windows native execution remains unverified.
