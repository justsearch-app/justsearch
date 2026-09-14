import json
import sqlite3
from types import SimpleNamespace

import pytest

from jseval.offline_procedure import (
    OfflineProcedureError,
    _open_operations_db,
    _require_corpus,
    _require_standard_runtime,
    _require_token,
    run_proof,
    read_scoped_rows,
    validate_preview_result,
    validate_pre_invoke_status,
    validate_operations_db,
    validate_scoped_rows,
    validate_terminal_row,
)


def _row(**changes):
    row = {
        "state": "COMPLETE", "checkpoint_cursor": json.dumps({
            "selected": 1, "processed": 1, "failed": 0, "remaining": 0,
            "blocked": 0, "blockedReason": "none", "embeddingHandoff": "not_needed",
        }), "units_completed": 1, "units_failed": 0,
        "accepted_at": 10, "started_at": 11, "completed_at": 12,
    }
    row.update(changes)
    return row


@pytest.mark.parametrize("changes", [
    {"checkpoint_cursor": json.dumps({"selected": 2})},
    {"units_completed": 0},
    {"units_completed": True},
    {"checkpoint_cursor": json.dumps({"selected": True, "processed": 1, "failed": 0,
        "remaining": 0, "blocked": 0, "blockedReason": "none", "embeddingHandoff": "not_needed"})},
    {"state": "FAILED"},
    {"state": "CANCELLED"},
])
def test_terminal_validation_rejects_mismatch_or_failed_state(changes):
    with pytest.raises(OfflineProcedureError):
        validate_terminal_row(_row(**changes))


def test_terminal_validation_rejects_empty_success_and_wrong_handoff():
    empty = {"selected": 1, "processed": 1, "failed": 0, "remaining": 0,
             "blocked": 0, "blockedReason": "none", "embeddingHandoff": "not_needed"}
    with pytest.raises(OfflineProcedureError):
        validate_terminal_row(_row(checkpoint_cursor=json.dumps({**empty, "processed": 0})))
    with pytest.raises(OfflineProcedureError):
        validate_terminal_row(_row(checkpoint_cursor=json.dumps({**empty, "embeddingHandoff": "not_evaluated"})))


@pytest.mark.parametrize("count", [True, 1.0, 0, 2, None])
def test_pre_invoke_requires_one_integer_pending_unit(count):
    with pytest.raises(OfflineProcedureError):
        validate_pre_invoke_status({"pendingVduCount": count, "vduProcessing": False})


def test_scoped_row_selection_rejects_missing_or_multiple_rows():
    with pytest.raises(OfflineProcedureError):
        validate_scoped_rows([])
    with pytest.raises(OfflineProcedureError):
        validate_scoped_rows([_row(), _row()])
    with pytest.raises(OfflineProcedureError):
        validate_scoped_rows([{**_row(), "operation_ref": "core.other"}])
    with pytest.raises(OfflineProcedureError):
        validate_scoped_rows([{**_row(), "operation_ref": "core.trigger-offline-processing",
                               "client_id": "wrong"}], client_id="expected")


def test_preview_validation_rejects_source_mismatch_and_empty_success():
    metadata = {"extractionStatus": "SUCCESS_FULL", "sourceSha256": "a" * 64}
    with pytest.raises(OfflineProcedureError):
        validate_preview_result("text", "success", metadata, "b" * 64)
    with pytest.raises(OfflineProcedureError):
        validate_preview_result("", "success", metadata, "a" * 64)
    with pytest.raises(OfflineProcedureError):
        validate_preview_result("", "success", {**metadata, "extractionStatus": "SUCCESS_EMPTY"}, "a" * 64)


