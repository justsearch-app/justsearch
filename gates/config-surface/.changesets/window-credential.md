---
classification: declared-growth
adr: 0046
task: t-20261102-agent-approval
---

One permanent shell launch switch, `justsearch.windowCredential` / `JUSTSEARCH_WINDOW_CREDENTIAL`,
requests the separate per-boot app-window approval credential in production. It has no effect in
development and stays unset for headless token-as-operator use. The corresponding apply-scope entry
is restart-required: changing it cannot alter authority within a running Engine. No saved file
format changes. Net growth is exactly this key: `env_sysprop_pairs` 248 → 249 and `apply_scope`
289 → 290 (baseline repinned in this change); `yaml_keys` and `config_keys` are unchanged.

Owner words (task t-20261102-agent-approval request):
"The Engine mints a second per-boot random secret, the window credential, only when the shell asks
for it at spawn (a launch flag or system property the shell sets; production only)."
