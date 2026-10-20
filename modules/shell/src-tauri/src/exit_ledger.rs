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

/// Archive .1 is newest; .2 and later preserve the previous .1 in allocation order.
fn ledger_files(dir: &Path, family: Family) -> io::Result<Vec<PathBuf>> {
    let (current, rotated) = family.paths(dir);
    let stem = current.file_stem().unwrap().to_string_lossy();
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e),
    };
    let mut archives = Vec::new();
    for entry in entries {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if let Some(n) = name
            .strip_prefix(&format!("{stem}."))
            .and_then(|s| s.strip_suffix(".jsonl"))
            .and_then(|s| s.parse::<u64>().ok())
        {
            if n > 1 {
                archives.push((n, entry.path()));
            }
        }
    }
    archives.sort_by_key(|(n, _)| *n);
    let mut files: Vec<_> = archives.into_iter().map(|(_, p)| p).collect();
    for file in [rotated, current.with_extension("lost.jsonl"), current] {
        if file.exists() {
            files.push(file);
        }
    }
    Ok(files)
}

fn evidence_scope(rows: &[Value]) -> Value {
    let mut sessions = std::collections::BTreeSet::new();
    let mut complete = !rows.is_empty();
    let mut range_complete = !rows.is_empty();
    let mut from = i64::MAX;
    let mut through = i64::MIN;
    for row in rows {
        let loss = row["reason"] == "EVIDENCE_LOST";
        let session = row["incarnation"]["session"].as_str();
        if let Some(session) = session {
            sessions.insert(session.to_owned());
        }
        if let Some(affected) = row["affectedSessions"].as_array() {
            sessions.extend(affected.iter().filter_map(Value::as_str).map(String::from));
        }
        complete &= session.is_some() || (loss && row["scopeComplete"] == true);
        let a = known_timestamp(&row[if loss { "evidenceFrom" } else { "at" }]);
        let b = known_timestamp(&row[if loss { "evidenceThrough" } else { "at" }]);
        if let (Some(a), Some(b)) = (a, b) {
            from = from.min(a);
            through = through.max(b);
        } else {
            range_complete = false;
        }
    }
    if sessions.len() > 128 || serde_json::to_vec(&sessions).unwrap().len() > 2048 {
        sessions.clear();
        complete = false;
    }
    let mut scope = json!({"affectedSessions": sessions, "scopeComplete": complete,
        "evidenceRangeComplete": range_complete});
    if range_complete {
        scope["evidenceFrom"] = json!(format_timestamp(from));
        scope["evidenceThrough"] = json!(format_timestamp(through));
    }
    scope
}

/// Every non-blank line with its parse, read leniently as the reader, Java and Node do:
/// malformed UTF-8 (a write cut off inside a non-ASCII path) is decoded with replacement, so
/// one bad line costs that line, never the whole file. `None` marks a line that is not a JSON
/// object.
fn decoded_lines(bytes: &[u8]) -> Vec<(String, Option<Value>)> {
    bytes
        .split(|b| *b == b'\n')
        .map(|line| String::from_utf8_lossy(line).into_owned())
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            let row = serde_json::from_str::<Value>(&line)
                .ok()
                .filter(Value::is_object);
            (line, row)
        })
        .collect()
}

fn journal_lines(file: &Path) -> io::Result<Vec<(String, Option<Value>)>> {
    match fs::read(file) {
        Ok(bytes) => Ok(decoded_lines(&bytes)),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(Vec::new()),
        Err(e) => Err(e),
    }
}

/// Every non-blank line of an evicted file, with the number of corrupt lines among them. An
/// unreadable line stays as an empty row, so the loss scope it contributes to is explicitly
/// incomplete rather than silently narrower.
fn dropped_rows(file: &Path) -> io::Result<(Vec<Value>, u64)> {
    let mut corrupt = 0;
    let rows = decoded_lines(&fs::read(file)?)
        .into_iter()
        .map(|(_, row)| {
            row.unwrap_or_else(|| {
                corrupt += 1;
                json!({})
            })
        })
        .collect();
    Ok((rows, corrupt))
}