def test_run_proof_rejects_token_or_remote_url_before_root_mutation(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    (corpus / "one.pdf").write_bytes(b"pdf")
    called = []
    monkeypatch.setattr("jseval.offline_procedure.ingest.add_watched_root",
                        lambda *args, **kwargs: called.append((args, kwargs)))
    monkeypatch.delenv("JUSTSEARCH_SESSION_TOKEN", raising=False)
    assert not run_proof("http://127.0.0.1:33221", corpus, tmp_path / "ops.db",
                          tmp_path / "missing-token.json", 1)
    monkeypatch.setenv("JUSTSEARCH_SESSION_TOKEN", "secret-for-test")
    assert not run_proof("https://127.0.0.1:33221", corpus, tmp_path / "ops.db",
                          tmp_path / "remote-url.json", 1)
    assert called == []


def test_preflight_requires_token_and_standard_online_identity():
    with pytest.raises(OfflineProcedureError):
        _require_token("")
    with pytest.raises(OfflineProcedureError):
        _require_standard_runtime({"chatProfile": "compact", "online": True, "modelFile": "x.gguf"})
    with pytest.raises(OfflineProcedureError):
        _require_standard_runtime({"chatProfile": "standard", "online": False, "modelFile": "x.gguf"})


def test_corpus_requires_exactly_one_pdf(tmp_path):
    with pytest.raises(OfflineProcedureError):
        _require_corpus(tmp_path)
    (tmp_path / "a.pdf").write_bytes(b"pdf")
    pdf, digest = _require_corpus(tmp_path)
    assert pdf.name == "a.pdf"
    assert len(digest) == 64
    (tmp_path / "b.PDF").write_bytes(b"pdf2")
    with pytest.raises(OfflineProcedureError):
        _require_corpus(tmp_path)


def test_readonly_operations_db_does_not_create(tmp_path):
    missing = tmp_path / "missing.db"
    with pytest.raises(OfflineProcedureError):
        _open_operations_db(missing)
    assert not missing.exists()

    db_path = tmp_path / "operations.db"
    with sqlite3.connect(db_path) as db:
        db.execute("create table operations (id integer)")
    with _open_operations_db(db_path) as db:
        with pytest.raises(sqlite3.OperationalError):
            db.execute("create table must_not_exist (id integer)")
    with pytest.raises(OfflineProcedureError):
        validate_operations_db(db_path)


def test_real_row_read_preserves_failure_and_receipt(tmp_path):
    db_path = tmp_path / "operations.db"
    with sqlite3.connect(db_path) as db:
        db.execute("CREATE TABLE operations (id INTEGER, operation_ref TEXT, state TEXT, "
                   "checkpoint_cursor TEXT, units_completed INTEGER, units_failed INTEGER, "
                   "accepted_at INTEGER, started_at INTEGER, completed_at INTEGER, "
                   "client_id TEXT, session_id TEXT, failure_reason TEXT, result_json TEXT)")
        db.execute("INSERT INTO operations VALUES (1, ?, 'FAILED', '{}', 0, 1, 10, 11, 12, "
                   "'client', 'session', 'ENRICHMENT_INCOMPLETE', ?)",
                   ("core.trigger-offline-processing", '{"success":false}'))
    validate_operations_db(db_path)
    row = read_scoped_rows(db_path, "client", "session")[0]
    assert row["failure_reason"] == "ENRICHMENT_INCOMPLETE"
    assert row["result_json"] == '{"success":false}'


def test_readonly_schema_probe_closes_connection(monkeypatch, tmp_path):
    class Spy:
        closed = False

        def execute(self, sql):
            assert "FROM operations" in sql

        def close(self):
            self.closed = True

    spy = Spy()
    monkeypatch.setattr("jseval.offline_procedure._open_operations_db", lambda _path: spy)
    validate_operations_db(tmp_path / "operations.db")
    assert spy.closed


def test_readonly_row_poll_closes_connection(monkeypatch, tmp_path):
    class Cursor:
        def fetchall(self):
            return []

    class Spy:
        closed = False
        row_factory = None

        def execute(self, sql, params):
            assert "LIMIT 2" in sql
            return Cursor()

        def close(self):
            self.closed = True

    spy = Spy()
    monkeypatch.setattr("jseval.offline_procedure._open_operations_db", lambda _path: spy)
    assert read_scoped_rows(tmp_path / "operations.db", "client", "session") == []
    assert spy.closed


def test_run_proof_mocked_happy_path_and_bounded_rows(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    pdf = corpus / "scanned-alpha.pdf"
    pdf.write_bytes(b"pdf bytes")
    out = tmp_path / "evidence.json"
    source_sha = __import__("hashlib").sha256(pdf.read_bytes()).hexdigest()
    calls = []

    class FakeClient:
        def __init__(self, *args, **kwargs):
            self._client = object()

        def list_document_ids(self, offset, limit):
            return {"docIds": [], "totalCount": 0, "tookMs": 0} if not calls else {
                "docIds": [str(pdf.resolve())], "totalCount": 1, "tookMs": 0}

        def status(self):
            return {"pendingVduCount": 1, "vduProcessing": False, "indexState": "IDLE"}

        def close(self):
            calls.append("closed")

    monkeypatch.setenv("JUSTSEARCH_SESSION_TOKEN", "test-token")
    monkeypatch.setattr("jseval.offline_procedure.production.ProductionHttpClient", FakeClient)
    monkeypatch.setattr("jseval.offline_procedure.validate_operations_db", lambda _path: None)
    monkeypatch.setattr("jseval.offline_procedure.workflow_fixture.read_ai_runtime",
                        lambda _client: {"chatProfile": "standard", "online": True,
                                         "modelFile": "standard.gguf", "activationState": "completed"})
    monkeypatch.setattr("jseval.offline_procedure.ingest.add_watched_root",
                        lambda *args, **kwargs: calls.append("root"))
    monkeypatch.setattr("jseval.offline_procedure.readiness.wait_index_idle",
                        lambda *args, **kwargs: SimpleNamespace(passed=True, failure_reasons=[]))
    monkeypatch.setattr("jseval.offline_procedure._invoke", lambda *args: {
        "success": True, "message": "Enrichment pass started", "executionId": "exec-1"})
    monkeypatch.setattr("jseval.offline_procedure._git_revision", lambda: "rev")
    listed_calls = {"count": 0}

    def listed(payload, expected):
        listed_calls["count"] += 1
        if expected == 0:
            return ()
        return (str(pdf.resolve()),)

    monkeypatch.setattr("jseval.offline_procedure.production._listed_documents", listed)
    def scoped(_db, client_id, session_id):
        row = {"operation_ref": "core.trigger-offline-processing", "client_id": client_id,
               "session_id": session_id, "units_completed": 1, "units_failed": 0,
               "accepted_at": 10, "started_at": 11, "completed_at": 12,
               "checkpoint_cursor": json.dumps({"selected": 1, "processed": 1, "failed": 0,
                 "remaining": 0, "blocked": 0, "blockedReason": "none",
                 "embeddingHandoff": "not_needed"}), "state": "COMPLETE"}
        return [row]
    monkeypatch.setattr("jseval.offline_procedure.read_scoped_rows", scoped)
    monkeypatch.setattr("jseval.offline_procedure.production._read_complete_document",
                        lambda client, doc_id: ("extracted alpha", "success", {
                            "extractionStatus": "SUCCESS_FULL", "sourceSha256": source_sha,
                            "contentSha256": __import__("hashlib").sha256(b"extracted alpha").hexdigest(),
                            "totalChars": 14, "mime": "application/pdf", "contentTruncated": False,
                            "extractionPolicyId": "p", "extractionParserId": "parser"}))
    assert run_proof("http://127.0.0.1:33221", corpus, tmp_path / "ops.db", out, 1)
    evidence = json.loads(out.read_text(encoding="utf-8"))
    assert evidence["result"] == "PASSED"
    assert evidence["observedRunning"] is False
    assert evidence["preview"]["sourceSha256"] == source_sha
    assert "test-token" not in out.read_text(encoding="utf-8")
    assert "root" in calls and "closed" in calls


def test_run_proof_rejects_success_ack_without_committed_acceptance(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    pdf = corpus / "one.pdf"
    pdf.write_bytes(b"pdf")
    out = tmp_path / "missing-row.json"

    class FakeClient:
        def __init__(self, *args, **kwargs):
            self._client = object()

        def list_document_ids(self, offset, limit):
            return {"docIds": [], "totalCount": 0, "tookMs": 0}

        def status(self):
            return {"pendingVduCount": 1, "vduProcessing": False, "indexState": "IDLE"}

        def close(self):
            pass

    monkeypatch.setenv("JUSTSEARCH_SESSION_TOKEN", "test-token")
    monkeypatch.setattr("jseval.offline_procedure.production.ProductionHttpClient", FakeClient)
    monkeypatch.setattr("jseval.offline_procedure.validate_operations_db", lambda _path: None)
    monkeypatch.setattr("jseval.offline_procedure.workflow_fixture.read_ai_runtime",
                        lambda _client: {"chatProfile": "standard", "online": True,
                                         "modelFile": "standard.gguf"})
    monkeypatch.setattr("jseval.offline_procedure.ingest.add_watched_root", lambda *a, **k: None)
    monkeypatch.setattr("jseval.offline_procedure.readiness.wait_index_idle",
                        lambda *a, **k: SimpleNamespace(passed=True, failure_reasons=[]))
    monkeypatch.setattr("jseval.offline_procedure._invoke",
                        lambda *a: {"success": True, "message": "started"})
    monkeypatch.setattr("jseval.offline_procedure.read_scoped_rows", lambda *a: [])
    assert not run_proof("http://127.0.0.1:33221", corpus, tmp_path / "ops.db", out, 1)
    evidence = json.loads(out.read_text(encoding="utf-8"))
    assert "exactly one scoped operation row" in evidence["error"]


@pytest.mark.parametrize("target", ["source", "new-corpus-file", "database", "wal", "existing", "alias"])
def test_output_cannot_overwrite_inputs_or_create_watched_content(tmp_path, monkeypatch, target):
    corpus = tmp_path / "corpus"
    corpus.mkdir()
    source = corpus / "one.pdf"
    source.write_bytes(b"unchanged source")
    database = tmp_path / "operations.db"
    database.write_bytes(b"unchanged database")
    existing = tmp_path / "earlier-proof.json"
    existing.write_bytes(b"earlier evidence")
    alias = tmp_path / "database-alias"
    alias.hardlink_to(database)
    output = {"source": source, "new-corpus-file": corpus / "new.json", "database": database,
              "wal": tmp_path / "operations.db-wal", "existing": existing, "alias": alias}[target]
    calls = []
    monkeypatch.setattr("jseval.offline_procedure.ingest.add_watched_root", lambda *a, **k: calls.append(1))
    assert not run_proof("http://127.0.0.1:33221", corpus, database, output, 1)
    assert calls == []
    assert source.read_bytes() == b"unchanged source"
    assert database.read_bytes() == b"unchanged database"
    assert existing.read_bytes() == b"earlier evidence"
    assert not (corpus / "new.json").exists()
    assert not (tmp_path / "operations.db-wal").exists()
