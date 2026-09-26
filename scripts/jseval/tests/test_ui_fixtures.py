"""Tests for the deterministic route-mock fixtures (tempdoc 615 §13 Move 1 / §16).

These pin the two experiment-found traps (the non-fail-open parse boundary needs
schema-valid bodies; the matcher must not catch the FE's own /src/api modules) and
the registry-catalog required shape, so a future edit can't silently reintroduce
the 502-storm / boot-break / module-load failure.
"""
from __future__ import annotations

import json

from jseval import ui_fixtures


class TestApiPathPredicate:
    def test_rest_root_matches(self):
        assert ui_fixtures.is_api_path("http://localhost:5174/api/status")
        assert ui_fixtures.is_api_path("http://localhost:5174/api/registry/operations")
        assert ui_fixtures.is_api_path("http://localhost:5174/api")

    def test_fe_source_modules_do_NOT_match(self):
        # The experiment's run-2 bug: a glob `**/api/**` served these as JSON and
        # broke module loading. The path predicate must exclude the source tree.
        assert not ui_fixtures.is_api_path("http://localhost:5174/src/api/http.ts")
        assert not ui_fixtures.is_api_path("http://localhost:5174/src/api/domains/status.ts")
        assert not ui_fixtures.is_api_path("http://localhost:5174/node_modules/.vite/deps/api.js")


class TestFixtureBodies:
    def test_boot_critical_bodies_are_valid_json_objects(self):
        for needle in ("/api/status", "/api/knowledge/search", "/api/settings"):
            body = json.loads(ui_fixtures.fixture_body(f"http://x{needle}"))
            assert isinstance(body, dict) and body, f"{needle} fixture is empty/non-object"

    def test_registry_catalogs_have_required_schema_keys(self):
        # operationCatalogSchema / resourceCatalogSchema / diagnosticChannelCatalogSchema
        # all require these keys (types/registry.ts, types/diagnostic.ts). A bare {} fails
        # the non-fail-open parse boundary. Operations intentionally carry the two Help
        # actions used by structural captures; the other catalogs remain minimal-empty.
        cases = {
            "/api/registry/operations": "Operation",
            "/api/registry/resources": "Resource",
            "/api/registry/diagnostic-channels": "DiagnosticChannel",
        }
        for needle, primitive in cases.items():
            body = json.loads(ui_fixtures.fixture_body(f"http://x{needle}"))
            assert body["primitive"] == primitive
            for key in ("schemaVersion", "catalogVersion", "namespace", "entries"):
                assert key in body, f"{needle} missing required {key}"
            if primitive != "Operation":
                assert body["entries"] == []

        operations = json.loads(
            ui_fixtures.fixture_body("http://x/api/registry/operations")
        )["entries"]
        assert {entry["id"] for entry in operations} == {
            "core.copy-diagnostic-summary",
            "core.export-diagnostics",
        }

    def test_unmapped_api_path_gets_empty_object(self):
        assert ui_fixtures.fixture_body("http://x/api/something/unmapped") == "{}"

    def test_indexed_roots_substrate_is_mapped_and_schema_valid(self):
        # Tempdoc 615 §33/§37.1: LibrarySurface strict-parses /api/indexing-roots/substrate
        # (non-fail-open parseWireContract, listResponseSchema {items, count?}). Before the fix it
        # was unmapped → {} → tripped the parse → logged [WireContract] drift → a fixtures gap that
        # ui_measure tagged `app` (a false bug). The mapped body must be a schema-valid empty list,
        # so `library --fixtures` renders the real empty-roots state with a clean console.
        body = json.loads(ui_fixtures.fixture_body("http://x/api/indexing-roots/substrate"))
        assert body == {"items": [], "count": 0}

    def test_walkthrough_seed_dismisses_welcome(self):
        assert "welcome" in ui_fixtures.WALKTHROUGH_SEED
        assert "dismissed: true" in ui_fixtures.WALKTHROUGH_SEED


