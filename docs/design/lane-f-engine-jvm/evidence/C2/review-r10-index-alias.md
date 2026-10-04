# R10 final-review correction: canonical index-root exclusion

The final review found that parent-only canonicalization assigned separate sibling
locks to an existing index directory and its symlink or junction alias. Resolve the
whole existing base before deriving its sibling. Preserve the real-parent fallback
for a genuinely missing base, and refuse existing unresolved aliases. This keeps the
lock outside the directory that legacy import may move.

Negative878 fails both alias/target acquisition orders (8 cases, 2 failures). The
preliminary correction passes17 cases at879. Root then tests an unresolved Windows
junction: negative880 fails acquisition refusal (7 cases, 1 failure). Checking
existence without following links closes that final-component gap.

Final881 executes18 cases/3 suites, zero failures/errors/skips; PMD and UI integration
test compilation pass. Both acquisition orders prove same-JVM refusal, child-JVM
refusal while held, and child acquisition through both paths after release. Windows
uses a junction; Linux uses a symbolic link. Fixture creation failures fail the test.
The configured Linux search-worker CI task executes this class; hosted execution at
the corrected revision remains owed. Exact command/counts: review-r10-index-alias-verification.json.

Raw evidence: `tmp/c2-review-r10-alias-negative878.txt`,
`tmp/c2-review-r10-alias-negative878-xml/`, `tmp/c2-review-r10-alias879.txt`,
`tmp/c2-review-r10-alias879-xml/`, `tmp/c2-review-r10-alias-dangling880.txt`,
`tmp/c2-review-r10-alias-dangling880-xml/`, `tmp/c2-review-r10-alias881.txt`,
and `tmp/c2-review-r10-alias881-xml/`. Retain through lane acceptance plus30 days;
export before worktree release. Fresh full proof follows all final-review corrections.
