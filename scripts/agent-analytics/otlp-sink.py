#!/usr/bin/env python3
"""Local Docker-free OTLP/HTTP sink for developer-agent telemetry (tempdoc 622,
Layer A). Receives native Claude Code OTLP traces/metrics/logs, decodes the
protobuf, and appends normalized JSON lines to tmp/agent-telemetry/otlp/.

This is the canonical on-machine sink the analytics scripts ingest from
(telemetry-io.mjs `loadEventsFromSource('otlp')`). It is intentionally minimal
and dependency-light (opentelemetry-proto + stdlib) so it runs on a bare Python
without Docker or a full collector.

Usage:  python scripts/agent-analytics/otlp-sink.py [--port 4318] [--out DIR]
Endpoints (OTLP/HTTP, protobuf): POST /v1/traces, /v1/metrics, /v1/logs
Streams written: traces / metrics / logs (one per endpoint, verbatim) plus
`ledger.ndjson`, the body-free projection of logs that carries the numbers on
a long retention while the raw bodies age out (see LEDGER_KEEP below).
"""
import argparse, datetime, json, os, re, sys, threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from opentelemetry.proto.trace.v1 import trace_pb2
from opentelemetry.proto.metrics.v1 import metrics_pb2
from opentelemetry.proto.logs.v1 import logs_pb2
from opentelemetry.proto.collector.trace.v1 import trace_service_pb2
from opentelemetry.proto.collector.metrics.v1 import metrics_service_pb2
from opentelemetry.proto.collector.logs.v1 import logs_service_pb2


def _anyval(v):
    """Flatten an OTLP AnyValue to a plain Python value."""
    k = v.WhichOneof("value")
    if k is None:
        return None
    if k == "string_value":
        return v.string_value
    if k == "bool_value":
        return v.bool_value
    if k == "int_value":
        return v.int_value
    if k == "double_value":
        return v.double_value
    if k == "array_value":
        return [_anyval(x) for x in v.array_value.values]
    if k == "kvlist_value":
        return {kv.key: _anyval(kv.value) for kv in v.kvlist_value.values}
    if k == "bytes_value":
        return v.bytes_value.hex()
    return None


def _attrs(kvs):
    return {kv.key: _anyval(kv.value) for kv in kvs}


def _hex(b):
    return b.hex() if b else None


def decode_traces(req):
    out = []
    for rs in req.resource_spans:
        res = _attrs(rs.resource.attributes)
        for ss in rs.scope_spans:
            for sp in ss.spans:
                out.append({
                    "signal": "trace",
                    "name": sp.name,
                    "trace_id": _hex(sp.trace_id),
                    "span_id": _hex(sp.span_id),
                    "parent_span_id": _hex(sp.parent_span_id) or None,
                    "start_unix_nano": sp.start_time_unix_nano,
                    "end_unix_nano": sp.end_time_unix_nano,
                    "attributes": _attrs(sp.attributes),
                    "events": [{"name": e.name, "attributes": _attrs(e.attributes)}
                               for e in sp.events],
                    "resource": res,
                })
    return out


def decode_metrics(req):
    out = []
    for rm in req.resource_metrics:
        res = _attrs(rm.resource.attributes)
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                points = []
                kind = m.WhichOneof("data")
                data = getattr(m, kind) if kind else None
                dps = list(getattr(data, "data_points", [])) if data else []
                for dp in dps:
                    val = None
                    if kind in ("histogram", "exponential_histogram"):
                        # Current Codex exports token usage as a histogram with
                        # one point per token_type; its value is the optional
                        # HistogramDataPoint.sum, not a NumberDataPoint oneof.
                        try:
                            if dp.HasField("sum"):
                                val = dp.sum
                        except ValueError:
                            pass
                    else:
                        value_kind = dp.WhichOneof("value") if hasattr(dp, "WhichOneof") else None
                        if value_kind in ("as_int", "as_double"):
                            val = getattr(dp, value_kind)
                    points.append({"attributes": _attrs(dp.attributes),
                                   "value": val,
                                   "time_unix_nano": getattr(dp, "time_unix_nano", 0)})
                out.append({"signal": "metric", "name": m.name, "kind": kind,
                            "points": points, "resource": res})
                out.extend(_genai_normalize(m.name, points, res))
    return out


