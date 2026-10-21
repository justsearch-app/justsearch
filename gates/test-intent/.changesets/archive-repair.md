---
schema: test-intent.v1
task: t-20261019-c04e9b
author-role: builder
author-session: f2d31f5a-e973-4775-83c9-32ccc34c0dba
---

# Test-intent entries

One check covers the whole repair class: run-evidence.mjs cannot select parameterized cases per method.
Cases that already passed on d05fb4bba moved to ArchiveInstallPreservationTest, which the gate exempts as an isolated new test class; run-evidence.mjs --before d05fb4bba (with the repair class overlaid for its zip helper) classifies it as passed on the before-state, and all 5 cases pass on the candidate (log: tmp/archive-repair-intent/before-preservation.log).
The earlier whole-file before-run (tmp/archive-repair-intent/explore.log: 29 tests completed, 24 failed) shows these same 5 cases executing and passing there.

```json
{
  "entries": [
    {
      "items": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ArchiveInstallRepairTest.java"
      ],
      "change": "added",
      "kind": "test-code",
      "class": "Defect pin",
      "behaviour": "Each case pins repaired archive-install behavior through the planner, acquisition and placement with real files: failed or interrupted unpacking of a kept GPU-pack ZIP is retried from the kept ZIP, a changed or corrupted pack replaces the old files, the ORT marker is hidden while its payload changes, and hostile entries and redirected paths are rejected. Unchanged behavior that already held on the before-state lives in ArchiveInstallPreservationTest (an exempt isolated new test class, so it has no entry).",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-words",
          "quote": "Fix the bug, park upgrade (Recommended)",
          "location": "task t-20261019-c04e9b user turn 2026-10-21 21:45, answer to: what to do about the ONNX Runtime upgrade; the chosen option read: retry failed GPU-pack unpacking, and make pack updates replace old files"
        },
        "reproduction": "On d05fb4bba (the merge-base, before fix 8789fe454), run-evidence.mjs --before ran the candidate ArchiveInstallRepairTest against the before-state production code: 24 tests completed, 24 failed, every one with AssertionFailedError (log tmp/archive-repair-intent/evidence-repair.log). An apply that cannot write an output is reported as success (failed() 0, not 1: the failed-promoted-apply, five-archive, same-version ORT and supplemental CUDA cases). A kept ZIP with missing or partial outputs counts as installed fully, whatever the install contract (legacy cases). A kept ZIP is not planned for repair when its outputs are deleted or truncated, after a disk-full write, when it is corrupted at equal length, or when it alone is present (0 downloads, not 1; the extraction-only case now fails on its added size assertion instead of on getFirst()). A changed archive under the same name is not re-planned at equal length, and its apply fails at a different length. An interrupted receipt publication raises no error, because nothing is published. Windows-style absolute and traversal names, and symlinked .partial and .archive-receipts paths, are not rejected; acquisition into a symlinked pack folder does not fail. Candidate: all 24 cases pass. Evidence: gates/test-intent/evidence/archive-repair.json."
      },
      "reproduction": "On d05fb4bba (the merge-base, before fix 8789fe454), run-evidence.mjs --before ran the candidate ArchiveInstallRepairTest against the before-state production code: 24 tests completed, 24 failed, every one with AssertionFailedError (log tmp/archive-repair-intent/evidence-repair.log). An apply that cannot write an output is reported as success (failed() 0, not 1: the failed-promoted-apply, five-archive, same-version ORT and supplemental CUDA cases). A kept ZIP with missing or partial outputs counts as installed fully, whatever the install contract (legacy cases). A kept ZIP is not planned for repair when its outputs are deleted or truncated, after a disk-full write, when it is corrupted at equal length, or when it alone is present (0 downloads, not 1; the extraction-only case now fails on its added size assertion instead of on getFirst()). A changed archive under the same name is not re-planned at equal length, and its apply fails at a different length. An interrupted receipt publication raises no error, because nothing is published. Windows-style absolute and traversal names, and symlinked .partial and .archive-receipts paths, are not rejected; acquisition into a symlinked pack folder does not fail. Candidate: all 24 cases pass. Evidence: gates/test-intent/evidence/archive-repair.json.",
      "sources": [
        {
          "kind": "owner-words",
          "quote": "Fix the bug, park upgrade (Recommended)",
          "location": "task t-20261019-c04e9b user turn 2026-10-21 21:45, answer to: what to do about the ONNX Runtime upgrade; the chosen option read: retry failed GPU-pack unpacking, and make pack updates replace old files"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously, from now on prefer codex for new agents heavily",
          "delegationLocation": "task t-20261019-9d870e user turn to session justsearch-8b, 2026-10-19",
          "delegationScope": "the remaining work of the session, including how to implement fixes the owner chose; it does not change an established contract",
          "label": "agent choice",
          "choice": "Implement the chosen fix this way: (1) receipt: after a complete apply, record the archive's SHA-256 and entry list in .archive-receipts/<zip>.json, published atomically; a kept ZIP whose receipt is missing, pending or names another SHA, or whose listed outputs are missing or have the wrong size, is planned again as extraction-only work that charges no transfer bytes. (2) legacy acceptance rule: an install from before receipts stays installed when no apply is pending and every listed output exists as a regular file of the listed size; otherwise it is repaired whatever the install contract says. (3) hash before apply: a kept ZIP is hashed against the registry SHA-256 before extraction, and a mismatch is re-downloaded rather than unpacked. (4) move-aside replacement: changed outputs are written to a .partial sibling and replace the old file by moving it aside, never by truncating it; unchanged outputs are not rewritten; paths through symlinks, traversal or absolute names (POSIX or Windows) are rejected; a failed apply keeps the ZIP and does not spend the transport retry budget. (5) marker hiding: ort-native-version.txt is removed before any payload write, including same-version and supplemental CUDA packs, and published last, so ONNX Runtime never sees a marker over a half-written pack."
        }
      ],
      "evidence": "gates/test-intent/evidence/archive-repair.json",
      "newChecks": [
        "modules/app-services/src/test/java/io/justsearch/app/services/ai/install/ArchiveInstallRepairTest.java"
      ]
    }
  ]
}
```

## Acceptance records
