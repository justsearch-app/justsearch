# C2 MCP outcome tool: ADR premise correction

2026-09-13, Windows/PowerShell, reviewed base15bac5f90 plus the ADR/probe amendment.
Hosted [CI34775117498](https://github.com/justsearch-app/justsearch/actions/runs/34775117498)
completed all13 jobs:12 passed; Public claims failed because ADR0015 still expected six
MCP tools. Runner availability is no longer blocking this run. CLA34775116640 passed.
Windows-native success does not prove the separately required installed schema5 scenarios.

The canonical ADR now records the required seventh read-only operation-outcome query as a
narrowed premise. The original six remain ordered first. The one-key recovery query has a
different schema and effect contract from status or dispatched mutations. Historical four-tool
selection evidence is preserved; no six/seven-tool comparative quality measurement is claimed.
The stage's live MCP and real-model obligations remain required. This changes no merge boundary.

## Verification

- Local1291 reproduced the hosted failure: expected6, found7.
- Local1294/1296 pass the amended exact-seven probe, with zero errors,50 notes and three
  inherited warnings (RISK003/006/008).
- Negative1295 inserts an actual eighth ToolDefinition into the production registry; the
  gate fails specifically with expected7/found8. The registry is restored byte-for-byte.
- Docs1299 passes llms generation/check, skills-sync check and canonical-link verification.
- Independent Sol/high review is clear after correcting the README's stale probe-id example.
- The subsequent source-comment correction says 'all production tools'; it changes no registry
  or behavior. Local1300 rechecks the final probe after that comment-only edit.

Commands: `node scripts/governance/run.mjs --gate adr-coverage --mode gate`;
`node scripts/docs/llmstxt-generate.mjs` and `--check`;
`node scripts/docs/skills-sync.mjs --check`;
`node scripts/docs/verify-canonical-doc-links.mjs`.

Raw logs/SARIF/source restoration evidence are listed and hashed in
[the manifest](mcp-adr-verification.json). They are accessible under this worktree's `tmp/`;
retain through lane acceptance plus30 days and export before removing the worktree.
No fresh hosted green run containing this amendment or new Gradle execution is claimed here.