# --- gen_ai.* normalisation (tempdoc 886 §12 PR 3, §10.3 option B) --------
# Both harnesses this sink receives emit their own token-usage metric under a
# harness-specific name and attribute vocabulary (Claude:
# `claude_code.token.usage{type}`; Codex: `codex.turn.token_usage{token_type}`).
# Neither emits the OTel GenAI semantic-convention names
# (`gen_ai.usage.*` / `gen_ai.token.kind`), so a downstream reader that wants
# "tokens by kind, harness-neutral" has to special-case every harness's own
# vocabulary. This table is additive and one entry per harness: it does not
# touch the original decoded records (kept verbatim, unchanged shape) and
# instead appends a normalised TWIN record alongside them for every data
# point whose raw type is a known token kind. A third harness is one new
# entry here, not a new code path.
#
# Deliberately flat (not nested "points" like the original per-metric-batch
# record): the acceptance criterion is "one normalised record per data
# point", which is what a per-token-kind consumer actually wants to filter
# on (`record.name == 'gen_ai.usage' and record.attributes['gen_ai.token.kind']
# == 'cache_read'`) without re-flattening a points array first.
#
# Written to the SAME metrics.ndjson stream as the originals (these records
# are appended into decode_metrics' own `out` list, so do_POST's existing
# rotate/prune/write path for "/v1/metrics" picks them up with no separate
# plumbing) rather than a second `metrics.genai.ndjson` file -- one rotation
# policy, one RETENTION entry, one archive-glob to keep in sync, and metrics
# is already the stream that is never pruned (RETENTION["metrics"] = None),
# which the normalised records need exactly as much as the originals do.
GENAI_TOKEN_MAP = {
    "claude_code.token.usage": {
        "system": "claude-code",
        "attr": "type",
        "kinds": {
            "input": "input",
            "output": "output",
            "cacheRead": "cache_read",
            "cacheCreation": "cache_creation",
        },
    },
    "codex.turn.token_usage": {
        "system": "codex-cli",
        "attr": "token_type",
        "kinds": {
            "input": "input",
            "cached_input": "cache_read",
            "cache_write_input": "cache_creation",
            "output": "output",
            "reasoning_output": "reasoning",
            # "total" is deliberately absent from this table: it is
            # input + output (derivable from the other normalised points,
            # per Codex's OpenAI-style convention where input already
            # includes cached_input), not a new token axis -- so it is
            # skipped rather than normalised.
        },
        "extra": {
            # Codex's raw "input" INCLUDES cached_input (the OpenAI
            # convention); Claude's "input" EXCLUDES cacheRead/cacheCreation
            # (they are separate metric-attribute types). Flagged on the
            # point itself so a reader summing "input" across harnesses
            # cannot silently treat the two as the same quantity.
            "input": {"gen_ai.input_includes_cache_read": True},
        },
    },
}


def _genai_normalize(metric_name, points, res):
    """Additive `gen_ai.usage` twin records for a GENAI_TOKEN_MAP-mapped
    metric's decoded `points` (see module comment above). Returns one flat
    record per data point whose raw token-kind attribute is in the map;
    an unmapped kind (e.g. Codex's `total`) or an unrecognised metric name
    yields no records for that point -- silently, by design, not an error."""
    spec = GENAI_TOKEN_MAP.get(metric_name)
    if spec is None:
        return []
    out = []
    for p in points:
        attrs = p.get("attributes") or {}
        raw_kind = attrs.get(spec["attr"])
        kind = spec["kinds"].get(raw_kind)
        if kind is None:
            continue
        norm_attrs = dict(attrs)
        norm_attrs["gen_ai.system"] = spec["system"]
        norm_attrs["gen_ai.request.model"] = attrs.get("model")
        norm_attrs["gen_ai.token.kind"] = kind
        norm_attrs.update(spec.get("extra", {}).get(raw_kind, {}))
        out.append({
            "signal": "metric",
            "name": "gen_ai.usage",
            "normalized": True,
            "attributes": norm_attrs,
            "value": p.get("value"),
            "time_unix_nano": p.get("time_unix_nano", 0),
            "resource": res,
        })
    return out


