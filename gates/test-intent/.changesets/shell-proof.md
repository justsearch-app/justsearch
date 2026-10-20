---
schema: test-intent.v1
task: t-20261019-5ec1a1
author-role: builder
author-session: 01a15751-d591-7ff0-864a-d34f1e5cdd4c
pr: 831
---

# Test-intent entries

All 18 gate-flagged items are accounted for below. The two isolated new frontend files are
exempt by the current gate, but retain full voluntary New entries here so their execution receipts
and expectation sources are bound to the same acceptance.

The owner adopted C1-C10 on 2026-10-20 with "i agree, proceed accordingly". Scenario quotations
below are labelled agent-drafted, owner-adopted; none is relabelled owner-words. All implementation
and test-harness choices use delegated-choice, quoting the delegation and labelling the choice
agent choice. The orchestrator's brief is not cited as the owner's words.

Before-state for this changeset: the exact token-branch tip
`7df82d2ef0944cef399db3daf0f8765442e487d4` (`origin/worktree-token-bootstrap`), not 4975ceedf
and not the parent of the merge. Candidate: `51f6ba6e1` plus these uncommitted test-diagnostic and
changeset/evidence edits. No production source or test-intent gate implementation is changed here.

Evidence was written by `scripts/governance/gates/test-intent/run-evidence.mjs`. The existing
`token-bootstrap-execution-adapter.cjs` supplies shared before clones and --maxWorkers=4; it does
not change assertions or results. No selected check calls Gradle. TMPDIR and the shared Cargo
target are under /home/elias; no temporary path is created in /tmp. Before compatibility patches
are retained and digest-bound. They expose legacy behaviour to new test signatures and wire
fixtures; they do not implement process proof, origin binding, redirect rejection or retry guards.

The evidence scopes are intentional:
- original: untouched original tests at the exact token tip, all passing;
- adapted: seven candidate preservation files pass after and with before input re-encoding;
- native: 16 selected checks pass after; 13 fail before by assertion and three preservation checks
  pass before. The evidence runner exits 1 for this mixed receipt because preserved checks pass
  before; that is not represented as a failed preservation check;
- web: the two candidate security files pass after and fail before by assertion, while their
  in-flight invalidation preservation case passes before too.

Only affected test files/checks are rerun. The opt-in installed-Engine test remains explicitly
ignored in default selection and is not counted as a new pass here; the user's request reports
independent Linux verification of C1-C8 on e17eedf5d. Windows C10 remains unverified. C9's
webview socket-owner limitation is preserved as the agreed residual, not silently removed.

The commit-transport entry explicitly records the lower observation boundary of the new shim.
Its passing receipt is not called actor-path preservation. Actual actor/send-path repair receipts
are recorded separately. A separate session must review these entries and append acceptance.