fn retain_and_rename(
    current: &Path,
    rotated: &Path,
    family: Family,
    writer: &Value,
    bound: u64,
) -> io::Result<()> {
    let dir = current.parent().unwrap();
    let before = ledger_files(dir, family)?;
    if rotated.exists() {
        if !rotated.is_file() {
            return Err(io::Error::other("archive is not a file"));
        }
        let next = before
            .iter()
            .filter_map(|p| {
                p.file_stem()?
                    .to_str()?
                    .rsplit('.')
                    .next()?
                    .parse::<u64>()
                    .ok()
            })
            .max()
            .unwrap_or(1)
            + 1;
        let stem = current.file_stem().unwrap().to_string_lossy();
        fs::rename(rotated, dir.join(format!("{stem}.{next}.jsonl")))?;
    }
    // A failed second move preserves the old generation at its new archive name.
    fs::rename(current, rotated)?;
    let loss_file = current.with_extension("lost.jsonl");
    let candidates: Vec<_> = ledger_files(dir, family)?
        .into_iter()
        .filter(|p| p != &loss_file)
        .collect();
    let mut size = 0;
    for file in &candidates {
        size += fs::metadata(file)?.len();
    }
    for file in candidates {
        if size <= bound || file == rotated {
            break;
        }
        let bytes = fs::metadata(&file)?.len();
        let (rows, corrupt_lines) = dropped_rows(&file)?;
        // Journal lines keep their text and order; one that does not parse is counted and kept
        // verbatim, never dropped: losing a record is worse than keeping a bad line.
        let journal = journal_lines(&loss_file)?;
        let unreadable: Vec<String> = journal
            .iter()
            .filter(|(_, row)| row.is_none())
            .map(|(line, _)| line.clone())
            .collect();
        let mut losses: Vec<Value> = journal.iter().filter_map(|(_, row)| row.clone()).collect();
        let mut added = Vec::new();
        for row in &rows {
            if row["pid"].as_u64().is_none()
                || (row["creationFileTime"].as_str().is_none()
                    && row["processIdentity"]["kind"] != "linux-proc-v1")
            {
                continue;
            }
            let mut loss = json!({"schemaVersion":1,"type":"ledger-gap","seq":row["seq"],"at":row["at"],
                "writer":row["writer"],"reason":"EVIDENCE_LOST","pid":row["pid"],"creationFileTime":row["creationFileTime"],
                "owner":row["owner"],"incarnation":row["incarnation"],"count":1,"since":row["at"],
                "droppedFile":file.file_name().unwrap().to_string_lossy(),"droppedBytes":bytes});
            if let Some(identity) = row.get("processIdentity") {
                loss["processIdentity"] = identity.clone();
            }
            loss.as_object_mut().unwrap().extend(
                evidence_scope(std::slice::from_ref(row))
                    .as_object()
                    .unwrap()
                    .clone(),
            );
            added.push(loss);
        }
        let now = format_timestamp(now_ms());
        let mut summary = json!({"schemaVersion":1,"type":"ledger-gap","seq":1,"at":now,"since":now,
            "writer":writer,"reason":"EVIDENCE_LOST","count":1,"droppedBytes":bytes,
            "droppedFile":file.file_name().unwrap().to_string_lossy(),"corruptLines":corrupt_lines + unreadable.len() as u64,
            "detail":"archive dropped at retention bound"});
        summary
            .as_object_mut()
            .unwrap()
            .extend(evidence_scope(&rows).as_object().unwrap().clone());
        added.push(summary.clone());
        losses.extend(added.iter().cloned());
        let mut lines: Vec<String> = journal
            .into_iter()
            .map(|(line, _)| line)
            .chain(added.iter().map(Value::to_string))
            .collect();
        if lines.iter().map(|line| line.len() + 1).sum::<usize>() > 512 * 1024 {
            let births: Vec<u64> = losses
                .iter()
                .flat_map(|r| {
                    [
                        r.get("birthFrom").or(r.get("creationFileTime")),
                        r.get("birthThrough").or(r.get("creationFileTime")),
                    ]
                })
                .flatten()
                .filter_map(|v| v.as_str()?.parse().ok())
                .collect();
            // The merged range replaces the newest eviction's range; an unknown merged range
            // keeps no bound, and `at` stays the newest eviction time, its upper bound.
            let fields = summary.as_object_mut().unwrap();
            fields.remove("evidenceFrom");
            fields.remove("evidenceThrough");
            fields.extend(evidence_scope(&losses).as_object().unwrap().clone());
            summary["count"] = json!(losses
                .iter()
                .map(|r| r["count"].as_u64().unwrap_or(1))
                .sum::<u64>());
            summary["detail"] =
                json!("loss journal compacted; identity detail and membership unknown");
            if let (Some(low), Some(high)) = (births.iter().min(), births.iter().max()) {
                summary["birthFrom"] = json!(low.to_string());
                summary["birthThrough"] = json!(high.to_string());
            }
            lines = std::iter::once(summary.to_string())
                .chain(unreadable)
                .collect();
        }
        let pending = loss_file.with_extension("jsonl.pending");
        let mut output = fs::File::create(&pending)?;
        for line in lines {
            output.write_all(line.as_bytes())?;
            output.write_all(b"\n")?;
        }
        output.sync_data()?;
        drop(output);
        fs::rename(pending, &loss_file)?; // durable loss receipt precedes eviction
        fs::remove_file(file)?;
        size -= bytes;
    }
    Ok(())
}

