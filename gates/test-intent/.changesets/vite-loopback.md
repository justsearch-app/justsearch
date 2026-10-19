---
schema: test-intent.v1
task: vite-loopback
author-role: builder
author-session: vite-loopback-builder
---

# Dev UI loopback isolation

Owner source: builder brief in the task request, scenarios V1–V5. V1 requires
loopback-only listening and LAN refusal; V2 requires refusal of non-local Host
before proxying; V3 preserves localhost/IPv4 UI, API, discovery and SSE; V4
preserves the printed URL on Windows; V5 preserves frontend harness launches.

`scripts/dev/test-dev-runner-vite-loopback.mjs` is a new durable script test
(outside the test-intent gate's flagged product-test paths). No existing checks
are removed or weakened. It covers launch arguments, the harness override,
real listener binding, Host refusal and local UI/proxy/SSE requests.

The owner accepted V1 as no wildcard listener plus LAN refusal, and accepted
separate owned Vite CLI processes after the in-process baseline attempt exited
139. That attempt (`tmp/vite/before-tap.log`) is infrastructure failure and is
not counted as scenario evidence. CLI children are recorded at spawn and stopped
through the existing identity-bound fixture helper; live Linux tests acquire
`/tmp/js-linux-port/devstack.lock` without taking over a held lease.

The detached baseline is `abe1aff3af934b4cd2de096bd2ff235f35361599`, with only the
new script overlaid and dependency/distribution links. `tmp/vite/before-cli.log`
records the launch and live listener assertions failing on unrestricted `--host`
and `*:port`, LAN HTTP 200, and V2 failing on non-local IP Host HTTP 200 through
both proxies. The harness override and local UI/proxy/SSE preservation checks
pass there. `tmp/vite/after.log` records all five checks passing: only IPv4
loopback listeners, LAN ECONNREFUSED, and non-local Host HTTP 403 with no backend
hits. Windows-native execution is reserved for hosted CI; the production launch
and printed URL use literal IPv4 without a platform-dependent localhost lookup.

Review follow-up (owner request): the Tauri `beforeDevCommand` also supplied a bare
`--host`, and its `devUrl` still named localhost. Both now explicitly use IPv4
loopback. The current develop-ui/use-ui how-to URLs match that address; historical
records and the jseval localhost auto-serve sentinel retain their original meaning.

`scripts/dev/test-dev-runner-vite-launchers.mjs` is an additional durable script
test (outside flagged product-test paths). It scans shipped operational source,
configuration and current documentation with Vite/frontend launch references;
bare and non-loopback Host flags fail, including source argv arrays. Test fixtures
and recorded historical evidence are excluded. A separate JSON-reading check pins
the Tauri launch host and devUrl. `tmp/vite/review-launchers-before.log` records two
assertion failures on the pre-review candidate (detached worktree with the original
three production fixes overlaid); `tmp/vite/review-launchers-after.log` records both
passing after the Tauri repair. Existing regression expectations are unchanged.

The gate flags the production regular expression's `.test(...)` call as a test
configuration block. The Incidental entry below records the actual impact on
test execution. No acceptance record is written by this builder.

```json
{
  "entries": [
    {
      "items": ["modules/ui-web/vite.config.js"],
      "class": "Incidental",
      "assessment": {
        "purpose": "Bind the dev UI to IPv4 loopback by default and reject non-local Host before the Engine proxy rewrites Host.",
        "callersAndStateOwners": "Vite dev and preview install the existing API proxy plugin; Vitest loads the same configuration. Backend discovery and cached manifest identity are unchanged. No saved state format, test selection, setup file or test expectation changes.",
        "indirectBehaviours": "Normal localhost and 127.0.0.1 UI/API/discovery/SSE requests remain usable. The guard also protects the existing preview proxy. Existing Vitest discovery and execution still select all 529 files and 6884 assertions.",
        "evidence": "tmp/vite/before-cli.log and tmp/vite/after.log exercise the production launch and real HTTP/socket boundaries. tmp/vite/ui-unit-before.log and tmp/vite/ui-unit-suite.log each record 529 files and 6884 assertions passing, with pre-existing unhandled teardown errors. tmp/vite/ui-unit-retry.log records the unchanged full unit command exiting 0, with all 529 files and 6884 assertions passing.",
        "unresolvedRisks": "Windows-native execution awaits hosted CI. The full unit suite exhibited teardown races on both baseline and candidate; no checks or failure handling were weakened, and the normal command passed on candidate retry. Independent acceptance is pending."
      },
      "sources": [
        {
          "kind": "owner-words",
          "quote": "a request reaching the dev server with a non-local Host is refused rather than proxied",
          "location": "task vite-loopback request, builder brief scenario V2"
        },
        {
          "kind": "owner-words",
          "quote": "the dev UI works at the URL the dev-runner and MCP print (localhost and 127.0.0.1), including /api and /.well-known/justsearch proxying and SSE.",
          "location": "task vite-loopback request, builder brief scenario V3"
        }
      ]
    }
  ]
}
```

## Acceptance records

```json
{
  "kind": "acceptance",
  "role": "verifier",
  "session": "verifier-vite-loopback-cc6d91b2",
  "verdict": "accept",
  "digest": "sha256:36f632e5f481c3125aad9d594a6318f456ae5964788c0d3d80aee36b7e336d49",
  "note": "Independently verified: vite.config.js change is incidental to tests (529 files/6884 tests pass on base and candidate); live LAN refusal and non-local Host 403 confirmed, base fails both."
}
```
