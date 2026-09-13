# MCP refusal retry safety

Final read-only review at `0b4b13ad03dd18eac0df66ab57dc8ec1071c710b` found that
McpProtocolHandler used the optional-field overload for both front admission and later
handler refusals. The strict live oracle correctly rejects the absent field.

The production encoder now emits `error.data.retrySafe: true` before dispatch and explicit
`false` after dispatch. Request IDs, JSON-RPC -32000, capacity429/Retry-After, freeze503,
and silent notifications retain their contracts. Canonical API documentation now names
MCP's field placement. No oracle was weakened.

Windows11 / Temurin25.0.2, base0b4b13ad0 plus this item:
- Old-code395 fails three loopback regressions: context refusal, frozen refusal and
  handler-thrown refusal. The handler failure is the missing boolean assertion.
- Restored396 passes all8 transport tests. The later-handler test exercises all four
  admission reasons, verifies exact status/reason/delay, explicit booleanfalse and one entry.
- Build397 passes in24s. Broader400 retains passing MCP tests but fails the existing
  parser surviving-retirement test at its successful replacement request: a cold JVM
  exceeded that fixture's one-second response budget. This failure is preserved and
  requires a separate fixture correction;400 is not reported green. The exact UTF-8
  source passes compile/gates403 (`build -x test`, stress and test Error Prone enabled).
- Documentation regeneration and canonical checks399 pass.

Evidence: `tmp/c1-mcp-retry-negative-395.txt`,
`tmp/c1-mcp-retry-negative-results-395/manifest.json`,
`tmp/c1-mcp-retry-restored-396.txt`,
`tmp/c1-mcp-retry-restored-results-396/manifest.json`,
`tmp/c1-mcp-retry-build-397.txt`, `tmp/c1-mcp-retry-build-403.txt`, `tmp/c1-mcp-docs-399.txt`,
`tmp/c1-mcp-retry-final-400.txt`, `tmp/c1-mcp-retry-final-results-400/manifest.json`.

The final reviewer has reached the owner's three-follow-up cap. The root took this two-call
diff, independently reread both encoder paths and reconciled the runnable regressions.
Fresh live fairness and integrated verification at the corrected candidate remain required.
