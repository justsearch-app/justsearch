//! Exit ledger v1 (Spec B T11/T12/T15). Keep the wire rules aligned with ExitLedger*.java
//! and scripts/dev/lib/exit-ledger.cjs. I/O failure degrades accounting, never supervision.
use serde_json::{json, Map, Value};
use std::fs::{self, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub(crate) const JS_CHILD_STOP: u32 = 0x4A53_0001;
pub(crate) const JS_ENGINE_STOP: u32 = 0x4A53_0002;
const MAX_RECORD_BYTES: usize = 4096;
const MAX_READ_LINE_BYTES: usize = 8192;
const SESSION_ROTATE_BYTES: u64 = 1024 * 1024;
const HARD_CAP_BYTES: u64 = 2 * 1024 * 1024;
const ENVELOPE: [&str; 5] = ["schemaVersion", "type", "seq", "at", "writer"];
const ROLES: [&str; 4] = ["engine", "shell", "dev-runner", "dev-cli"];
const GAP_REASONS: [&str; 4] = [
    "WRITE_FAILED",
    "ROTATE_FAILED",
    "CORRUPT_LINES",
    "UNKNOWN_SCHEMA",
];
const TIME_FIELDS: [&str; 8] = [
    "processStartedAt",
    "requestedAt",
    "deadlineAt",
    "exitedAt",
    "observedAt",
    "terminateCalledAt",
    "cooperativeAt",
    "since",
];

#[derive(Clone, Copy, Debug)]
pub(crate) enum Family {
    Children,
    Engine,
}
impl Family {
    fn name(self) -> &'static str {
        match self {
            Self::Children => "children",
            Self::Engine => "engine",
        }
    }
    fn paths(self, dir: &Path) -> (PathBuf, PathBuf) {
        (
            dir.join(format!("{}.v1.jsonl", self.name())),
            dir.join(format!("{}.v1.1.jsonl", self.name())),
        )
    }
    fn accepts(self, token: &str) -> bool {
        token == "ledger-gap"
            || match self {
                Self::Children => [
                    "child-spawn",
                    "child-start",
                    "stop-intent",
                    "stop-escalate",
                    "child-exit",
                    "child-survived",
                    "gpu-release",
                    "child-note",
                    "owner-died",
                ]
                .contains(&token),
                Self::Engine => [
                    "engine-start",
                    "engine-intent",
                    "engine-self-intent",
                    "engine-exit",
                    "engine-owner-died",
                ]
                .contains(&token),
            }
    }
}

pub(crate) fn ledger_directory(data_dir: &Path) -> PathBuf {
    data_dir.join("runtime/exit-ledger")
}

/// Java Instant's UTC grammar, including midnight 24:00 and the leap-second normalization.
/// Numerical timestamps and dates before 2001 always read as unknown.
pub(crate) fn known_timestamp(value: &Value) -> Option<i64> {
    let s = value.as_str()?;
    let b = s.as_bytes();
    if b.len() < 20
        || b[4] != b'-'
        || b[7] != b'-'
        || !matches!(b[10], b'T' | b't')
        || b[13] != b':'
        || b[16] != b':'
        || *b.last()? != b'Z'
    {
        return None;
    }
    let number = |a: usize, z: usize| -> Option<u32> {
        let bytes = b.get(a..z)?;
        if !bytes.iter().all(u8::is_ascii_digit) {
            return None;
        }
        std::str::from_utf8(bytes).ok()?.parse().ok()
    };
    let year = number(0, 4)? as i32;
    let month = time::Month::try_from(number(5, 7)? as u8).ok()?;
    let date = time::Date::from_calendar_date(year, month, number(8, 10)? as u8).ok()?;
    let (mut hour, minute, mut second) = (number(11, 13)?, number(14, 16)?, number(17, 19)?);
    let fraction = &b[19..b.len() - 1];
    let digits = if fraction.is_empty() {
        &[][..]
    } else {
        if fraction[0] != b'.'
            || !(2..=10).contains(&fraction.len())
            || !fraction[1..].iter().all(u8::is_ascii_digit)
        {
            return None;
        }
        &fraction[1..]
    };
    let mut millis = 0;
    for i in 0..3 {
        millis = millis * 10 + digits.get(i).map_or(0, |v| u16::from(v - b'0'));
    }
    let extra_day = hour == 24 && minute == 0 && second == 0 && digits.iter().all(|v| *v == b'0');
    if extra_day {
        hour = 0;
    }
    if hour == 23 && minute == 59 && second == 60 {
        second = 59;
    }
    if hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    let instant = date
        .with_hms_milli(hour as u8, minute as u8, second as u8, millis)
        .ok()?
        .assume_utc();
    let ms = instant.unix_timestamp_nanos() / 1_000_000 + if extra_day { 86_400_000 } else { 0 };
    (ms >= 978_307_200_000).then_some(ms as i64)
}

