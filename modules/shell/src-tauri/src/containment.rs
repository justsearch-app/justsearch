//! One kill-on-close Windows job per Engine incarnation (Spec F §2).
//!
//! The shell creates a fresh job for every Engine it starts and starts the Engine inside it, so
//! everything the Engine starts (llama-server, extraction JVMs, their console hosts) is in that job
//! too. Two things end the job and everything in it:
//!
//! - the shell observing the Engine's exit, which terminates the job with [`JS_ENGINE_STOP`]
//!   (`engine_host::EngineHost::try_reap`), so no helper outlives its Engine;
//! - the shell process ending for any reason, crash or `TerminateProcess` included, because the
//!   job handle is not inheritable and closing the last handle of a kill-on-close job kills it.
//!
//! The shell itself never joins a job (P8): Explorer windows, opened links and the update installer
//! are started outside every incarnation job and outlive the shell. Only `spawn_contained` assigns a
//! process, and it only assigns the child it just created.
//!
//! Linux binds each Engine to the shell's lifetime with PDEATHSIG, spawning only from one
//! process-lifetime OS thread. This is parent-death binding, not inherited job membership:
//! helpers must bind their own children at their shared spawn seam.

use std::fmt;
use std::process::{Child, Command};
#[cfg(windows)]
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

/// Exit code of every process a supervisor ends by terminating an Engine incarnation's job.
/// Distinct from the Engine's own exit codes and from the Engine's child stop code (0x4A530001).
pub(crate) use crate::exit_ledger::JS_ENGINE_STOP;
/// How long a terminate is given to show an empty job before the caller moves on.
pub(crate) const JOB_TERMINATE_CONFIRM_MS: u64 = 2_000;
/// The restart floor's job-empty wait (Spec F §2.3; used from step 3).
#[allow(dead_code)]
pub(crate) const RESTART_FLOOR_WAIT_MS: u64 = 5_000;
/// Poll interval of [`IncarnationJob::wait_empty`].
pub(crate) const JOB_POLL_MS: u64 = 50;

#[derive(Debug)]
pub(crate) enum ContainmentError {
    /// Not Windows: there is no job to create.
    Unsupported,
    /// `CreateJobObjectW` failed with this Win32 error.
    Create(u32),
    /// `SetInformationJobObject` failed with this Win32 error.
    Configure(u32),
    /// The process could not be started at all.
    Spawn(std::io::Error),
    /// The new process could not join the job; it was terminated and reaped.
    AssignFailed(u32),
    /// No primary thread was resumed; the process was terminated and reaped.
    ResumeFailed(String),
    /// A job query failed with this Win32 error.
    Query(u32),
    /// `TerminateJobObject` failed with this Win32 error.
    Terminate(u32),
}

impl fmt::Display for ContainmentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Unsupported => write!(f, "job objects are not supported on this platform"),
            Self::Create(e) => write!(f, "CreateJobObjectW:{e}"),
            Self::Configure(e) => write!(f, "SetInformationJobObject:{e}"),
            Self::Spawn(e) => write!(f, "{e}"),
            Self::AssignFailed(e) => write!(f, "AssignProcessToJobObject:{e}"),
            Self::ResumeFailed(why) => write!(f, "ResumeThread:{why}"),
            Self::Query(e) => write!(f, "QueryInformationJobObject:{e}"),
            Self::Terminate(e) => write!(f, "TerminateJobObject:{e}"),
        }
    }
}

/// Outcome of waiting for a job to have no live process.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)] // `waited` and `active` are read by the step-3 restart floor and updater wait
pub(crate) enum JobEmpty {
    Empty {
        waited: Duration,
    },
    /// `active` is the last count observed, or `u32::MAX` when the count could not be read at all.
    TimedOut {
        active: u32,
    },
}

/// The job of one Engine incarnation. Not `Clone`: dropping it closes the only handle, which kills
/// whatever is still inside.
pub(crate) struct IncarnationJob {
    #[cfg(windows)]
    handle: windows_sys::Win32::Foundation::HANDLE,
    incarnation: u64,
    #[cfg(windows)]
    resumed_threads: AtomicU32,
}

