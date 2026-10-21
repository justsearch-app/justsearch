# Sandbox MCP TYPED_CONFIRM Client Harness

Staged into the sandbox at `<mapped folder>\mcp-client\` by `sandbox-launch.py`
alongside `collect-evidence.ps1` and `gui\`. Exists to make the `cohort:mcp`
mutating-tool procedure (`justsearch_ingest` via TYPED_CONFIRM) actually
followable — the MCP Inspector CLI's `--tool-arg` string-coerces every value
and cannot express `paths: string[]`, so `tools/call justsearch_ingest` cannot
be driven through it (verified against a real round).

## What's staged

| File | What it is |
|---|---|
| `index.js` | Verbatim copy of `packaging/mcpb/server/index.js` — the REAL shipped MCPB stdio bridge. Not a second bespoke client: this is the artifact JustSearch actually ships. |
| `engine-discovery.cjs` | The bridge's shared manifest validation and process/connection proof; also used for the driver's authenticated advisory stream. |
| `mcp-typed-confirm.mjs` | Thin Node driver: spawns `node index.js` as a child process, speaks newline-delimited JSON-RPC to its stdin/stdout, and drives `initialize` -> `notifications/initialized` -> `tools/call justsearch_ingest`. |

**Why Node, not PowerShell:** an earlier PowerShell driver hung waiting on
the `tools/call` response and has been removed. The sandbox already requires Node (the MCP Inspector CLI needs
`npx`), so a Node driver spawning the Node bridge is the natural, proven
shape — Node's async stream handling matches how the bridge itself streams
JSON-RPC frames, where PowerShell's synchronous `StandardOutput.ReadLine()` /
stderr handling did not.

## Usage

```powershell
node --version   # confirm Node is on PATH (same requirement as the existing
                  # MCP Inspector CLI check in collect-evidence.ps1)
node .\mcp-client\mcp-typed-confirm.mjs --target "C:\Users\WDAGUtilityAccount\Desktop\JustSearchTest\scifact"
```

Optional flags: `--port <n>` (sets `JUSTSEARCH_API_PORT` for the bridge; by
default the bridge discovers the port itself), `--bridge <path>` (default:
`index.js` next to this script), `--timeout <seconds>` (default 45),
`--out <file>` (write the raw JSON-RPC frame transcript for evidence).

Normal discovery reads `head.sessionToken` from the owner-only canonical runtime manifest
only after proving the Engine identity. An explicit `--port` or `JUSTSEARCH_API_PORT`
requires `JUSTSEARCH_SESSION_TOKEN`; it never takes a credential from another manifest.
All manual non-public reads, streams and writes below also require the
`X-JustSearch-Session` header. No HTTP token-bootstrap endpoint exists.

Read the printed `tools/call justsearch_ingest` result and the trailing
PASS/FAIL line. Per the published TYPED_CONFIRM trust-gate claim
(`docs/reference/mcp-production-server.md` §Trust Model), the response must
indicate a **PENDING authorization** (`isError:true`, "requires your
approval (gate: TYPED_CONFIRM)"), not a silent/immediate ingest — the script
distinguishes these explicitly rather than treating any response as a pass,
since a silent ingest is the actual bug this check exists to catch.

**The gate response text does not carry the pending id** (verified against
`McpToolSurface.handleConfirmationRequired` — the message says only "A
request is now showing in the JustSearch app"). Get it from the driver's
own stdout: `mcp-typed-confirm.mjs` resolves and prints it as
`PENDING_ID=<id>` right after the gate result (it does this itself by
opening `GET /api/advisory/authorization-pending/stream` and reading the
`authorization.pending` advisory's `classExtras.pendingId` — see
`AuthorizationPendingAdvisoryStreamController` / `PendingAuthorizationAdvisoryProjector`).
If you need to re-derive it by hand instead (e.g. driving a different
client), the same id is reachable two ways:

- The SSE advisory stream the driver itself reads:
  `GET http://127.0.0.1:<port>/api/advisory/authorization-pending/stream`
  — a snapshot/update frame's `payload.classExtras.pendingId`.
- Once you have a candidate id, confirm its content with the point-to-point
  peek endpoint (does not consume/approve it):
  `GET http://127.0.0.1:<port>/api/authorizations/pending/<id>`.

Resolve the pending authorization with the id (**the body field is
`pendingId`** — `AuthorizationController.handleApprove` reads
`body.get("pendingId")`; any other key name for the id 400s):

```powershell
$manifest = Get-Content "$env:APPDATA\io.justsearch.shell\runtime\manifest.json" -Raw | ConvertFrom-Json
$headers = @{ "X-JustSearch-Session" = [string]$manifest.head.sessionToken }
Invoke-RestMethod -Headers $headers -Method Post -Uri "http://127.0.0.1:<port>/api/authorizations/approve" `
  -ContentType "application/json" -Body (@{ pendingId = "<id from mcp-typed-confirm.mjs's PENDING_ID= line>"; execute = $true } | ConvertTo-Json)
```

**`execute: true` is mandatory here, not optional.** Without it, the call
mints a consent capsule and returns — it does NOT dispatch the ingest (see
`AuthorizationController`'s own Javadoc on `handleApprove`, tempdoc 655
addendum): approving normally only *authorizes* an operation the caller then
re-invokes itself with the capsule attached, which works for the browser FE
(it already has the original args to replay) but not for an MCP-originated
pending — the MCP tool call already returned, so there is no live caller
left to replay anything. `execute: true` tells the server to complete the
dispatch itself, server-side, using the SAME args the capsule is bound to. A
prior round's driving doc named a different (wrong) body key for the id with
no `execute` flag at all: the wrong key name alone made every approve
attempt 400, and even a fixed key name without `execute: true` would still
leave the ingest un-dispatched — this was misdiagnosed as "approve never
executes the operation," a false HIGH regression against a documentation
defect, not a product one. `scripts/ci/check-sandbox-authorization-field.mjs`
now fails the build if that retired key name reappears in this file or in
`governance/sandbox-coverage.v1.json`'s `cohort:mcp` procedure.

Then re-check `/api/knowledge/status` (or run the driver again with the same
target) to confirm the ingest actually proceeded after approval.
`tools/list` reachability alone does not exercise this claim — this is a
separate, mandatory step.

## Status

**Live-verified 2026-07-15** against a running dev stack, in three
independently isolated layers:

1. **Server**: fixed and conformant — `notifications/initialized` now
   returns `202 Accepted` with an empty body (JSON-RPC 2.0 §4.1 forbids
   replying to a Notification); an unknown *request* still correctly
   returns `-32601`. Verified by curl.
2. **The shipped MCPB bridge** (`index.js`), driven over stdio from a Node
   driver using this script's exact message sequence: **works end-to-end**
   — `initialize` succeeds, then `tools/call justsearch_ingest` with a real
   array `paths` returns the TYPED_CONFIRM gate response
   (`isError:true`, "requires your approval (gate: TYPED_CONFIRM)"). It
   also handles the 202-no-body notification response correctly.
3. **The PowerShell predecessor hung** — it never received the `tools/call`
   response, both before and after the server fix. The server and bridge
   are exonerated; the PowerShell driver itself was the defect.

The server, the shipped bridge, and this driver's message sequence are
live-verified. The next Sandbox round must still execute
`mcp-typed-confirm.mjs` itself end-to-end (including the post-approval
re-check) and report the result.
