//! Quit callers create a ticket and post it. Only the Engine actor performs shutdown.
use crate::engine_host::EngineHost;
use crate::supervisor::{self, Outcome};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, Debug)]
pub(crate) enum QuitCause {
    TrayQuit,
    WindowExit,
    Drop,
    SessionEnd { close_app: bool },
    Upgrade,
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
}
#[derive(Debug, PartialEq, Eq)]
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
        }
    }
    pub(crate) fn begin(&self, cause: QuitCause) -> &QuitTicket {
        self.ticket.get_or_init(|| {
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
        })
    }
    pub(crate) fn wait(&self, ticket: &QuitTicket) -> QuitOutcome {
        match self
            .host
            .wait_terminal(ticket.active_deadline + crate::containment::JOB_TERMINATE_CONFIRM_MS)
        {
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
    }
}
