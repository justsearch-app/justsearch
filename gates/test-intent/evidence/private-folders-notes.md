# Folder-protection evidence repair

Task t-20261019-5ec1a1; builder session 01a15776-d065-7d12-8b21-f9e5b2e63fdb.
The existing rejection/acceptance records are unchanged. No author acceptance is appended.
Earlier sections describe reviewed pre-merge revisions; current merge results are in the final section.

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
in that pre-merge checkout was c00a0cb24; these are not described as the same revision.
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

## Merge onto main after token PR #825 was squashed

Pinned main: 4f595453517bbf430bf907117f2c60303b7357ba. Merge-only commit:
75853ca84, with pre-merge branch b932c1224 as its first parent and that main as its
second parent. No fetch was made after pinning and no rebase was performed.
Token-only paths match this main byte-for-byte. Cargo's workspace lock updater retained
main's package versions and added only the shell's direct libc dependency; the Unix
directory implementation and Windows ACL feature remain this branch's own additions.
The live project generator rebuilt the route/OpenAPI/client projections after the merge;
all match main exactly. The offline generator check also passed for all five hermetic sets.

Main now intentionally stores keystore.json plus keystore.replica.json and returns save
outcomes. The merged implementation retains its full validation, read/restore, save,
rollback and cleanup logic. Folder preparation runs before exists/load/save filesystem
access, using main's exception/result mapping. The privacy fixture's old assertion of
one retained file is replaced with the literal primary+replica filename set. Both bytes
are checked after interrupted replacement; both files' 0600 modes are checked throughout
setup, passphrase/recovery changes and repair of a broad restore. These expectation
changes follow P2/P3 and the quoted merge choice. Existing test inputs and usability
assertions remain; no main-owned test was edited.

The fresh receipts use pinned main for the ordinary repair and preservation controls.
The hard-link/iterator controls still compare with the explicitly requested historical
regression 46816f593. The helper/backend API checks remain supplemental candidate checks
because main does not define those APIs. Prior receipts and all rejection/acceptance
records are retained. Main's updated gate exempts the new Java test files from mandatory
entries; their existing entries/evidence are retained and refreshed anyway.

The unchanged producer is run five times as fresh ES modules in one Node process under
one js-gradle-slot. It writes each receipt itself; the temporary driver only sets argv,
selects the before revision, records each actual exit and checks the expected exit.
The existing token execution-policy adapter continues to enforce whole-producer slot
ownership, private HOME/DATA/test temp directories and shared-clone before states.
Neither the producer nor adapter is modified. No overlay or hand-written before patch is
used. The preservation producer's actual exit 1 is retained as such because before passes.

The installed Engine was started with private HOME/DATA inside the assigned temporary
root and umask 002. Runtime and encryption were 0700. The first live generator attempt
returned HTTP 401 for OpenAPI because it received a client-supplied token; the corrected
run read the boot token from the owner-only manifest without printing it and completed
successfully. Both Engine processes were stopped and reaped; their isolated HOME/DATA
were removed and ~/.config/justsearch was confirmed absent. Windows distinct-account
native behavior remains unverified.

The main KeystoreStartupAvailabilityHttpTest read-only rows originally relied on directory
mode 0500 alone. The required privacy repair makes that 0700, so the intended real replica
write failure no longer occurs. The fixture now also denies only the folder-mode repair
setter for those rows. The directory remains genuinely read-only to the OS, all other
filesystem operations call through, and every existing assertion/parameter row is unchanged.
The adaptation receives an identical-fixture preservation replay on pinned main and the
candidate, covering all eight read-only/blocked/read-error/read-only-filesystem restart/save
rows. No token-only file is changed by this adaptation.

The post-merge full touched-module invocation used :modules:configuration:test,
:modules:app-api:test, :modules:app-services:test, :modules:ui:test and the offline
OpenAPI check, with --continue and -PskipWebBuild=true. It exited 1: configuration
345 cases (two sandbox-override failures), API 460 cases (no failures, five skips),
app-services 4,079 cases (one sandbox-override failure, 11 skips), UI 2,124 cases
(two read-only fixture failures, one 30-second lifecycle timeout, one skip).