```json
{
  "entries": [
    {
      "items": [
        "modules/shell/src-tauri/src/bin/supervisor_conformance.rs#cfg(test)",
        "modules/shell/src-tauri/src/engine_conn.rs#cfg(test)",
        "modules/shell/src-tauri/src/lib.rs#cfg(test)",
        "modules/shell/src-tauri/src/shell_actuator.rs#cfg(test)",
        "modules/shell/src-tauri/src/updater.rs#cfg(test)"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Register the new test modules and make the binary test BackendState constructible; these cfg(test) additions do not replace the old assertions in their parent production files.",
        "callersAndStateOwners": "The library and conformance binary include the same EngineHost, actor, updater and fixture modules. Default on the binary BackendState is test-only; the library already had Default. There is no persisted-state owner or runtime default change in these cfg(test) items.",
        "indirectBehaviours": "New test module selection is deliberate instrumentation. The separate New entries below own the new expectations. Existing commit-transport assertions are accounted for separately as Incidental because their observation boundary changed. The evidence producer uses --lib, so it does not independently exercise the binary-only Default derive; the reported independent Linux verification covers that candidate topology.",
        "evidence": "shell-proof-native.json records 16 selected library checks, and shell-proof-original.json records the original before-state preservation checks. The unchanged token-bootstrap execution adapter is used only to put before checkouts in shared clones and bound Vitest workers; it changes no oracle or result.",
        "unresolvedRisks": "Windows live C10 remains unverified, as stated in the request. No Windows execution is inferred from Linux cfg(test) evidence."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C7",
          "quote": "Desktop quit asks the Engine for an ordered shutdown with no force-kill, and an update prepare and commit succeed end to end on Linux; existing commit-transport and delivery tests pass.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Load the proof, actor, updater and credential tests through cfg(test) modules shared with their production owners.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/token-bootstrap-execution-adapter.cjs"
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_conn_test_support.rs"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Supply an owned Engine child and an independently owned listener, so sender checks can falsify process ownership instead of trusting a health response.",
        "callersAndStateOwners": "Fixture owns its EngineHost and TempDir and reaps only its recorded Child. Sleeper never owns the impostor socket. The Python server owns its socket and checks a token tied to its actual PID; its response reports that PID. The parent impostor would return plausible instance/health/token-rejection JSON if the caller writes.",
        "indirectBehaviours": "Listener accept/read and process lifetime are real OS boundaries. Three-second fixture startup/accept deadlines and a two-second read timeout fail or close the fixture; they are not product timeouts. Rejected connections disclose zero application bytes. The before-only patch loads these test modules and adapts signatures, without adding ownership proof to an old sender.",
        "evidence": "All native after checks in shell-proof-native.json pass. Its before receipt is exactly 7df82d2ef0944cef399db3daf0f8765442e487d4: 13 assertion failures and three preservation passes. The patch attached here is fully recorded by the producer with its content digest.",
        "unresolvedRisks": "The fixtures do not simulate system-wide /proc denial or a failing Windows API. Timeout and unavailable expected-owner evidence are narrower checks; the existing force-kill path is exercised by the actor rejection test."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C2",
          "quote": "Proof is per connection: a takeover between a probe and the credential-bearing connection, and any reconnect or retry, is proven again.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C3",
          "quote": "Proof targets the current Engine incarnation's PID (after supervisor restart, the new child); a dead or reused PID fails proof; port, token and identity come from one coherent snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Use separately owned processes and real sockets for the native impostor and successor fixtures.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-proof-before-compat.patch"
    },
    {
      "items": [
        "modules/shell/src-tauri/src/engine_conn_tests.rs"
      ],
      "class": "New",
      "behaviour": "Each helper expectation has a source: (C1/C2) an already-connected legitimate server is accepted but a new connection naming another PID is not; (C4) a socket still awaiting accept must time out without proof and disclose zero bytes; (C7 plus the delegated HTTP choice) transfer-coded JSON is decoded, truncated chunks are rejected, and both content-length and chunked successful replies preserve their JSON value and sent=true. The chunk assertions pin new shared-writer machinery required when replacing reqwest; they do not claim the old reqwest client could not decode chunks. Before scaffolding for connect_proven is the old plain TCP connect with PID ignored. For post_connected it is the old raw actor writer with only its connect step supplied by the caller. decode_chunks is an identity view of the old raw writer's undecoded transfer body. No OS proof or chunk decoder is transplanted into the before state.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C2",
          "quote": "Proof is per connection: a takeover between a probe and the credential-bearing connection, and any reconnect or retry, is proven again.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C7",
          "quote": "Desktop quit asks the Engine for an ordered shutdown with no force-kill, and an update prepare and commit succeed end to end on Linux; existing commit-transport and delivery tests pass.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "HTTP responses support content-length/close and chunked JSON.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-proof-native.json",
      "newChecks": [
        "modules/shell/src-tauri/src/engine_conn_tests.rs#engine_conn::tests::exact_connection_is_proven_again_after_port_takeover",
        "modules/shell/src-tauri/src/engine_conn_tests.rs#engine_conn::tests::accepted_socket_must_belong_to_expected_pid_and_timeout_writes_nothing",
        "modules/shell/src-tauri/src/engine_conn_tests.rs#engine_conn::tests::chunked_engine_json_is_decoded_and_truncated_chunks_fail",
        "modules/shell/src-tauri/src/engine_conn_tests.rs#engine_conn::tests::proven_sender_accepts_content_length_and_chunked_responses"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/session_credential_tests.rs"
      ],
      "class": "New",
      "behaviour": "C5/C6: return no credential before either field exists, do not issue a token without its origin port, issue the exact loopback origin and corresponding token together, clear the predecessor token when a new child publishes a new port, and issue only the successor pair after its token arrives. The before-only observation adapter projects the original get_session_token response as a token with no origin and no port-readiness guard; the real before-state Tauri command still returns its original string. The first token-only assertion fails before; it is not a compile failure or a fabricated new before-state origin binding.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C5",
          "quote": "Webview: the shell issues the token together with its exact origin (`http://127.0.0.1:<port>`); the token is attached only to requests whose URL origin equals it (not another host on the same port, not an override URL, not an arbitrary Request input), and never follows a redirect; the stale-token retry goes out only if the re-resolved origin equals the request's; a fake impostor on port A answering 401 TOKEN_REQUIRED while the binding moved to B never receives the new token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-proof-native.json",
      "newChecks": [
        "modules/shell/src-tauri/src/session_credential_tests.rs#session_credential_tests::credential_waits_for_both_fields_and_keeps_their_incarnation_together"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs"
      ],
      "class": "New",
      "behaviour": "C1/C4: shutdown to an impostor writes no bytes, falls back to the owned request file, and the recorded child can still be force-killed and reaped. A commit cannot reach its send hook or be marked sent before proof, even when the listener would acknowledge. C2: every later connection, including after replacing the child and binding, is checked afresh. A successful real health probe is not authority for the later send connection after port takeover. All four named checks fail by assertion at the token tip and pass after. Explicit is_err and hook-called assertions replace unwrap/panic-only diagnostics; the forbidden send hook remains forbidden.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C2",
          "quote": "Proof is per connection: a takeover between a probe and the credential-bearing connection, and any reconnect or retry, is proven again.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C3",
          "quote": "Proof targets the current Engine incarnation's PID (after supervisor restart, the new child); a dead or reused PID fails proof; port, token and identity come from one coherent snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Make the before-state fail by assertion rather than by an unwrap panic, keeping the asserted rejection and zero-byte obligations unchanged.",
          "label": "agent choice"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Preserve the existing request-file fallback before force-kill when native HTTP proof fails.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-proof-native.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::shutdown_impostor_gets_zero_bytes_and_quit_can_force_kill",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::commit_impostor_is_not_marked_sent_or_allowed_to_run_send_hook",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::every_reconnect_requires_fresh_proof",
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::takeover_after_a_successful_health_probe_is_rejected_on_the_actual_send_connection"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs"
      ],
      "class": "New",
      "behaviour": "C3/C6 positive preservation: initial, same-port successor and new-port successor use distinct admitted PIDs; the server accepts only its own PID-derived token; each response must identify the current owned process. This passes on both the original sender and the candidate. It is not claimed as a fail-before repair. The unchanged opt-in installed_engine_shutdown_upgrade_and_released_port function is separate C7 delivery instrumentation: it is still explicitly ignored in default runs, not a passed check in this receipt. Its ordered-quit, prepare/cancel/prepare/commit and released-port assertions cite C7/C1/C4; the request reports independent Linux verification of C1-C8. It is not rerun for this changeset-only task.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C3",
          "quote": "Proof targets the current Engine incarnation's PID (after supervisor restart, the new child); a dead or reused PID fails proof; port, token and identity come from one coherent snapshot.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C7",
          "quote": "Desktop quit asks the Engine for an ordered shutdown with no force-kill, and an update prepare and commit succeed end to end on Linux; existing commit-transport and delivery tests pass.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-proof-native.json",
      "newChecks": [
        "modules/shell/src-tauri/src/shell_connection_tests.rs#shell_actuator::connection_tests::supervisor_successors_use_the_new_pid_and_coherent_binding_on_same_and_new_ports"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/updater_connection_tests.rs"
      ],
      "class": "New",
      "behaviour": "C1/C4: prepare, cancel, commit and reconciliation reject the separately owned listener with a distinguishable not-proven error and disclose zero bytes; a plausible acknowledgement must not make any of the four calls succeed. Each named case fails by an assertion against the real before-state post_head and passes after. The fixture installs the same Rustls provider needed to reach HTTP on the before-state, so a missing provider is not counted as fail-before.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-proof-native.json",
      "newChecks": [
        "modules/shell/src-tauri/src/updater_connection_tests.rs#updater::connection_tests::prepare_rejects_impostor",
        "modules/shell/src-tauri/src/updater_connection_tests.rs#updater::connection_tests::cancel_rejects_impostor",
        "modules/shell/src-tauri/src/updater_connection_tests.rs#updater::connection_tests::commit_rejects_impostor",
        "modules/shell/src-tauri/src/updater_connection_tests.rs#updater::connection_tests::reconcile_rejects_impostor"
      ]
    },
    {
      "items": [
        "modules/shell/src-tauri/src/shell_commit_transport_tests.rs"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Keep the distinction between no attempted commit write and ambiguous delivery after an attempted write. This is not classified as Adaptation: the actor function is no longer the observation boundary.",
        "callersAndStateOwners": "At the token tip, these two original functions call shell_actuator::post_before_send(port, token, ...). At the candidate, a test-local function with that name shadows the actor function and calls engine_conn::connect_proven with the fixture process PID, then engine_conn::post_connected. The test owns the listener, not a host-owned Engine child. No current-host snapshot or actor PID admission is exercised by this shim.",
        "indirectBehaviours": "The old assertions remain: failed connect must not call before_send; a false before_send leaves sent=false and zero bytes; a written request followed by a missing receipt is an error with sent=true. Inputs, loopback fault listeners and read timeouts remain. Coverage is narrower at the actor boundary even though these transport assertions survive. Actor ownership is independently covered by the actual actor/sender checks in shell_connection_tests.rs and updater_connection_tests.rs, not by pretending the shim checks the actor.",
        "evidence": "shell-proof-original.json runs the untouched two test functions against the actual original actor at 7df82d2ef0944cef399db3daf0f8765442e487d4 and records both passing. shell-proof-native.json records the changed shim passing after and under legacy writer scaffolding before; its real actor checks separately fail before by assertion and pass after. The two receipts are distinct: passing the shared writer is not called proof that the actor path was preserved.",
        "unresolvedRisks": "The retained transport tests alone cannot catch bypassing EngineHost::connect_engine in the actor. That gap is explicit and is covered by the separate actor regressions. Windows live execution is not claimed."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C1",
          "quote": "Shell (Rust): shutdown, upgrade prepare, cancel, commit and reconciliation send nothing to a listener that is not the shell's own Engine process; a test impostor on the port (also one answering with the right instance ID, health and token-rejection JSON) receives zero bytes; the call fails with a distinguishable \"not proven\" error.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C4",
          "quote": "Proof unavailable (no /proc access, OS table call fails, timeout, unsupported platform): quit falls through to the existing force-kill and completes; upgrade commit reports not sent and nothing is sent twice; an impostor's acknowledgement never advances an upgrade.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C7",
          "quote": "Desktop quit asks the Engine for an ordered shutdown with no force-kill, and an update prepare and commit succeed end to end on Linux; existing commit-transport and delivery tests pass.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Exercise the same proven connection and write boundary with a listener owned by this fixture.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-proof-original.json"
    },
    {
      "items": [
        "modules/ui-web/src/api/backendRestart.test.ts",
        "modules/ui-web/src/api/http.test.ts",
        "modules/ui-web/src/api/registry/AuthenticatedCatalogs.test.ts",
        "modules/ui-web/src/shell-v0/api/authorizedFetch.test.ts",
        "modules/ui-web/src/shell-v0/controllers/liveRuns.test.ts",
        "modules/ui-web/src/shell-v0/streaming/AuthenticatedStream.test.ts"
      ],
      "class": "Adaptation",
      "moved": [
        "input re-encoding"
      ],
      "whatMoved": "The session_token test input is re-encoded from a bare string into {origin, token}, with a matching synthetic Engine origin. apiBase/test URLs test.local or test become 127.0.0.1 fixture origins; path/query assertions remain the same. Every original behavioural assertion is retained: restart notification invalidates the cache before callbacks and browser mode avoids the event API; GET/POST and catalog/schema/default-operation requests carry the token; caller same-origin headers and other headers survive; browser mode has no token; transient null is retried, successful resolution is single-flight/cached, invalidation abandons old resolution, only UI_TOKEN_REQUIRED gets one refresh and response bodies remain readable; live-run shape filtering, newest ordering, asymmetric conversation adoption and failure-to-empty outcomes survive; authenticated streams preserve chunked SSE parsing, resume checkpoints, reconnect token refresh and cold-start recovery. Literal host changes encode the issued Engine origin; they do not preserve authorization for arbitrary foreign hosts, which C5 expressly forbids and the new origin tests cover. No tags, skips, abort expectations, expected token values or invocation counts are relaxed.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C5",
          "quote": "Webview: the shell issues the token together with its exact origin (`http://127.0.0.1:<port>`); the token is attached only to requests whose URL origin equals it (not another host on the same port, not an override URL, not an arbitrary Request input), and never follows a redirect; the stale-token retry goes out only if the re-resolved origin equals the request's; a fake impostor on port A answering 401 TOKEN_REQUIRED while the binding moved to B never receives the new token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Re-encode the session_token fixture as {origin, token} and point protected requests at that issued loopback origin.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-proof-adapted.json",
      "beforeEvidence": "gates/test-intent/evidence/shell-proof-original.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/controllers/AgentSessionController.test.ts"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Preserve one operation key and the initially captured conversation across an allowed token retry, and use a fresh key for a later action.",
        "callersAndStateOwners": "runBackgroundTask is the caller; AgentSessionController owns conversation capture and the operation key, while http.ts owns the credential cache. The changed case now explicitly establishes a Tauri binding, invalidates that cache, recreates the controller at the issued origin and uses a test invoke mock.",
        "indirectBehaviours": "This changes preconditions from an implicit browser/no-binding retry to an explicit permitted desktop retry, so it is not an Adaptation of the whole oracle. The old two-identical-bodies, one-key, original-conversation, new-action/new-key, timer-count and no-toast assertions remain exactly in place. RestoreAllMocks tears down the runtime spy. C5 separately removes the old unconditional stale-token retry to a nonmatching/no-issued origin.",
        "evidence": "shell-proof-original.json runs the original file at the token tip: all its original cases pass. shell-proof-adapted.json runs the candidate file after and with only input-decoding compatibility before: all cases pass in both states. This includes the changed retry case and the unaffected controller cases, so the new top-level mock is not accepted just by shape.",
        "unresolvedRisks": "The old no-binding browser retry is deliberately not claimed as preserved: it conflicts with the agreed C5 retry restriction. Cross-origin refusal has separate fail-before evidence in shell-proof-web.json. No unrelated controller assertion or skip is changed."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C5",
          "quote": "Webview: the shell issues the token together with its exact origin (`http://127.0.0.1:<port>`); the token is attached only to requests whose URL origin equals it (not another host on the same port, not an override URL, not an arbitrary Request input), and never follows a redirect; the stale-token retry goes out only if the re-resolved origin equals the request's; a fake impostor on port A answering 401 TOKEN_REQUIRED while the binding moved to B never receives the new token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Retain the operation-key and conversation assertions while restricting the token-retry fixture to an issued desktop origin.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-proof-adapted.json"
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/api/engineOrigin.test.ts",
        "modules/ui-web/src/shell-v0/api/engineOrigin.live.test.ts"
      ],
      "class": "New",
      "behaviour": "C5: a 401 from old port A cannot obtain B's successor token; other hosts on the same port, other ports and protocols get no token; a foreign Request loses a session header; token-bearing strings and Requests cannot opt into following redirects; the common HTTP and both streaming paths obey the same binding. The real HTTP cases observe the successor-token race and a redirected request destination, not only a mocked fetch option. C6: invalidation during an in-flight resolution leaves that old credential uncached and unattached; this one preservation case passes before and after. C9: the old-incarnation token sent to A in the real race case is the explicit residual, not a claim of socket-owner proof in a webview. Before scaffolding only unwraps the token field of candidate input objects, deliberately ignoring origin; it adds no request-origin test, redirect policy or retry restriction. A redirect mismatch now fails expect(result).toBeInstanceOf(Error), rather than a rejects-helper exception, keeping the required fetch rejection and zero redirected requests.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C5",
          "quote": "Webview: the shell issues the token together with its exact origin (`http://127.0.0.1:<port>`); the token is attached only to requests whose URL origin equals it (not another host on the same port, not an override URL, not an arbitrary Request input), and never follows a redirect; the stale-token retry goes out only if the re-resolved origin equals the request's; a fake impostor on port A answering 401 TOKEN_REQUIRED while the binding moved to B never receives the new token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C9",
          "quote": "The webview cannot prove a socket's owner. An impostor can hold a port only after the Engine released it; the token bound to that origin then belongs to a dead incarnation (tokens are per boot), so it opens nothing, but request bodies sent in that window (search text, passphrases) reach the impostor.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "I’m consolidating it into one function to apply the origin and redirect rules to every webview path.",
          "label": "agent choice"
        }
      ],
      "repaired": true,
      "evidence": "gates/test-intent/evidence/shell-proof-web.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/api/engineOrigin.test.ts",
        "modules/ui-web/src/shell-v0/api/engineOrigin.live.test.ts"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/shell-v0/api/engineOrigin.test.ts"
      ],
      "class": "New",
      "behaviour": "C6 preservation: invalidating the binding while session_token resolution is in flight prevents that obsolete credential from being attached. The named check is selected separately, in addition to the whole security-file checks, so its passing before-state result is explicit rather than inferred from a file-level regression result. It passes before and after and is not claimed as repaired behaviour.",
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        }
      ],
      "repaired": false,
      "evidence": "gates/test-intent/evidence/shell-proof-web.json",
      "newChecks": [
        "modules/ui-web/src/shell-v0/api/engineOrigin.test.ts#drops an in-flight resolution invalidated by a restart"
      ]
    },
    {
      "items": [
        "modules/ui-web/src/api/http.test.ts"
      ],
      "class": "Incidental",
      "assessment": {
        "purpose": "Make the new wire-format fixtures executable against the token tip without transplanting the shell-proof authorization repair.",
        "callersAndStateOwners": "This is before-only execution instrumentation, not a production change in the candidate: the old session-token resolver can unwrap the candidate object input, but still ignores origin and retains its original sending, redirects and retry behaviour.",
        "indirectBehaviours": "The producer applies this patch before overlaying the candidate check files. All seven preservation files pass on both states. Both security files fail before by assertion and pass after; thus the projection cannot supply the missing protection. No assertions, expected values or reports are synthesized by the patch.",
        "evidence": "shell-proof-adapted.json and shell-proof-web.json record the exact token-tip revision and this patch content digest. shell-proof-original.json additionally records the untouched string-format tests on the real token tip. This entry binds the patch bytes to acceptance.",
        "unresolvedRisks": "The decoded-before run alone is not the untouched-original run; both are provided and kept separate. There is no changed before-state authorization condition."
      },
      "sources": [
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C5",
          "quote": "Webview: the shell issues the token together with its exact origin (`http://127.0.0.1:<port>`); the token is attached only to requests whose URL origin equals it (not another host on the same port, not an override URL, not an arbitrary Request input), and never follows a redirect; the stale-token retry goes out only if the re-resolved origin equals the request's; a fake impostor on port A answering 401 TOKEN_REQUIRED while the binding moved to B never receives the new token.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "owner-adopted-scenario",
          "task": "t-20261019-5ec1a1",
          "scenario": "C6",
          "quote": "Normal Engine restart on the same port, and on a new port, still recovers through the restart event; cold start with a late or partial manifest still connects within today's bounds (packaged desktop, Tauri dev, wrapper-based test runners; browser-only dev stays token-free); streams, chat streaming, uploads, downloads and aborts behave as before; in-flight requests at restart do not leak the predecessor's token to the successor or the successor's to the old port.",
          "label": "agent-drafted, owner-adopted"
        },
        {
          "kind": "delegated-choice",
          "delegationQuote": "resume with remaining work autonomously",
          "delegationLocation": "task t-20261019-5ec1a1 request",
          "delegationScope": "implementation choices inside the agreed shell-proof scenarios C1-C10",
          "choice": "Accept the candidate credential object only as input re-encoding in the before-state; deliberately ignore its origin there.",
          "label": "agent choice"
        }
      ],
      "evidence": "gates/test-intent/evidence/shell-proof-before-ui-compat.patch"
    }
  ]
}
```

## Acceptance records
