# C2-3 keyed dispatcher and receipt access

The canonical dispatcher SPI now accepts an explicit optional key for invoke and
undo. Legacy implementations refuse a supplied key rather than silently dropping it;
the production executor implements it. A lookup compares public identity before
preparation or gate consumption. A matching terminal or running row yields its
metadata receipt/observation and cannot start an effect. Undo identity includes its
execution target. Fresh attempts retain the existing trust gate and acceptance
before effect. Responses carry authoritative operationKey and operationRecordId,
including Engine-minted keys for unkeyed accepted calls.

Receipt access retains provenance validation, current hard-stop/DENY and the
untrusted-plugin sandbox refusal. The read-only exploration found that client ids,
sessions and grantReference are not credentials; no invented identity comparison or
new grant resolver is used. A consumed capsule stays consumed: a receipt can be read,
but the same token cannot authorize a new key's effect. Actual recovery remains
owner-authorized; this branch only observes an already accepted row.

Negative910 bypasses the early lookup and fails3 of11 cases: both synchronous and
asynchronous retries prepare again, and an approved terminal retry requires another
capsule. Full focused911 passes the new keyed cases but fails two old assertSame
refusal-object checks, because the required response key metadata creates a copy.
The corrected assertions compare every original result field after removing and
validating exactly the two new authority fields; typed code, message, details,
retryability, failed row and zero admission all remain checked.

The root's boundary review then finds that the existing untrusted-plugin refusal
lives in handler resolution, which receipt lookup bypasses. Negative912 fails the
new named witness (7 cases,1 intended failure). The keyed read now refuses that tier
before exposing a row, while the existing trusted-plugin transport whitelist and
hard stop still apply. This does not enable plugin execution or create a new policy.

Final913 passes333 represented cases/46 suites with zero failures/errors/skips:
94 service cases execute;239 app-agent-api cases reuse unchanged successful911
inputs. Services PMD main/test execute successfully; app-agent-api PMD and UI
integration-test compilation are UP-TO-DATE with unchanged inputs. Exact command
and attribution: keyed-dispatch-verification.json.
Raw: `tmp/c2-3-dispatch-negative{910,912}.txt`,
`tmp/c2-3-dispatch-negative{910,912}-xml/`, `tmp/c2-3-dispatch{911,913}.txt`,
`tmp/c2-3-dispatch{911,913}-xml/` and `tmp/c2-3-dispatch{911,913}-counts.json`.
Retain through acceptance plus30 days and export before worktree release.

HTTP/MCP request key delivery, pending approval key carry-through, persisted
preparation, concurrent unknown-key freezing and later producer ingress remain
C2-3 work. Current production handlers are passthrough; replay-schema preparation
is still refused until that persistence mechanism is connected. This is an item
checkpoint, not whole C2-3 or batch2 acceptance.
