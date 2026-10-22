---
schema: test-intent.v1
task: t-20261019-c04e9b
author-role: builder
author-session: f2d31f5a-e973-4775-83c9-32ccc34c0dba
---

# Test-intent entries

One check covers the whole repair class: run-evidence.mjs cannot select parameterized cases per method.
Cases that already passed on d05fb4bba live in ArchiveInstallPreservationTest, which the gate exempts as an isolated new test class; run-evidence.mjs --before d05fb4bba (with the repair class overlaid for its zip helper) classifies it as passed on the before-state, and all 10 cases pass on the candidate, including the symlinked-home and symlinked-pack-folder cases and unlisted files named like temporaries next to an existing output (log: tmp/archive-repair-ar7/evidence-preservation.log).
ArchiveReplacementWindowsTest and ArchiveInstallWindowsPreservationTest (a home reached through a junction, or a junctioned pack folder, installs and stays installed, as before) are Windows-only isolated new test classes and did not run on this Linux host.

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
      "behaviour": "Each case pins repaired archive-install behavior through the planner, acquisition and placement with real files: failed or interrupted unpacking of a kept GPU-pack ZIP is retried from the kept ZIP, also when the AI home is reached through a symlinked folder or the pack folder itself is a symlink, which is followed like a link above it; a changed or corrupted pack replaces the old files; an install run hashes a pack installed before receipts once, records it when it matches and downloads and applies the registry pack when it does not, even at equal length; an archive that would overwrite a file another listed archive recorded with different content fails on every run, naming both, instead of the two alternating; a registry update that renames an archive replaces the one it supersedes without an overlap failure, because only receipts of archives the registry still lists for the folder take part and the receipt of one no longer listed is deleted, leaving its outputs and everything outside the receipt folder; the ORT marker is hidden while its payload changes; a changed file held open is replaced by moving it into the extractor's move-aside folder inside the pack folder, never truncated; leftovers of an interrupted apply in the extractor's staging and move-aside folders are cleaned by the next apply, while a file no archive lists survives even when named like a staging file; an entry named like another kept archive, the extractor's folders, a receipt or another entry's staging name fails its archive visibly without writing or publishing a receipt; an entry name the filesystem cannot represent fails its archive, not the install run; and hostile entries and links below the pack folder (the download staging file, the extractor's staging, move-aside and receipt folders) are rejected. Unchanged behavior that already held on the before-state lives in ArchiveInstallPreservationTest (an exempt isolated new test class, so it has no entry).",
      "defectSource": {
        "kind": "defect-source",
        "stronger": {
          "kind": "owner-words",
          "quote": "Fix the bug, park upgrade (Recommended)",
          "location": "task t-20261019-c04e9b user turn 2026-10-21 21:45, answer to: what to do about the ONNX Runtime upgrade; the chosen option read: retry failed GPU-pack unpacking, and make pack updates replace old files"
        },
        "reproduction": "On d05fb4bba (the merge-base, before fix 8789fe454), run-evidence.mjs --before ran the candidate ArchiveInstallRepairTest against the before-state production code: 41 tests completed, 41 failed, every one with AssertionFailedError (log tmp/archive-repair-ar7/evidence-repair.log). An apply that cannot write an output is reported as success (failed() 0, not 1: the failed-promoted-apply, five-archive, same-version ORT and supplemental CUDA cases). A kept ZIP with missing or partial outputs counts as installed fully, whatever the install contract (legacy cases). A kept ZIP is not planned for repair when its outputs are deleted or truncated, including below a symlinked home or symlinked pack folder, after a disk-full write, when it is corrupted at equal length, or when it alone is present (0 downloads, not 1; the extraction-only case fails on its added size assertion). A changed archive under the same name is not re-planned at equal length, and its apply fails at a different length. Install runs have no planning that verifies a pack installed before receipts, so a changed equal-length pack is never applied (the legacy install-run cases fail on that assertion). Two archives listing one file with different content both apply without failure (failed() 0, not 1). No receipt is recorded, so the renamed-archive case fails on its first receipt assertion, and the receipt of an archive the registry no longer lists is not deleted. An interrupted receipt publication raises no error, because nothing is published. Windows-style absolute and traversal names, and symlinked .archive-staging, .archive-old and .archive-receipts folders, are not rejected; acquisition through a symlinked download staging file does not fail. A file named library.dll.partial that no archive lists is deleted when library.dll is first written; leftovers in .archive-staging and .archive-old stay; a changed file held open is not replaced; entries named like another kept archive, the extractor's folders, a receipt or another entry's staging name apply without failure (failed() 0, not 1); and an entry name containing NUL throws out of the install run (assertDoesNotThrow fails). Candidate: all 41 cases pass. Evidence: gates/test-intent/evidence/archive-repair.json."
      },
      "reproduction": "On d05fb4bba (the merge-base, before fix 8789fe454), run-evidence.mjs --before ran the candidate ArchiveInstallRepairTest against the before-state production code: 41 tests completed, 41 failed, every one with AssertionFailedError (log tmp/archive-repair-ar7/evidence-repair.log). An apply that cannot write an output is reported as success (failed() 0, not 1: the failed-promoted-apply, five-archive, same-version ORT and supplemental CUDA cases). A kept ZIP with missing or partial outputs counts as installed fully, whatever the install contract (legacy cases). A kept ZIP is not planned for repair when its outputs are deleted or truncated, including below a symlinked home or symlinked pack folder, after a disk-full write, when it is corrupted at equal length, or when it alone is present (0 downloads, not 1; the extraction-only case fails on its added size assertion). A changed archive under the same name is not re-planned at equal length, and its apply fails at a different length. Install runs have no planning that verifies a pack installed before receipts, so a changed equal-length pack is never applied (the legacy install-run cases fail on that assertion). Two archives listing one file with different content both apply without failure (failed() 0, not 1). No receipt is recorded, so the renamed-archive case fails on its first receipt assertion, and the receipt of an archive the registry no longer lists is not deleted. An interrupted receipt publication raises no error, because nothing is published. Windows-style absolute and traversal names, and symlinked .archive-staging, .archive-old and .archive-receipts folders, are not rejected; acquisition through a symlinked download staging file does not fail. A file named library.dll.partial that no archive lists is deleted when library.dll is first written; leftovers in .archive-staging and .archive-old stay; a changed file held open is not replaced; entries named like another kept archive, the extractor's folders, a receipt or another entry's staging name apply without failure (failed() 0, not 1); and an entry name containing NUL throws out of the install run (assertDoesNotThrow fails). Candidate: all 41 cases pass. Evidence: gates/test-intent/evidence/archive-repair.json.",
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
          "choice": "Implement the chosen fix this way: (1) receipt: after a complete apply, record the archive's SHA-256 and entry list in .archive-receipts/<zip>.json, published atomically; a kept ZIP whose receipt is missing, pending or names another SHA, or whose listed outputs are missing or have the wrong size, is planned again as extraction-only work that charges no transfer bytes. (2) legacy acceptance rule: an install from before receipts stays installed when no apply is pending and every listed output exists as a regular file of the listed size; otherwise it is repaired whatever the install contract says. (3) hash before apply: a kept ZIP is hashed against the registry SHA-256 before extraction, and a mismatch is re-downloaded rather than unpacked. (4) move-aside replacement: changed outputs are written to a .partial sibling and replace the old file by moving it aside, never by truncating it; unchanged outputs are not rewritten; paths through symlinks, traversal or absolute names (POSIX or Windows) are rejected; a failed apply keeps the ZIP and does not spend the transport retry budget. (5) marker hiding: ort-native-version.txt is removed before any payload write, including same-version and supplemental CUDA packs, and published last, so ONNX Runtime never sees a marker over a half-written pack. (6) containment: paths are checked against the pack folder's real path, resolved once per apply; links above it (a symlinked home, a Windows junction or 8.3 short name) are followed, links and junctions at or below it are refused. (7) legacy verification: status keeps a healthy pre-receipt install installed without hashing; an install run hashes its kept ZIP once, records the receipt after confirming the outputs on a match, and downloads and applies the registry archive on a mismatch. (8) overlap: receipts record each entry's CRC-32; an apply that would overwrite a file another archive's receipt in the same folder records with a different CRC-32 fails, naming both archives, and records no receipt. Only receipts of archives the current registry lists for the folder take part: at apply, the receipt file of an archive no longer listed (one a registry update renamed) is deleted, never its outputs."
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
