"""Bounded production proof for one manually-triggered offline VDU pass.

This is deliberately a proof instrument, not a second operation implementation.  The
HTTP adapter and preview validators are reused from duplicate_prevalence_production;
the operations database is opened read-only so this command cannot create or mutate it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sqlite3
import subprocess
import sys
import time
import uuid
from contextlib import closing
from pathlib import Path
from typing import Any, Mapping

import httpx

from . import duplicate_prevalence_production as production
from . import ingest, readiness, workflow_fixture


OPERATION_REF = "core.trigger-offline-processing"
SESSION_TOKEN_ENV = "JUSTSEARCH_SESSION_TOKEN"
TERMINAL_STATES = {"COMPLETE", "FAILED", "CANCELLED"}
POLL_SECONDS = 0.5


class OfflineProcedureError(ValueError):
    """The bounded offline proof cannot establish its contract."""


def _validate_output(out: Path, corpus_dir: Path, operations_db: Path) -> Path:
    resolved = out.resolve()
    if resolved.is_relative_to(corpus_dir.resolve()):
        raise OfflineProcedureError("--out must be outside the watched corpus")
    database = operations_db.resolve()
    protected = {database, *(database.with_name(database.name + suffix)
                            for suffix in ("-wal", "-shm", "-journal"))}
    if resolved in protected or resolved.exists():
        raise OfflineProcedureError("--out must be a new file outside the database file family")
    return resolved


def _require_token(value: str | None) -> str:
    if value is None or not value.strip():
        raise OfflineProcedureError(f"{SESSION_TOKEN_ENV} is required")
    return value.strip()


def _require_corpus(corpus_dir: Path) -> tuple[Path, str]:
    if not corpus_dir.is_dir():
        raise OfflineProcedureError("--corpus-dir must name an existing directory")
    files = sorted(p for p in corpus_dir.rglob("*") if p.is_file())
    pdfs = [p for p in files if p.suffix.lower() == ".pdf"]
    if len(files) != 1 or len(pdfs) != 1:
        raise OfflineProcedureError(
            f"--corpus-dir must contain exactly one file and it must be a PDF (found {len(files)} files, {len(pdfs)} PDFs)"
        )
    digest = hashlib.sha256(pdfs[0].read_bytes()).hexdigest()
    return pdfs[0], digest


def _require_standard_runtime(runtime: Mapping[str, Any]) -> None:
    if runtime.get("chatProfile") != "standard":
        raise OfflineProcedureError("AI runtime identity is not the standard chat profile")
    if runtime.get("online") is not True:
        raise OfflineProcedureError("AI runtime is not online")
    if not isinstance(runtime.get("modelFile"), str) or not runtime["modelFile"].strip():
        raise OfflineProcedureError("AI runtime has no realized model identity")


def _flat_status(raw: Mapping[str, Any]) -> dict[str, Any]:
    return readiness.flatten_status(dict(raw))


def validate_pre_invoke_status(raw: Mapping[str, Any]) -> dict[str, Any]:
    status = _flat_status(raw)
    if type(status.get("pendingVduCount")) is not int or status.get("pendingVduCount") != 1:
        raise OfflineProcedureError("pre-invoke status did not show exactly one pending VDU")
    if status.get("vduProcessing") is not False:
        raise OfflineProcedureError("pre-invoke status showed VDU processing in flight")
    return status


def _open_operations_db(path: Path) -> sqlite3.Connection:
    """Open an existing SQLite database in URI mode=ro; never creates a file."""
    if not path.is_file():
        raise OfflineProcedureError("--operations-db must name an existing file")
    try:
        return sqlite3.connect(f"{path.resolve().as_uri()}?mode=ro", uri=True)
    except sqlite3.Error as exc:
        raise OfflineProcedureError(f"unable to open operations database read-only: {exc}") from exc


def read_scoped_rows(path: Path, client_id: str, session_id: str) -> list[dict[str, Any]]:
    with closing(_open_operations_db(path)) as db:
        db.row_factory = sqlite3.Row
        try:
            rows = db.execute(
                "SELECT id, operation_ref, state, checkpoint_cursor, units_completed, "
                "units_failed, accepted_at, started_at, completed_at, client_id, session_id, "
                "failure_reason, result_json "
                "FROM operations WHERE operation_ref = ? AND client_id = ? AND session_id = ? "
                "ORDER BY id LIMIT 2",
                (OPERATION_REF, client_id, session_id),
            ).fetchall()
        except sqlite3.Error as exc:
            raise OfflineProcedureError(f"operations database schema/query failed: {exc}") from exc
    return [dict(row) for row in rows]


def validate_operations_db(path: Path) -> None:
    """Check the required schema through the same read-only connection used for polling."""
    with closing(_open_operations_db(path)) as db:
        try:
            db.execute("SELECT operation_ref, client_id, session_id, state, checkpoint_cursor, "
                       "units_completed, units_failed, accepted_at, started_at, completed_at, "
                       "failure_reason, result_json "
                       "FROM operations LIMIT 0")
        except sqlite3.Error as exc:
            raise OfflineProcedureError(f"operations database schema/query failed: {exc}") from exc


def validate_scoped_rows(rows: list[Mapping[str, Any]], *, client_id: str | None = None,
                         session_id: str | None = None) -> Mapping[str, Any]:
    if len(rows) != 1:
        raise OfflineProcedureError(f"expected exactly one scoped operation row, found {len(rows)}")
    row = rows[0]
    if client_id is not None and row.get("client_id") != client_id:
        raise OfflineProcedureError("scoped operation row has the wrong client id")
    if session_id is not None and row.get("session_id") != session_id:
        raise OfflineProcedureError("scoped operation row has the wrong session id")
    if row.get("operation_ref") != OPERATION_REF:
        raise OfflineProcedureError("scoped operation row has the wrong operation reference")
    return row


def _checkpoint(row: Mapping[str, Any]) -> dict[str, Any]:
    value = row.get("checkpoint_cursor")
    if not isinstance(value, str) or not value.strip():
        raise OfflineProcedureError("operation row has no checkpoint")
    try:
        checkpoint = json.loads(value)
    except json.JSONDecodeError as exc:
        raise OfflineProcedureError("operation checkpoint is not JSON") from exc
    if not isinstance(checkpoint, dict):
        raise OfflineProcedureError("operation checkpoint is not an object")
    return checkpoint


def validate_terminal_row(row: Mapping[str, Any]) -> dict[str, Any]:
    state = row.get("state")
    if state in {"FAILED", "CANCELLED"}:
        raise OfflineProcedureError(f"offline operation ended in {state}")
    if state != "COMPLETE":
        raise OfflineProcedureError(f"offline operation is not terminal: {state!r}")
    accepted, started, completed = (row.get("accepted_at"), row.get("started_at"), row.get("completed_at"))
    if any(isinstance(v, bool) or not isinstance(v, int) for v in (accepted, started, completed)):
        raise OfflineProcedureError("operation lifecycle timestamps are incomplete")
    if not accepted <= started <= completed:
        raise OfflineProcedureError("operation lifecycle timestamps are out of order")
    if any(type(row.get(key)) is not int for key in ("units_completed", "units_failed")) \
            or row.get("units_completed") != 1 or row.get("units_failed") != 0:
        raise OfflineProcedureError("durable operation unit counts are not 1 completed, 0 failed")
    checkpoint = _checkpoint(row)
    expected = {"selected": 1, "processed": 1, "failed": 0, "remaining": 0, "blocked": 0,
                "blockedReason": "none"}
    for key, value in expected.items():
        if type(checkpoint.get(key)) is not type(value) or checkpoint.get(key) != value:
            raise OfflineProcedureError(f"offline checkpoint {key} is {checkpoint.get(key)!r}, expected {value!r}")
    if checkpoint.get("embeddingHandoff") not in {"not_needed", "handed_off"}:
        raise OfflineProcedureError("offline checkpoint has no completed embedding handoff")
    return checkpoint


def validate_preview_result(text: str | None, extraction_status: str,
                            metadata: Mapping[str, Any], expected_source_sha: str) -> None:
    if extraction_status != "success" or metadata.get("extractionStatus") == "SUCCESS_EMPTY":
        raise OfflineProcedureError("final PDF preview was not a successful extraction")
    if not isinstance(text, str) or not text.strip():
        raise OfflineProcedureError("final PDF preview had empty text")
    if metadata.get("sourceSha256") != expected_source_sha:
        raise OfflineProcedureError("final preview source SHA-256 does not match the PDF")


def _evidence_row(row: Mapping[str, Any]) -> dict[str, Any]:
    """Bounded durable metadata; operation rows never contain document text."""
    result = {}
    for key in ("id", "operation_ref", "state", "units_completed", "units_failed",
                "accepted_at", "started_at", "completed_at", "failure_reason", "result_json"):
        value = row.get(key)
        if isinstance(value, str):
            value = value[:4096]
        result[key] = value
    checkpoint = row.get("checkpoint_cursor")
    result["checkpoint_cursor"] = checkpoint[:4096] if isinstance(checkpoint, str) else checkpoint
    return result


def _invoke(client: production.ProductionHttpClient, client_id: str, session_id: str) -> dict[str, Any]:
    response = client._client.post(  # type: ignore[attr-defined]  # reuse transport/token boundary
        f"/api/operations/{OPERATION_REF}/invoke", json={}, headers={
            "X-JustSearch-Client-Id": client_id,
            "X-JustSearch-Session-Id": session_id,
        })
    try:
        response.raise_for_status()
        payload = response.json()
    except (httpx.HTTPError, ValueError) as exc:
        raise OfflineProcedureError(f"offline invoke failed: HTTP {response.status_code}") from exc
    if not isinstance(payload, dict) or payload.get("success") is not True:
        raise OfflineProcedureError("offline invoke did not return a started acknowledgement")
    return payload


def _git_revision() -> str | None:
    try:
        return subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True,
                              check=True, timeout=5).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def run_proof(base_url: str, corpus_dir: Path, operations_db: Path, out: Path, timeout: float) -> bool:
    evidence: dict[str, Any] = {
        "schema": "jseval.offline-procedure-proof.v1", "revision": _git_revision(),
        "operationRef": OPERATION_REF, "result": "FAILED", "rowObservations": [],
        "response": None, "runtimeBefore": None, "preview": None,
        "durableDbProof": "durable operations.db proof; not C2-6 public-query proof",
    }
    client: production.ProductionHttpClient | None = None
    safe_output = False
    try:
        out = _validate_output(out, corpus_dir, operations_db)
        safe_output = True
        token = _require_token(os.environ.get(SESSION_TOKEN_ENV))
        checked_url = production._checked_base_url(base_url)
        pdf, source_sha = _require_corpus(corpus_dir)
        evidence["source"] = {"path": str(pdf.resolve()), "sha256": source_sha}
        validate_operations_db(operations_db)
        if isinstance(timeout, bool) or not math.isfinite(timeout) or timeout <= 0:
            raise OfflineProcedureError("--timeout must be finite and positive")
        client_id, session_id = f"jseval-offline-{uuid.uuid4()}", str(uuid.uuid4())
        evidence["clientId"], evidence["sessionId"] = client_id, session_id
        client = production.ProductionHttpClient(checked_url, session_token=token,
                                                  timeout_seconds=min(timeout, 30.0))
        initial = client.list_document_ids(0, production.MAX_DOCUMENTS)
        production._listed_documents(initial, 0)
        evidence["initialDocumentCount"] = 0
        runtime = workflow_fixture.read_ai_runtime(client._client)  # type: ignore[attr-defined]
        evidence["runtimeBefore"] = {k: runtime.get(k) for k in
                                      ("chatProfile", "activationState", "online", "onlineSignal", "activeVariantId", "modelFile")}
        _require_standard_runtime(runtime)
        ingest.add_watched_root(checked_url, corpus_dir, timeout_sec=timeout, session_token=token)
        readiness_result = readiness.wait_index_idle(checked_url, expected_doc_count_min=1,
                                                      timeout_sec=timeout, stable_polls_required=2)
        if not readiness_result.passed:
            raise OfflineProcedureError(f"index did not become idle: {readiness_result.failure_reasons}")
        pre = validate_pre_invoke_status(client.status())
        evidence["preInvokeStatus"] = {k: pre.get(k) for k in ("indexedDocuments", "pendingVduCount", "vduProcessing", "indexState")}
        response = _invoke(client, client_id, session_id)
        evidence["response"] = {k: response.get(k) for k in ("success", "message", "executionId", "errorClass", "errorCode", "retryable")}
        deadline = time.monotonic() + timeout
        saw_running = False
        while True:
            rows = read_scoped_rows(operations_db, client_id, session_id)
            observation = [_evidence_row(row) for row in rows]
            if len(evidence["rowObservations"]) < 100:
                evidence["rowObservations"].append(observation)
            else:
                evidence["rowObservationsTruncated"] = True
            row = validate_scoped_rows(rows, client_id=client_id, session_id=session_id)
            if row.get("state") == "RUNNING":
                saw_running = True
            if row.get("state") in TERMINAL_STATES:
                # Capture the durable terminal facts before validation so a FAILED or malformed
                # row remains reviewable in the failure artifact.
                evidence["terminalRow"] = _evidence_row(row)
                raw_checkpoint = row.get("checkpoint_cursor")
                if isinstance(raw_checkpoint, str):
                    try:
                        parsed_checkpoint = json.loads(raw_checkpoint)
                    except json.JSONDecodeError:
                        parsed_checkpoint = None
                    evidence["terminalCheckpoint"] = (
                        parsed_checkpoint if isinstance(parsed_checkpoint, dict)
                        else raw_checkpoint[:4096]
                    )
                checkpoint = validate_terminal_row(row)
                evidence["checkpoint"] = checkpoint
                break
            if time.monotonic() >= deadline:
                raise OfflineProcedureError("no terminal scoped operation row before timeout")
            time.sleep(min(POLL_SECONDS, max(0.0, deadline - time.monotonic())))
        evidence["observedRunning"] = saw_running
        final_ids = production._listed_documents(client.list_document_ids(0, production.MAX_DOCUMENTS), 1)
        if len(final_ids) != 1:
            raise OfflineProcedureError("final document enumeration was not exactly one document")
        expected_doc_id = production._worker_path_key(str(pdf.resolve()))
        if production._worker_path_key(final_ids[0]) != expected_doc_id:
            raise OfflineProcedureError("final document identity does not match the intended PDF")
        text_value, extraction_status, metadata = production._read_complete_document(client, final_ids[0])
        validate_preview_result(text_value, extraction_status, metadata, source_sha)
        evidence["preview"] = {"docId": final_ids[0], "extractionStatus": metadata.get("extractionStatus"),
                                "sourceSha256": metadata.get("sourceSha256"), "contentSha256": metadata.get("contentSha256"),
                                "textSha256": hashlib.sha256(text_value.encode("utf-8")).hexdigest(),
                                "totalChars": metadata.get("totalChars"), "mime": metadata.get("mime"),
                                "contentTruncated": metadata.get("contentTruncated"),
                                "extractionPolicyId": metadata.get("extractionPolicyId"),
                                "extractionParserId": metadata.get("extractionParserId")}
        evidence["result"] = "PASSED"
        return True
    except Exception as exc:
        evidence["error"] = str(exc)
        if not safe_output:
            print(f"offline-procedure: {exc}", file=sys.stderr)
        return False
    finally:
        evidence["rowObservations"] = evidence.get("rowObservations", [])[-100:]
        try:
            if safe_output:
                out.parent.mkdir(parents=True, exist_ok=True)
                with out.open("x", encoding="utf-8") as stream:
                    stream.write(json.dumps(evidence, indent=2, sort_keys=True) + "\n")
        finally:
            if client is not None:
                client.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--corpus-dir", required=True, type=Path)
    parser.add_argument("--operations-db", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout", required=True, type=float)
    args = parser.parse_args(argv)
    return 0 if run_proof(args.base_url, args.corpus_dir, args.operations_db, args.out, args.timeout) else 1


if __name__ == "__main__":
    sys.exit(main())
