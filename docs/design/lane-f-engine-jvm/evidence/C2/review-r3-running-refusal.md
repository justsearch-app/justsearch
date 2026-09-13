# R3 final-review correction: start and resume refusal

The final independent review atc56e1a838 finds the RUNNING transition still treats
every false return as an existing result. If SQLite ignores a fresh start or owner
resume without changing the row, no effect runs but completion remains pending and
no degradation is reported. This is the remaining R3 ignored-transition defect.

Negative874 executes4 cases and fails the two ignored-start/resume cases because no
exception is thrown. A terminal winner remains legitimate. The correction reads the
row once after a refusal: return the captured terminal receipt, or raise STORAGE_FAILED
through the existing ERROR, sticky Health and exceptional-completion path. No effect
or new recovery state is introduced.

Root's reread caught a retention race in the preliminary two-read correction: a row
can be pruned after the first terminal read. Negative876 executes3 cases and fails
that deterministic eviction case with NoSuchElementException. The final code publishes
the captured row directly. Both terminal-winner cases (retained and immediately pruned)
pass, without repeating the body or creating a false Health failure.

Final877 executes105 cases/7 suites across the runner, dispatcher and background-owner
tasks, with zero failures/errors/skips. PMD and UI integration-test compilation pass.
Exact command/counts: review-r3-running-verification.json. Raw:
`tmp/c2-review-r3-running-negative874.txt`,
`tmp/c2-review-r3-running-negative874-xml/`,
`tmp/c2-review-r3-terminal-negative876.txt`,
`tmp/c2-review-r3-terminal-negative876-xml/`,
`tmp/c2-review-r3-running877.txt`, and `tmp/c2-review-r3-running877-xml/`.

The full869/hosted c56e1 checkpoint predates this correction. Fresh integrated proof
follows the consolidated final-review corrections, rather than a full rerun per fix.
Retain through lane acceptance plus30 days and export before worktree release.
