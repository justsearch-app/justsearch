# WP5: design-record corrections (docs only)

Type: corrections to design statements the review found inaccurate or unfalsifiable. Each
edit is a dated line in §0 plus the owning section (§17.6). No code.

## 5.1 §13 complexity ledger: separate "widened by the merge" from "caused by the merge"

The ledger's "required by the merge's own consequences" row includes items whose underlying
problem also existed under the split:

- **Operation outcome after a crash.** Under the split, a write in flight when the *Worker*
  died also had an unknown outcome, even though the API survived. The merge adds that the
  API itself and the client connection also die. **Edit:** "widened by the merge (the
  connection is now lost too); the unknown-outcome problem predates it". Keep it required.
- **Journaling writes accepted during a rebuild.** The split's Blue/Green migration already
  lacked a journal (§17.4 says so). **Edit:** move it to the "independent capability
  investment" row, with the note "fixes a pre-existing gap".
- **Foreground/background fairness at the encoder gate.** The same unfair single-permit
  semaphore existed in the Worker. **Edit:** capability investment, "fixes a pre-existing
  gap".

**Why it matters:** the ledger is how the design separates what the merge needs from what
the owner chose to build. Accurate rows keep future scope decisions honest. No scope change
follows from this edit.

## 5.2 §17.1 "rollback is one revert"

**Edit:** "Before the merge, withdrawing the lane is one revert of the squash. After a
release it is not: the release changes persisted formats (`ui/settings.json` schema 4, new
`operations.db`, `jobs.db` v21), so recovery is fix-forward only, through the updater and
the dead-Engine path. See WP2 for downgrade behavior and safeguards."

## 5.3 §4 and §16: make the positive-benefit claim observable without new gating

The lane's case is "lower coordination cost". After the E re-cut, the representative-changes
row is conditional, and the deletion count is already met. **Add** to the E record, reported
and not gating, cheap objective measures taken on both sides:
- full-suite wall-clock (`./gradlew test`) on `main` vs the branch;
- dev-stack start-to-first-search time;
- `verification` profile boot time (D2-2);
- whether `reload` (hot reload) covers the whole Engine (yes or no, with one probe);
- production Java line counts per ring (API front, core, edges), so the size growth is
  explained rather than implied.

This gives the owner real numbers on the thesis without adding a gate.

## 5.4 Stage F: a subsystem map in the PR body

The final squash is about 2.6k files. **Add** to `stages/F.md` F-8 (PR 1 to green-and-ready):
the PR body carries a subsystem map with these columns:
- module or area;
- stages that changed it;
- managed review record ids covering those commits;
- the acceptance rows that exercise it.

A reviewer can then navigate the diff by subsystem and trace each part to its review and its
proof.

## Implementation plan

| # | Item | R/I | Acceptance |
|---|---|---|---|
| 5.1 | §13 ledger edit plus §0 line | R | text review |
| 5.2 | §17.1 edit | R | text review; consistent with WP2 2e |
| 5.3 | E record section in `stages/E.md` §8 | R | the section lists the five measures and their commands |
| 5.4 | F-8 subsystem-map requirement in `stages/F.md` | R | text review |

**Estimate:** under 0.5 session, at any batch boundary.
