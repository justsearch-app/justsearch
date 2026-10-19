---
schema: test-intent.v1
task: rust-esrch
author-role: builder
author-session: cc6d91b2-5314-42fd-867b-dbc2d8a68699
---

# Linux process exit races

The first two checks pin repaired exit classification (`New` with `repaired: true`).
The third is new coverage for bounded rechecking while preserving the existing unknown verdict
for a process whose birth identity still matches; it is not a repair of that existing verdict.
All three cover the changed inline test section in `linux_process.rs`.

Before-state references supplied by the verifier: `892243645` with the held-stat test moved in,
and `1a29a58f4`'s capture behavior for the two executable-link checks. The committed compatibility patches
only expose the existing read-classification and capture seams. They retain the old ESRCH
classification and the immediate executable-unreadable return, without adding either repair.

Existing candidate receipts are retained at `tmp/rust-esrch-round2/after-tests.log` and
`tmp/rust-esrch-round2/full-shell.log`. Both executable-link checks passed in both test binaries;
the full library run passed all three named checks and finished with 153 passed and 0 failed.
These raw logs are supporting receipts, not substitutes for the script-generated D4 files
referenced below.

The repository evidence runner generated both D4 files without hand edits:

| Check | Before-state and result | Candidate result |
| --- | --- | --- |
| `reaped_process_stat_read_is_ended` | `892243645`: assertion failure, `Other` versus `NotFound` | Executed and passed |
| `unreadable_executable_then_exit_is_ended` | `1a29a58f4`: assertion failure, `Other` versus `NotFound` | Executed and passed |
| `unreadable_executable_still_present_after_window_is_unknown` | `1a29a58f4`: the repeated-read assertion fails with `matching identity must be rechecked` | Executed and passed |

For the third check, Rust's custom `assert!` message omits the standard assertion prefix.
The evidence runner therefore records its fail-before classification as `exception`; that
script-produced classification is retained unchanged. The failing source assertion is
`assert!(birth_reads > 2, "matching identity must be rechecked")`. This check is `New`, not a
repair of the existing unknown verdict, so its entry does not require an assertion-classified
fail-before. Both entries marked `repaired: true` have assertion-classified fail-before records.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/linux_process.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "A held /proc/<pid>/stat file read after the child is killed and reaped returns Linux ESRCH; the same classifier used by birth() must map it to NotFound with the message process ended, rather than unreadable identity.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task rust-esrch request",
          "quote": "An ESRCH (raw OS error `libc::ESRCH`, or the std equivalent) from reading `/proc/<pid>/stat` means the process ended."
        }
      ],
      "evidence": "gates/test-intent/evidence/rust-esrch-stat.json",
      "newChecks": [
        "modules/shell/src-tauri/src/linux_process.rs#linux_process::tests::reaped_process_stat_read_is_ended"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/linux_process.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": true,
      "behaviour": "When the executable link is unreadable but birth initially still matches, capture rechecks birth within the bounded window and reports NotFound process ended if birth subsequently reports an ended process. The scripted test preserves one matching recheck before the ended result.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task rust-esrch user turn builder-round-2",
          "quote": "If the process becomes Z, X or gone in that window, return the NotFound \"process ended\" error."
        }
      ],
      "evidence": "gates/test-intent/evidence/rust-esrch-capture.json",
      "newChecks": [
        "modules/shell/src-tauri/src/linux_process.rs#linux_process::tests::unreadable_executable_then_exit_is_ended"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/linux_process.rs#cfg(test)"
      ],
      "class": "New",
      "repaired": false,
      "behaviour": "A persistently unreadable executable link with an unchanged, present birth identity remains an Other/unreadable error after the bounded recheck window. The test requires repeated birth reads, so an immediate unreadable result does not satisfy the new bounded-recheck contract. It does not authorise GONE from the missing executable link alone.",
      "sources": [
        {
          "kind": "owner-words",
          "location": "task rust-esrch user turn builder-round-2",
          "quote": "If it is still present with the same identity after the window, keep today's \"unreadable\" error."
        },
        {
          "kind": "owner-words",
          "location": "task rust-esrch user turn builder-round-2",
          "quote": "Never declare a process gone while it is present with a matching identity and not in state Z, X or x."
        }
      ],
      "evidence": "gates/test-intent/evidence/rust-esrch-capture.json",
      "newChecks": [
        "modules/shell/src-tauri/src/linux_process.rs#linux_process::tests::unreadable_executable_still_present_after_window_is_unknown"
      ]
    }
  ]
}
```

## Acceptance records