// A job handle is a kernel handle: every call on it is thread-safe, and the struct never hands the
// raw value out.
#[cfg(windows)]
unsafe impl Send for IncarnationJob {}
#[cfg(windows)]
unsafe impl Sync for IncarnationJob {}

impl fmt::Debug for IncarnationJob {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("IncarnationJob")
            .field("incarnation", &self.incarnation)
            .finish()
    }
}

/// Build the Engine command before callers configure its environment, cwd and stdio.
/// Linux executes the same distribution helper as the Node and Java launchers.
pub(crate) fn engine_command(program: impl AsRef<std::ffi::OsStr>) -> std::io::Result<Command> {
    #[cfg(target_os = "linux")]
    {
        let mut command = Command::new(crate::linux_process::bound_exec()?);
        command
            .args(["exec", &std::process::id().to_string(), "--"])
            .arg(program);
        Ok(command)
    }
    #[cfg(not(target_os = "linux"))]
    {
        Ok(Command::new(program))
    }
}

/// Start `cmd` with no job, the shell's fallback when [`IncarnationJob::create`] fails. It keeps the
/// one creation flag every Engine spawn carries, so the fallback differs only in containment.
pub(crate) fn spawn_uncontained(cmd: &mut Command) -> std::io::Result<Child> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(windows_sys::Win32::System::Threading::CREATE_NO_WINDOW);
    }
    #[cfg(target_os = "linux")]
    return linux_spawn(cmd);
    #[cfg(not(target_os = "linux"))]
    cmd.spawn()
}

