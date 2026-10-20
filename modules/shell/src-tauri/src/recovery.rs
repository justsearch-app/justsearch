//! Crash artifacts and recovery data remain available while the Engine is down.
use crate::{exit_ledger, supervisor};
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read};
use std::path::Path;

pub(crate) fn jvm_options(data_dir: &Path) -> Result<Vec<String>, String> {
    let dir = data_dir.join("crashes");
    std::fs::create_dir_all(&dir).map_err(|error| {
        let reason = format!("Cannot create crash folder {}: {error}", dir.display());
        eprintln!("{reason}");
        reason
    })?;
    // Preserve the first OOM and the latest one, while bounding repeated OOM disk use.
    let prune = || -> std::io::Result<()> {
        let mut dumps = Vec::new();
        for entry in std::fs::read_dir(&dir)? {
            let entry = entry?;
            if entry.file_type()?.is_file()
                && entry.path().extension().is_some_and(|ext| ext == "hprof")
            {
                dumps.push((entry.metadata()?.modified()?, entry.path()));
            }
        }
        dumps.sort();
        for (_, path) in dumps.iter().skip(1).take(dumps.len().saturating_sub(2)) {
            std::fs::remove_file(path)?;
        }
        Ok(())
    };
    prune().map_err(|error| format!("Cannot retain heap dumps in {}: {error}", dir.display()))?;
    eprintln!("Engine crash evidence: {}", dir.display());
    // A PID can be reused after a relaunch. Give each boot its own artifact names.
    let boot = uuid::Uuid::new_v4();
    Ok(vec![
        format!(
            "-XX:ErrorFile={}",
            dir.join(format!("hs_err_pid%p-{boot}.log")).display()
        ),
        format!(
            "-XX:HeapDumpPath={}",
            dir.join(format!("heap-{boot}.hprof")).display()
        ),
    ])
}

pub(crate) fn recent_exits(data_dir: &Path, now: u64) -> Option<Value> {
    let read = exit_ledger::read_ledger(
        &exit_ledger::ledger_directory(data_dir),
        exit_ledger::Family::Engine,
    )
    .ok()?;
    let mut codes = std::collections::BTreeMap::<String, u64>::new();
    let mut first = None::<u64>;
    let mut last = None::<u64>;
    let mut count = 0u64;
    for row in read.records {
        let Some(at) =
            exit_ledger::known_timestamp(&row["at"]).and_then(|at| u64::try_from(at).ok())
        else {
            continue;
        };
        if row["type"] != "engine-exit"
            || at > now
            || now - at > 86_400_000
            || (row["exitClass"] == "REQUESTED"
                && row["contract"]["counted"] != true
                && row["contract"]["exitClass"] != "TRANSIENT")
        {
            continue;
        }
        count += 1;
        let code = row["code"]
            .as_i64()
            .map(|v| v.to_string())
            .or_else(|| row["signalName"].as_str().map(str::to_owned))
            .unwrap_or("unknown".into());
        *codes.entry(code).or_default() += 1;
        first = Some(first.map_or(at, |old| old.min(at)));
        last = Some(last.map_or(at, |old| old.max(at)));
    }
    Some(json!({"count": count, "codes": codes,
        "firstAt": first.map(supervisor::epoch_ms_to_iso), "lastAt": last.map(supervisor::epoch_ms_to_iso),
        "windowMs": 86_400_000, "incomplete": read.corrupt_lines > 0 || read.torn_tail}))
}

pub(crate) fn latest_crash(data_dir: &Path) -> Option<Value> {
    let dir = data_dir.join("crashes");
    let mut files: Vec<_> = std::fs::read_dir(dir)
        .ok()?
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
        .filter(|entry| {
            let name = entry.file_name();
            let name = name.to_string_lossy();
            name.starts_with("hs_err_pid") && name.ends_with(".log")
        })
        .filter_map(|entry| Some((entry.metadata().ok()?.modified().ok()?, entry.path())))
        .collect();
    files.sort_by(|a, b| b.cmp(a));
    for (modified, path) in files {
        let file = match std::fs::File::open(&path) {
            Ok(file) => file,
            Err(_) => continue,
        };
        let mut exception = None;
        let mut frame = None;
        let mut next_frame = false;
        for line in BufReader::new(file.take(64 * 1024))
            .lines()
            .take(64)
            .map_while(Result::ok)
        {
            let line = line.trim();
            if line.starts_with("# Problematic frame:") {
                next_frame = true;
                continue;
            }
            if next_frame {
                frame = Some(safe_crash_line(line));
                next_frame = false;
            }
            if line.starts_with("#  SIG")
                || line.starts_with("#  EXCEPTION_")
                || line.starts_with("#  Internal Error")
            {
                exception = Some(safe_crash_line(line));
            }
        }
        if exception.is_none() && frame.is_none() {
            continue;
        }
        let at = modified
            .duration_since(std::time::UNIX_EPOCH)
            .ok()?
            .as_millis() as u64;
        return Some(
            json!({"name":path.file_name()?.to_str()?, "at":supervisor::epoch_ms_to_iso(at), "exception":exception, "problematicFrame":frame}),
        );
    }
    None
}