def decode_logs(req):
    out = []
    for rl in req.resource_logs:
        res = _attrs(rl.resource.attributes)
        for sl in rl.scope_logs:
            for lr in sl.log_records:
                out.append({
                    "signal": "log",
                    "time_unix_nano": lr.time_unix_nano,
                    "severity": lr.severity_text,
                    "body": _anyval(lr.body),
                    "attributes": _attrs(lr.attributes),
                    "resource": res,
                })
    return out


ROUTES = {
    "/v1/traces": (trace_service_pb2.ExportTraceServiceRequest, decode_traces, "traces.ndjson"),
    "/v1/metrics": (metrics_service_pb2.ExportMetricsServiceRequest, decode_metrics, "metrics.ndjson"),
    "/v1/logs": (logs_service_pb2.ExportLogsServiceRequest, decode_logs, "logs.ndjson"),
}


# --- ledger.ndjson: the body-free projection of logs (tempdoc 930 F2) -----
# logs.ndjson is the only stream whose retention is genuinely infeasible:
# `api_request` records embed the request/response bodies, so the stream
# rotates every ~25 minutes of active work (~1 GB/active day) and RETENTION
# can only keep a couple of archives -- which means the numbers analytics
# actually reads (tokens, cost, durations, per-tool outcomes) age out of the
# capture within an hour, together with the bodies nothing reads.
#
# The ledger separates those two lifetimes. It is a PROJECTION of the same
# decoded log records (not a second capture authority): the route below still
# writes logs.ndjson verbatim first, and every ledger row is derived from a
# record already in that batch. Rows carry an allow-listed, body-free
# attribute subset, so the stream runs ~1 MB/day and 90 archives of 20 MB is
# years of history rather than hours.
#
# Written as its own base name (not appended into logs.ndjson) precisely
# because the point is a SEPARATE retention policy -- one rotation policy per
# base name is the sink's existing shape, so a second lifetime needs a second
# base.
LEDGER_FILE = "ledger.ndjson"

# The route whose decoded records the ledger projects from.
LEDGER_SOURCE_ROUTE = "/v1/logs"

# Allow-list per event name (`event.name` attribute; body `claude_code.<name>`
# is the fallback). ONLY these names are copied -- an attribute added upstream
# is absent from the ledger until it is named here, so the stream cannot grow
# a body by accident. Identity attributes are deliberately NOT listed:
# `user.email`, `user.account_id`, `user.account_uuid`, `user.id` and
# `organization.id` are all dropped, and `session.id` is the one identity the
# ledger keeps because it is the field every reader joins on
# (telemetry-io.mjs `loadEventsFromOtlp`/`loadCostsFromOtlp`).
LEDGER_KEEP = {
    "api_request": (
        "model", "input_tokens", "output_tokens", "cache_read_tokens",
        "cache_creation_tokens", "cost_usd", "duration_ms", "request_id",
        "query_source", "agent.name", "agent_id",
    ),
    "subagent_completed": (
        "agent_type", "total_tokens", "total_tool_uses", "duration_ms",
        "model", "final_model", "model_swapped",
    ),
    "tool_result": ("tool_name", "success", "duration_ms"),
    "tool_decision": ("tool_name", "decision", "duration_ms"),
}

# Second net UNDER the allow-list, not a replacement for it: if one of the
# names above ever starts carrying content upstream, it is dropped here rather
# than silently reintroducing bodies into the long-retention stream.
LEDGER_CONTENT_TOKENS = ("prompt", "body", "content", "message", "input",
                         "output", "text", "arguments", "result")

