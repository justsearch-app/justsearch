---
schema: test-intent.v1
task: updater-v2
author-role: builder
author-session: codex-builder-rel-upd
---

# Test-intent entries

Independent acceptance belongs to the verifier. Baseline checks used a shared clone of
4bf3524394 with its own target directory, not a source worktree or a running service.
Two parser/format assertions fail there (missing target and Windows-only format), and the
sequence check fails 42 versus 43. Classification has no existing Linux implementation at the
baseline. The seven reported binary handoff failures reproduce at the real probe stub.
The before-state parser adapter only calls the baseline's existing serde v1 parser; it adds no
v2 support. The test-only descriptor fixture is made visible to the sibling format test.
The fake pkexec child gets a private PATH containing only the fake executable, and no real
pkexec or apt is reachable from that child. Installed state in that seam is synthetic: this host
has no installed JustSearch package. Real file staging and the production handoff coordinator
are used. Relaunch and post-start reconciliation on installed Windows/Linux remain VM work.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/updater.rs#cfg(test)",
        "modules/shell/src-tauri/src/lib.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_actor_hold_witness_tests.rs",
        "modules/shell/src-tauri/src/updater_commit_delivery_tests.rs",
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "Adaptation",
      "moved": [
        "signature",
        "input re-encoding"
      ],
      "whatMoved": "Existing handoff oracles retain their stop paths, fault injections, receipts, hashes, durable witnesses, hold and resume assertions. Artifact fixtures now use debian-binary ar bytes on Linux and the same MZ bytes on Windows, with lengths and digests following those fixture inputs. The launch seam carries a typed error with the same injected messages; all original errors mean resumable. The Command import is test-only after moving the shared child reconciler. Test-only descriptor helpers use v2 and the running target and add the artifact key ID; the wire parser is exercised separately. pub(super) on test_descriptor only lets the new sibling download-format check reuse its fixture. No existing assertion is removed or weakened.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Keep everything the Windows path already does around the install step: authorization, Engine hold and admission drain, shutdown receipts, descendant termination, durable INSTALL_LAUNCHING, reconciliation through `/api/upgrade/reconcile` after start (version equality alone never commits an update).",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        ".github/workflows/ci.yml"
      ],
      "class": "Structural rule kept",
      "rule": "Both existing shell library lanes and producer tests remain required and unfiltered. The Linux Rust lane additionally runs the supervisor-conformance binary unit tests; the Windows lane runs exactly what it ran before, because the binary's one Windows-only handoff test does not compile there (it names a helper only the library has, as on main); the release test step additionally runs the v2 producer and sequence checks.",
      "whySurvives": "No job, condition, runner, assertion, command or filter is removed. The previously broken binary handoff suite now uses the same child reconciliation implementation as the library rather than the probe error stub.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Since you change the handoff, make these tests meaningful again (run them where they belong, or fix the probe) without weakening what they check, and say which.",
          "label": "agent choice"
        }
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_linux.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater_linux_tests.rs",
        "modules/shell/src-tauri/src/updater_v2_tests.rs",
        "modules/shell/src-tauri/src/updater_handoff_tests.rs"
      ],
      "class": "New",
      "behaviour": "The producer-generated, metadata-signed two-target v2 bytes select Windows or Linux explicitly and retain every installed durable owner. Missing targets/owners, tampered metadata, wrong root/key ID and a lower sequence fail. The real Tauri downloader verifies the delegated key for Debian bytes and rejects a Windows-artifact signature attached to them. Linux format checks require an ar debian-binary version member, while Windows still requires MZ. Fake pkexec records separate exact argv including absolute .deb path; exit 0 creates a launch witness; 126/127 resume and re-offer; 100, other failure, a signal and spawn errors resume only with unchanged installed version/files. File-only changes or inability to inspect state retain the Engine hold and require manual repair.",
      "sources": [
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Tests: both parsers (client Rust, producer and any JS consumer) against bytes the producer actually generated, including a two-target descriptor, a tampered byte, a wrong metadata key, a mismatched artifact signature, a lower sequence, a missing target for this platform, and a compatibility table missing a predecessor store.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "Linux install classification tests for 0, 100, 126, 127, a signal, a spawn error, unchanged versus changed installed state; use a fake pkexec/apt seam, not the real one.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "no, i think its better if we just make the next release for both windows and linux, theres no hurry. and id like you to takeover that work.",
          "delegationLocation": "task t-20261027-4e4d6b request",
          "delegationScope": "preparing the next release for Windows and Linux, including the Linux package, the in-app updater for both platforms and its release assets",
          "choice": "S8 \"Linux in-app update via descriptor v2 and pkexec apt-get: success relaunches the new version; cancelled prompt, lock timeout or failure resumes the old Engine and re-offers the update\".",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/updater-round4-v2.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::producer_bytes_select_both_targets_and_cover_every_installed_store",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::producer_bytes_enforce_metadata_signature_root_identity_and_rollback",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::tauri_download_verifies_producer_deb_and_rejects_mismatched_signature",
        "modules/shell/src-tauri/src/updater_v2_tests.rs#updater::v2_tests::authenticated_deb_bytes_pass_download_validation",
        "modules/shell/src-tauri/src/updater_linux_tests.rs#updater::linux_installer::tests::linux_install_exit_classification_preserves_old_engine_only_when_safe",
        "modules/shell/src-tauri/src/updater_linux_tests.rs#updater::linux_installer::tests::deb_format_requires_complete_version_member",
        "modules/shell/src-tauri/src/updater_handoff_tests.rs#updater::handoff_tests::linux_fake_pkexec_handoff_records_exact_arguments_and_recovers_or_holds"
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "claude-verifier-60x_8huq",
  "verdict": "accept",
  "digest": "sha256:b4c1ef0dc92f72204462f80cbab2637191e77113a89808f30007de5101b88e5e",
  "note": "Verifier of d0895c8587. Adaptation: removed assertions in updater.rs/commit-delivery tests are only v1->v2 URL inputs and reformatting; no oracle weakened. ci.yml only adds the supervisor-conformance binary tests and v2 producer/sequence suites; nothing removed. New checks pass on the candidate per evidence; Linux S8 checked end to end on an Ubuntu 24.04 VM (cancel resumes and re-offers; accepted 0.4.0->0.4.1 relaunches and commits). Delegation quotes not checked against the task record (not available to the verifier)."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-ka8rebaa",
  "verdict": "accept",
  "digest": "sha256:9e3396666c571ad85ef4af3f03ca898f5d4ffb2bcdc8793395d75ecc872d7f4f",
  "note": "Delta review of eba4c31c28 relative to previously accepted 3d0cd9a1cf. Windows entire shell job is byte-identical to main a0022c79f1; Linux binary tests and all existing selections survive. Windows-only handoff test references a helper in the library test crate, absent from the binary root (source inspection; Windows not run). Structural rule and whySurvives are accurate against main. All other entries and cited evidence are unchanged from the accepted head; Rust/VM behavior was not rerun in this delta review. Section 10.7 body is byte-identical after relocation; size gate fails before at 809 and passes after at 771. Gate has only this stale acceptance before appending. Node subprocess stdin pipes receive sandbox EPERM; checks use a saved harness transporting the same stdin bytes through private files, with normal error propagation. Independent session; only the Codex model family is available here."
}
```

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "codex-verifier-ug7ys9vf",
  "verdict": "accept",
  "digest": "sha256:86c4b36ba56fd2e12969910492a34419f3b9027095868a51897f6967b3fc6d80",
  "note": "Delta acceptance of aa94ff13faecfa72b3063aa51b55faf9e0e2bb25 relative to accepted e0bf131af5. Exact delta: Linux-only cfg plus comment for the Debian Tauri downloader test and regenerated evidence; same seven checks and unchanged assertions. All seven focused checks executed and passed without skips on baseline and candidate after building each checkout own native helper as CI does. Searched branch Rust additions and all crate sources for AppHandle, tauri, mock apps, plugins, handle, updater and emit references and followed changed test helper paths; no other new Windows-test-reachable Tauri runtime use found. The download test explicitly verifies Linux Debian bytes; unguarded v2 parser and format tests retain Windows coverage. Windows execution and STATUS_ENTRYPOINT_NOT_FOUND repair are unverified because no Windows runner is available. Initial full Linux suite: 210 passed, 52 failed, 1 ignored due to missing native helper and denied X11 socket setup; retained as failed evidence, not a full-suite pass. Acceptance is for this test-intent delta, not release sign-off. Separate verifier session; only the Codex model family is available. Supplied owner quote checked against this brief; private task records were not accessed."
}
```