/// Create with private permissions, then repair existing directories without following links.
/// Permission failures are reported once per path; unsafe paths and creation failures are refused.
pub(crate) fn ensure_owner_only_directory(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => {
            #[cfg(windows)]
            {
                use std::os::windows::fs::MetadataExt;
                if metadata.file_attributes() & 0x400 != 0 {
                    warn_owner_only_directory(path, "refusing directory reparse point");
                    return Err(io::Error::other("refusing directory reparse point"));
                }
            }
            if metadata.file_type().is_symlink() || !metadata.is_dir() {
                warn_owner_only_directory(path, "refusing linked or non-directory runtime path");
                return Err(io::Error::other(
                    "refusing linked or non-directory runtime path",
                ));
            }
        }
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            #[cfg(unix)]
            let creation = {
                use std::os::unix::fs::DirBuilderExt;
                fs::DirBuilder::new().mode(0o700).create(path)
            };
            #[cfg(windows)]
            let creation = private_windows_directory(path, true);
            match creation {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
                Err(e) => {
                    fs::create_dir(path)?;
                    warn_owner_only_directory(path, &e.to_string());
                }
            }
            return ensure_owner_only_directory(path);
        }
        Err(e) => return Err(e),
    }
    #[cfg(unix)]
    let result = {
        use std::os::unix::ffi::OsStrExt;
        let directory = std::ffi::CString::new(path.as_os_str().as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "directory contains NUL"))?;
        // The mutation itself must not follow a link swapped in after symlink_metadata.
        let changed = unsafe {
            libc::fchmodat(
                libc::AT_FDCWD,
                directory.as_ptr(),
                0o700,
                libc::AT_SYMLINK_NOFOLLOW,
            )
        };
        if changed == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    };
    #[cfg(windows)]
    let result = private_windows_directory(path, false);
    if let Err(error) = result {
        warn_owner_only_directory(path, &error.to_string());
    }
    Ok(())
}

fn warn_owner_only_directory(path: &Path, error: &str) {
    static WARNED: std::sync::OnceLock<std::sync::Mutex<std::collections::HashSet<PathBuf>>> =
        std::sync::OnceLock::new();
    if WARNED
        .get_or_init(Default::default)
        .lock()
        .unwrap()
        .insert(path.to_path_buf())
    {
        eprintln!(
            "Could not protect owner-only directory {}: {error}",
            path.display()
        );
    }
}

#[cfg(any(windows, test))]
const OWNER_ONLY_DIRECTORY_SDDL: &str = "D:P(A;OICI;FA;;;OW)(A;OICI;FA;;;SY)";

// DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION.
#[cfg(any(windows, test))]
const OWNER_ONLY_DIRECTORY_SECURITY_INFORMATION: u32 = 0x00000004 | 0x80000000;

#[cfg(windows)]
fn private_windows_directory(path: &Path, create: bool) -> io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::{
        Foundation::LocalFree,
        Security::{
            Authorization::ConvertStringSecurityDescriptorToSecurityDescriptorW, SetFileSecurityW,
            SECURITY_ATTRIBUTES,
        },
        Storage::FileSystem::CreateDirectoryW,
    };
    let path: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    // OW is the object's owner; protect the DACL against later changes to the data parent's ACL.
    let sddl: Vec<u16> = OWNER_ONLY_DIRECTORY_SDDL
        .encode_utf16()
        .chain(Some(0))
        .collect();
    unsafe {
        let mut descriptor = std::ptr::null_mut();
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            1,
            &mut descriptor,
            std::ptr::null_mut(),
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
        let attributes = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: descriptor,
            bInheritHandle: 0,
        };
        let ok = if create {
            CreateDirectoryW(path.as_ptr(), &attributes)
        } else {
            SetFileSecurityW(
                path.as_ptr(),
                OWNER_ONLY_DIRECTORY_SECURITY_INFORMATION,
                descriptor,
            )
        };
        let result = if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        };
        LocalFree(descriptor);
        result
    }
}