LEDGER_MAX_VALUE_CHARS = 512


def _ledger_event_name(rec):
    """`event.name` for a decoded log record, falling back to the body string
    (`claude_code.api_request` -> `api_request`) for a harness that sets only
    the body. Returns None when neither is a usable string."""
    attrs = rec.get("attributes") or {}
    name = attrs.get("event.name")
    if isinstance(name, str) and name:
        return name
    body = rec.get("body")
    if isinstance(body, str) and body:
        prefix = "claude_code."
        return body[len(prefix):] if body.startswith(prefix) else body
    return None


def _ledger_value_ok(name, value):
    """Whether an allow-listed attribute's VALUE may enter the ledger.

    A number/bool is always allowed: it cannot carry a request body, and that
    exemption is what lets `input_tokens` / `output_tokens` through a
    content-name screen that contains the substrings `input` and `output`.
    A string is screened on both the name (content vocabulary) and the length
    cap; anything structured (list/map) is refused outright -- no allow-listed
    attribute is structured, so one appearing means the shape changed."""
    if value is None or isinstance(value, (bool, int, float)):
        return True
    if not isinstance(value, str):
        return False
    if len(value) > LEDGER_MAX_VALUE_CHARS:
        return False
    low = name.lower()
    return not any(tok in low for tok in LEDGER_CONTENT_TOKENS)


def build_ledger_rows(records):
    """Project decoded log records (decode_logs' output) to compact ledger
    rows. A record whose event name is not in LEDGER_KEEP yields nothing --
    silently, by design: the ledger is a named subset, not a filtered copy of
    everything."""
    out = []
    for rec in records:
        if rec.get("signal") != "log":
            continue
        event = _ledger_event_name(rec)
        keep = LEDGER_KEEP.get(event)
        if keep is None:
            continue
        attrs = rec.get("attributes") or {}
        kept = {}
        for name in keep:
            if name not in attrs:
                continue
            value = attrs[name]
            if _ledger_value_ok(name, value):
                kept[name] = value
        ts = attrs.get("event.timestamp")
        out.append({
            "signal": "ledger",
            "event": event,
            "time_unix_nano": rec.get("time_unix_nano", 0),
            "ts": ts if isinstance(ts, str) and len(ts) <= LEDGER_MAX_VALUE_CHARS else None,
            "session.id": attrs.get("session.id"),
            "attributes": kept,
        })
    return out


ROTATE_BYTES = 20 * 1024 * 1024  # rotate a stream file past 20 MB

# Per-stream archive retention: int = max archived generations kept (oldest
# pruned first), None = keep every archive forever. Measured on this machine
# (tempdoc 930 F2), superseding the earlier "traces ~4 GB/month" estimate:
#   metrics  ~tens of MB total     -- None: the sole source the cost baseline
#                                    is computed from (tempdoc 745), never pruned.
#   logs     ~1 GB per ACTIVE day  -- 2: `api_request` rows embed request and
#                                    response bodies, so the file rotates every
#                                    ~25 min of active work. Long raw retention
#                                    is infeasible; ledger carries the numbers.
#   ledger   ~1 MB per day         -- 90: the body-free projection of logs
#                                    (LEDGER_KEEP above). 90 rotations of 20 MB
#                                    is years at this rate, so the numbers
#                                    outlive the bodies by orders of magnitude.
#   traces   17 GB unpruned here   -- 14: was None, which is what let it reach
#                                    17 GB. 14 archives caps it at ~280 MB while
#                                    still covering the recent window span
#                                    analysis actually reads.
RETENTION = {"logs": 2, "ledger": 90, "traces": 14, "metrics": None}

# Guards rotate-then-prune so two concurrent POSTs to the same route (the
# server is a ThreadingHTTPServer -- one thread per connection) can't both
# archive the same oversized file. One lock shared across all four streams
# (rather than a lock per stream) is deliberate: rotation is a rare,
# sub-millisecond event relative to POST volume, so the cross-stream
# serialization it costs is negligible, and it avoids a small per-stream
# lock dict to maintain.
_rotate_lock = threading.Lock()

