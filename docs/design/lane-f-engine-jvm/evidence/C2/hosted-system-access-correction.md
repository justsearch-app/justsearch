# C2-6 hosted system-access residue correction — 2026-09-14

Hosted CI34794274393 at09f91917e failed SystemAccessFunnelTest because nine
allowlist coordinates no longer had direct system-access sites after the accepted
activation/installer migration. Local1438 reproduces the exact nine-entry failure.
Remove only those obsolete entries, preserving the shrinking ratchet and its tests.
Local1439 executes the guard successfully; config-surface passes with eight existing
note-level findings (available shrink, known dead keys and declared growth).

Raw evidence: active worktree tmp/hosted1437-failure.txt, workflow1437.txt,
sysaccess1438-red.txt/-counts.json/-xml, sysaccess1439.txt/-counts.json/-xml and
sysaccess1439.sarif. Retain through lane acceptance plus30 days and export before
worktree release. No new hosted-success claim; the next push must prove this fix.

Hosted correction proof: [CI34795894101](https://github.com/justsearch-app/justsearch/actions/runs/34795894101)
and CLA34795892998 succeed at7f665a33d1ab55d8c493d1e322e8f22c31f39c70.
The earlier wire/cleanup runs were superseded, not successful. This hosted result
covers the pushed replay/allowlist checkpoint; the subsequent public-producer
changes still require their own hosted result.
