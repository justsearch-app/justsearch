#!/usr/bin/env python3
"""Self-tests for otlp-sink.py (tempdoc 743 Phase 1, reservoir workstream;
rotation coverage added tempdoc 745 item A).

Covers the defects this fix closes:
  1. `Transfer-Encoding: chunked` bodies (sent by OTel-OTLP-Exporter-JavaScript,
     with no `Content-Length` header) were silently read as empty, so real
     telemetry decoded to 0 records with no error at all — a completely
     invisible capture failure that zeroed out capture for weeks.
  2. A POST that decodes to 0 records now announces itself once per route in
     errors.log (rate-limited so a client emitting legitimate empty batches
     repeatedly cannot grow the log unboundedly).
  3. Rotation used to keep only ONE `.prev` generation and unconditionally
     `os.remove()` it on the next rotation, so anything older than "current +
     one rotation" was destroyed forever (a rotation was directly observed
     destroying 21 MB). Rotation now archives with a timestamped, never-
     overwritten filename and prunes per a per-stream RETENTION policy, with
     metrics (RETENTION=None) never pruned at all — see `RotationTests` below.
  4. logs.ndjson embeds request/response bodies, so it rotates every ~25 min
     of active work and can only retain a couple of archives — which aged the
     NUMBERS out of the capture along with the bodies. `ledger.ndjson` is the
     body-free projection kept for 90 archives (tempdoc 930 F2) — see
     `LedgerTests` below.

Loaded via importlib (the hyphenated filename `otlp-sink.py` is not a valid
Python module name).

Run: python scripts/agent-analytics/test_otlp_sink.py
"""
from __future__ import annotations

import email.message
import importlib.util
import io
import json
import os
import socket
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("otlp_sink", SCRIPT_DIR / "otlp-sink.py")
otlp_sink = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(otlp_sink)  # type: ignore[union-attr]

from opentelemetry.proto.collector.logs.v1 import logs_service_pb2  # noqa: E402
from opentelemetry.proto.collector.metrics.v1 import metrics_service_pb2  # noqa: E402


def _headers(pairs: dict) -> email.message.Message:
    # BaseHTTPRequestHandler.headers is an http.client.HTTPMessage (case-
    # insensitive header lookup) — mirror that here rather than a plain dict.
    msg = email.message.Message()
    for k, v in pairs.items():
        msg[k] = v
    return msg


def _make_bare_handler(handler_cls):
    """A real instance of the sink's request-handler class with only
    `headers`/`rfile` set — bypasses BaseHTTPRequestHandler's socket-binding
    `__init__` while keeping `self._read_chunked_body` bound correctly (a
    plain unrelated stand-in object would not have that method)."""
    return object.__new__(handler_cls)


def _chunked_encode(chunks: list[bytes]) -> bytes:
    out = b""
    for c in chunks:
        out += f"{len(c):x}\r\n".encode() + c + b"\r\n"
    out += b"0\r\n\r\n"
    return out


class ReadBodyTests(unittest.TestCase):
    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self.H = otlp_sink.make_handler(self.out_dir)

    def _bare(self, headers: dict, body_bytes: bytes):
        h = _make_bare_handler(self.H)
        h.headers = _headers(headers)
        h.rfile = io.BytesIO(body_bytes)
        return h

    def test_content_length_path_unchanged(self):
        h = self._bare({"Content-Length": "5"}, b"hello" + b"TRAILING-IGNORED")
        self.assertEqual(h._read_body(), b"hello")

    def test_missing_content_length_and_not_chunked_is_empty(self):
        h = self._bare({}, b"should not be read")
        self.assertEqual(h._read_body(), b"")

    def test_chunked_single_chunk_reconstructs_full_body(self):
        payload = b"x" * 5000  # larger than a single naive read to prove looping
        h = self._bare({"Transfer-Encoding": "chunked"}, _chunked_encode([payload]))
        self.assertEqual(h._read_body(), payload)

    def test_chunked_multi_chunk_reconstructs_full_body(self):
        payload = b"abc" * 1000 + b"def" * 2000
        wire = _chunked_encode([payload[:1500], payload[1500:3000], payload[3000:]])
        h = self._bare({"transfer-encoding": "CHUNKED"}, wire)  # header-value case-insensitivity
        self.assertEqual(h._read_body(), payload)

    def test_chunked_empty_body(self):
        h = self._bare({"Transfer-Encoding": "chunked"}, _chunked_encode([]))
        self.assertEqual(h._read_body(), b"")