/// Linux binds PDEATHSIG to the *spawning thread*. A Tauri async task or the supervisor thread
/// may retire while a child still lives, so all Engine spawns go through one never-retired thread.
#[cfg(target_os = "linux")]
fn linux_spawn(cmd: &mut Command) -> std::io::Result<Child> {
    use std::io;
    use std::sync::{mpsc, OnceLock};

    let helper = crate::linux_process::bound_exec()?.canonicalize()?;
    if std::path::Path::new(cmd.get_program()).canonicalize()? != helper {
        return Err(io::Error::other(
            "Engine command must use the shared Linux child helper",
        ));
    }

    type Request = (Command, mpsc::SyncSender<io::Result<Child>>);
    static SPAWNER: OnceLock<Result<mpsc::Sender<Request>, String>> = OnceLock::new();
    let spawner = SPAWNER.get_or_init(|| {
        let (send, recv) = mpsc::channel::<Request>();
        std::thread::Builder::new()
            .name("engine-spawn".into())
            .spawn(move || {
                for (mut command, reply) in recv {
                    let result = (|| {
                        let helper = crate::linux_process::bound_exec()?.canonicalize()?;
                        let mut child = command.spawn()?;
                        let deadline = std::time::Instant::now() + Duration::from_secs(2);
                        loop {
                            let observed = match child.try_wait() {
                                Ok(status) => status,
                                Err(error) => {
                                    let _ = child.kill();
                                    let _ = child.wait();
                                    return Err(error);
                                }
                            };
                            if let Some(status) = observed {
                                if status.code() == Some(125) {
                                    return Err(io::Error::other(
                                        "Linux child helper refused launch (exit 125)",
                                    ));
                                }
                                return Ok(child);
                            }
                            let image = std::fs::read_link(format!("/proc/{}/exe", child.id()));
                            if image.is_ok_and(|image| image != helper) {
                                return Ok(child);
                            }
                            if std::time::Instant::now() >= deadline {
                                let _ = child.kill();
                                let _ = child.wait();
                                return Err(io::Error::other("Linux child helper did not exec"));
                            }
                            std::thread::sleep(Duration::from_millis(1));
                        }
                    })();
                    if let Err(undelivered) = reply.send(result) {
                        // A cancelled caller cannot surrender responsibility for a spawned child.
                        if let Ok(mut child) = undelivered.0 {
                            let _ = child.kill();
                            let _ = child.wait();
                        }
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(send)
    });
    let spawner = spawner.as_ref().map_err(|e| io::Error::other(e.clone()))?;
    let (reply, recv) = mpsc::sync_channel(1);
    let command = std::mem::replace(cmd, Command::new(""));
    spawner
        .send((command, reply))
        .map_err(|_| io::Error::other("Engine spawn thread ended"))?;
    recv.recv()
        .map_err(|_| io::Error::other("Engine spawn reply lost"))?
}

#[cfg(all(test, target_os = "linux"))]
unsafe fn bind_parent(expected_parent: i32) -> std::io::Result<()> {
    unsafe extern "C" {
        fn prctl(option: i32, ...) -> i32;
        fn getppid() -> i32;
    }
    // Only async-signal-safe syscalls and errno reads are allowed between fork and exec.
    if prctl(
        1,    /* PR_SET_PDEATHSIG */
        9i32, /* SIGKILL */
        0usize, 0usize, 0usize,
    ) != 0
    {
        return Err(std::io::Error::last_os_error());
    }
    if getppid() != expected_parent {
        return Err(std::io::Error::from_raw_os_error(10 /* ECHILD */));
    }
    Ok(())
}

#[cfg(windows)]
mod win {
    pub(super) use windows_sys::Win32::Foundation::{
        CloseHandle, GetLastError, HANDLE, INVALID_HANDLE_VALUE,
    };
    pub(super) use windows_sys::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Thread32First, Thread32Next, TH32CS_SNAPTHREAD, THREADENTRY32,
    };
    pub(super) use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, IsProcessInJob,
        JobObjectBasicAccountingInformation, JobObjectExtendedLimitInformation,
        QueryInformationJobObject, SetInformationJobObject, TerminateJobObject,
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };
    pub(super) use windows_sys::Win32::System::Threading::{
        OpenThread, ResumeThread, SetProcessShutdownParameters, TerminateProcess, CREATE_NO_WINDOW,
        CREATE_SUSPENDED, THREAD_SUSPEND_RESUME,
    };

    pub(super) fn last_error() -> u32 {
        unsafe { GetLastError() }
    }
}

#[cfg(windows)]
impl IncarnationJob {
    /// A new job whose only limit is KILL_ON_JOB_CLOSE: no breakaway, so nothing started inside it
    /// can leave it. The handle is not inheritable, so only this process holds it.
    pub(crate) fn create(incarnation: u64) -> Result<Self, ContainmentError> {
        use win::*;
        let handle = unsafe { CreateJobObjectW(std::ptr::null(), std::ptr::null()) };
        if handle.is_null() {
            return Err(ContainmentError::Create(last_error()));
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { std::mem::zeroed() };
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok = unsafe {
            SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                (&info as *const JOBOBJECT_EXTENDED_LIMIT_INFORMATION).cast(),
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )
        };
        if ok == 0 {
            let error = last_error();
            unsafe { CloseHandle(handle) };
            return Err(ContainmentError::Configure(error));
        }
        Ok(Self {
            handle,
            incarnation,
            resumed_threads: AtomicU32::new(0),
        })
    }

    /// Start `cmd` inside this job with no window in between: the process is created suspended,
    /// joins the job, and only then runs. If the shell dies between create and assign (a window of
    /// microseconds) a suspended process that never ran `main` is left; that residual is accepted
    /// (Spec F Q2).
    pub(crate) fn spawn_contained(&self, cmd: &mut Command) -> Result<Child, ContainmentError> {
        use std::os::windows::io::AsRawHandle;
        use std::os::windows::process::CommandExt;
        use win::*;
        cmd.creation_flags(CREATE_NO_WINDOW | CREATE_SUSPENDED);
        let mut child = cmd.spawn().map_err(ContainmentError::Spawn)?;
        let process = child.as_raw_handle() as HANDLE;
        if unsafe { AssignProcessToJobObject(self.handle, process) } == 0 {
            let error = last_error();
            stop_unstarted(&mut child, process);
            return Err(ContainmentError::AssignFailed(error));
        }
        match resume_all_threads(child.id()) {
            Ok(resumed) => {
                self.resumed_threads.store(resumed, Ordering::Relaxed);
                Ok(child)
            }
            Err(why) => {
                stop_unstarted(&mut child, process);
                Err(ContainmentError::ResumeFailed(why))
            }
        }
    }

    /// Threads resumed by the last [`spawn_contained`](Self::spawn_contained). More than one is not
    /// a failure: security products can inject a thread before the resume. Read by tests now and by
    /// the `engine-start` record once the shell writes one (Spec F step 6).
    #[allow(dead_code)]
    pub(crate) fn resumed_threads(&self) -> u32 {
        self.resumed_threads.load(Ordering::Relaxed)
    }

    /// Live processes in the job (JOBOBJECT_BASIC_ACCOUNTING_INFORMATION.ActiveProcesses).
    pub(crate) fn active_processes(&self) -> Result<u32, ContainmentError> {
        use win::*;
        let mut info: JOBOBJECT_BASIC_ACCOUNTING_INFORMATION = unsafe { std::mem::zeroed() };
        let ok = unsafe {
            QueryInformationJobObject(
                self.handle,
                JobObjectBasicAccountingInformation,
                (&mut info as *mut JOBOBJECT_BASIC_ACCOUNTING_INFORMATION).cast(),
                std::mem::size_of::<JOBOBJECT_BASIC_ACCOUNTING_INFORMATION>() as u32,
                std::ptr::null_mut(),
            )
        };
        if ok == 0 {
            return Err(ContainmentError::Query(last_error()));
        }
        Ok(info.ActiveProcesses)
    }

    /// Terminate every process in the job with `code`.
    pub(crate) fn terminate(&self, code: u32) -> Result<(), ContainmentError> {
        if unsafe { win::TerminateJobObject(self.handle, code) } == 0 {
            return Err(ContainmentError::Terminate(win::last_error()));
        }
        Ok(())
    }

    /// Whether `child` is in this job; `None` when Windows cannot say.
    #[allow(dead_code)]
    pub(crate) fn contains(&self, child: &Child) -> Option<bool> {
        use std::os::windows::io::AsRawHandle;
        let mut result = 0;
        let ok = unsafe {
            win::IsProcessInJob(
                child.as_raw_handle() as win::HANDLE,
                self.handle,
                &mut result,
            )
        };
        (ok != 0).then_some(result != 0)
    }
}

#[cfg(not(windows))]
impl IncarnationJob {
    pub(crate) fn create(_incarnation: u64) -> Result<Self, ContainmentError> {
        Err(ContainmentError::Unsupported)
    }
    pub(crate) fn spawn_contained(&self, _cmd: &mut Command) -> Result<Child, ContainmentError> {
        Err(ContainmentError::Unsupported)
    }
    #[allow(dead_code)]
    pub(crate) fn resumed_threads(&self) -> u32 {
        0
    }
    pub(crate) fn active_processes(&self) -> Result<u32, ContainmentError> {
        Err(ContainmentError::Unsupported)
    }
    pub(crate) fn terminate(&self, _code: u32) -> Result<(), ContainmentError> {
        Err(ContainmentError::Unsupported)
    }
    #[allow(dead_code)]
    pub(crate) fn contains(&self, _child: &Child) -> Option<bool> {
        None
    }
}

impl IncarnationJob {
    #[allow(dead_code)]
    pub(crate) fn incarnation(&self) -> u64 {
        self.incarnation
    }

    /// Wait until the job has no live process, polling every [`JOB_POLL_MS`].
    pub(crate) fn wait_empty(&self, timeout: Duration) -> JobEmpty {
        let started = std::time::Instant::now();
        let mut last = u32::MAX;
        loop {
            if let Ok(active) = self.active_processes() {
                if active == 0 {
                    return JobEmpty::Empty {
                        waited: started.elapsed(),
                    };
                }
                last = active;
            }
            if started.elapsed() >= timeout {
                return JobEmpty::TimedOut { active: last };
            }
            std::thread::sleep(Duration::from_millis(JOB_POLL_MS));
        }
    }

    /// End this incarnation: terminate whatever is left in the job with [`JS_ENGINE_STOP`] and wait
    /// up to [`JOB_TERMINATE_CONFIRM_MS`] for it to be empty. Dropping the job afterwards closes the
    /// handle, which kills anything a failed terminate left behind.
    pub(crate) fn end(self) -> JobEmpty {
        let _ = self.terminate(JS_ENGINE_STOP);
        self.wait_empty(Duration::from_millis(JOB_TERMINATE_CONFIRM_MS))
    }
}

#[cfg(windows)]
impl Drop for IncarnationJob {
    fn drop(&mut self) {
        unsafe { win::CloseHandle(self.handle) };
    }
}

/// A process that never ran user code (still suspended, or just failed to join): terminate it with
/// the supervisor stop code and reap it, so a failed spawn leaves nothing behind.
#[cfg(windows)]
fn stop_unstarted(child: &mut Child, process: win::HANDLE) {
    unsafe { win::TerminateProcess(process, JS_ENGINE_STOP) };
    let _ = child.wait();
}

/// Resume every thread of `pid`. Fails when no thread is found or none was actually suspended
/// (the primary thread was not resumed).
#[cfg(windows)]
fn resume_all_threads(pid: u32) -> Result<u32, String> {
    use win::*;
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
    if snapshot == INVALID_HANDLE_VALUE {
        return Err(format!("CreateToolhelp32Snapshot:{}", last_error()));
    }
    let mut entry: THREADENTRY32 = unsafe { std::mem::zeroed() };
    entry.dwSize = std::mem::size_of::<THREADENTRY32>() as u32;
    let (mut found, mut resumed) = (0u32, 0u32);
    let mut more = unsafe { Thread32First(snapshot, &mut entry) } != 0;
    while more {
        if entry.th32OwnerProcessID == pid {
            found += 1;
            let thread = unsafe { OpenThread(THREAD_SUSPEND_RESUME, 0, entry.th32ThreadID) };
            if !thread.is_null() {
                let previous = unsafe { ResumeThread(thread) };
                if previous != u32::MAX && previous >= 1 {
                    resumed += 1;
                }
                unsafe { CloseHandle(thread) };
            }
        }
        more = unsafe { Thread32Next(snapshot, &mut entry) } != 0;
    }
    unsafe { CloseHandle(snapshot) };
    match (found, resumed) {
        (0, _) => Err("no thread found".into()),
        (_, 0) => Err(format!("none of {found} threads was suspended")),
        (_, resumed) => Ok(resumed),
    }
}

/// Ask Windows to end the shell before other applications at shutdown and logoff (level 0x3FF is
/// the highest an application may take), so the shell's job close ends the Engine and its helpers
/// before Windows reaches them one by one. Returns whether Windows accepted it.
pub(crate) fn set_shell_shutdown_priority() -> bool {
    #[cfg(windows)]
    {
        const SHELL_SHUTDOWN_LEVEL: u32 = 0x3FF;
        unsafe { win::SetProcessShutdownParameters(SHELL_SHUTDOWN_LEVEL, 0) != 0 }
    }
    #[cfg(not(windows))]
    {
        false
    }
}

#[cfg(test)]
mod tests {
    #[cfg(not(windows))]
    use super::*;

    #[cfg(not(windows))]
    #[test]
    fn off_windows_there_is_no_job_and_the_shell_spawns_plainly() {
        assert!(matches!(
            IncarnationJob::create(1),
            Err(ContainmentError::Unsupported)
        ));
        let mut child = spawn_uncontained(
            engine_command("true")
                .unwrap()
                .stdout(std::process::Stdio::null()),
        )
        .unwrap();
        assert!(child.wait().unwrap().success());
    }

    #[cfg(windows)]
    mod windows {
        use super::super::*;
        use std::process::Stdio;
        use std::time::Instant;

        fn quiet(program: &str, args: &[&str]) -> Command {
            let mut command = Command::new(program);
            command
                .args(args)
                .stdout(Stdio::null())
                .stderr(Stdio::null());
            command
        }

        fn sleeper() -> Command {
            quiet("ping.exe", &["-n", "30", "127.0.0.1"])
        }

        fn alive(pid: u32) -> bool {
            use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
            use windows_sys::Win32::System::Threading::{
                GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
            };
            let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
            if handle.is_null() {
                return false;
            }
            let mut code = 0u32;
            let ok = unsafe { GetExitCodeProcess(handle, &mut code) };
            unsafe { CloseHandle(handle) };
            ok != 0 && code == STILL_ACTIVE as u32
        }

        /// The suspended spawn is resumed: the child runs its program to the end.
        #[test]
        fn contained_spawn_runs_the_program_inside_the_job() {
            let job = IncarnationJob::create(1).unwrap();
            let mut child = job
                .spawn_contained(&mut quiet("cmd.exe", &["/c", "exit 7"]))
                .unwrap();
            assert!(job.resumed_threads() >= 1);
            let deadline = Instant::now() + Duration::from_secs(10);
            let status = loop {
                if let Some(status) = child.try_wait().unwrap() {
                    break status;
                }
                assert!(
                    Instant::now() < deadline,
                    "the contained child never ran: still suspended?"
                );
                std::thread::sleep(Duration::from_millis(20));
            };
            assert_eq!(status.code(), Some(7));
        }

        /// S14 (shell side): terminating the job ends the Engine and what it started, with the
        /// supervisor's own code, and the job reads empty.
        #[test]
        fn terminate_ends_the_engine_and_its_helpers_with_the_engine_stop_code() {
            let job = IncarnationJob::create(2).unwrap();
            // cmd starts a helper of its own and waits on it: an Engine with a child.
            let mut engine = job
                .spawn_contained(&mut quiet("cmd.exe", &["/c", "ping -n 30 127.0.0.1 >NUL"]))
                .unwrap();
            assert_eq!(job.contains(&engine), Some(true));
            let deadline = Instant::now() + Duration::from_secs(10);
            while job.active_processes().unwrap() < 2 {
                assert!(Instant::now() < deadline, "the helper never started");
                std::thread::sleep(Duration::from_millis(20));
            }
            job.terminate(JS_ENGINE_STOP).unwrap();
            assert_eq!(engine.wait().unwrap().code(), Some(JS_ENGINE_STOP as i32));
            assert!(matches!(
                job.wait_empty(Duration::from_millis(JOB_TERMINATE_CONFIRM_MS)),
                JobEmpty::Empty { .. }
            ));
        }

        /// S13 (shell side): when the job's last handle closes, as it does when the shell process
        /// dies, everything in the job dies with it.
        #[test]
        fn closing_the_job_kills_what_is_left_in_it() {
            let job = IncarnationJob::create(3).unwrap();
            let mut child = job.spawn_contained(&mut sleeper()).unwrap();
            let pid = child.id();
            drop(job);
            let deadline = Instant::now() + Duration::from_secs(2);
            while alive(pid) {
                assert!(Instant::now() < deadline, "the child outlived its job");
                std::thread::sleep(Duration::from_millis(20));
            }
            let _ = child.wait();
        }

        /// `end` is what the shell does on every observed Engine exit: a helper left behind by an
        /// Engine that already exited is terminated and the job confirmed empty.
        #[test]
        fn ending_an_incarnation_leaves_no_helper() {
            let job = IncarnationJob::create(4).unwrap();
            let mut engine = job
                .spawn_contained(&mut quiet(
                    "cmd.exe",
                    &["/c", "start /b ping -n 30 127.0.0.1 >NUL"],
                ))
                .unwrap();
            // The "Engine" exits at once and leaves its helper running in the job.
            engine.wait().unwrap();
            assert!(
                job.active_processes().unwrap() >= 1,
                "the helper outlived its parent"
            );
            assert!(matches!(job.end(), JobEmpty::Empty { .. }));
        }

        /// A spawn that cannot start reports the start error and leaves nothing behind.
        #[test]
        fn failed_spawn_reports_the_start_error() {
            let job = IncarnationJob::create(5).unwrap();
            let error = job
                .spawn_contained(&mut Command::new(
                    "a-command-that-does-not-exist-justsearch",
                ))
                .unwrap_err();
            assert!(matches!(error, ContainmentError::Spawn(_)), "{error}");
            assert_eq!(job.active_processes().unwrap(), 0);
        }

        /// P8-U1: the shell never joins an incarnation job. At run time the shell process is in no
        /// job it created; in source, nothing but the just-spawned child is ever assigned.
        #[test]
        fn shell_never_joins_an_incarnation_job() {
            use windows_sys::Win32::System::JobObjects::IsProcessInJob;
            use windows_sys::Win32::System::Threading::GetCurrentProcess;
            let job = IncarnationJob::create(6).unwrap();
            let mut child = job.spawn_contained(&mut sleeper()).unwrap();
            let mut in_job = 1;
            let ok = unsafe { IsProcessInJob(GetCurrentProcess(), job.handle, &mut in_job) };
            assert_ne!(ok, 0);
            assert_eq!(in_job, 0, "the shell process joined the incarnation job");
            assert_eq!(job.contains(&child), Some(true));
            let _ = job.end();
            let _ = child.wait();

            let source = include_str!("containment.rs");
            let production = source.split("#[cfg(test)]").next().unwrap();
            let assigns: Vec<&str> = production
                .lines()
                .filter(|l| {
                    l.contains("AssignProcessToJobObject(") && !l.trim_start().starts_with("//")
                })
                .collect();
            assert_eq!(
                assigns,
                ["        if unsafe { AssignProcessToJobObject(self.handle, process) } == 0 {"]
            );
            assert!(
                !production.contains("GetCurrentProcess"),
                "production code names the shell process"
            );
            let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
            for entry in std::fs::read_dir(&dir).unwrap() {
                let path = entry.unwrap().path();
                if path.extension().is_some_and(|e| e == "rs") && !path.ends_with("containment.rs")
                {
                    let text = std::fs::read_to_string(&path).unwrap();
                    assert!(
                        !text.contains("AssignProcessToJobObject"),
                        "{} assigns a job",
                        path.display()
                    );
                }
            }
        }
    }
}

/// The fallback keeps the distinctive stop code even when Windows refused a shell job.
pub(crate) fn terminate_uncontained(child: &mut Child) -> std::io::Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::io::AsRawHandle;
        if unsafe { win::TerminateProcess(child.as_raw_handle() as win::HANDLE, JS_ENGINE_STOP) }
            == 0
        {
            return Err(std::io::Error::last_os_error());
        }
        Ok(())
    }
    #[cfg(not(windows))]
    {
        child.kill()
    }
}

