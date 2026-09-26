# C2-3 validated key lookup prerequisite

The shared runner now exposes a read before preparation. The SQLite implementation
uses the same canonical public-identity and missing-key checks for lookup and
acceptance. Lookup returns an existing capability or unknown without accepting a
row; acceptance still repeats the comparison transactionally. All six stored states
compare input, operation reference, kind and invoke/undo before a receipt is usable.
Calling start with the lookup capability cannot execute a new body.

Negative906 replaces only the new checked read with the existing raw-find behavior:
8 cases execute and7 intended assertions fail. Negative908 separates malformed,
future and expired cases so each refusal has its own failing witness:10 cases and9
intended failures. The other six witnesses independently cover stored states. The
correct source is restored exactly after each control. Neither negative is a
baseline-build claim: the public lookup interface is new.

Preliminary907 passes292 cases and executes all three selected module test tasks.
Final909 passes294 represented cases across62 suites, zero failures/errors/skips;
app-observability executes, app-api and app-launcher reuse unchanged907 results.
Affected PMD and UI integration-test compilation pass (unchanged compilation may
reuse its previous output). Exact command/task attribution: key-lookup-verification.json.
Raw: `tmp/c2-3-lookup-negative{906,908}.txt`,
`tmp/c2-3-lookup-negative{906,908}-xml/`, `tmp/c2-3-lookup{907,909}.txt`,
`tmp/c2-3-lookup{907,909}-xml/` and `tmp/c2-3-lookup{907,909}-counts.json`.
Retain through lane acceptance plus30 days; export before worktree release.

This is the store/runner prerequisite for C2-3a. Request key propagation, receipt
access enforcement, persisted preparation and approval are the next changes in
C2-3-plan.md. No prepared producer or new public request behavior is activated by
this prerequisite. Whole C2 remains open.