_ARCHIVE_TS_FORMAT = "%Y-%m-%dT%H%M%SZ"


def _archive_timestamp():
    return datetime.datetime.now(datetime.timezone.utc).strftime(_ARCHIVE_TS_FORMAT)


def _archive_regex(base):
    # `<base>.<UTC-compact-timestamp>[_NN].ndjson`, e.g.
    # `logs.2026-07-16T133648Z.ndjson` or `logs.2026-07-16T133648Z_01.ndjson`
    # (the `_NN` suffix only appears on a same-second collision). Anchored on
    # both ends and on the literal base so base="logs" cannot match
    # `logs-something-else.ndjson` or a different stream's archives.
    return re.compile(r"^" + re.escape(base) + r"\.\d{4}-\d{2}-\d{2}T\d{6}Z(_\d+)?\.ndjson$")


def _reserve_archive_path(out_dir, base, ts):
    """Return an archive path for (base, ts) guaranteed not to already exist,
    appending a zero-padded counter on a same-second collision. The counter
    is introduced with `_` (which sorts after `.` in ASCII), so
    `logs.<ts>.ndjson` always sorts before `logs.<ts>_01.ndjson` -- archive
    filenames stay lexicographically == chronologically ordered even across
    a same-second collision."""
    candidate = os.path.join(out_dir, f"{base}.{ts}.ndjson")
    n = 1
    while os.path.exists(candidate):
        # 3 digits, not 2: the counter is read back by a LEXICAL sort, and `_100`
        # sorts before `_99`. Unreachable in practice (it needs 100 rotations of a
        # 20 MB file inside one second) but the padding is free and the ordering
        # contract should not depend on that being true.
        candidate = os.path.join(out_dir, f"{base}.{ts}_{n:03d}.ndjson")
        n += 1
    return candidate


def _list_archives(out_dir, base):
    """Archive paths for `base`, oldest first (filename sort order ==
    chronological order, see `_reserve_archive_path`)."""
    pattern = _archive_regex(base)
    try:
        names = [n for n in os.listdir(out_dir) if pattern.match(n)]
    except OSError:
        return []
    names.sort()
    return [os.path.join(out_dir, n) for n in names]


def _append_error(out_dir, message):
    # Mirrors do_POST's own error-write pattern: never let logging a failure
    # crash the receiver.
    try:
        with open(os.path.join(out_dir, "errors.log"), "a", encoding="utf-8") as f:
            f.write(f"{message}\n")
    except OSError:
        pass


def _prune_archives(out_dir, base):
    """Delete the oldest archives for `base` beyond its RETENTION cap. A cap
    of None (metrics) means never prune -- metrics is the cost baseline's sole
    source and must survive indefinitely. A failure
    to remove one archive is logged (not swallowed) and does not stop the
    rest of the prune."""
    cap = RETENTION.get(base)
    if cap is None:
        return
    archives = _list_archives(out_dir, base)
    excess = len(archives) - cap
    for old_path in archives[:max(0, excess)]:
        try:
            os.remove(old_path)
        except OSError as e:
            _append_error(out_dir, f"prune failed to remove {old_path}: {e}")