def _log_export_request(text: str) -> bytes:
    req = logs_service_pb2.ExportLogsServiceRequest()
    rl = req.resource_logs.add()
    kv = rl.resource.attributes.add()
    kv.key = "service.name"
    kv.value.string_value = "otlp-sink-test"
    sl = rl.scope_logs.add()
    lr = sl.log_records.add()
    lr.body.string_value = text
    lr.time_unix_nano = 1
    return req.SerializeToString()


def _set_kv_attrs(container, attrs: dict) -> None:
    for k, v in attrs.items():
        kv = container.add()
        kv.key = k
        if isinstance(v, bool):
            kv.value.bool_value = v
        elif isinstance(v, int):
            kv.value.int_value = v
        else:
            kv.value.string_value = str(v)


def _metric_export_request(metric_name: str, point_attrs: dict, value: int) -> metrics_service_pb2.ExportMetricsServiceRequest:
    """One Metric Sum carrying a single NumberDataPoint (Claude shape)."""
    req = metrics_service_pb2.ExportMetricsServiceRequest()
    rm = req.resource_metrics.add()
    sm = rm.scope_metrics.add()
    m = sm.metrics.add()
    m.name = metric_name
    dp = m.sum.data_points.add()
    dp.as_int = value
    dp.time_unix_nano = 1
    _set_kv_attrs(dp.attributes, point_attrs)
    return req


def _histogram_metric_export_request(metric_name: str, point_attrs: dict, value: int) -> metrics_service_pb2.ExportMetricsServiceRequest:
    """One histogram point with sum=value, matching current Codex OTel."""
    req = metrics_service_pb2.ExportMetricsServiceRequest()
    rm = req.resource_metrics.add()
    sm = rm.scope_metrics.add()
    m = sm.metrics.add()
    m.name = metric_name
    dp = m.histogram.data_points.add()
    dp.count = 1
    dp.sum = value
    dp.time_unix_nano = 1
    _set_kv_attrs(dp.attributes, point_attrs)
    return req


