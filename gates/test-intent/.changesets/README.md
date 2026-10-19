# `gates/test-intent/.changesets/`

Test-intent entries for the `test-intent` gate (tempdoc 966 D1). One changeset per PR (or per task):
each flagged test file, test-owned data file or watched baseline gets an entry with a class and the
warrant that class needs, and the acceptor (never the author) appends the acceptance record.

Start from the skeleton the gate writes:

```bash
node scripts/governance/gates/test-intent/cli.mjs --skeleton <name> --task <task id> --session <your session id>
```

The rules, the classes, the source table and the record format are in
`docs/reference/testing/test-intent.md`.
