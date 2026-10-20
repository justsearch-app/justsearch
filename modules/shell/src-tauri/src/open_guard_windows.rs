// Test-only GUI observation/ownership, shared by historical and current dispatch checks.
use std::path::Path;
use std::process::{Command, Output};
use std::sync::{Mutex, MutexGuard};

static GUI_TEST: Mutex<()> = Mutex::new(());

pub struct WindowsProbe {
    _serial: MutexGuard<'static, ()>,
    scratch: tempfile::TempDir,
    root: std::path::PathBuf,
    documents: Vec<std::path::PathBuf>,
}

impl WindowsProbe {
    pub fn new(root: &Path, documents: &[&Path]) -> Self {
        let probe = Self {
            _serial: GUI_TEST
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()),
            scratch: tempfile::tempdir().unwrap(),
            root: root.to_path_buf(),
            documents: documents.iter().map(|path| path.to_path_buf()).collect(),
        };
        std::fs::write(
            probe.scratch.path().join("probe.ps1"),
            include_str!("open_guard_windows.ps1"),
        )
        .unwrap();
        let output = probe.command("snapshot").output().unwrap();
        assert_eq!(
            output.status.code(),
            Some(0),
            "GUI baseline unavailable: {output:?}"
        );
        probe
    }

    fn command(&self, mode: &str) -> Command {
        let mut command = Command::new("powershell.exe");
        command
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
            ])
            .arg(self.scratch.path().join("probe.ps1"))
            .arg(mode)
            .env(
                "OPEN_GUARD_SNAPSHOT",
                self.scratch.path().join("snapshot.json"),
            )
            .env("OPEN_GUARD_ROOT", &self.root)
            .env(
                "OPEN_GUARD_DOCUMENTS",
                self.documents
                    .iter()
                    .map(|path| path.to_str().unwrap())
                    .collect::<Vec<_>>()
                    .join("|"),
            );
        command
    }

    pub fn assert_folder(&self, expected: &Path, item: Option<&Path>) {
        let output = self
            .command("observe")
            .env("OPEN_GUARD_EXPECTED", expected)
            .env("OPEN_GUARD_SELECTED", item.unwrap_or(Path::new("")))
            .output()
            .unwrap();
        assert_eq!(
            output.status.code(),
            Some(0),
            "actual Explorer parent folder (item state is diagnostic): {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
    }

    fn cleanup(&self) -> Output {
        self.command("cleanup").output().expect("run GUI cleanup")
    }
}

impl Drop for WindowsProbe {
    fn drop(&mut self) {
        // No dispatch happened when establishing the baseline failed.
        if !self.scratch.path().join("snapshot.json").exists() {
            return;
        }
        let output = self.cleanup();
        eprintln!(
            "GUI cleanup: {} {}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        if std::thread::panicking() {
            if !output.status.success() {
                eprintln!("GUI cleanup FAILED: {:?}", output.status);
            }
        } else {
            assert_eq!(output.status.code(), Some(0), "GUI cleanup failed");
        }
    }
}