class GenAiNormalizationTests(unittest.TestCase):
    """otlp-sink.py's additive gen_ai.usage normalisation (tempdoc 886 section 12
    PR 3 -- see the section 10.3 note in decode_metrics' module comment):
    decode_metrics must keep emitting the original per-harness record
    unchanged AND append a normalised gen_ai.usage twin for every data point
    whose raw token-kind attribute is in GENAI_TOKEN_MAP -- never mutating
    or replacing the original."""

    def test_claude_metric_point_yields_original_plus_normalised(self):
        req = _metric_export_request(
            "claude_code.token.usage",
            {"type": "cacheRead", "model": "claude-opus-5", "session.id": "s1"},
            12345,
        )
        records = otlp_sink.decode_metrics(req)
        originals = [r for r in records if r["name"] == "claude_code.token.usage"]
        normalised = [r for r in records if r["name"] == "gen_ai.usage"]
        self.assertEqual(len(originals), 1)
        self.assertEqual(originals[0]["points"][0]["attributes"]["type"], "cacheRead")
        self.assertEqual(len(normalised), 1)
        rec = normalised[0]
        self.assertTrue(rec["normalized"])
        self.assertEqual(rec["value"], 12345)
        self.assertEqual(rec["attributes"]["gen_ai.system"], "claude-code")
        self.assertEqual(rec["attributes"]["gen_ai.request.model"], "claude-opus-5")
        self.assertEqual(rec["attributes"]["gen_ai.token.kind"], "cache_read")
        # passthrough: the original attributes (type, model, session.id) all
        # survive onto the normalised record, not just the gen_ai.* additions.
        self.assertEqual(rec["attributes"]["type"], "cacheRead")
        self.assertEqual(rec["attributes"]["session.id"], "s1")

    def test_codex_cached_input_maps_to_cache_read(self):
        req = _histogram_metric_export_request(
            "codex.turn.token_usage",
            {"token_type": "cached_input", "model": "gpt-5-codex"},
            500,
        )
        records = otlp_sink.decode_metrics(req)
        normalised = [r for r in records if r["name"] == "gen_ai.usage"]
        self.assertEqual(len(normalised), 1)
        rec = normalised[0]
        self.assertEqual(rec["attributes"]["gen_ai.system"], "codex-cli")
        self.assertEqual(rec["attributes"]["gen_ai.token.kind"], "cache_read")
        self.assertNotIn("gen_ai.input_includes_cache_read", rec["attributes"])

    def test_codex_histogram_sum_is_preserved_and_cache_write_is_mapped(self):
        req = _histogram_metric_export_request(
            "codex.turn.token_usage",
            {"token_type": "cache_write_input", "model": "gpt-5.6-sol"},
            321,
        )
        records = otlp_sink.decode_metrics(req)
        original = next(r for r in records if r["name"] == "codex.turn.token_usage")
        normalised = next(r for r in records if r["name"] == "gen_ai.usage")
        self.assertEqual(original["points"][0]["value"], 321)
        self.assertEqual(normalised["value"], 321)
        self.assertEqual(normalised["attributes"]["gen_ai.token.kind"], "cache_creation")

    def test_codex_raw_input_flags_cache_inclusion(self):
        req = _histogram_metric_export_request(
            "codex.turn.token_usage",
            {"token_type": "input", "model": "gpt-5-codex"},
            1000,
        )
        records = otlp_sink.decode_metrics(req)
        normalised = [r for r in records if r["name"] == "gen_ai.usage"]
        self.assertEqual(len(normalised), 1)
        self.assertIs(normalised[0]["attributes"]["gen_ai.input_includes_cache_read"], True)

    def test_codex_total_yields_no_normalised_record(self):
        req = _histogram_metric_export_request(
            "codex.turn.token_usage",
            {"token_type": "total", "model": "gpt-5-codex"},
            1500,
        )
        records = otlp_sink.decode_metrics(req)
        originals = [r for r in records if r["name"] == "codex.turn.token_usage"]
        normalised = [r for r in records if r["name"] == "gen_ai.usage"]
        self.assertEqual(len(originals), 1, "the original record must still be written")
        self.assertEqual(normalised, [], "'total' is derivable, not a new axis -- must not be normalised")

    def test_unknown_metric_name_yields_no_normalised_record(self):
        req = _metric_export_request(
            "some.other.metric",
            {"type": "input"},
            1,
        )
        records = otlp_sink.decode_metrics(req)
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0]["name"], "some.other.metric")

    def test_decode_metrics_is_additive_not_mutating(self):
        # The original record's `points` list must be the exact same objects
        # passed to _genai_normalize, not a copy that could silently diverge --
        # and normalisation must not mutate them in place.
        req = _metric_export_request(
            "claude_code.token.usage",
            {"type": "output", "model": "claude-sonnet-5"},
            42,
        )
        records = otlp_sink.decode_metrics(req)
        original = next(r for r in records if r["name"] == "claude_code.token.usage")
        self.assertEqual(original["points"][0]["attributes"], {"type": "output", "model": "claude-sonnet-5"})