#[cfg(all(test, target_os = "linux"))]
mod linux_tests {
    use super::*;

    #[test]
    fn missing_shared_helper_refuses_workload() {
        const FIXTURE: &str = "JUSTSEARCH_MISSING_BOUND_EXEC_TEST";
        if let Some(path) = std::env::var_os(FIXTURE) {
            let marker = std::path::PathBuf::from(path);
            crate::linux_process::set_bundle_dir(&marker.with_extension("missing-bundle"));
            let mut command = engine_command("touch").unwrap();
            command.arg(&marker);
            let result = spawn_uncontained(&mut command);
            if let Ok(mut child) = result {
                let _ = child.wait();
                panic!("workload launched without the shared helper");
            }
            assert!(!marker.exists());
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("must-not-execute");
        let result = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "containment::linux_tests::missing_shared_helper_refuses_workload",
            ])
            .env(FIXTURE, &marker)
            .status()
            .unwrap();
        assert!(result.success(), "missing helper must refuse the launch");
        assert!(!marker.exists());
    }

    #[test]
    fn shared_helper_preserves_command_and_refuses_parent_mismatch() {
        let dir = tempfile::tempdir().unwrap();
        let argument = "literal ; $() 検索";
        let mut command = engine_command("sh").unwrap();
        command
            .args([
                "-c",
                "printf '%s\\n' \"$PWD\" \"$JS_BOUND_FIXTURE\" \"$1\"; printf error >&2",
                "fixture",
                argument,
            ])
            .current_dir(dir.path())
            .env_clear()
            .env("PATH", "/usr/bin:/bin")
            .env("JS_BOUND_FIXTURE", "owned environment")
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        let output = spawn_uncontained(&mut command)
            .unwrap()
            .wait_with_output()
            .unwrap();
        assert!(output.status.success());
        assert_eq!(
            String::from_utf8(output.stdout).unwrap(),
            format!("{}\nowned environment\n{argument}\n", dir.path().display())
        );
        assert_eq!(output.stderr, b"error");

        let marker = dir.path().join("must-not-execute");
        let status = Command::new(crate::linux_process::bound_exec().unwrap())
            .args(["exec", "1", "--", "touch"])
            .arg(&marker)
            .status()
            .unwrap();
        assert_eq!(status.code(), Some(125));
        assert!(!marker.exists());
        let mut bypass = Command::new("/bin/touch");
        bypass.arg(&marker);
        assert!(spawn_uncontained(&mut bypass).unwrap_err().to_string()
            .contains("shared Linux child helper"));
        assert!(!marker.exists());
    }

    #[test]
    fn child_survives_caller_thread_exit_but_not_owner_death() {
        const FIXTURE: &str = "JUSTSEARCH_LINUX_CONTAINMENT_TEST_PID";
        if let Some(path) = std::env::var_os(FIXTURE) {
            let mut child = std::thread::spawn(|| {
                let mut command = engine_command("sh").unwrap();
                command.args(["-c", "trap '' TERM; exec sleep 30"]);
                spawn_uncontained(&mut command).unwrap()
            })
            .join()
            .unwrap();
            std::fs::write(path, child.id().to_string()).unwrap();
            // Bounded fixture lifetime even if its test driver fails.
            std::thread::sleep(Duration::from_secs(15));
            let _ = child.kill();
            let _ = child.wait();
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let pid_file = dir.path().join("child.pid");
        let mut owner = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "containment::linux_tests::child_survives_caller_thread_exit_but_not_owner_death",
                "--nocapture",
            ])
            .env(FIXTURE, &pid_file)
            .spawn()
            .unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(5);
        while !pid_file.is_file() && std::time::Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        let pid = std::fs::read_to_string(&pid_file)
            .ok()
            .and_then(|s| s.parse::<u32>().ok());
        // Give the short-lived caller time to exit and deliver a wrongly thread-bound signal.
        std::thread::sleep(Duration::from_millis(150));
        let alive_before = pid.and_then(|pid| crate::linux_process::capture(pid).ok());
        owner.kill().unwrap();
        owner.wait().unwrap();
        let (identity, _) =
            alive_before.expect("child must survive retirement of its caller thread");
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        while crate::linux_process::check(identity.pid, Some(&identity)) == "ALIVE_MATCH"
            && std::time::Instant::now() < deadline
        {
            std::thread::sleep(Duration::from_millis(5));
        }
        let observed = crate::linux_process::check(identity.pid, Some(&identity));
        // Keep a failed before-state probe from leaving its bounded sleep fixture running.
        if observed != "GONE" {
            let cleanup_deadline = std::time::Instant::now() + Duration::from_secs(30);
            while crate::linux_process::check(identity.pid, Some(&identity)) == "ALIVE_MATCH"
                && std::time::Instant::now() < cleanup_deadline
            {
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        assert_eq!(observed, "GONE", "child survived owner SIGKILL");
    }

    #[test]
    fn changed_parent_refuses_exec_before_user_code() {
        use std::os::unix::process::CommandExt;
        let dir = tempfile::tempdir().unwrap();
        let marker = dir.path().join("executed");
        let mut command = Command::new("touch");
        command.arg(&marker);
        unsafe {
            command.pre_exec(|| bind_parent(-1));
        }
        assert!(command.spawn().is_err());
        assert!(!marker.exists());
    }
}