fn safe_crash_line(line: &str) -> String {
    let mut text = line.trim_start_matches('#').trim().to_string();
    if text.starts_with("Internal Error") {
        return "Internal Error [path]".into();
    }
    // Native frame libraries can be absolute paths (including spaces on Windows).
    if let (Some(start), Some(end)) = (text.find('['), text.rfind(']')) {
        if end > start {
            let library = text[start + 1..end]
                .rsplit(['/', '\\'])
                .next()
                .unwrap_or("")
                .to_string();
            text.replace_range(start + 1..end, &library);
        }
    }
    text.split_whitespace()
        .map(|token| {
            if token.contains('/') || token.contains('\\') || token.contains(":\\") {
                "[path]"
            } else {
                token
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .filter(|ch| !ch.is_control())
        .take(256)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_crash_folder_with_spaces_keeps_artifacts_across_boots() {
        let root = tempfile::tempdir().unwrap();
        let profile = root.path().join("Name With Spaces");
        let first = jvm_options(&profile).unwrap();
        assert!(profile.join("crashes").is_dir());
        let report = profile.join("crashes/hs_err_pid42-first.log");
        std::fs::write(&report, "first crash evidence").unwrap();
        let second = jvm_options(&profile).unwrap();
        let relaunch = jvm_options(&profile).unwrap();
        assert_ne!(first, second);
        assert_ne!(first, relaunch);
        assert!(first
            .iter()
            .all(|option| option.contains("Name With Spaces")));
        assert!(first[0].contains("hs_err_pid%p-"));
        assert!(first[1].ends_with(".hprof"));
        assert_eq!(
            std::fs::read_to_string(report).unwrap(),
            "first crash evidence"
        );
    }

    #[test]
    fn spawn_preparation_keeps_only_the_first_and_latest_heap_dumps() {
        let root = tempfile::tempdir().unwrap();
        let dir = root.path().join("crashes");
        std::fs::create_dir_all(&dir).unwrap();
        for n in 1..=5 {
            let path = dir.join(format!("heap-{n}.hprof"));
            let file = std::fs::File::create(path).unwrap();
            file.set_times(
                std::fs::FileTimes::new()
                    .set_modified(std::time::UNIX_EPOCH + std::time::Duration::from_secs(n)),
            )
            .unwrap();
        }
        std::fs::write(dir.join("hs_err_pid1.log"), "first native crash").unwrap();
        std::fs::create_dir(dir.join("not-a-dump.hprof")).unwrap();
        jvm_options(root.path()).unwrap();
        let mut dumps: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .map(Result::unwrap)
            .filter(|e| {
                e.file_type().unwrap().is_file()
                    && e.path().extension().is_some_and(|ext| ext == "hprof")
            })
            .map(|e| e.file_name())
            .collect();
        dumps.sort();
        assert_eq!(dumps, vec!["heap-1.hprof", "heap-5.hprof"]);
        assert!(dir.join("hs_err_pid1.log").is_file());
        assert!(dir.join("not-a-dump.hprof").is_dir());
        jvm_options(root.path()).unwrap();
        assert!(dir.join("heap-1.hprof").is_file());
        assert!(dir.join("heap-5.hprof").is_file());
    }

    #[test]
    fn unavailable_crash_folder_reports_the_real_obstacle() {
        let root = tempfile::tempdir().unwrap();
        std::fs::write(root.path().join("crashes"), "occupied").unwrap();
        let error = jvm_options(root.path()).unwrap_err();
        assert!(error.contains("Cannot create crash folder"));
        assert!(error.contains(&root.path().display().to_string()));
    }

    #[test]
    fn fatal_summary_is_bounded_and_redacts_windows_paths_with_spaces() {
        let root = tempfile::tempdir().unwrap();
        let dir = root.path().join("crashes");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("hs_err_pid42.log"), "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005)\n# Problematic frame:\n# C [C:\\Users\\Private Middle Name\\onnxruntime.dll+0x123]\n").unwrap();
        let crash = latest_crash(root.path()).unwrap();
        assert_eq!(crash["name"], "hs_err_pid42.log");
        assert!(crash["problematicFrame"]
            .as_str()
            .unwrap()
            .contains("onnxruntime.dll"));
        assert!(!crash.to_string().contains("Private"));
        assert!(!crash.to_string().contains("Middle"));
        std::fs::write(
            dir.join("hs_err_pid42.log"),
            "# filler\n".repeat(64) + "#  SIGSEGV (0xb)\n",
        )
        .unwrap();
        assert!(latest_crash(root.path()).is_none());
    }

    #[test]
    fn the_daily_count_survives_budget_reset_and_shell_relaunch() {
        let root = tempfile::tempdir().unwrap();
        let directory = exit_ledger::ledger_directory(root.path());
        let make_writer = || {
            exit_ledger::LedgerWriter::new(
                directory.clone(),
                exit_ledger::Family::Engine,
                json!({"role":"shell","pid":42,"startedAt":"2026-10-20T00:00:00Z"}),
                true,
            )
            .unwrap()
        };
        let mut writer = make_writer();
        for n in 1..=4 {
            writer.append("engine-exit", json!({"incarnation":{"session":"test","n":n},"code":3,"exitClass":"UNEXPECTED","contract":{"counted":true}}).as_object().unwrap().clone()).unwrap();
        }
        writer.append("engine-exit", json!({"incarnation":{"session":"test","n":5},"code":0,"exitClass":"REQUESTED","contract":{"exitClass":"TRANSIENT","counted":false}}).as_object().unwrap().clone()).unwrap();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_millis() as u64;
        let mut supervisor = supervisor::Supervisor::new(supervisor::load_policy());
        supervisor.restart_count = 3;
        supervisor.observe(supervisor::Event::StabilityElapsed, None);
        assert_eq!(supervisor.restart_count, 0);
        assert_eq!(recent_exits(root.path(), now).unwrap()["count"], 5);
        drop(writer);
        make_writer().start_session();
        assert_eq!(recent_exits(root.path(), now).unwrap()["count"], 5);
    }
}