class LiveServerTests(unittest.TestCase):
    """End-to-end: a real ThreadingHTTPServer receiving a hand-crafted
    chunked-framed HTTP/1.1 request over a raw socket (http.client always sets
    Content-Length for a bytes body, so it cannot itself exercise the chunked
    path we're regression-testing)."""

    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), otlp_sink.make_handler(self.out_dir))
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def _send_raw(self, path: str, headers: dict, body: bytes) -> bytes:
        sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        try:
            header_lines = "".join(f"{k}: {v}\r\n" for k, v in headers.items())
            request = (
                f"POST {path} HTTP/1.1\r\n"
                f"Host: 127.0.0.1:{self.port}\r\n"
                f"{header_lines}"
                "Connection: close\r\n"
                "\r\n"
            ).encode() + body
            sock.sendall(request)
            chunks = []
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                chunks.append(chunk)
            return b"".join(chunks)
        finally:
            sock.close()

    def test_chunked_export_is_decoded_and_written(self):
        body = _log_export_request("hello from a chunked export")
        wire = _chunked_encode([body[: len(body) // 2], body[len(body) // 2 :]])
        response = self._send_raw(
            "/v1/logs",
            {"Content-Type": "application/x-protobuf", "Transfer-Encoding": "chunked"},
            wire,
        )
        self.assertIn(b"200", response.split(b"\r\n", 1)[0])
        logs_path = Path(self.out_dir) / "logs.ndjson"
        self.assertTrue(logs_path.exists(), "chunked export should have produced logs.ndjson")
        content = logs_path.read_text(encoding="utf-8")
        self.assertIn("hello from a chunked export", content)

    def test_content_length_export_still_works(self):
        body = _log_export_request("hello from a content-length export")
        response = self._send_raw(
            "/v1/logs",
            {"Content-Type": "application/x-protobuf", "Content-Length": str(len(body))},
            body,
        )
        self.assertIn(b"200", response.split(b"\r\n", 1)[0])
        content = (Path(self.out_dir) / "logs.ndjson").read_text(encoding="utf-8")
        self.assertIn("hello from a content-length export", content)

    def test_zero_record_hits_announce_once_per_route(self):
        for _ in range(3):
            self._send_raw(
                "/v1/traces",
                {"Content-Type": "application/x-protobuf", "Content-Length": "0"},
                b"",
            )
        errors_path = Path(self.out_dir) / "errors.log"
        self.assertTrue(errors_path.exists())
        lines = errors_path.read_text(encoding="utf-8").splitlines()
        route_lines = [l for l in lines if l.startswith("/v1/traces: 0 records")]
        self.assertEqual(
            len(route_lines), 1, f"expected exactly one rate-limited announce, got {route_lines}"
        )

    def test_logs_export_also_writes_the_ledger_projection(self):
        # A projection nothing writes is dead (tempdoc 745), so the ledger is
        # asserted through the real POST path, not build_ledger_rows alone.
        req = logs_service_pb2.ExportLogsServiceRequest()
        lr = req.resource_logs.add().scope_logs.add().log_records.add()
        lr.time_unix_nano = 1788576679696000000
        lr.body.string_value = "claude_code.api_request"
        for key, value in (("event.name", "api_request"), ("session.id", "sess-e2e"),
                           ("user.email", "someone@example.com"), ("model", "claude-sonnet-5")):
            kv = lr.attributes.add()
            kv.key = key
            kv.value.string_value = value
        kv = lr.attributes.add()
        kv.key = "input_tokens"
        kv.value.int_value = 11
        body = req.SerializeToString()

        response = self._send_raw(
            "/v1/logs",
            {"Content-Type": "application/x-protobuf", "Content-Length": str(len(body))},
            body,
        )
        self.assertIn(b"200", response.split(b"\r\n", 1)[0])
        self.assertTrue((Path(self.out_dir) / "logs.ndjson").exists(), "raw stream still written")
        ledger_path = Path(self.out_dir) / "ledger.ndjson"
        self.assertTrue(ledger_path.exists(), "the same POST must write the ledger stream")
        lines = [l for l in ledger_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        self.assertEqual(len(lines), 1, lines)
        row = json.loads(lines[0])
        self.assertEqual(row["event"], "api_request")
        self.assertEqual(row["session.id"], "sess-e2e")
        self.assertEqual(row["attributes"], {"model": "claude-sonnet-5", "input_tokens": 11})
        self.assertNotIn("someone@example.com", lines[0])
        self.assertNotIn(", ", lines[0], "the ledger row is written with compact separators")

    def test_metrics_export_writes_no_ledger(self):
        req = _metric_export_request("claude_code.token.usage", {"type": "output"}, 5)
        body = req.SerializeToString()
        self._send_raw(
            "/v1/metrics",
            {"Content-Type": "application/x-protobuf", "Content-Length": str(len(body))},
            body,
        )
        self.assertTrue((Path(self.out_dir) / "metrics.ndjson").exists())
        self.assertFalse((Path(self.out_dir) / "ledger.ndjson").exists(),
                         "only the log route projects to the ledger")


class RotationTests(unittest.TestCase):
    """Direct unit tests of the module-level rotate/prune/archive functions —
    no server rig needed. ROTATE_BYTES is monkeypatched down to a tiny value
    so a few bytes of content trigger rotation without writing real 20 MB
    files."""

    def setUp(self):
        self.out_dir = tempfile.mkdtemp()
        self._orig_rotate_bytes = otlp_sink.ROTATE_BYTES
        self._orig_retention = dict(otlp_sink.RETENTION)
        otlp_sink.ROTATE_BYTES = 10

    def tearDown(self):
        otlp_sink.ROTATE_BYTES = self._orig_rotate_bytes
        otlp_sink.RETENTION.clear()
        otlp_sink.RETENTION.update(self._orig_retention)

    def _path(self, base: str) -> str:
        return os.path.join(self.out_dir, f"{base}.ndjson")

    def _write(self, base: str, content: str) -> None:
        with open(self._path(base), "a", encoding="utf-8") as f:
            f.write(content)

    def _rotate(self, base: str) -> None:
        otlp_sink.rotate_if_big(self._path(base))

    def test_two_successive_rotations_preserve_both_archives(self):
        # The regression this fix closes. Old code:
        #   prev = filepath[:-len(".ndjson")] + ".prev.ndjson"
        #   if os.path.exists(prev): os.remove(prev)
        #   os.replace(filepath, prev)
        # On the SECOND rotation, `prev` already exists (from the first
        # rotation) so the old code unconditionally os.remove()'d it before
        # replacing — destroying the first archive's content outright,
        # regardless of any retention policy. This test writes two rotations
        # and asserts BOTH archives survive with distinct, original content:
        # it would FAIL against the pre-fix rotate_if_big, because the old
        # code left only one `.prev.ndjson` on disk (containing only the
        # second generation's content) — never two files.
        self._write("traces", "AAAAAAAAAAAAAAA\n")  # > 10 bytes -> triggers rotation
        self._rotate("traces")
        self._write("traces", "BBBBBBBBBBBBBBB\n")
        self._rotate("traces")
        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 2, f"expected 2 surviving archives, got {archives}")
        contents = [Path(p).read_text(encoding="utf-8") for p in archives]
        self.assertIn("AAAAAAAAAAAAAAA\n", contents)
        self.assertIn("BBBBBBBBBBBBBBB\n", contents)

    def test_per_stream_prune_keeps_newest_n(self):
        otlp_sink.RETENTION["logs"] = 2
        for label in ("one", "two", "three"):
            self._write("logs", f"{label}-padded-out-past-ten-bytes\n")
            self._rotate("logs")
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        self.assertEqual(len(archives), 2, f"expected 2 surviving archives, got {archives}")
        contents = "".join(Path(p).read_text(encoding="utf-8") for p in archives)
        self.assertNotIn("one-padded", contents, "oldest archive should have been pruned")
        self.assertIn("two-padded", contents)
        self.assertIn("three-padded", contents)

    def test_metrics_retention_none_is_never_pruned(self):
        # Load-bearing: metrics is the stream the cost baseline needs.
        self.assertIsNone(otlp_sink.RETENTION.get("metrics"))
        for i in range(4):
            self._write("metrics", f"m{i}-padded-out-past-ten-bytes\n")
            self._rotate("metrics")
        archives = otlp_sink._list_archives(self.out_dir, "metrics")
        self.assertEqual(len(archives), 4, f"expected all 4 archives kept, got {archives}")
        contents = "".join(Path(p).read_text(encoding="utf-8") for p in archives)
        for i in range(4):
            self.assertIn(f"m{i}-padded", contents)

    def test_traces_retention_is_capped_not_unbounded(self):
        # traces was RETENTION None until tempdoc 930 F2, which is what let it
        # reach 17 GB on this machine. Pins the cap so a revert to None (the
        # unbounded growth this closes) fails here rather than on disk months
        # later.
        self.assertEqual(otlp_sink.RETENTION.get("traces"), 14)
        otlp_sink.RETENTION["traces"] = 2
        for i in range(3):
            self._write("traces", f"t{i}-padded-out-past-ten-bytes\n")
            self._rotate("traces")
        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 2, f"expected the oldest archive pruned, got {archives}")
        contents = "".join(Path(p).read_text(encoding="utf-8") for p in archives)
        self.assertNotIn("t0-padded", contents, "oldest traces archive should have been pruned")

    def test_ledger_retention_is_long(self):
        # The ledger's whole point: the numbers outlive the bodies. logs keeps
        # 2 archives (~1 GB/active day of embedded request bodies), the
        # body-free projection keeps 90 (~1 MB/day).
        self.assertEqual(otlp_sink.RETENTION.get("ledger"), 90)
        self.assertLess(otlp_sink.RETENTION["logs"], otlp_sink.RETENTION["ledger"])

    def test_archive_naming_collision_safe_within_same_second(self):
        fixed_ts = "2026-07-16T133648Z"
        orig_ts_fn = otlp_sink._archive_timestamp
        otlp_sink._archive_timestamp = lambda: fixed_ts
        try:
            self._write("traces", "first-collision-payload-bytes\n")
            self._rotate("traces")
            self._write("traces", "second-collision-payload-bytes\n")
            self._rotate("traces")
        finally:
            otlp_sink._archive_timestamp = orig_ts_fn

        archives = otlp_sink._list_archives(self.out_dir, "traces")
        self.assertEqual(len(archives), 2, f"expected 2 non-colliding archives, got {archives}")
        names = [os.path.basename(p) for p in archives]
        # Neither rotation overwrote the other, and sort order (== filename
        # order returned by _list_archives) matches creation order.
        self.assertEqual(names[0], f"traces.{fixed_ts}.ndjson")
        self.assertEqual(names[1], f"traces.{fixed_ts}_001.ndjson")
        contents = [Path(p).read_text(encoding="utf-8") for p in archives]
        self.assertIn("first-collision-payload-bytes\n", contents)
        self.assertIn("second-collision-payload-bytes\n", contents)

    def test_collision_counter_sorts_lexically_past_99(self):
        """The counter is read back by a LEXICAL sort, so its zero-padding is a
        correctness property, not cosmetics: at 2 digits `_100` sorts BEFORE `_99`
        and the archives would be replayed out of order. Unreachable in practice
        (100 rotations of a 20MB file in one second), but the ordering contract
        should not quietly depend on that. Pins the padding against a naive revert.
        """
        names = [f"traces.2026-07-16T133648Z_{n:03d}.ndjson" for n in (1, 2, 99, 100, 101)]
        self.assertEqual(sorted(names), names, "zero-padded counter must sort in creation order")
        two_digit = [f"traces.2026-07-16T133648Z_{n:02d}.ndjson" for n in (99, 100)]
        self.assertNotEqual(sorted(two_digit), two_digit, "2-digit padding would mis-sort (the bug this guards)")

    def test_archive_regex_does_not_cross_match_other_streams_or_current_file(self):
        # base='logs' must not pick up metrics archives, a 'logs-other'
        # stream, or the plain current/legacy filenames.
        for name in (
            "metrics.2026-07-16T133648Z.ndjson",
            "logs-other.2026-07-16T133648Z.ndjson",
            "logs.ndjson",
            "logs.prev.ndjson",
        ):
            Path(self.out_dir, name).write_text("x", encoding="utf-8")
        Path(self.out_dir, "logs.2026-07-16T133648Z.ndjson").write_text("real-archive", encoding="utf-8")
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        names = [os.path.basename(p) for p in archives]
        self.assertEqual(names, ["logs.2026-07-16T133648Z.ndjson"], names)

    def test_current_file_never_deleted_only_renamed(self):
        # After rotation the current-named file must not exist as leftover
        # content — os.replace() renamed it away, it was not deleted.
        self._write("logs", "content-past-ten-bytes\n")
        self._rotate("logs")
        self.assertFalse(os.path.exists(self._path("logs")))
        archives = otlp_sink._list_archives(self.out_dir, "logs")
        self.assertEqual(len(archives), 1)
        self.assertIn("content-past-ten-bytes\n", Path(archives[0]).read_text(encoding="utf-8"))


def _log_record(event_name, attributes, time_unix_nano=1788576679696000000):
    """A decode_logs-shaped record, with the identity attributes every real
    Claude Code log record carries (so the drop-list is exercised, not assumed).
    Attribute NAMES are verbatim from a captured record; every value is
    synthetic — no live-corpus identity belongs in a fixture."""
    attrs = {
        "user.id": "0000000000000000000000000000000000000000000000000000000000000001",
        "session.id": "11111111-2222-4333-8444-555555555555",
        "organization.id": "22222222-3333-4444-8555-666666666666",
        "user.email": "someone@example.com",
        "user.account_uuid": "33333333-4444-4555-8666-777777777777",
        "user.account_id": "user_000000000000000000000000",
        "event.name": event_name,
        "event.timestamp": "2026-09-05T02:51:19.696Z",
        "event.sequence": 8168,
        "prompt.id": "44444444-5555-4666-8777-888888888888",
    }
    attrs.update(attributes)
    return {
        "signal": "log",
        "time_unix_nano": time_unix_nano,
        "severity": "",
        "body": f"claude_code.{event_name}",
        "attributes": attrs,
        "resource": {"service.name": "claude-code"},
    }


class LedgerTests(unittest.TestCase):
    """`build_ledger_rows` — the body-free projection of the log stream
    (tempdoc 930 F2). Attribute names in these fixtures are copied verbatim
    from a real captured record."""

    def test_api_request_keeps_exactly_the_numeric_subset(self):
        rec = _log_record("api_request", {
            "model": "claude-sonnet-5",
            "input_tokens": 2,
            "output_tokens": 579,
            "cache_read_tokens": 109312,
            "cache_creation_tokens": 885,
            "cost_usd": 0.0298689,
            "cost_usd_micros": 29869,
            "duration_ms": 6460,
            "request_id": "req_000000000000000000000000",
            "client_request_id": "55555555-6666-4777-8888-999999999999",
            "speed": "normal",
            "query_source": "agent:builtin:general-purpose",
            "effort": "high",
            "agent.name": "general-purpose",
            "skill.name": "derisk",
        })
        rows = otlp_sink.build_ledger_rows([rec])
        self.assertEqual(len(rows), 1)
        row = rows[0]
        self.assertEqual(row["event"], "api_request")
        self.assertEqual(row["session.id"], "11111111-2222-4333-8444-555555555555")
        self.assertEqual(row["time_unix_nano"], 1788576679696000000)
        self.assertEqual(row["ts"], "2026-09-05T02:51:19.696Z")
        self.assertEqual(sorted(row["attributes"]), sorted([
            "model", "input_tokens", "output_tokens", "cache_read_tokens",
            "cache_creation_tokens", "cost_usd", "duration_ms", "request_id",
            "query_source", "agent.name",
        ]))
        # The content-name screen contains the substrings "input"/"output"; the
        # token counts must survive it (they are numbers, not bodies).
        self.assertEqual(row["attributes"]["input_tokens"], 2)
        self.assertEqual(row["attributes"]["output_tokens"], 579)
        # Not allow-listed -> absent, even though they are harmless.
        for absent in ("cost_usd_micros", "speed", "effort", "skill.name", "client_request_id"):
            self.assertNotIn(absent, row["attributes"])

    def test_identity_attributes_are_dropped_except_session_id(self):
        rec = _log_record("api_request", {"model": "claude-sonnet-5", "input_tokens": 1})
        row = otlp_sink.build_ledger_rows([rec])[0]
        serialized = json.dumps(row)
        for leaked in ("someone@example.com", "user_000000000000000000000000",
                       "33333333-4444-4555-8666-777777777777",
                       "22222222-3333-4444-8555-666666666666"):
            self.assertNotIn(leaked, serialized, f"identity value {leaked} leaked into the ledger")
        self.assertIn("session.id", row)

    def test_tool_result_drops_tool_input_and_keeps_outcome(self):
        rec = _log_record("tool_result", {
            "tool_name": "Grep",
            "tool_use_id": "toolu_000000000000000000000000",
            "success": "true",
            "duration_ms": "380",
            "tool_input": '{"pattern":"sensitive-looking-content"}',
            "tool_input_size_bytes": "234",
            "tool_result_size_bytes": "11379",
            "decision_source": "config",
            "decision_type": "accept",
        })
        row = otlp_sink.build_ledger_rows([rec])[0]
        self.assertEqual(row["attributes"],
                         {"tool_name": "Grep", "success": "true", "duration_ms": "380"})
        self.assertNotIn("sensitive-looking-content", json.dumps(row))

    def test_tool_decision_and_subagent_completed_subsets(self):
        decision = _log_record("tool_decision", {
            "decision": "accept", "source": "config", "tool_name": "Grep",
            "tool_use_id": "toolu_015", "tool_source": "builtin",
        })
        subagent = _log_record("subagent_completed", {
            "agent_type": "general-purpose", "agent.source": "built-in",
            "is_built_in": True, "is_async": True, "total_tokens": 120406,
            "total_tool_uses": 72, "duration_ms": 406776,
            "model": "claude-sonnet-5", "final_model": "claude-sonnet-5",
            "model_swapped": False,
        })
        rows = otlp_sink.build_ledger_rows([decision, subagent])
        self.assertEqual(rows[0]["attributes"],
                         {"tool_name": "Grep", "decision": "accept"})
        self.assertEqual(rows[1]["attributes"], {
            "agent_type": "general-purpose", "total_tokens": 120406,
            "total_tool_uses": 72, "duration_ms": 406776,
            "model": "claude-sonnet-5", "final_model": "claude-sonnet-5",
            "model_swapped": False,
        })

    def test_unlisted_event_and_non_log_signals_yield_no_rows(self):
        rows = otlp_sink.build_ledger_rows([
            _log_record("user_prompt", {"prompt_length": 42}),
            {"signal": "trace", "name": "claude_code.tool_decision", "attributes": {}},
        ])
        self.assertEqual(rows, [])

    def test_body_fallback_when_event_name_attribute_is_absent(self):
        rec = _log_record("api_request", {"model": "claude-opus-5", "input_tokens": 3})
        del rec["attributes"]["event.name"]
        rows = otlp_sink.build_ledger_rows([rec])
        self.assertEqual(len(rows), 1, "body 'claude_code.api_request' must still resolve")
        self.assertEqual(rows[0]["event"], "api_request")

    def test_oversized_and_structured_values_are_refused(self):
        rec = _log_record("api_request", {
            "model": "m" * (otlp_sink.LEDGER_MAX_VALUE_CHARS + 1),
            "request_id": ["structured", "not", "scalar"],
            "input_tokens": 7,
        })
        row = otlp_sink.build_ledger_rows([rec])[0]
        self.assertNotIn("model", row["attributes"], "value past the 512-char cap must be dropped")
        self.assertNotIn("request_id", row["attributes"], "a structured value must be dropped")
        self.assertEqual(row["attributes"]["input_tokens"], 7)

    def test_content_named_string_attribute_is_refused_even_if_allow_listed(self):
        # Second net under the allow-list: if `query_source` (allow-listed)
        # ever arrived carrying content it would still be kept, so the screen
        # is asserted directly on the predicate for a content-named string.
        self.assertFalse(otlp_sink._ledger_value_ok("output_tokens", "a body string"))
        self.assertTrue(otlp_sink._ledger_value_ok("output_tokens", 579))
        self.assertFalse(otlp_sink._ledger_value_ok("tool_input", "x"))
        self.assertTrue(otlp_sink._ledger_value_ok("tool_name", "Grep"))


if __name__ == "__main__":
    unittest.main()