fn ensure_ledger_directory(dir: &Path) -> io::Result<()> {
    if let Some(parent) = dir.parent() {
        if dir.file_name().is_some_and(|name| name == "exit-ledger")
            && parent.file_name().is_some_and(|name| name == "runtime")
        {
            if let Some(data) = parent.parent() {
                fs::create_dir_all(data)?;
            }
            ensure_owner_only_directory(parent)?;
        } else {
            fs::create_dir_all(parent)?;
        }
    }
    ensure_owner_only_directory(dir)
}

pub(crate) fn read_ledger(dir: &Path, family: Family) -> io::Result<LedgerRead> {
    let mut out = LedgerRead::default();
    for file in ledger_files(dir, family)? {
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
        // Opening a writer must remain nonfatal; failed writes become ledger gaps.
        let _ = ensure_ledger_directory(&dir);
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
        ensure_ledger_directory(&self.dir)?;
        let (current, _) = self.family.paths(&self.dir);
        // Rust std's Windows open defaults include FILE_SHARE_DELETE; the whole record is one
        // append-mode write, followed by sync_data before the caller takes its action.
        let mut file = OpenOptions::new().create(true).append(true).open(current)?;
        file.write_all(bytes)?;
        file.sync_data()
    }
    fn rotate(&mut self, at: i64) {
        let (current, rotated) = self.family.paths(&self.dir);
        let result = retain_and_rename(
            &current,
            &rotated,
            self.family,
            &self.writer,
            128 * 1024 * 1024,
        );
        if result.is_err() {
            self.note_gap(1, at);
        }
    }
    pub(crate) fn start_session(&mut self) {
        self.start_session_at(now_ms());
    }
    fn start_session_at(&mut self, at: i64) {
        if ensure_ledger_directory(&self.dir).is_err() {
            self.healthy = false;
            return;
        }
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
    #[test]
    fn windows_directory_descriptor_is_protected_and_only_owner_and_system_inherit_full_control() {
        let parts: Vec<_> = OWNER_ONLY_DIRECTORY_SDDL.split('(').collect();
        assert_eq!(
            parts[0], "D:P",
            "disable parent DACL inheritance at creation"
        );
        assert_eq!(parts.len(), 3, "exactly two explicit access entries");
        assert_eq!(&parts[1..], &["A;OICI;FA;;;OW)", "A;OICI;FA;;;SY)"]);
        assert_eq!(OWNER_ONLY_DIRECTORY_SECURITY_INFORMATION, 0x80000004);
    }
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
    #[cfg(unix)]
    #[test]
    fn shell_first_ledger_and_recreation_are_private() {
        use std::os::unix::fs::PermissionsExt;
        let data = tempfile::tempdir().unwrap();
        fs::set_permissions(data.path(), fs::Permissions::from_mode(0o775)).unwrap();
        let parent_mode = fs::metadata(data.path()).unwrap().permissions().mode() & 0o777;
        let engine_data = data.path().join("new-data");
        let dir = ledger_directory(&engine_data);
        let mut ledger = writer(&dir, true);
        ledger.write_line(b"{}\n").unwrap();
        assert_eq!(
            fs::metadata(engine_data.join("runtime"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(&dir).unwrap().permissions().mode() & 0o777,
            0o700
        );
        fs::set_permissions(
            engine_data.join("runtime"),
            fs::Permissions::from_mode(0o775),
        )
        .unwrap();
        ledger.start_session();
        assert_eq!(
            fs::metadata(engine_data.join("runtime"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
        fs::remove_dir_all(engine_data.join("runtime")).unwrap();
        ledger.write_line(b"{}\n").unwrap();
        assert_eq!(
            fs::metadata(engine_data.join("runtime"))
                .unwrap()
                .permissions()
                .mode()
                & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(data.path()).unwrap().permissions().mode() & 0o777,
            parent_mode
        );
    }

    #[cfg(unix)]
    #[test]
    fn shell_refuses_linked_runtime_without_touching_target() {
        use std::os::unix::fs::{symlink, PermissionsExt};
        let data = tempfile::tempdir().unwrap();
        let outside = tempfile::tempdir().unwrap();
        fs::set_permissions(outside.path(), fs::Permissions::from_mode(0o775)).unwrap();
        symlink(outside.path(), data.path().join("runtime")).unwrap();
        let ledger = writer(&ledger_directory(data.path()), true);
        assert!(ledger.write_line(b"{}\n").is_err());
        assert_eq!(
            fs::metadata(outside.path()).unwrap().permissions().mode() & 0o777,
            0o775
        );
        assert_eq!(fs::read_dir(outside.path()).unwrap().count(), 0);
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
    fn all_five_incarnations_survive_per_file_rotation() {
        let dir = tempfile::tempdir().unwrap();
        let mut w = writer(dir.path(), true);
        w.session_rotate_bytes = 400;
        w.hard_cap_bytes = 900;
        for n in 1..=5 {
            for i in 0..8 {
                assert!(w
                    .append(
                        "engine-start",
                        fields(json!({"incarnation":{"session":"retention","n":n},
                    "pid":n * 100 + i,"creationFileTime":"134358000000000000"}))
                    )
                    .unwrap());
            }
        }
        assert_eq!(
            read_ledger(dir.path(), Family::Engine)
                .unwrap()
                .records
                .iter()
                .filter(|r| r["type"] == "engine-start")
                .count(),
            40,
            "per-file rotation must retain prior generations below the retention bound"
        );
    }
    #[test]
    fn retention_keeps_linux_process_identity_in_loss_receipts() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("engine.v1.2.jsonl");
        let identity = json!({"kind":"linux-proc-v1", "pid":7,
            "bootId":"4a4a6c93-537e-491c-8a81-a967ce9b4f80", "startTicks":"123456"});
        let row = json!({"schemaVersion":1,"type":"engine-start","at":"2026-10-01T10:00:00Z",
            "incarnation":{"session":"old","n":1},"pid":7,"processIdentity":identity});
        fs::write(&old, format!("{row}\n")).unwrap();
        fs::OpenOptions::new().write(true).open(&old).unwrap()
            .set_len(128 * 1024 * 1024 + 1).unwrap();
        let mut w = writer(dir.path(), true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        let losses: Vec<Value> = fs::read_to_string(dir.path().join("engine.v1.lost.jsonl"))
            .unwrap().lines().map(|line| serde_json::from_str(line).unwrap()).collect();
        let loss = losses.iter().find(|r| r["pid"] == 7).expect("retained Linux identity");
        assert_eq!(loss["processIdentity"], identity);
        assert_eq!(loss["affectedSessions"], json!(["old"]));
    }

    #[test]
    fn eviction_at_the_retention_bound_records_scoped_loss_first() {
        // Through the production rotation and its real 128 MiB bound: an old archive above the
        // bound is evicted only after its loss receipts are durable. Its readable identity keeps
        // its session and evidence time; an unreadable line leaves the summary's scope unknown.
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("engine.v1.2.jsonl");
        let row = r#"{"schemaVersion":1,"type":"engine-start","at":"2026-10-01T10:00:00Z","incarnation":{"session":"old","n":1},"pid":7,"creationFileTime":"134353224000000000"}"#;
        fs::write(&old, format!("{row}\n{{torn\n")).unwrap();
        fs::OpenOptions::new()
            .write(true)
            .open(&old)
            .unwrap()
            .set_len(128 * 1024 * 1024 + 1)
            .unwrap();
        let mut w = writer(dir.path(), true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        let losses: Vec<Value> = fs::read_to_string(dir.path().join("engine.v1.lost.jsonl"))
            .unwrap_or_default()
            .lines()
            .filter_map(|line| serde_json::from_str(line).ok())
            .collect();
        let identity: Vec<_> = losses.iter().filter(|r| r["pid"] == 7).collect();
        assert_eq!(
            identity.len(),
            1,
            "the evicted identity has a loss receipt: {losses:?}"
        );
        assert_eq!(identity[0]["reason"], "EVIDENCE_LOST");
        assert_eq!(identity[0]["affectedSessions"], json!(["old"]));
        assert_eq!(identity[0]["evidenceThrough"], "2026-10-01T10:00:00Z");
        let summary: Vec<_> = losses.iter().filter(|r| r["pid"].is_null()).collect();
        assert_eq!(summary.len(), 1, "{losses:?}");
        assert_eq!(summary[0]["droppedFile"], "engine.v1.2.jsonl");
        assert!(
            !old.exists(),
            "the archive above the retention bound is evicted"
        );
        assert_eq!(
            summary[0]["scopeComplete"], false,
            "an unreadable evicted line has unknown scope"
        );
        assert_eq!(
            read_ledger(dir.path(), Family::Engine)
                .unwrap()
                .records
                .iter()
                .filter(|r| r["pid"] == 42)
                .count(),
            1,
            "the retained generation stays readable"
        );
    }
    #[test]
    fn eviction_proceeds_past_a_line_cut_inside_a_multibyte_character() {
        // A write cut off inside a non-ASCII path leaves bytes that are not UTF-8. That line is
        // corrupt; the archive is still evicted at the real 128 MiB bound and the loss recorded.
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("engine.v1.2.jsonl");
        let row = r#"{"schemaVersion":1,"type":"engine-start","at":"2026-10-01T10:00:00Z","incarnation":{"session":"old","n":1},"pid":7,"creationFileTime":"134353224000000000"}"#;
        let mut bytes = format!("{row}\n").into_bytes();
        bytes.extend_from_slice(b"{\"schemaVersion\":1,\"executable\":\"C:/Users/Z\xC3");
        fs::write(&old, bytes).unwrap();
        fs::OpenOptions::new()
            .write(true)
            .open(&old)
            .unwrap()
            .set_len(128 * 1024 * 1024 + 1)
            .unwrap();
        let mut w = writer(dir.path(), true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        assert_eq!(
            fs::metadata(&old).ok().map(|m| m.len()),
            None,
            "the archive above the retention bound is evicted"
        );
        let losses: Vec<Value> = fs::read_to_string(dir.path().join("engine.v1.lost.jsonl"))
            .unwrap_or_default()
            .lines()
            .filter_map(|line| serde_json::from_str(line).ok())
            .collect();
        let summary: Vec<_> = losses.iter().filter(|r| r["pid"].is_null()).collect();
        assert_eq!(summary.len(), 1, "{losses:?}");
        assert_eq!(
            summary[0]["corruptLines"], 1,
            "the cut-off line is counted as corrupt"
        );
        assert_eq!(summary[0]["scopeComplete"], false);
        assert_eq!(
            losses.iter().filter(|r| r["pid"] == 7).count(),
            1,
            "the readable row keeps its loss receipt"
        );
    }
    /// Evicts one readable archive above the real 128 MiB bound through a writer rotation, with
    /// `journal` as the loss journal beforehand; returns the journal lines afterwards.
    fn evict_with_journal(dir: &Path, journal: &str) -> Vec<String> {
        let row = r#"{"schemaVersion":1,"type":"engine-start","at":"2026-10-01T10:00:00Z","incarnation":{"session":"old","n":1},"pid":7,"creationFileTime":"134353224000000000"}"#;
        fs::write(dir.join("engine.v1.lost.jsonl"), journal).unwrap();
        let old = dir.join("engine.v1.2.jsonl");
        let head = format!("{row}\n");
        let pad = 128 * 1024 * 1024 + 1 - head.len();
        fs::write(&old, head + &"\n".repeat(pad)).unwrap();
        let mut w = writer(dir, true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        assert_eq!(
            fs::metadata(&old).ok().map(|m| m.len()),
            None,
            "the archive above the retention bound is evicted"
        );
        fs::read_to_string(dir.join("engine.v1.lost.jsonl"))
            .unwrap()
            .lines()
            .map(str::to_owned)
            .collect()
    }
    const EARLIER_LOSS: &str = r#"{"schemaVersion":1,"type":"ledger-gap","seq":1,"at":"2026-10-07T10:00:00Z","writer":{"role":"engine","pid":90,"startedAt":"2026-10-07T10:00:00Z"},"reason":"EVIDENCE_LOST","count":1,"since":"2026-10-07T10:00:00Z","droppedFile":"engine.v1.3.jsonl","droppedBytes":10}"#;
    const CUT_OFF: &str = r#"{"schemaVersion":1,"type":"ledger-gap","detail":"cut"#;
    #[test]
    fn journal_rewrite_keeps_an_unreadable_line_verbatim() {
        let dir = tempfile::tempdir().unwrap();
        let lines = evict_with_journal(dir.path(), &format!("{EARLIER_LOSS}\n{CUT_OFF}\n"));
        assert_eq!(
            lines.iter().filter(|line| *line == CUT_OFF).count(),
            1,
            "the unreadable journal line is kept as it was: {lines:?}"
        );
        assert_eq!(lines[0], EARLIER_LOSS, "earlier loss records stay first");
        let summary: Vec<Value> = lines
            .iter()
            .filter_map(|line| serde_json::from_str::<Value>(line).ok())
            .filter(|r| r["detail"] == "archive dropped at retention bound")
            .collect();
        assert_eq!(summary.len(), 1, "{lines:?}");
        assert_eq!(
            summary[0]["corruptLines"], 1,
            "the unreadable journal line is counted as corrupt"
        );
    }
    #[test]
    fn journal_compaction_keeps_an_unreadable_line_verbatim() {
        let dir = tempfile::tempdir().unwrap();
        let journal = format!("{EARLIER_LOSS}\n").repeat(3000) + CUT_OFF + "\n";
        let lines = evict_with_journal(dir.path(), &journal);
        assert_eq!(
            lines.iter().filter(|line| *line == CUT_OFF).count(),
            1,
            "compaction keeps the unreadable journal line"
        );
        assert_eq!(
            lines.iter().filter(|line| *line != CUT_OFF).count(),
            1,
            "the readable journal compacted to one row"
        );
    }
    #[test]
    fn compacted_unknown_range_drops_stale_bounds_and_keeps_eviction_time() {
        // The journal holds summaries written before scope fields existed, so the compacted
        // range is unknown: no bound from the newest eviction may survive in it, and its `at`
        // is the newest eviction time, the upper bound a reader may use instead.
        let dir = tempfile::tempdir().unwrap();
        let row = r#"{"schemaVersion":1,"type":"engine-start","at":"2026-10-01T10:00:00Z","incarnation":{"session":"old","n":1},"pid":7,"creationFileTime":"134353224000000000"}"#;
        let unscoped = r#"{"schemaVersion":1,"type":"ledger-gap","seq":1,"at":"2026-09-30T10:00:00Z","since":"2026-09-30T10:00:00Z","reason":"EVIDENCE_LOST","count":1,"droppedFile":"engine.v1.9.jsonl","droppedBytes":512,"detail":"archive dropped at retention bound"}"#;
        fs::write(
            dir.path().join("engine.v1.lost.jsonl"),
            format!("{unscoped}\n").repeat(3000),
        )
        .unwrap();
        // A readable archive above the real 128 MiB bound: its own range is known, so only the
        // merge with the journal makes the compacted range unknown.
        let old = dir.path().join("engine.v1.2.jsonl");
        let head = format!("{row}\n");
        let pad = 128 * 1024 * 1024 + 1 - head.len();
        fs::write(&old, head + &"\n".repeat(pad)).unwrap();
        let started = now_ms();
        let mut w = writer(dir.path(), true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        let losses: Vec<Value> = fs::read_to_string(dir.path().join("engine.v1.lost.jsonl"))
            .unwrap()
            .lines()
            .filter_map(|line| serde_json::from_str(line).ok())
            .collect();
        assert!(
            !old.exists(),
            "the archive above the retention bound is evicted"
        );
        assert_eq!(
            losses.len(),
            1,
            "the journal compacted to one row: {}",
            losses.len()
        );
        let compacted = &losses[0];
        assert_eq!(compacted["evidenceRangeComplete"], false, "{compacted}");
        assert_eq!(
            compacted.get("evidenceFrom"),
            None,
            "an unknown merged range keeps no stale lower bound: {compacted}"
        );
        assert_eq!(
            compacted.get("evidenceThrough"),
            None,
            "an unknown merged range keeps no stale upper bound: {compacted}"
        );
        assert!(
            known_timestamp(&compacted["at"]).is_some_and(|at| at >= started - 1000),
            "the compacted row keeps the newest eviction time: {compacted}"
        );
    }
    #[test]
    fn failed_rotation_preserves_previous_archive() {
        let dir = tempfile::tempdir().unwrap();
        let mut w = writer(dir.path(), true);
        assert!(w.append("engine-start", fields(json!({"pid":42}))).unwrap());
        w.rotate(now_ms());
        // A second move fails because current is absent, after the previous archive was moved.
        w.rotate(now_ms());
        assert_eq!(
            read_ledger(dir.path(), Family::Engine)
                .unwrap()
                .records
                .len(),
            1,
            "failed rotation cannot destroy the retained previous archive"
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