class TestWitnessedSettingsFixture:
    KEY = "01994b86-2c00-7000-8000-000000000001"

    def test_get_post_get_retains_the_committed_projection(self):
        state = ui_fixtures._SettingsFixtureState("default")
        observed = json.loads(state.get_body())
        request = {
            "ui": {"mode": "simple"},
            "witness": observed["witness"],
            "operationKey": self.KEY,
        }

        status, raw_receipt = state.post(json.dumps(request))
        receipt = json.loads(raw_receipt)
        assert status == 200
        assert receipt["state"] == "COMPLETE"
        assert receipt["operationKey"] == self.KEY
        assert receipt["ui"]["mode"] == "simple"
        assert receipt["witness"] == {
            "acceptedRevision": observed["witness"]["acceptedRevision"] + 1,
            "lastCommittedOperationKey": self.KEY,
        }

        later = json.loads(state.get_body())
        assert later["ui"]["mode"] == "simple"
        assert later["witness"] == receipt["witness"]
        assert later["state"] is None and later["operationKey"] is None

    def test_same_key_replays_the_fixed_receipt_and_changed_input_is_refused(self):
        state = ui_fixtures._SettingsFixtureState("default")
        witness = json.loads(state.get_body())["witness"]
        request = {"ui": {"mode": "simple"}, "witness": witness, "operationKey": self.KEY}
        first = state.post(json.dumps(request))
        replay = state.post(json.dumps(request))
        assert replay == first

        changed = {**request, "ui": {"mode": "advanced"}}
        status, raw = state.post(json.dumps(changed))
        assert status == 409
        assert json.loads(raw)["errorCode"] == "OPERATION_KEY_REUSED"

    def test_stale_witness_is_refused_without_changing_state(self):
        state = ui_fixtures._SettingsFixtureState("default")
        before = json.loads(state.get_body())
        stale = {
            "ui": {"mode": "simple"},
            "witness": {"acceptedRevision": 99, "lastCommittedOperationKey": self.KEY},
            "operationKey": self.KEY,
        }
        status, raw = state.post(json.dumps(stale))
        assert status == 409
        assert json.loads(raw)["errorCode"] == "VERSION_CONFLICT"
        assert json.loads(state.get_body()) == before


class TestAgentRunVariant:
    """Tempdoc 814 §D8 — the variant that makes a COMPLETED agent run capture-reachable.

    Two halves, deliberately split by provider (§D8.1/§D8.2): the thread RECORD carries the
    grounding + the DONE lifecycle, the SSE body carries budget/context. Keeping one
    provider per fact is what makes each capture assertion falsifiable on its own.
    """

    def _thread(self, variant):
        return json.loads(ui_fixtures.fixture_body(
            f"http://localhost:5174/api/thread/{ui_fixtures._FIXTURE_CONVERSATION_ID}", variant))

    def test_the_record_carries_sources_on_the_last_assistant_message(self):
        body = self._thread("agent-run")
        # hydrateAnswerEvidenceFromRecord scans BACKWARD for the newest ASSISTANT_MESSAGE
        # with a non-empty attributes.sources, so the LAST one has to carry it.
        assistants = [e for e in body["events"] if e["kind"] == "ASSISTANT_MESSAGE"]
        assert assistants[-1]["attributes"]["sources"], "the newest answer must carry grounding"
        assert len(assistants[-1]["attributes"]["sources"]) >= 2

    def test_every_source_has_the_full_AgentSource_shape(self):
        required = {"parentDocId", "chunkIndex", "path", "title", "excerpt",
                    "startLine", "endLine", "headingText"}
        for s in self._thread("agent-run")["events"][-1]["attributes"]["sources"]:
            assert required <= set(s), f"missing {required - set(s)}"

    def test_the_record_carries_a_DONE_lifecycle(self):
        lifecycles = self._thread("agent-run")["lifecycles"]
        assert len(lifecycles) == 1
        lc = lifecycles[0]
        assert lc["state"] == "DONE"
        # The lifecycleSchema (unifiedThreadClient.ts) requires all of these.
        assert {"sessionId", "state", "actor", "turns", "iterations", "toolCalls",
                "actors", "budget"} <= set(lc)
        assert {"initial", "consumed", "remaining", "overBudget"} <= set(lc["budget"])

    def test_the_record_and_the_stream_agree_about_the_same_run(self):
        from jseval import agent_stream_fixture as asf

        lc = self._thread("agent-run")["lifecycles"][0]
        started = dict(asf.DONE_RUN)["session_started"]
        done = dict(asf.DONE_RUN)["done"]
        budget = dict(asf.DONE_RUN)["budget_update"]
        assert lc["sessionId"] == started["sessionId"]
        assert lc["iterations"] == done["iterationsUsed"]
        assert lc["toolCalls"] == done["toolCallsExecuted"]
        assert lc["budget"]["consumed"] == budget["totalTokensConsumed"]
        assert lc["budget"]["remaining"] == budget["tokensRemaining"]
        assert lc["budget"]["initial"] == budget["totalTokensConsumed"] + budget["tokensRemaining"]

    def test_the_degraded_thread_variant_is_unchanged(self):
        # `chat-spine-multi` reads this record; §D8 must not move it.
        body = self._thread("degraded-thread")
        assert body["lifecycles"] == []
        assert all(e["attributes"] == {} for e in body["events"])

    def test_agent_availability_is_reported_only_for_this_variant(self):
        url = "http://localhost:5174/api/chat/agent/tools"
        assert json.loads(ui_fixtures.fixture_body(url, "agent-run"))["available"] is True
        # Every other variant leaves it unmapped — an agent run must not become reachable
        # from a step that does not model one.
        for other in ("default", "degraded", "degraded-thread", "degraded-detailed"):
            assert json.loads(ui_fixtures.fixture_body(url, other)) == {}