def rotate_if_big(filepath):
    """Archive filepath -> `<base>.<timestamp>.ndjson` once it exceeds
    ROTATE_BYTES, then prune that stream's archives per RETENTION. Unlike the
    old `.prev`-only scheme this replaced, the current file is always renamed
    (never deleted) and every archive is timestamped and kept unless its
    stream's RETENTION says otherwise -- so a stream with RETENTION None
    (metrics) retains its full history instead of losing everything
    older than one rotation. (tempdoc 745: a rotation was directly observed
    destroying 21 MB under the old scheme, with metrics -- the cost
    baseline's sole source -- exposed to the same loss.)

    Rotation/prune failures are caught (never crash the receiver or the
    write path that follows) but are always logged to errors.log -- this
    sink has now had two silent-total-loss bugs, so a retention failure must
    not be invisible a third time."""
    out_dir = os.path.dirname(filepath) or "."
    base = os.path.basename(filepath)
    if base.endswith(".ndjson"):
        base = base[: -len(".ndjson")]
    try:
        size = os.path.getsize(filepath)
    except OSError:
        return  # file doesn't exist yet -- nothing to rotate
    if size <= ROTATE_BYTES:
        return
    with _rotate_lock:
        try:
            # Re-check under the lock: a concurrent POST to the same route
            # may have already rotated this file between the unlocked size
            # check above and acquiring the lock. That is an expected race,
            # not a failure -- stay silent.
            size = os.path.getsize(filepath)
        except OSError:
            return
        if size <= ROTATE_BYTES:
            return
        try:
            archive_path = _reserve_archive_path(out_dir, base, _archive_timestamp())
            os.replace(filepath, archive_path)
            _prune_archives(out_dir, base)
        except OSError as e:
            _append_error(out_dir, f"rotate/prune failed for {base}: {e}")


def write_stream(out_dir, fname, records, compact=False):
    """Rotate-if-needed, then append `records` as NDJSON to `out_dir/fname`.
    Every stream (including the ledger) goes through the one rotate/prune path
    so a new base name inherits the existing retention machinery unchanged."""
    outpath = os.path.join(out_dir, fname)
    rotate_if_big(outpath)
    separators = (",", ":") if compact else None
    with open(outpath, "a", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r, separators=separators) + "\n")


def make_handler(out_dir):
    # Rate-limit for the zero-record announce (below): first occurrence per
    # route per sink lifetime only, so a client that legitimately exports an
    # empty batch on every call can't grow errors.log unboundedly.
    zero_record_announced = set()

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _read_body(self):
            # OTel-OTLP-Exporter-JavaScript sends chunked bodies with no
            # Content-Length (tempdoc 743): reading Content-Length alone
            # silently yields an empty body -> 0 records -> no exception ->
            # a completely invisible capture failure. Handle both framings.
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                return self._read_chunked_body()
            n = int(self.headers.get("Content-Length", 0))
            return self.rfile.read(n) if n else b""

        def _read_chunked_body(self):
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";", 1)[0], 16)
                if size == 0:
                    self.rfile.readline()  # trailing CRLF after the 0-size chunk
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()  # CRLF after each chunk's data
            return b"".join(chunks)

        def do_POST(self):
            route = ROUTES.get(self.path)
            body = self._read_body()
            count = 0
            if route is not None:
                msg_cls, decode, fname = route
                try:
                    req = msg_cls()
                    req.ParseFromString(body)
                    records = decode(req)
                    count = len(records)
                    if records:
                        write_stream(out_dir, fname, records)
                        if self.path == LEDGER_SOURCE_ROUTE:
                            # Projection, written AFTER the verbatim stream so a
                            # ledger-side failure can never cost the raw capture.
                            ledger_rows = build_ledger_rows(records)
                            if ledger_rows:
                                write_stream(out_dir, LEDGER_FILE, ledger_rows, compact=True)
                    elif self.path not in zero_record_announced:
                        zero_record_announced.add(self.path)
                        with open(os.path.join(out_dir, "errors.log"), "a", encoding="utf-8") as f:
                            f.write(
                                f"{self.path}: 0 records decoded from a {len(body)}B body "
                                "(further zero-record hits on this route are not logged)\n"
                            )
                except Exception as e:  # never crash the receiver
                    with open(os.path.join(out_dir, "errors.log"), "a", encoding="utf-8") as f:
                        f.write(f"{self.path}: {e}\n")
            print(f"{self.path} -> {count} records ({len(body)}B)", flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()
    return H


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4318)
    ap.add_argument("--out", default=os.path.join("tmp", "agent-telemetry", "otlp"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    print(f"OTLP sink on :{args.port} -> {args.out}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(args.out)).serve_forever()


if __name__ == "__main__":
    main()
