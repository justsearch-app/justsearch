//! Quit callers create a ticket and post it. Only the Engine actor performs shutdown.
use crate::engine_host::EngineHost;
use crate::supervisor::{self, Outcome};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, OnceLock,
};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug)]
pub(crate) enum QuitCause {
    TrayQuit,
    WindowExit,
    Drop,
    SessionEnd { close_app: bool },
    Upgrade,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    #[test]
    fn cancelled_session_end_still_requests_shell_exit_once() {
        let quit = QuitCoordinator::new(Arc::new(EngineHost::default()));
        let exits = Arc::new(AtomicUsize::new(0));
        let count = exits.clone();
        quit.set_session_exit(move || {
            count.fetch_add(1, Ordering::SeqCst);
        });
        quit.begin(QuitCause::SessionEnd { close_app: false });
        // FALSE cancels the Windows session end, not the ticket QUERY already began.
        quit.exit_app_if_session_quit();
        quit.begin(QuitCause::WindowExit);
        assert_eq!(
            exits.load(Ordering::SeqCst),
            1,
            "cancelled session end must close the shell once"
        );
    }

    #[test]
    fn early_session_query_is_closed_when_tauri_is_ready() {
        let quit = QuitCoordinator::new(Arc::new(EngineHost::default()));
        quit.begin(QuitCause::SessionEnd { close_app: false });
        let exits = Arc::new(AtomicUsize::new(0));
        let count = exits.clone();
        quit.set_session_exit(move || {
            count.fetch_add(1, Ordering::SeqCst);
        });
        assert_eq!(exits.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn other_stop_causes_do_not_request_session_exit() {
        for cause in [
            QuitCause::TrayQuit,
            QuitCause::Upgrade,
            QuitCause::WindowExit,
        ] {
            let quit = QuitCoordinator::new(Arc::new(EngineHost::default()));
            quit.set_session_exit(|| panic!("non-session stop requested session exit"));
            quit.begin(cause);
            quit.exit_app_if_session_quit();
        }
    }
}
pub(crate) struct QuitTicket {
    pub started: Instant,
    pub deadline: Instant,
    pub cause: QuitCause,
    active_deadline: u64,
}
pub(crate) struct QuitCoordinator {
    host: Arc<EngineHost>,
    ticket: OnceLock<QuitTicket>,
    outcome: OnceLock<QuitOutcome>,
    session_exit: OnceLock<Box<dyn Fn() + Send + Sync>>,
    exit_requested: AtomicBool,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum QuitOutcome {
    Cooperative { code: i32 },
    Forced { job_empty: bool },
    NoEngine,
    Unconfirmed,
}
impl QuitCoordinator {
    pub(crate) fn new(host: Arc<EngineHost>) -> Self {
        Self {
            host,
            ticket: OnceLock::new(),
            outcome: OnceLock::new(),
            session_exit: OnceLock::new(),
            exit_requested: AtomicBool::new(false),
        }
    }
    pub(crate) fn begin(&self, cause: QuitCause) -> &QuitTicket {
        let ticket = self.ticket.get_or_init(|| {
            let policy = supervisor::load_policy();
            let started = Instant::now();
            let budget = Duration::from_millis(policy.quit_deadline_ms);
            let active_deadline = self.host.now_ms() + policy.quit_deadline_ms;
            self.host.begin_close();
            if self.host.supervisor_running() {
                let detail = match cause {
                    QuitCause::SessionEnd { .. } => Some("session_end".into()),
                    _ => None,
                };
                let _ = self.host.post_stop("quit", detail, budget);
            } else if self.host.child_pid().is_none() {
                self.host.publish_terminal(&Outcome::NoEngine);
            }
            QuitTicket {
                started,
                deadline: started + budget,
                cause,
                active_deadline,
            }
        });
        self.exit_app_if_session_quit();
        ticket
    }
    pub(crate) fn set_session_exit(&self, exit: impl Fn() + Send + Sync + 'static) {
        let _ = self.session_exit.set(Box::new(exit));
        // QUERY can arrive before Tauri setup installs the app handle.
        self.exit_app_if_session_quit();
    }
    pub(crate) fn exit_app_if_session_quit(&self) {
        if self
            .ticket
            .get()
            .is_some_and(|ticket| matches!(ticket.cause, QuitCause::SessionEnd { .. }))
        {
            if let Some(exit) = self.session_exit.get() {
                if !self.exit_requested.swap(true, Ordering::SeqCst) {
                    exit();
                }
            }
        }
    }
    pub(crate) fn wait(&self, ticket: &QuitTicket) -> QuitOutcome {
        self.outcome
            .get_or_init(|| {
                match self.host.wait_terminal(
                    ticket.active_deadline + crate::containment::JOB_TERMINATE_CONFIRM_MS,
                ) {
                    Some(Outcome::Stopped { exit_code, .. })
                        if exit_code == crate::containment::JS_ENGINE_STOP as i32 =>
                    {
                        QuitOutcome::Forced {
                            job_empty: self.host.predecessor_job_empty(),
                        }
                    }
                    Some(Outcome::Stopped { exit_code, .. }) => {
                        QuitOutcome::Cooperative { code: exit_code }
                    }
                    Some(Outcome::NoEngine | Outcome::Exhausted { .. }) => QuitOutcome::NoEngine,
                    None => QuitOutcome::Unconfirmed,
                }
            })
            .clone()
    }
}