The full-suite test execution policy now leaves launcher HOME/DATA overrides unset for configuration
and app-services contract fixtures; UI tests that may start an Engine remain isolated.
The recheck passed all 345 configuration cases and all 82 selected runtime/startup cases.
Of 56 encryption/GPU cases, 55 passed and one relative GPU path case still failed while
HOME was injected; that exact failure also reproduced on pinned main. Removing both
launcher overrides for that fixture made all six GPU cases pass on the candidate.
The unchanged lifecycle test passed in isolation on main (17.672s) and candidate
(20.502s), with the original 30-second timeout and all 1,296 combinations retained.
No timeout, assertion or selection condition was weakened.

Full cargo test --locked completed 217 library cases successfully and 131 conformance
cases successfully, with the seven known updater_probe handoff failures (exit 101).
Those seven failures reproduced on pinned main after staging its unchanged required
Linux launch helper; the remaining selected handoff case passed. The first baseline
attempt lacked that installed helper and failed all eight with ENOENT; it is not used
as comparison evidence. The Node engine-discovery suite passed 31 cases and had one
existing platform skip. The project generators/installDist passed; no snapshot differs
from pinned main. No commit was made after the requested merge.

The corrected GPU fixture run passed all six cases on both the candidate and pinned main,
with its original no-launcher-override environment. The temporary baseline clone was removed.
Final receipt validation passed: 13 repair cases pass now and fail by assertion on pinned
main; four preservation cases pass on both revisions; 11 supplemental API cases pass now;
two historical regression cases pass now and fail by assertion on 46816f593; all eight
adapted startup cases pass on both revisions. Each receipt names the unchanged producer
script digest and matches the final test-file digests; every before overlay/patch list is
empty. Actual producer exits were 0, 1, 0, 0, 1 (the two before-passing preservation jobs
retain exit 1). All shared before clones were removed.

The default test-intent CLI resolved its base to the pinned origin/main merge-base and
exited 1 with exactly one problem: stale acceptance for private-folders.md. The advisory
rolling audit rate was one third-reviewer tie-break among nine audited PRs (11.1%).
All three existing private-folder review records are unchanged. All token-only paths
and generated snapshots match pinned main. HEAD remains the sole requested merge commit;
the fixture adaptation, refreshed changeset/notes and five new receipts are uncommitted.

The shared origin/main ref advanced during the work to 738e6a443c9d6ce82383e9c938c264a3a20fb506. Per the owner instruction, the merge remains pinned to 4f595453517bbf430bf907117f2c60303b7357ba; no follow-up merge or rebase was made. The default gate still resolves the PR merge-base to the pinned revision.

## PMD static-mock resource adaptation (PR #873)

The only test-code change renames the unused `nativeSecurity` resource to `_` in
`windowsRepairIncludesExistingFilesAndSkipsEveryReparsePoint`. Its conditional static-mock
initializer, answer, resource ordering, scope and automatic close are identical. Every
assertion, input, fault injection, platform condition and test selection is unchanged.

One `js-gradle-slot ./gradlew ... --continue` invocation ran `pmdMain`, `pmdTest`,
`compileJava` and `compileTestJava` for configuration, app-api, app-services and ui, plus
`:modules:ui:test --tests '*RuntimeDirectoryPermissionsTest*'`. It exited 0 with
BUILD SUCCESSFUL: all eight PMD reports contain zero violations, and all 10 runtime
permission tests passed without skips. No additional PMD fix was needed.

The unchanged evidence producer and token-bootstrap execution-policy adapter ran whole
under `js-gradle-slot`, with JAVA_TOOL_OPTIONS unset and all temporary files and shared
before clones below `/home/elias/.cache/justsearch-private-folders-builder`. The three
new v5 receipts retain the previous check selections; v4 receipts and every review
record remain unchanged. The repair/preservation before-state is the current PR
merge-base `eae9993ac7ec8fd0e17a0d0c8adb935ee3121741`; the explicitly requested historical
hard-link/iterator regression state remains `46816f593`.

- `private-folders-merge-repair-v5.json`: producer exit 0; all 13 candidate checks
  passed, and all 13 before-state checks failed by assertion.
- `private-folders-merge-preservation-v5.json`: producer exit 1 because its four
  before-state controls passed; all four checks passed on both revisions. This is
  preservation evidence, not a producer pass or a fail-before claim.
- `private-folders-merge-hardlinks-iterator-v5.json`: producer exit 0; both candidate
  checks passed, and both historical before-state checks failed by assertion.

No receipt uses a production overlay or hand-written patch. The producer removed all
three before clones. No Engine was started for these permission tests.