pub(crate) fn format_timestamp(ms: i64) -> String {
    let t = time::OffsetDateTime::from_unix_timestamp_nanos(i128::from(ms) * 1_000_000)
        .expect("timestamp within supported calendar");
    let prefix = format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}",
        t.year(),
        t.month() as u8,
        t.day(),
        t.hour(),
        t.minute(),
        t.second()
    );
    if t.millisecond() == 0 {
        format!("{prefix}Z")
    } else {
        format!("{prefix}.{:03}Z", t.millisecond())
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
fn normalized_timestamp(value: &Value) -> Value {
    known_timestamp(value)
        .map(format_timestamp)
        .map(Value::String)
        .unwrap_or(Value::Null)
}
fn java_blank(text: &str) -> bool {
    text.chars().all(|c| {
        matches!(c as u32, 0x9..=0xd | 0x1c..=0x20 | 0x1680 |
        0x2000..=0x2006 | 0x2008..=0x200a | 0x2028 | 0x2029 | 0x205f | 0x3000)
    })
}

#[derive(Default, Debug)]
pub(crate) struct LedgerRead {
    pub records: Vec<Value>,
    pub corrupt_lines: u64,
    pub unknown_schema_lines: u64,
    pub unknown_type_lines: u64,
    pub torn_tail: bool,
}
impl LedgerRead {
    pub(crate) fn skipped_lines(&self) -> u64 {
        self.corrupt_lines + self.unknown_schema_lines + self.unknown_type_lines
    }
    fn line(&mut self, bytes: &[u8], family: Family) {
        if bytes.len() > MAX_READ_LINE_BYTES {
            self.corrupt_lines += 1;
            return;
        }
        // Java/Node decode malformed UTF-8 with replacement, then parse the JSON.
        let text = String::from_utf8_lossy(bytes);
        if java_blank(&text) {
            return;
        }
        let v: Value = match serde_json::from_str(&text) {
            Ok(v) => v,
            Err(_) => {
                self.corrupt_lines += 1;
                return;
            }
        };
        let Some(schema) = v
            .get("schemaVersion")
            .and_then(Value::as_u64)
            .filter(|v| *v >= 1)
        else {
            self.corrupt_lines += 1;
            return;
        };
        if schema > 1 {
            self.unknown_schema_lines += 1;
            return;
        }
        let Some(token) = v.get("type").and_then(Value::as_str) else {
            self.corrupt_lines += 1;
            return;
        };
        if !family.accepts(token) {
            self.unknown_type_lines += 1;
            return;
        }
        let writer = &v["writer"];
        if v["seq"].as_u64().filter(|v| *v > 0).is_none()
            || writer["role"].as_str().is_none_or(|r| !ROLES.contains(&r))
            || writer["pid"].as_u64().filter(|v| *v > 0).is_none()
            || known_timestamp(&writer["startedAt"]).is_none()
        {
            self.corrupt_lines += 1;
            return;
        }
        self.records.push(v);
    }
}

pub(crate) fn read_ledger(dir: &Path, family: Family) -> io::Result<LedgerRead> {
    let (current, rotated) = family.paths(dir);
    let mut out = LedgerRead::default();
    for file in [rotated, current] {
        let bytes = match fs::read(file) {
            Ok(v) => v,
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        };
        let mut start = 0;
        for (i, byte) in bytes.iter().enumerate() {
            if *byte != b'\n' {
                continue;
            }
            let end = if i > start && bytes[i - 1] == b'\r' {
                i - 1
            } else {
                i
            };
            if end > start {
                out.line(&bytes[start..end], family);
            }
            start = i + 1;
        }
        out.torn_tail |= start < bytes.len();
    }
    Ok(out)
}

pub(crate) fn summarize_ledger(dir: &Path, family: Family) -> io::Result<Value> {
    let read = read_ledger(dir, family)?;
    let schema: Value = serde_json::from_str(include_str!(
        "../../../../governance/exit-ledger.v1.schema.json"
    ))
    .expect("registered schema");
    let details = schema["$defs"]["stopDetailOrNull"]["enum"]
        .as_array()
        .expect("detail vocabulary");
    let records: Vec<Value> = read
        .records
        .iter()
        .map(|v| {
            let mut times = Map::new();
            for field in TIME_FIELDS {
                if let Some(t) = v.get(field) {
                    times.insert(field.into(), normalized_timestamp(t));
                }
            }
            let detail_raw = v["detail"].as_str().map(Value::from).unwrap_or(Value::Null);
            let detail = if detail_raw.is_null() || details.contains(&detail_raw) {
                detail_raw.clone()
            } else {
                json!("unknown")
            };
            json!({"type":v["type"], "seq":v["seq"], "at":normalized_timestamp(&v["at"]),
            "writer":{"role":v["writer"]["role"],"pid":v["writer"]["pid"],
                "startedAt":normalized_timestamp(&v["writer"]["startedAt"])},
            "detail":detail,"detailRaw":detail_raw,"times":times})
        })
        .collect();
    Ok(
        json!({"family":family.name(),"records":records,"corruptLines":read.corrupt_lines,
        "unknownSchemaLines":read.unknown_schema_lines,"unknownTypeLines":read.unknown_type_lines,
        "tornTail":read.torn_tail}),
    )
}

/// Only the family's owner sets rotates=true. The Engine's self-intent writer never rotates.
pub(crate) struct LedgerWriter {
    dir: PathBuf,
    family: Family,
    writer: Value,
    rotates: bool,
    seq: u64,
    healthy: bool,
    pending: [Option<(u64, i64)>; 4],
    session_rotate_bytes: u64,
    hard_cap_bytes: u64,
}
impl LedgerWriter {
    pub(crate) fn new(
        dir: PathBuf,
        family: Family,
        writer: Value,
        rotates: bool,
    ) -> Result<Self, String> {
        if writer["role"].as_str().is_none_or(|v| !ROLES.contains(&v))
            || writer["pid"].as_u64().filter(|v| *v > 0).is_none()
            || known_timestamp(&writer["startedAt"]).is_none()
        {
            return Err("invalid writer identity".into());
        }
        let writer = json!({"role":writer["role"],"pid":writer["pid"],
            "startedAt":normalized_timestamp(&writer["startedAt"])});
        Ok(Self {
            dir,
            family,
            writer,
            rotates,
            seq: 0,
            healthy: true,
            pending: [None; 4],
            session_rotate_bytes: SESSION_ROTATE_BYTES,
            hard_cap_bytes: HARD_CAP_BYTES,
        })
    }
    fn encode(&self, token: &str, fields: &Map<String, Value>, at: i64, seq: u64) -> Vec<u8> {
        let mut node = Map::new();
        node.insert("schemaVersion".into(), json!(1));
        node.insert("type".into(), json!(token));
        node.insert("seq".into(), json!(seq));
        node.insert("at".into(), json!(format_timestamp(at)));
        node.insert("writer".into(), self.writer.clone());
        for (k, v) in fields {
            node.insert(k.clone(), v.clone());
        }
        let mut bytes = serde_json::to_vec(&node).expect("JSON values serialize");
        bytes.push(b'\n');
        bytes
    }
    fn note_gap(&mut self, index: usize, at: i64) {
        let gap = self.pending[index].get_or_insert((0, at));
        gap.0 += 1;
    }
    fn torn(&self) -> bool {
        let (current, _) = self.family.paths(&self.dir);
        let read = || -> io::Result<bool> {
            let mut file = fs::File::open(current)?;
            if file.metadata()?.len() == 0 {
                return Ok(false);
            }
            file.seek(SeekFrom::End(-1))?;
            let mut last = [0];
            file.read_exact(&mut last)?;
            Ok(last[0] != b'\n')
        };
        match read() {
            Ok(v) => v,
            Err(e) => e.kind() != io::ErrorKind::NotFound,
        }
    }
    fn write_line(&self, bytes: &[u8]) -> io::Result<()> {
        fs::create_dir_all(&self.dir)?;
        let (current, _) = self.family.paths(&self.dir);
        // Rust std's Windows open defaults include FILE_SHARE_DELETE; the whole record is one
        // append-mode write, followed by sync_data before the caller takes its action.
        let mut file = OpenOptions::new().create(true).append(true).open(current)?;
        file.write_all(bytes)?;
        file.sync_data()
    }
    fn rotate(&mut self, at: i64) {
        let (current, rotated) = self.family.paths(&self.dir);
        let result = (|| -> io::Result<()> {
            match fs::remove_file(&rotated) {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::NotFound => {}
                Err(e) => return Err(e),
            }
            fs::rename(current, rotated)
        })();
        if result.is_err() {
            self.note_gap(1, at);
        }
    }
    pub(crate) fn start_session(&mut self) {
        self.start_session_at(now_ms());
    }
    fn start_session_at(&mut self, at: i64) {
        if self.torn() && self.write_line(b"\n").is_err() {
            self.healthy = false;
        }
        if !self.rotates {
            return;
        }
        let (current, _) = self.family.paths(&self.dir);
        match fs::metadata(current) {
            Ok(m) if m.len() > self.session_rotate_bytes => self.rotate(at),
            Err(e) if e.kind() != io::ErrorKind::NotFound => self.note_gap(1, at),
            _ => {}
        }
    }
    pub(crate) fn append(
        &mut self,
        token: &str,
        fields: Map<String, Value>,
    ) -> Result<bool, String> {
        self.append_at(token, fields, now_ms())
    }
    fn append_at(
        &mut self,
        token: &str,
        fields: Map<String, Value>,
        at: i64,
    ) -> Result<bool, String> {
        if !self.family.accepts(token) {
            return Err(format!("{token} is not a {} record", self.family.name()));
        }
        if fields.keys().any(|k| ENVELOPE.contains(&k.as_str())) {
            return Err("reserved envelope key".into());
        }
        let gaps = self.pending.iter().flatten().count() as u64;
        let probe = self.encode(token, &fields, at, self.seq + gaps + 2);
        if probe.len() > MAX_RECORD_BYTES {
            return Err("record exceeds 4 KiB".into());
        }
        let (current, _) = self.family.paths(&self.dir);
        if self.rotates
            && fs::metadata(current)
                .is_ok_and(|m| m.len() + 256 * gaps + probe.len() as u64 > self.hard_cap_bytes)
        {
            self.rotate(at);
        }
        let mut lines = Vec::new();
        for (i, reason) in GAP_REASONS.iter().enumerate() {
            if let Some((count, since)) = self.pending[i] {
                self.seq += 1;
                let fields = json!({"reason":reason,"count":count,"since":format_timestamp(since)});
                lines.push((
                    Some(i),
                    self.encode("ledger-gap", fields.as_object().unwrap(), at, self.seq),
                ));
            }
        }
        self.seq += 1;
        lines.push((None, self.encode(token, &fields, at, self.seq)));
        if self.torn() {
            lines[0].1.insert(0, b'\n');
        }
        for (gap, bytes) in lines {
            if self.write_line(&bytes).is_err() {
                self.note_gap(0, at);
                self.healthy = false;
                return Ok(false);
            }
            if let Some(i) = gap {
                self.pending[i] = None;
            }
        }
        self.healthy = true;
        Ok(true)
    }
    pub(crate) fn healthy(&self) -> bool {
        self.healthy
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixtures() -> PathBuf {
        Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../governance/fixtures/exit-ledger")
    }
    fn writer(dir: &Path, rotates: bool) -> LedgerWriter {
        LedgerWriter::new(
            dir.into(),
            Family::Engine,
            json!({"role":"shell","pid":4100,"startedAt":"2026-10-06T08:00:00Z"}),
            rotates,
        )
        .unwrap()
    }
    fn fields(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }
    #[test]
    fn t15_every_shared_fixture_has_the_java_and_node_summary() {
        for dir in fs::read_dir(fixtures().join("cases")).unwrap() {
            let dir = dir.unwrap().path();
            if !dir.is_dir() {
                continue;
            }
            let expected: Value =
                serde_json::from_slice(&fs::read(dir.join("expected-summary.json")).unwrap())
                    .unwrap();
            let actual = json!({"children":summarize_ledger(&dir,Family::Children).unwrap(),
                "engine":summarize_ledger(&dir,Family::Engine).unwrap()});
            assert_eq!(actual, expected, "{}", dir.display());
        }
    }
    #[test]
    fn t15_every_shared_record_writes_the_same_bytes_as_node_and_java() {
        let plans: Vec<Value> =
            serde_json::from_slice(&fs::read(fixtures().join("writer-parity/cases.json")).unwrap())
                .unwrap();
        let tmp = tempfile::tempdir().unwrap();
        let mut actual = Vec::new();
        for (i, plan) in plans.iter().enumerate() {
            let dir = tmp.path().join(i.to_string());
            let family = if plan["family"] == "children" {
                Family::Children
            } else {
                Family::Engine
            };
            let identity = json!({"role":plan["writer"]["role"], "pid":plan["writer"]["pid"],
                "startedAt":format_timestamp(plan["writer"]["startedAt"].as_i64().unwrap())});
            let mut w = LedgerWriter::new(dir.clone(), family, identity, false).unwrap();
            assert!(w
                .append_at(
                    plan["type"].as_str().unwrap(),
                    fields(plan["fields"].clone()),
                    plan["atMs"].as_i64().unwrap()
                )
                .unwrap());
            actual.extend(fs::read(family.paths(&dir).0).unwrap());
        }
        assert_eq!(
            actual,
            fs::read(fixtures().join("writer-parity/expected.jsonl")).unwrap()
        );
    }
    #[test]
    fn t11_unknown_times_and_java_calendar_edges() {
        for v in [
            Value::Null,
            json!(0),
            json!(-11644473600000i64),
            json!("1999-01-01T00:00:00Z"),
            json!("2026-02-30T00:00:00Z"),
            json!("2026-01-01T00:00:00+00:00"),
            json!("2026-01-01T00:00:00z"),
        ] {
            assert_eq!(known_timestamp(&v), None, "{v}");
        }
        for (raw, want) in [
            ("2000-12-31T24:00:00Z", "2001-01-01T00:00:00Z"),
            ("2026-10-06t08:00:00.123456789Z", "2026-10-06T08:00:00.123Z"),
            ("2016-12-31T23:59:60Z", "2016-12-31T23:59:59Z"),
        ] {
            assert_eq!(
                format_timestamp(known_timestamp(&json!(raw)).unwrap()),
                want
            );
        }
    }
    #[test]
    fn t12_rotation_replaces_old_generation_and_respects_hard_cap() {
        let dir = tempfile::tempdir().unwrap();
        let mut w = writer(dir.path(), true);
        let (current, rotated) = Family::Engine.paths(dir.path());
        fs::write(&current, vec![b'\n'; SESSION_ROTATE_BYTES as usize + 1]).unwrap();
        fs::write(&rotated, b"old").unwrap();
        w.start_session();
        assert_eq!(
            fs::metadata(&rotated).unwrap().len(),
            SESSION_ROTATE_BYTES + 1
        );
        assert!(!current.exists());
        fs::write(&current, vec![b'\n'; HARD_CAP_BYTES as usize - 1]).unwrap();
        assert!(w.append("engine-exit", fields(json!({"code":0}))).unwrap());
        assert_eq!(fs::metadata(&rotated).unwrap().len(), HARD_CAP_BYTES - 1);
        assert!(fs::metadata(&current).unwrap().len() < HARD_CAP_BYTES);
        assert_eq!(
            read_ledger(dir.path(), Family::Engine)
                .unwrap()
                .records
                .len(),
            1
        );
    }
    #[test]
    fn write_failure_is_nonfatal_and_gap_precedes_next_success() {
        let dir = tempfile::tempdir().unwrap();
        let blocked = dir.path().join("blocked");
        fs::write(&blocked, b"x").unwrap();
        let mut w = writer(&blocked, true);
        let at = known_timestamp(&json!("2026-10-06T09:00:00Z")).unwrap();
        assert!(!w.append_at("engine-exit", Map::new(), at).unwrap());
        assert!(!w.healthy());
        fs::remove_file(&blocked).unwrap();
        assert!(w.append_at("engine-exit", Map::new(), at).unwrap());
        assert!(w.healthy());
        let read = read_ledger(&blocked, Family::Engine).unwrap();
        assert_eq!(read.records[0]["reason"], "WRITE_FAILED");
        assert_eq!(read.records[0]["seq"], 2);
        assert_eq!(read.records[1]["seq"], 3);
    }
    #[test]
    fn torn_tail_is_closed_and_rotation_failure_records_a_gap() {
        let dir = tempfile::tempdir().unwrap();
        let mut w = writer(dir.path(), true);
        let (current, rotated) = Family::Engine.paths(dir.path());
        fs::write(&current, b"{torn").unwrap();
        w.start_session();
        assert!(fs::read(&current).unwrap().ends_with(b"\n"));
        w.session_rotate_bytes = 1;
        w.hard_cap_bytes = 2;
        fs::create_dir(&rotated).unwrap(); // deterministic rename failure, no platform permission dependency
        assert!(w.append("engine-exit", Map::new()).unwrap());
        let read = read_ledger(dir.path(), Family::Engine);
        assert!(read.is_err(), "a directory is unreadable");
        let mut tally = LedgerRead::default();
        for line in fs::read(current).unwrap().split(|v| *v == b'\n') {
            tally.line(line, Family::Engine);
        }
        assert_eq!(tally.records[0]["reason"], "ROTATE_FAILED");
        assert_eq!(tally.records[0]["count"], 1);
        assert_eq!(tally.records[1]["type"], "engine-exit");
    }
    #[test]
    fn appender_follows_rotations_and_rejects_caller_bugs() {
        let dir = tempfile::tempdir().unwrap();
        let mut rotator = writer(dir.path(), true);
        rotator.session_rotate_bytes = 100;
        rotator.hard_cap_bytes = 700;
        let mut appender = writer(dir.path(), false);
        for i in 0..40 {
            assert!(rotator
                .append("engine-start", fields(json!({"i":i})))
                .unwrap());
            assert!(appender
                .append("engine-self-intent", fields(json!({"i":i})))
                .unwrap());
        }
        let read = read_ledger(dir.path(), Family::Engine).unwrap();
        assert_eq!(read.skipped_lines(), 0);
        assert!(!read.torn_tail);
        assert!(!read.records.is_empty());
        assert!(appender.append("child-exit", Map::new()).is_err());
        assert!(appender
            .append("engine-exit", fields(json!({"seq":9})))
            .is_err());
        assert!(appender
            .append("engine-exit", fields(json!({"label":"x".repeat(4096)})))
            .is_err());
    }
}
