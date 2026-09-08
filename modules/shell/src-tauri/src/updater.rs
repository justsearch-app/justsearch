use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use base64::Engine;
use ring::signature::{UnparsedPublicKey, ED25519};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use tauri::{AppHandle, Manager};
use tauri_plugin_updater::UpdaterExt;

use crate::{restart_headless_backend, BackendState};

const INTENT_SCHEMA_VERSION: u32 = 1;
const EVIDENCE_SCHEMA_VERSION: u32 = 1;
const DESCRIPTOR_SCHEMA_VERSION: u32 = 1;
const HEAD_TIMEOUT: Duration = Duration::from_secs(15);
const BACKEND_EXIT_TIMEOUT: Duration = Duration::from_secs(30);
const BACKEND_READY_TIMEOUT: Duration = Duration::from_secs(60);
/// `on_chunk` fires per network chunk (potentially thousands/sec on a fast link) — emitting a
/// status update per chunk would flood the `app_update_status` polling channel for no perceptible
/// UI benefit. 300ms is human-meaningful (well under the ~1s cadence the AI-install poller already
/// uses for the analogous model-download progress, `aiInstallPoll.ts`) while cutting emit volume by
/// orders of magnitude. `on_download_finish` always emits once more regardless of this throttle, so
/// the terminal state is never truncated by a skipped tick.
const DOWNLOAD_PROGRESS_THROTTLE: Duration = Duration::from_millis(300);
const LOCAL_STORE_REGISTER: &str =
    include_str!("../../../../governance/store-recoverability.v1.json");

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseDescriptor {
    schema_version: u32,
    sequence: u64,
    version: String,
    channel: String,
    target: String,
    metadata_key_id: String,
    metadata_root_policy: String,
    artifact: ReleaseArtifact,
    compatibility: Vec<ReleaseStoreCompatibility>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseArtifact {
    url: String,
    sha256: String,
    size: u64,
    signature: String,
    public_key: String,
}

/// Target-side compatibility declaration copied from the release's reviewed store register.
/// `role` and `reconciliationStrategy` prevent a format-number-only check from silently changing
/// an AUTHORED store into a disposable/derived store (or vice versa).
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReleaseStoreCompatibility {
    owner_id: String,
    owner: String,
    role: String,
    format_version: u32,
    readable_source_versions: Vec<u32>,
    reconciliation_strategy: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalStoreRegister {
    durable_stores: Vec<LocalDurableStore>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalDurableStore {
    id: String,
    owner: String,
    recoverability: String,
    current_version: u32,
    reconciliation: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum UpgradePhase {
    Prepared,
    HeadStopped,
    EngineUnrecoverable,
    InstallLaunching,
    InstallLaunched,
    Reconciling,
    Committed,
    Cancelled,
    RepairRequired,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ShutdownReceipt {
    schema_version: u32,
    preparation_id: String,
    shutdown_nonce: String,
    shutdown_accepted: bool,
    admission_frozen: bool,
    active_lease_count: u64,
    issued_at_epoch_ms: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HeadShutdownReceipt {
    schema_version: u32,
    preparation_id: String,
    shutdown_nonce: String,
    head_pid: u64,
    clean: bool,
    worker_outcome: String,
    errors: Vec<String>,
    completed_at: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StagedArtifact {
    path: String,
    sha256: String,
    size: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallerLaunchWitness {
    schema_version: u32,
    attempt_id: String,
    process_id: u32,
    launched_at_epoch_ms: u128,
    staged_path: String,
    staged_sha256: String,
    staged_size: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct OwnerExpectation {
    owner_id: String,
    format_version: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpgradeIntent {
    schema_version: u32,
    phase: UpgradePhase,
    attempt_id: String,
    #[serde(default)]
    stop_evidence: StopEvidence,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    preparation_id: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    shutdown_nonce: String,
    shutdown_receipt: Option<ShutdownReceipt>,
    head_shutdown_receipt: Option<HeadShutdownReceipt>,
    /// PID of the Head child observed at commit-shutdown time, persisted independently of the
    /// receipt so that restart reconciliation has a witness the receipt cannot supply for itself.
    /// Absent before HEAD_STOPPED.
    head_pid: Option<u64>,
    staged_artifact: StagedArtifact,
    launch_witness: Option<InstallerLaunchWitness>,
    owner_expectations: Vec<OwnerExpectation>,
    source_version: String,
    target_version: String,
    release_sequence: u64,
    updated_at_epoch_ms: u128,
    error: Option<String>,
}

/// The stop evidence survives phase changes. Legacy intents default to their prepared path.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "SCREAMING_SNAKE_CASE", deny_unknown_fields)]
enum StopEvidence {
    Prepared {},
    EngineUnrecoverable {
        #[serde(rename = "attemptId")]
        attempt_id: String,
        #[serde(rename = "enginePid")]
        engine_pid: Option<u32>,
        #[serde(rename = "confirmedGoneAtEpochMs")]
        confirmed_gone_at_epoch_ms: u64,
    },
}

impl Default for StopEvidence {
    fn default() -> Self { Self::Prepared {} }
}

impl StopEvidence {
    fn kind(&self) -> &'static str {
        match self {
            Self::Prepared {} => "PREPARED",
            Self::EngineUnrecoverable { .. } => "ENGINE_UNRECOVERABLE",
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SequenceState {
    schema_version: u32,
    highest_accepted_sequence: u64,
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppUpdateStatus {
    state: String,
    current_version: String,
    available_version: Option<String>,
    release_sequence: Option<u64>,
    intent_phase: Option<UpgradePhase>,
    attempt_id: Option<String>,
    error: Option<String>,
    /// Bytes transferred so far for the in-progress installer download. `None` outside the
    /// `downloading` state (or before the first progress tick lands).
    bytes_downloaded: Option<u64>,
    /// Total installer size, when the server reported `Content-Length`. Deliberately `None` (never
    /// `0`) rather than a fabricated denominator when the header is absent — the frontend must show
    /// bytes-so-far without a fake percentage rather than a misleading one.
    bytes_total: Option<u64>,
}

/// Scratch accumulator shared between the `on_chunk`/`on_download_finish` closures passed to
/// `Update::download` — not part of the published `AppUpdateStatus` wire shape.
struct DownloadProgress {
    downloaded: u64,
    total: Option<u64>,
    last_emit: Instant,
}

#[derive(Default)]
pub struct UpdateCoordinator {
    pending: Mutex<Option<ReleaseDescriptor>>,
    status: Mutex<AppUpdateStatus>,
    installing: AtomicBool,
}

impl UpdateCoordinator {
    pub fn initialize(&self, app: &AppHandle) {
        let current = app.package_info().version.to_string();
        let mut status = self.status.lock().expect("update status mutex poisoned");
        status.current_version = current.clone();
        status.state = "idle".into();

        let path = intent_path(app);
        match read_intent(&path).and_then(|intent| {
            intent
                .map(|value| {
                    reconcile_intent(
                        &path,
                        &launch_witness_path(app),
                        &staging_root(app),
                        &current,
                        value,
                    )
                })
                .transpose()
        }) {
            Ok(Some(intent)) => apply_intent_to_status(&mut status, &intent),
            Ok(None) => {}
            Err(error) => {
                status.state = "repair_required".into();
                status.error = Some(error);
            }
        }
    }

    fn snapshot(&self) -> AppUpdateStatus {
        self.status
            .lock()
            .expect("update status mutex poisoned")
            .clone()
    }

    pub async fn reconcile_after_backend_ready(&self, app: AppHandle, backend: Arc<BackendState>) {
        let path = intent_path(&app);
        let Some(mut intent) = (match read_intent(&path) {
            Ok(intent) => intent,
            Err(error) => {
                set_state(self, "repair_required", Some(error));
                return;
            }
        }) else {
            return;
        };
        if intent.phase != UpgradePhase::Reconciling {
            return;
        }

        let result = async {
            let head_pid = wait_for_backend_ready(&backend).await?;
            let request = ReconciliationRequest {
                schema_version: 1,
                attempt_id: intent.attempt_id.clone(),
                stop_evidence_kind: intent.stop_evidence.kind().into(),
                shutdown_nonce: intent.shutdown_nonce.clone(),
                source_version: intent.source_version.clone(),
                target_version: intent.target_version.clone(),
                release_sequence: intent.release_sequence,
                head_pid: u64::from(head_pid),
                owners: intent.owner_expectations.clone(),
            };
            let response: ReconciliationResponse = post_head(
                &backend,
                "/api/upgrade/reconcile",
                Some(serde_json::to_value(&request).map_err(|error| {
                    format!("Failed to encode reconciliation request: {error}")
                })?),
            )
            .await?;
            validate_reconciliation_response(&request, &response)?;
            Ok::<(), String>(())
        }
        .await;

        if let Err(error) = apply_reconciliation_outcome(&mut intent, result)
            .and_then(|_| persist_intent(&app, self, &intent))
        {
            set_state(self, "repair_required", Some(error));
        }
    }
}

#[tauri::command]
pub fn app_update_status(coordinator: tauri::State<'_, Arc<UpdateCoordinator>>) -> AppUpdateStatus {
    coordinator.snapshot()
}

#[tauri::command]
pub async fn check_for_app_update(
    app: AppHandle,
    coordinator: tauri::State<'_, Arc<UpdateCoordinator>>,
) -> Result<AppUpdateStatus, String> {
    ensure_no_unresolved_intent(&coordinator)?;
    if coordinator.installing.load(Ordering::Acquire) {
        return Err("An update installation is already in progress".into());
    }
    set_state(&coordinator, "checking", None);
    match check_release(&app).await {
        Ok(Some(descriptor)) => {
            *coordinator
                .pending
                .lock()
                .expect("pending update mutex poisoned") = Some(descriptor.clone());
            let mut status = coordinator
                .status
                .lock()
                .expect("update status mutex poisoned");
            status.state = "available".into();
            status.available_version = Some(descriptor.version.clone());
            status.release_sequence = Some(descriptor.sequence);
            status.intent_phase = None;
            status.attempt_id = None;
            status.error = None;
            Ok(status.clone())
        }
        Ok(None) => {
            let mut status = coordinator
                .status
                .lock()
                .expect("update status mutex poisoned");
            status.state = "up_to_date".into();
            status.available_version = None;
            status.release_sequence = None;
            status.intent_phase = None;
            status.attempt_id = None;
            status.error = None;
            Ok(status.clone())
        }
        Err(error) => {
            set_state(&coordinator, "error", Some(error.clone()));
            Err(error)
        }
    }
}

#[tauri::command]
pub async fn install_app_update(
    app: AppHandle,
    backend: tauri::State<'_, Arc<BackendState>>,
    coordinator: tauri::State<'_, Arc<UpdateCoordinator>>,
) -> Result<(), String> {
    run_install_now(app, (*backend).clone(), (*coordinator).clone()).await
}

/// The apply path, expressed without Tauri `State` so it can also be driven headlessly by the
/// Sandbox qualification lane (`maybe_autorun_qualification`). The command above is a thin
/// delegate, so the lane exercises the same code the button does rather than a parallel path —
/// a qualification round that proved a different code path would prove nothing.
pub(crate) async fn run_install_now(
    app: AppHandle,
    backend: Arc<BackendState>,
    coordinator: Arc<UpdateCoordinator>,
) -> Result<(), String> {
    ensure_no_unresolved_intent(&coordinator)?;
    let _install_guard = InstallGuard::begin(&coordinator.installing)?;
    let descriptor = coordinator
        .pending
        .lock()
        .expect("pending update mutex poisoned")
        .clone()
        .ok_or_else(|| "No authenticated update is pending; check for updates first".to_string())?;

    let updater = updater_for(&app, &descriptor)?;
    let update = updater
        .check()
        .await
        .map_err(|error| format!("Updater check failed: {error}"))?
        .ok_or_else(|| "Authenticated release is no longer available".to_string())?;
    require_closed_set(&descriptor, &update)?;

    set_state(&coordinator, "downloading", None);
    // `on_chunk` and `on_download_finish` are two separate closures alive for the whole call, so
    // shared mutable progress state needs interior mutability rather than two conflicting captures
    // of a bare local. Both closures clone the cheap `Arc`s they need.
    let progress = Arc::new(Mutex::new(DownloadProgress {
        downloaded: 0,
        total: None,
        last_emit: Instant::now(),
    }));
    let chunk_progress = progress.clone();
    let chunk_coordinator = coordinator.clone();
    let finish_progress = progress.clone();
    let finish_coordinator = coordinator.clone();
    let bytes = update
        .download(
            move |chunk_len, content_length| {
                let mut state = chunk_progress
                    .lock()
                    .expect("download progress mutex poisoned");
                state.downloaded = state.downloaded.saturating_add(chunk_len as u64);
                // The server's Content-Length (or its absence) is authoritative per chunk; never
                // fabricate a total when the header was never sent.
                state.total = content_length;
                let now = Instant::now();
                if now.duration_since(state.last_emit) >= DOWNLOAD_PROGRESS_THROTTLE {
                    state.last_emit = now;
                    set_download_progress(&chunk_coordinator, state.downloaded, state.total);
                }
            },
            move || {
                // Always emit a final update on completion so a throttled-out last tick can never
                // leave the UI showing a truncated byte count.
                let state = finish_progress
                    .lock()
                    .expect("download progress mutex poisoned");
                set_download_progress(&finish_coordinator, state.downloaded, state.total);
            },
        )
        .await
        .map_err(|error| format!("Update download or signature verification failed: {error}"))?;
    let digest = sha256_bytes(&bytes);
    if digest != descriptor.artifact.sha256.to_ascii_lowercase()
        || bytes.len() as u64 != descriptor.artifact.size
    {
        return Err("Downloaded installer bytes do not match release descriptor".into());
    }
    if bytes.get(0..2) != Some(b"MZ") {
        return Err("Authenticated updater artifact is not a Windows NSIS executable".into());
    }

    let attempt_id = uuid::Uuid::new_v4().to_string();
    let staged = stage_installer(&app, &attempt_id, &bytes, &digest)?;
    validate_staged_artifact(&staged, &staging_root(&app))?;

    finish_staged_update(&upgrade_root(&app), &coordinator,
        &ShellUpgradeHandoff { app: &app, backend: backend.clone() },
        &descriptor, staged, attempt_id, app.package_info().version.to_string()).await?;
    // The coordinator persisted the Windows launch witness before the shell exits.
    app.exit(0);
    Ok(())
}

/// Production post-authentication coordinator. Tests inject only process/HTTP/launch edges;
/// all intent, receipt, artifact and launch-witness validation uses the real filesystem path.
async fn finish_staged_update(
    root: &Path,
    coordinator: &UpdateCoordinator,
    edge: &impl UpgradeHandoff,
    descriptor: &ReleaseDescriptor,
    staged: StagedArtifact,
    attempt_id: String,
    source_version: String,
) -> Result<(), String> {
    validate_staged_artifact(&staged, &root.join("staged"))?;
    let result = async {
        edge.hold()?;
        let prepared = if edge.engine_available() {
            let prepared = edge.prepare().await?;
            validate_prepare_response(&prepared)?;
            if !prepared.ready {
                edge.cancel(&prepared.preparation_id, &prepared.shutdown_nonce).await?;
                return Err("Update is blocked until active operations drain".into());
            }
            Some(prepared)
        } else { None };
        let stop_evidence = if prepared.is_some() { StopEvidence::Prepared {} } else {
            let engine_pid = edge.stop_unrecoverable()?;
            StopEvidence::EngineUnrecoverable {
                attempt_id: attempt_id.clone(), engine_pid,
                confirmed_gone_at_epoch_ms: u64::try_from(now_epoch_ms())
                    .map_err(|_| "Engine stop timestamp exceeds the wire format")?,
            }
        };
    let mut intent = UpgradeIntent {
        schema_version: INTENT_SCHEMA_VERSION,
        phase: if prepared.is_some() { UpgradePhase::Prepared } else { UpgradePhase::EngineUnrecoverable },
        stop_evidence,
        attempt_id,
        preparation_id: prepared.as_ref().map(|p| p.preparation_id.clone()).unwrap_or_default(),
        shutdown_nonce: prepared.as_ref().map(|p| p.shutdown_nonce.clone()).unwrap_or_default(),
        shutdown_receipt: None,
        head_shutdown_receipt: None,
        head_pid: None,
        staged_artifact: staged,
        launch_witness: None,
        owner_expectations: descriptor
            .compatibility
            .iter()
            .map(|owner| OwnerExpectation {
                owner_id: owner.owner_id.clone(),
                format_version: owner.format_version,
            })
            .collect(),
        source_version,
        target_version: descriptor.version.clone(),
        release_sequence: descriptor.sequence,
        updated_at_epoch_ms: now_epoch_ms(),
        error: None,
    };
    if let Some(prepared) = prepared {
    if let Err(error) = persist_intent_at(root, coordinator, &intent) {
        let cancel =
            edge.cancel( &prepared.preparation_id, &prepared.shutdown_nonce).await;
        return Err(match cancel {
            Ok(_) => error,
            Err(cancel_error) => {
                format!("{error}; Head admission cancellation also failed: {cancel_error}")
            }
        });
    }

    let expected_head_pid = match edge.child_pid() {
        Some(pid) => pid,
        None => {
            let error = "Head child process witness is unavailable".to_string();
            let cancel =
                edge.cancel( &prepared.preparation_id, &prepared.shutdown_nonce).await;
            let (phase, message) = match cancel {
                Ok(_) => (UpgradePhase::Cancelled, error),
                Err(cancel_error) => (
                    UpgradePhase::RepairRequired,
                    format!("{error}; admission cancellation failed: {cancel_error}"),
                ),
            };
            transition(&mut intent, phase, Some(message.clone()))?;
            persist_intent_at(root, coordinator, &intent)?;
            return Err(message);
        }
    };
    let receipt = match edge.commit( &prepared.preparation_id, &prepared.shutdown_nonce)
        .await
    {
        Ok(receipt) => receipt,
        Err(error) => {
            if edge.wait_for_exit(Duration::from_millis(250)) {
                mark_repair_required_at(
                    root,
                    coordinator,
                    intent,
                    &format!("Head exited without a verifiable shutdown receipt: {error}"),
                )?;
            } else {
                let cancel =
                    edge.cancel( &prepared.preparation_id, &prepared.shutdown_nonce).await;
                let (phase, message) = match cancel {
                    Ok(_) => (
                        UpgradePhase::Cancelled,
                        format!("Shutdown handoff was rejected: {error}"),
                    ),
                    Err(cancel_error) => (
                        UpgradePhase::RepairRequired,
                        format!(
                            "Shutdown handoff failed and admission cancellation was not acknowledged: {error}; {cancel_error}"
                        ),
                    ),
                };
                transition(&mut intent, phase, Some(message))?;
                persist_intent_at(root, coordinator, &intent)?;
            }
            return Err(error);
        }
    };
    if let Err(error) =
        validate_shutdown_receipt(&receipt, &prepared.preparation_id, &prepared.shutdown_nonce)
    {
        mark_repair_required_at(root, coordinator, intent, &error)?;
        return Err(error);
    }
    intent.shutdown_receipt = Some(receipt);
    persist_intent_at(root, coordinator, &intent)?;

    if !edge.wait_for_exit(BACKEND_EXIT_TIMEOUT) {
        mark_repair_required_at(
            root,
            coordinator,
            intent,
            "Head acknowledged shutdown but did not exit before the handoff deadline",
        )?;
        return Err("Head did not complete orderly shutdown; installer was not launched".into());
    }

    let head_receipt: HeadShutdownReceipt = match read_json(&root.join("head-shutdown-receipt.v1.json")) {
        Ok(receipt) => receipt,
        Err(error) => {
            mark_repair_required_at(
                root,
                coordinator,
                intent,
                &format!("Head exited without a readable shutdown receipt: {error}"),
            )?;
            return Err(error);
        }
    };
    if let Err(error) = validate_head_shutdown_receipt(
        &head_receipt,
        &prepared.preparation_id,
        &prepared.shutdown_nonce,
        expected_head_pid,
    ) {
        mark_repair_required_at(root, coordinator, intent, &error)?;
        return Err(error);
    }
    intent.head_shutdown_receipt = Some(head_receipt);
    intent.head_pid = Some(u64::from(expected_head_pid));
    transition(&mut intent, UpgradePhase::HeadStopped, None)?;
    persist_intent_at(root, coordinator, &intent)?;
    } else {
        persist_intent_at(root, coordinator, &intent)?;
    }
    transition(&mut intent, UpgradePhase::InstallLaunching, None)?;
    persist_intent_at(root, coordinator, &intent)?;

    let launch = edge.launch(Path::new(&intent.staged_artifact.path));
    let (process_id, launched_at_epoch_ms) = match launch {
        Ok(witness) => witness,
        Err(error) => {
            let restart = edge.resume();
            let (phase, message) = match restart {
                Ok(()) => (
                    UpgradePhase::Cancelled,
                    format!("Installer launch failed; Engine supervision was resumed: {error}"),
                ),
                Err(restart_error) => (
                    UpgradePhase::RepairRequired,
                    format!(
                        "Installer launch failed and Engine supervised resume failed: {error}; {restart_error}"
                    ),
                ),
            };
            transition(&mut intent, phase, Some(message.clone()))?;
            persist_intent_at(root, coordinator, &intent)?;
            return Err(message);
        }
    };

    let witness = InstallerLaunchWitness {
        schema_version: EVIDENCE_SCHEMA_VERSION,
        attempt_id: intent.attempt_id.clone(),
        process_id,
        launched_at_epoch_ms,
        staged_path: intent.staged_artifact.path.clone(),
        staged_sha256: intent.staged_artifact.sha256.clone(),
        staged_size: intent.staged_artifact.size,
    };
    write_json_atomic(&root.join("installer-launch-witness.v1.json"), &witness)?;
    let persisted_witness: InstallerLaunchWitness = read_json(&root.join("installer-launch-witness.v1.json"))?;
    validate_launch_witness(&persisted_witness, &intent, &root.join("staged"))?;
    intent.launch_witness = Some(persisted_witness);
    transition(&mut intent, UpgradePhase::InstallLaunched, None)?;
    persist_intent_at(root, coordinator, &intent)?;

    Ok(())
    }.await;
    match result {
        Err(error) if edge.is_held() => {
            let error =
                format!("{error}; Engine replacement remains held; update recovery is required");
            set_state(coordinator, "repair_required", Some(error.clone()));
            Err(error)
        }
        other => other,
    }
}

trait UpgradeHandoff: Sync {
    fn hold(&self) -> Result<(), String>;
    fn is_held(&self) -> bool;
    fn engine_available(&self) -> bool;
    fn child_pid(&self) -> Option<u32>;
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String>;
    fn prepare(&self) -> impl std::future::Future<Output = Result<PrepareResponse, String>> + Send;
    fn cancel(
        &self,
        preparation: &str,
        nonce: &str,
    ) -> impl std::future::Future<Output = Result<serde_json::Value, String>> + Send;
    fn commit(
        &self,
        preparation: &str,
        nonce: &str,
    ) -> impl std::future::Future<Output = Result<ShutdownReceipt, String>> + Send;
    fn wait_for_exit(&self, timeout: Duration) -> bool;
    fn resume(&self) -> Result<(), String>;
    fn launch(&self, path: &Path) -> Result<(u32, u128), String>;
}

struct ShellUpgradeHandoff<'a> {
    app: &'a AppHandle,
    backend: Arc<BackendState>,
}
impl UpgradeHandoff for ShellUpgradeHandoff<'_> {
    fn hold(&self) -> Result<(), String> {
        self.backend
            .host
            .acquire_replacement_hold(BACKEND_EXIT_TIMEOUT)
    }
    fn is_held(&self) -> bool {
        self.backend.host.replacement_held()
    }
    fn engine_available(&self) -> bool {
        self.backend.child_pid().is_some() && self.backend.get_port().is_some()
    }
    fn child_pid(&self) -> Option<u32> {
        self.backend.child_pid()
    }
    fn stop_unrecoverable(&self) -> Result<Option<u32>, String> {
        let pid = self.backend.host.stop_owned_engine(BACKEND_EXIT_TIMEOUT)?;
        let data_dir = crate::resolve_app_data_dir(self.app)?;
        crate::reconcile_registered_children(&data_dir)?;
        Ok(pid)
    }
    async fn prepare(&self) -> Result<PrepareResponse, String> {
        prepare_head(&self.backend).await
    }
    async fn cancel(&self, preparation: &str, nonce: &str) -> Result<serde_json::Value, String> {
        cancel_head(&self.backend, preparation, nonce).await
    }
    async fn commit(&self, preparation: &str, nonce: &str) -> Result<ShutdownReceipt, String> {
        commit_head(&self.backend, preparation, nonce).await
    }
    fn wait_for_exit(&self, timeout: Duration) -> bool {
        self.backend.wait_for_child_exit(timeout)
    }
    fn resume(&self) -> Result<(), String> {
        self.backend.host.release_replacement_hold()?;
        let result = (|| {
            if self.backend.child_pid().is_none() {
                restart_headless_backend(self.app, self.backend.clone())?;
            }
            crate::start_supervision(self.app, self.backend.clone())
        })();
        if result.is_err() {
            let _ = self
                .backend
                .host
                .acquire_replacement_hold(BACKEND_EXIT_TIMEOUT);
        }
        result
    }
    fn launch(&self, path: &Path) -> Result<(u32, u128), String> {
        launch_installer(path)
    }
}

/// Sandbox qualification autorun — tempdoc 617 §9 items 3-4.
///
/// Drives check -> install with no human present so the N->N+1 machinery (prepare, freeze,
/// witnessed shutdown, installer launch, restart reconciliation) can be qualified unattended. It
/// deliberately calls [`run_install_now`], the same function the UI button delegates to: a lane
/// that exercised a parallel code path would prove nothing about the shipped one.
///
/// This does NOT replace the consent round. "The user is asked before anything is applied" and
/// "the apply machinery is correct" are separate claims; this covers the second, and the
/// human whole-product Sandbox round covers the first.
///
/// Double-gated. `sandbox_test_mode()` is `option_env!`, so in any build not compiled for
/// qualification this function is dead on the first line and no environment variable can revive
/// it. The runtime opt-in exists so that even a qualification build does not self-update merely
/// by being launched.
///
/// Spans two boots by construction: a successful apply exits this process, so PASS can only be
/// observed by the *next* launch reconciling the durable intent to COMMITTED. Reading the phase
/// first is therefore the terminal check, not a shortcut.
pub(crate) async fn maybe_autorun_qualification(
    app: AppHandle,
    backend: Arc<BackendState>,
    coordinator: Arc<UpdateCoordinator>,
) {
    if !sandbox_test_mode() {
        return;
    }
    if std::env::var("JUSTSEARCH_UPDATER_QUALIFICATION_AUTORUN")
        .ok()
        .as_deref()
        != Some("1")
    {
        return;
    }

    match qualification_step(coordinator.snapshot().intent_phase) {
        QualificationStep::Report(verdict, detail) => {
            write_qualification_result(&app, verdict, &detail);
            return;
        }
        QualificationStep::Attempt => {}
    }

    match run_qualification_attempt(&app, &backend, &coordinator).await {
        // Unreachable on the happy path: a successful apply exits before returning.
        Ok(version) => write_qualification_result(
            &app,
            "FAIL",
            &format!("Install returned without handing off to the {version} installer"),
        ),
        Err(error) => write_qualification_result(&app, "FAIL", &error),
    }
}

#[derive(Debug, PartialEq, Eq)]
enum QualificationStep {
    /// Terminal: emit this verdict and stop.
    Report(&'static str, String),
    /// No attempt is on record; start one.
    Attempt,
}

/// The two-boot decision, kept pure so it can be tested without a Tauri app.
///
/// A successful apply exits the process, so the lane's PASS is never observed by the boot that
/// started it — only by the next one, reconciling the durable intent. Anything other than
/// COMMITTED is a FAIL rather than a retry: re-attempting from a non-terminal phase would stack a
/// second update on an unresolved one, and re-attempting from a terminal failure would overwrite
/// the evidence the round exists to capture.
fn qualification_step(phase: Option<UpgradePhase>) -> QualificationStep {
    match phase {
        None => QualificationStep::Attempt,
        Some(UpgradePhase::Committed) => QualificationStep::Report(
            "PASS",
            "Update applied and reconciled to COMMITTED".to_string(),
        ),
        Some(phase @ (UpgradePhase::RepairRequired | UpgradePhase::Cancelled)) => {
            QualificationStep::Report("FAIL", format!("Update ended in terminal phase {phase:?}"))
        }
        Some(phase) => QualificationStep::Report(
            "FAIL",
            format!("Update is stuck in non-terminal phase {phase:?}"),
        ),
    }
}

async fn run_qualification_attempt(
    app: &AppHandle,
    backend: &Arc<BackendState>,
    coordinator: &Arc<UpdateCoordinator>,
) -> Result<String, String> {
    let descriptor = check_release(app)
        .await?
        .ok_or_else(|| "Qualification feed offered no update".to_string())?;
    let version = descriptor.version.clone();
    *coordinator
        .pending
        .lock()
        .expect("pending update mutex poisoned") = Some(descriptor);
    run_install_now(app.clone(), backend.clone(), coordinator.clone()).await?;
    Ok(version)
}

/// Emits the lane's verdict in the same shape `sandbox-silent-install-test.ps1` uses, so
/// `collect-updater-evidence.ps1` can read one file rather than infer success from logs.
fn write_qualification_result(app: &AppHandle, verdict: &str, detail: &str) {
    let payload = serde_json::json!({
        "schema": "justsearch.sandbox-in-app-update-test.v1",
        "verdict": verdict,
        "detail": detail,
        "currentVersion": app.package_info().version.to_string(),
    });
    let path = upgrade_root(app).join("qualification-result.v1.json");
    if let Err(error) = write_json_atomic(&path, &payload) {
        eprintln!("Failed to write qualification result to {}: {error}", path.display());
    }
}

async fn check_release(app: &AppHandle) -> Result<Option<ReleaseDescriptor>, String> {
    let descriptor_url = descriptor_url()?;
    ensure_https(&descriptor_url)?;
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .build()
        .map_err(|error| format!("Failed to create release client: {error}"))?;
    let descriptor_bytes = fetch_bytes(&client, &descriptor_url).await?;
    let signature = fetch_text(&client, &format!("{descriptor_url}.sig")).await?;
    verify_metadata_signature(&descriptor_bytes, signature.trim())?;
    let descriptor: ReleaseDescriptor = serde_json::from_slice(&descriptor_bytes)
        .map_err(|error| format!("Release descriptor is invalid: {error}"))?;
    validate_descriptor(&descriptor)?;

    let sequence_path = sequence_path(app);
    let sequence = read_sequence(&sequence_path)?;
    if descriptor.sequence < sequence.highest_accepted_sequence {
        return Err(format!(
            "Release sequence {} is older than accepted sequence {}",
            descriptor.sequence, sequence.highest_accepted_sequence
        ));
    }

    let update = updater_for(app, &descriptor)?
        .check()
        .await
        .map_err(|error| format!("Updater check failed: {error}"))?;
    let Some(update) = update else {
        return Ok(None);
    };
    require_closed_set(&descriptor, &update)?;
    write_json_atomic(
        &sequence_path,
        &SequenceState {
            schema_version: 1,
            highest_accepted_sequence: descriptor.sequence,
        },
    )?;
    Ok(Some(descriptor))
}

fn updater_for(
    app: &AppHandle,
    descriptor: &ReleaseDescriptor,
) -> Result<tauri_plugin_updater::Updater, String> {
    let latest = latest_url()?;
    app.updater_builder()
        .pubkey(descriptor.artifact.public_key.clone())
        .endpoints(vec![latest
            .parse()
            .map_err(|error| format!("Invalid updater endpoint: {error}"))?])
        .map_err(|error| format!("Updater endpoint rejected: {error}"))?
        .build()
        .map_err(|error| format!("Updater initialization failed: {error}"))
}

fn require_closed_set(
    descriptor: &ReleaseDescriptor,
    update: &tauri_plugin_updater::Update,
) -> Result<(), String> {
    if update.version != descriptor.version
        || update.download_url.as_str() != descriptor.artifact.url
        || update.signature.trim() != descriptor.artifact.signature.trim()
    {
        return Err("release.v1.json and latest.json do not describe the same artifact".into());
    }
    Ok(())
}

fn validate_descriptor(descriptor: &ReleaseDescriptor) -> Result<(), String> {
    if descriptor.schema_version != DESCRIPTOR_SCHEMA_VERSION
        || descriptor.channel != "stable"
        || descriptor.target != "windows-x86_64"
        || descriptor.sequence == 0
        || descriptor.version.trim().is_empty()
        || descriptor.metadata_key_id != metadata_root_key_id()?
        || descriptor.metadata_root_policy != "OFFLINE_LONG_LIVED_V1"
    {
        return Err("Release descriptor identity or metadata key id is invalid".into());
    }
    ensure_https(&descriptor.artifact.url)?;
    if descriptor.artifact.sha256.len() != 64
        || !descriptor
            .artifact
            .sha256
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
        || descriptor.artifact.size == 0
        || descriptor.artifact.signature.trim().is_empty()
        || descriptor.artifact.public_key.trim().is_empty()
    {
        return Err("Release descriptor artifact fields are incomplete".into());
    }
    validate_store_compatibility(descriptor)
}

fn validate_store_compatibility(descriptor: &ReleaseDescriptor) -> Result<(), String> {
    let local: LocalStoreRegister = serde_json::from_str(LOCAL_STORE_REGISTER)
        .map_err(|error| format!("Embedded store compatibility register is invalid: {error}"))?;
    let mut release_owners = HashMap::new();
    for compatibility in &descriptor.compatibility {
        if compatibility.owner_id.trim().is_empty()
            || compatibility.owner.trim().is_empty()
            || compatibility.role.trim().is_empty()
            || compatibility.reconciliation_strategy.trim().is_empty()
            || release_owners
                .insert(compatibility.owner_id.as_str(), compatibility)
                .is_some()
        {
            return Err(format!(
                "Release compatibility entry is missing strategy fields or duplicated: {}",
                compatibility.owner_id
            ));
        }
        if !compatibility
            .readable_source_versions
            .contains(&compatibility.format_version)
        {
            return Err(format!(
                "Release compatibility owner {} cannot read its target format {}",
                compatibility.owner_id, compatibility.format_version
            ));
        }
    }
    if release_owners.len() != local.durable_stores.len() {
        return Err("Release compatibility table is not a closed set".into());
    }
    for store in local.durable_stores {
        let compatibility = release_owners.get(store.id.as_str()).ok_or_else(|| {
            format!(
                "Release does not declare compatibility for durable store {}",
                store.id
            )
        })?;
        if compatibility.owner != store.owner
            || compatibility.role != store.recoverability
            || compatibility.reconciliation_strategy != store.reconciliation
        {
            return Err(format!(
                "Release changes ownership or recovery strategy for durable store {}",
                store.id
            ));
        }
        if !compatibility
            .readable_source_versions
            .contains(&store.current_version)
        {
            return Err(format!(
                "Release cannot read durable store {} version {}",
                store.id, store.current_version
            ));
        }
    }
    Ok(())
}

fn verify_metadata_signature(bytes: &[u8], signature: &str) -> Result<(), String> {
    let key = base64::engine::general_purpose::STANDARD
        .decode(metadata_root_key()?.trim())
        .map_err(|error| format!("Metadata root key is not base64: {error}"))?;
    if key.len() != 32 {
        return Err("Metadata root key must decode to 32 Ed25519 bytes".into());
    }
    let signature = base64::engine::general_purpose::STANDARD
        .decode(signature)
        .map_err(|error| format!("Metadata signature is not base64: {error}"))?;
    UnparsedPublicKey::new(&ED25519, key)
        .verify(bytes, &signature)
        .map_err(|_| "Release metadata signature verification failed".into())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrepareResponse {
    schema_version: u32,
    preparation_id: String,
    shutdown_nonce: String,
    ready: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ReconciliationRequest {
    schema_version: u32,
    attempt_id: String,
    #[serde(default = "prepared_evidence_kind")]
    stop_evidence_kind: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    shutdown_nonce: String,
    source_version: String,
    target_version: String,
    release_sequence: u64,
    head_pid: u64,
    owners: Vec<OwnerExpectation>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ReconciliationResponse {
    schema_version: u32,
    attempt_id: String,
    #[serde(default = "prepared_evidence_kind")]
    stop_evidence_kind: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    shutdown_nonce: String,
    target_version: String,
    head_pid: u64,
    ready: bool,
    head_ready: bool,
    worker_ready: bool,
    owners: Vec<OwnerHealth>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OwnerHealth {
    owner_id: String,
    format_version: u32,
    healthy: bool,
}

async fn prepare_head(backend: &BackendState) -> Result<PrepareResponse, String> {
    post_head(backend, "/api/upgrade/prepare", None).await
}

async fn cancel_head(
    backend: &BackendState,
    preparation_id: &str,
    shutdown_nonce: &str,
) -> Result<serde_json::Value, String> {
    post_head(
        backend,
        "/api/upgrade/cancel",
        Some(serde_json::json!({
            "schemaVersion": 1,
            "preparationId": preparation_id,
            "shutdownNonce": shutdown_nonce
        })),
    )
    .await
}

async fn commit_head(
    backend: &BackendState,
    preparation_id: &str,
    shutdown_nonce: &str,
) -> Result<ShutdownReceipt, String> {
    post_head(
        backend,
        "/api/upgrade/commit-shutdown",
        Some(serde_json::json!({
            "schemaVersion": 1,
            "preparationId": preparation_id,
            "shutdownNonce": shutdown_nonce
        })),
    )
    .await
}

fn validate_prepare_response(response: &PrepareResponse) -> Result<(), String> {
    if response.schema_version != 1
        || response.preparation_id.trim().is_empty()
        || response.shutdown_nonce.len() < 32
    {
        return Err("Head returned an invalid upgrade preparation capability".into());
    }
    Ok(())
}

fn validate_shutdown_receipt(
    receipt: &ShutdownReceipt,
    preparation_id: &str,
    shutdown_nonce: &str,
) -> Result<(), String> {
    if receipt.schema_version != EVIDENCE_SCHEMA_VERSION
        || receipt.preparation_id != preparation_id
        || receipt.shutdown_nonce != shutdown_nonce
        || !receipt.shutdown_accepted
        || !receipt.admission_frozen
        || receipt.active_lease_count != 0
        || receipt.issued_at_epoch_ms == 0
    {
        return Err("Head shutdown receipt did not prove the prepared quiescent session".into());
    }
    Ok(())
}

fn validate_head_shutdown_receipt(
    receipt: &HeadShutdownReceipt,
    preparation_id: &str,
    shutdown_nonce: &str,
    expected_head_pid: u32,
) -> Result<(), String> {
    if receipt.schema_version != EVIDENCE_SCHEMA_VERSION
        || receipt.preparation_id != preparation_id
        || receipt.shutdown_nonce != shutdown_nonce
        || receipt.head_pid == 0
        || receipt.head_pid > u64::from(u32::MAX)
        || receipt.head_pid != u64::from(expected_head_pid)
        || !receipt.clean
        || receipt.worker_outcome != "GRACEFUL"
        || !receipt.errors.is_empty()
        || receipt.completed_at.trim().is_empty()
    {
        return Err(
            "Final Head shutdown receipt did not prove clean shutdown of the prepared process"
                .into(),
        );
    }
    Ok(())
}

async fn wait_for_backend_ready(backend: &BackendState) -> Result<u32, String> {
    let deadline = std::time::Instant::now() + BACKEND_READY_TIMEOUT;
    loop {
        if backend.has_spawn_error() {
            return Err("Target Head failed during startup".into());
        }
        if backend.get_port().is_some() && backend.get_session_token().is_some() {
            if let Some(pid) = backend.child_pid() {
                return Ok(pid);
            }
        }
        if std::time::Instant::now() >= deadline {
            return Err("Target Head did not become authenticated and ready before timeout".into());
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn prepared_evidence_kind() -> String { "PREPARED".into() }

fn validate_reconciliation_response(
    request: &ReconciliationRequest,
    response: &ReconciliationResponse,
) -> Result<(), String> {
    let nonce_valid = match request.stop_evidence_kind.as_str() {
        "PREPARED" => request.shutdown_nonce.len() >= 32
            && response.shutdown_nonce == request.shutdown_nonce,
        "ENGINE_UNRECOVERABLE" => request.shutdown_nonce.is_empty()
            && response.shutdown_nonce.is_empty(),
        _ => false,
    };
    if response.schema_version != 1
        || !nonce_valid
        || response.stop_evidence_kind != request.stop_evidence_kind
        || response.attempt_id != request.attempt_id
        || response.shutdown_nonce != request.shutdown_nonce
        || response.target_version != request.target_version
        || response.head_pid != request.head_pid
        || !response.ready
        || !response.head_ready
        || !response.worker_ready
    {
        return Err("Target Head reconciliation identity/readiness attestation is invalid".into());
    }
    let expected: HashMap<_, _> = request
        .owners
        .iter()
        .map(|owner| (owner.owner_id.as_str(), owner.format_version))
        .collect();
    if expected.len() != request.owners.len() || response.owners.len() != expected.len() {
        return Err("Target Head reconciliation owner set is not closed".into());
    }
    let mut actual = HashMap::new();
    for owner in &response.owners {
        if !owner.healthy
            || actual
                .insert(owner.owner_id.as_str(), owner.format_version)
                .is_some()
        {
            return Err("Target Head reported a duplicate or unhealthy durable owner".into());
        }
    }
    if actual != expected {
        return Err("Target Head durable-owner formats differ from the signed release".into());
    }
    Ok(())
}

fn apply_reconciliation_outcome(
    intent: &mut UpgradeIntent,
    outcome: Result<(), String>,
) -> Result<(), String> {
    match outcome {
        Ok(()) => transition(intent, UpgradePhase::Committed, None),
        Err(error) => transition(
            intent,
            UpgradePhase::RepairRequired,
            Some(format!("Target-side reconciliation failed: {error}")),
        ),
    }
}

async fn post_head<T: for<'de> Deserialize<'de>>(
    backend: &BackendState,
    path: &str,
    body: Option<serde_json::Value>,
) -> Result<T, String> {
    let port = backend
        .get_port()
        .ok_or_else(|| "Backend is unavailable".to_string())?;
    let token = backend
        .get_session_token()
        .ok_or_else(|| "Backend session token is unavailable".to_string())?;
    let client = reqwest::Client::builder()
        .timeout(HEAD_TIMEOUT)
        .build()
        .map_err(|error| format!("Failed to create backend client: {error}"))?;
    let mut request = client
        .post(format!("http://127.0.0.1:{port}{path}"))
        .header("X-JustSearch-Session", token);
    if let Some(body) = body {
        request = request.json(&body);
    }
    let response = request
        .send()
        .await
        .map_err(|error| format!("Backend upgrade handshake failed: {error}"))?;
    let status = response.status();
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("Backend upgrade response failed: {error}"))?;
    if !status.is_success() {
        return Err(format!(
            "Backend rejected upgrade handshake with {status}: {}",
            String::from_utf8_lossy(&bytes)
        ));
    }
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("Backend upgrade response was invalid: {error}"))
}

async fn fetch_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>, String> {
    client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("Release request failed: {error}"))?
        .error_for_status()
        .map_err(|error| format!("Release request failed: {error}"))?
        .bytes()
        .await
        .map(|bytes| bytes.to_vec())
        .map_err(|error| format!("Release body failed: {error}"))
}

async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String, String> {
    String::from_utf8(fetch_bytes(client, url).await?)
        .map_err(|error| format!("Release signature is not UTF-8: {error}"))
}

fn descriptor_url() -> Result<String, String> {
    pinned_release_value(
        "JUSTSEARCH_RELEASE_DESCRIPTOR_URL",
        option_env!("JUSTSEARCH_RELEASE_DESCRIPTOR_URL"),
    )
}

fn latest_url() -> Result<String, String> {
    descriptor_url()?
        .strip_suffix("release.v1.json")
        .map(|prefix| format!("{prefix}latest.json"))
        .ok_or_else(|| "Release descriptor URL must end with release.v1.json".into())
}

fn metadata_root_key() -> Result<String, String> {
    pinned_release_value(
        "JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY",
        option_env!("JUSTSEARCH_RELEASE_METADATA_ROOT_PUBLIC_KEY"),
    )
}

fn metadata_root_key_id() -> Result<String, String> {
    pinned_release_value(
        "JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID",
        option_env!("JUSTSEARCH_RELEASE_METADATA_ROOT_KEY_ID"),
    )
}

fn pinned_release_value(name: &str, built: Option<&'static str>) -> Result<String, String> {
    if sandbox_test_mode() {
        if let Ok(value) = std::env::var(name) {
            if !value.trim().is_empty() {
                return Ok(value);
            }
        }
    }
    if let Some(value) = built.filter(|value| !value.trim().is_empty()) {
        return Ok(value.to_owned());
    }
    #[cfg(debug_assertions)]
    {
        return std::env::var(name)
            .ok()
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| format!("{name} is not configured"));
    }
    #[cfg(not(debug_assertions))]
    Err(format!("{name} was not pinned into this release build"))
}

fn sandbox_test_mode() -> bool {
    option_env!("JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE") == Some("1")
}

fn ensure_https(url: &str) -> Result<(), String> {
    let parsed = reqwest::Url::parse(url)
        .map_err(|error| format!("Release endpoint is invalid: {error}"))?;
    if parsed.scheme() == "https" {
        Ok(())
    } else if sandbox_test_mode()
        && parsed.scheme() == "http"
        && matches!(parsed.host_str(), Some("127.0.0.1") | Some("localhost"))
    {
        Ok(())
    } else {
        Err("Release endpoints must use HTTPS (except loopback in a sandbox-gated build)".into())
    }
}

fn upgrade_root(app: &AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("app data directory unavailable")
        .join("upgrade")
}

fn intent_path(app: &AppHandle) -> PathBuf {
    upgrade_root(app).join("intent.v1.json")
}

fn sequence_path(app: &AppHandle) -> PathBuf {
    upgrade_root(app).join("sequence.v1.json")
}

fn launch_witness_path(app: &AppHandle) -> PathBuf {
    upgrade_root(app).join("installer-launch-witness.v1.json")
}

fn head_shutdown_receipt_path(app: &AppHandle) -> PathBuf {
    upgrade_root(app).join("head-shutdown-receipt.v1.json")
}

fn staging_root(app: &AppHandle) -> PathBuf {
    upgrade_root(app).join("staged")
}

fn stage_installer(
    app: &AppHandle,
    attempt_id: &str,
    bytes: &[u8],
    sha256: &str,
) -> Result<StagedArtifact, String> {
    let directory = staging_root(app).join(attempt_id);
    fs::create_dir_all(&directory)
        .map_err(|error| format!("Failed to create installer staging directory: {error}"))?;
    let path = directory.join("JustSearch-update-setup.exe");
    write_bytes_atomic(&path, bytes)?;
    let staged = StagedArtifact {
        path: path.to_string_lossy().into_owned(),
        sha256: sha256.to_ascii_lowercase(),
        size: bytes.len() as u64,
    };
    validate_staged_artifact(&staged, &staging_root(app))?;
    Ok(staged)
}

fn validate_staged_artifact(staged: &StagedArtifact, root: &Path) -> Result<(), String> {
    if staged.sha256.len() != 64 || staged.size == 0 {
        return Err("Staged installer evidence is incomplete".into());
    }
    let root = fs::canonicalize(root)
        .map_err(|error| format!("Failed to resolve installer staging root: {error}"))?;
    let path = fs::canonicalize(&staged.path)
        .map_err(|error| format!("Failed to resolve staged installer: {error}"))?;
    if !path.starts_with(&root) {
        return Err("Staged installer escaped the shell-owned staging root".into());
    }
    let bytes =
        fs::read(&path).map_err(|error| format!("Failed to re-read staged installer: {error}"))?;
    if bytes.len() as u64 != staged.size || sha256_bytes(&bytes) != staged.sha256 {
        return Err("Staged installer failed post-write size or digest verification".into());
    }
    if bytes.get(0..2) != Some(b"MZ") {
        return Err("Staged installer is not a Windows executable".into());
    }
    Ok(())
}

fn validate_launch_witness(
    witness: &InstallerLaunchWitness,
    intent: &UpgradeIntent,
    staging_root: &Path,
) -> Result<(), String> {
    if witness.schema_version != EVIDENCE_SCHEMA_VERSION
        || witness.attempt_id != intent.attempt_id
        || witness.process_id == 0
        || witness.launched_at_epoch_ms == 0
        || witness.staged_path != intent.staged_artifact.path
        || witness.staged_sha256 != intent.staged_artifact.sha256
        || witness.staged_size != intent.staged_artifact.size
    {
        return Err("Installer launch witness does not match the durable upgrade intent".into());
    }
    validate_staged_artifact(&intent.staged_artifact, staging_root)
}

#[cfg(windows)]
fn launch_installer(path: &Path) -> Result<(u32, u128), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::Threading::GetProcessId;
    use windows_sys::Win32::UI::Shell::{
        ShellExecuteExW, SEE_MASK_FLAG_NO_UI, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOW;

    let file: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    let verb: Vec<u16> = "open".encode_utf16().chain(Some(0)).collect();
    // Match Tauri updater 2.10.1's basicUi NSIS invocation. Basic UI intentionally adds neither
    // /P nor /S; /UPDATE selects the updater path and /ARGS preserves its expected handoff form.
    let parameters: Vec<u16> = "/UPDATE /ARGS".encode_utf16().chain(Some(0)).collect();
    let directory: Vec<u16> = path
        .parent()
        .unwrap_or_else(|| Path::new("."))
        .as_os_str()
        .encode_wide()
        .chain(Some(0))
        .collect();
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI,
        hwnd: std::ptr::null_mut(),
        lpVerb: verb.as_ptr(),
        lpFile: file.as_ptr(),
        lpParameters: parameters.as_ptr(),
        lpDirectory: directory.as_ptr(),
        nShow: SW_SHOW,
        ..Default::default()
    };
    if unsafe { ShellExecuteExW(&mut info) } == 0 {
        return Err(format!(
            "Windows rejected installer launch: {}",
            std::io::Error::last_os_error()
        ));
    }
    if info.hProcess.is_null() {
        return Err("Windows accepted installer verb without returning a process witness".into());
    }
    let process_id = unsafe { GetProcessId(info.hProcess) };
    unsafe {
        CloseHandle(info.hProcess);
    }
    if process_id == 0 {
        return Err(format!(
            "Windows returned an invalid installer process witness: {}",
            std::io::Error::last_os_error()
        ));
    }
    Ok((process_id, now_epoch_ms()))
}

#[cfg(not(windows))]
fn launch_installer(_path: &Path) -> Result<(u32, u128), String> {
    Err("Application updater installer launch is Windows-only".into())
}

fn read_intent(path: &Path) -> Result<Option<UpgradeIntent>, String> {
    if !path.exists() {
        return Ok(None);
    }
    let intent: UpgradeIntent = read_json(path)?;
    if intent.schema_version != INTENT_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported upgrade intent schema version {}",
            intent.schema_version
        ));
    }
    Ok(Some(intent))
}

fn reconcile_intent(
    path: &Path,
    witness_path: &Path,
    staging_root: &Path,
    current_version: &str,
    mut intent: UpgradeIntent,
) -> Result<UpgradeIntent, String> {
    validate_intent_evidence(&intent, witness_path, staging_root)?;
    match intent.phase {
        UpgradePhase::Prepared | UpgradePhase::HeadStopped | UpgradePhase::EngineUnrecoverable
            if intent.source_version == current_version =>
        {
            transition(
                &mut intent,
                UpgradePhase::Cancelled,
                Some("Update handoff ended before installer launch".into()),
            )?;
        }
        UpgradePhase::InstallLaunched if intent.source_version == current_version => {
            transition(
                &mut intent,
                UpgradePhase::Cancelled,
                Some("Installer exited or was cancelled without changing the app version".into()),
            )?;
        }
        UpgradePhase::InstallLaunched if intent.target_version == current_version => {
            transition(
                &mut intent,
                UpgradePhase::Reconciling,
                Some("Installed version is awaiting durable-owner reconciliation".into()),
            )?;
        }
        UpgradePhase::Reconciling if intent.target_version == current_version => {
            // Version equality is only an observation. Owner health must explicitly advance this
            // intent to COMMITTED; startup must never convert equality into success.
        }
        UpgradePhase::Committed | UpgradePhase::Cancelled | UpgradePhase::RepairRequired => {}
        _ => {
            let expected = intent.target_version.clone();
            let previous_phase = intent.phase.clone();
            transition(
                &mut intent,
                UpgradePhase::RepairRequired,
                Some(format!(
                    "Upgrade phase {:?} cannot be reconciled with running version {}; expected {}",
                    previous_phase, current_version, expected
                )),
            )?;
        }
    }
    write_json_atomic(path, &intent)?;
    Ok(intent)
}

/// The Head PID recorded on the intent at commit-shutdown time.
///
/// Reconciliation must validate a shutdown receipt against this independently persisted value.
/// Passing the receipt's own `head_pid` back in makes the comparison tautological, so a receipt
/// fabricated or carried over from a different attempt would satisfy the PID clause on the one
/// path — restart — where the live `backend.child_pid()` witness is gone.
fn expected_head_pid(intent: &UpgradeIntent) -> Result<u32, String> {
    let pid = intent.head_pid.ok_or_else(|| {
        "Upgrade intent is missing the Head process id recorded at commit-shutdown".to_string()
    })?;
    if pid == 0 || pid > u64::from(u32::MAX) {
        return Err("Upgrade intent recorded an out-of-range Head process id".into());
    }
    Ok(pid as u32)
}

fn validate_prepared_stop(intent: &UpgradeIntent) -> Result<(), String> {
    let receipt = intent.shutdown_receipt.as_ref()
        .ok_or_else(|| "Upgrade intent is missing its quiescence receipt".to_string())?;
    validate_shutdown_receipt(receipt, &intent.preparation_id, &intent.shutdown_nonce)?;
    let final_receipt = intent.head_shutdown_receipt.as_ref()
        .ok_or_else(|| "Upgrade intent is missing its final Head shutdown receipt".to_string())?;
    validate_head_shutdown_receipt(final_receipt, &intent.preparation_id,
        &intent.shutdown_nonce, expected_head_pid(intent)?)
}

fn validate_intent_evidence(
    intent: &UpgradeIntent,
    witness_path: &Path,
    staging_root: &Path,
) -> Result<(), String> {
    if intent.attempt_id.trim().is_empty() || intent.owner_expectations.is_empty() {
        return Err("Durable upgrade intent is missing its attempt/owner identity".into());
    }
    let prepared = match &intent.stop_evidence {
        StopEvidence::Prepared {} => {
            if intent.preparation_id.trim().is_empty() || intent.shutdown_nonce.len() < 32
                || intent.phase == UpgradePhase::EngineUnrecoverable {
                return Err("Durable upgrade intent is missing its prepared-session identity".into());
            }
            true
        }
        StopEvidence::EngineUnrecoverable { attempt_id, engine_pid, confirmed_gone_at_epoch_ms } => {
            if attempt_id != &intent.attempt_id || *confirmed_gone_at_epoch_ms == 0
                || engine_pid == &Some(0) || !intent.preparation_id.is_empty()
                || !intent.shutdown_nonce.is_empty() || intent.shutdown_receipt.is_some()
                || intent.head_shutdown_receipt.is_some() || intent.head_pid.is_some()
                || matches!(intent.phase, UpgradePhase::Prepared | UpgradePhase::HeadStopped) {
                return Err("Unrecoverable Engine stop evidence is missing or mixed with prepared evidence".into());
            }
            false
        }
    };
    if matches!(intent.phase, UpgradePhase::Cancelled) { return Ok(()); }
    if matches!(intent.phase, UpgradePhase::Committed) {
        if prepared { validate_prepared_stop(intent)?; }
        if intent.launch_witness.is_none() {
            return Err("Committed intent is missing its embedded installer witness".into());
        }
        return Ok(());
    }
    validate_staged_artifact(&intent.staged_artifact, staging_root)?;
    if prepared && matches!(intent.phase, UpgradePhase::HeadStopped | UpgradePhase::InstallLaunching
        | UpgradePhase::InstallLaunched | UpgradePhase::Reconciling) {
        validate_prepared_stop(intent)?;
    }
    if matches!(intent.phase, UpgradePhase::InstallLaunched | UpgradePhase::Reconciling) {
        let persisted: InstallerLaunchWitness = read_json(witness_path)?;
        validate_launch_witness(&persisted, intent, staging_root)?;
        let embedded = intent.launch_witness.as_ref()
            .ok_or_else(|| "Upgrade intent is missing its installer launch witness".to_string())?;
        if serde_json::to_value(embedded).ok() != serde_json::to_value(&persisted).ok() {
            return Err("Embedded and separately durable installer witnesses differ".into());
        }
    }
    Ok(())
}

fn transition(
    intent: &mut UpgradeIntent,
    next: UpgradePhase,
    error: Option<String>,
) -> Result<(), String> {
    let legal = matches!(
        (&intent.phase, &next),
        (UpgradePhase::Prepared, UpgradePhase::HeadStopped)
            | (UpgradePhase::Prepared, UpgradePhase::Cancelled)
            | (UpgradePhase::Prepared, UpgradePhase::RepairRequired)
            | (UpgradePhase::HeadStopped, UpgradePhase::InstallLaunching)
            | (UpgradePhase::EngineUnrecoverable, UpgradePhase::InstallLaunching)
            | (UpgradePhase::EngineUnrecoverable, UpgradePhase::Cancelled)
            | (UpgradePhase::EngineUnrecoverable, UpgradePhase::RepairRequired)
            | (UpgradePhase::HeadStopped, UpgradePhase::Cancelled)
            | (UpgradePhase::HeadStopped, UpgradePhase::RepairRequired)
            | (
                UpgradePhase::InstallLaunching,
                UpgradePhase::InstallLaunched
            )
            | (UpgradePhase::InstallLaunching, UpgradePhase::Cancelled)
            | (UpgradePhase::InstallLaunching, UpgradePhase::RepairRequired)
            | (UpgradePhase::InstallLaunched, UpgradePhase::Reconciling)
            | (UpgradePhase::InstallLaunched, UpgradePhase::Cancelled)
            | (UpgradePhase::InstallLaunched, UpgradePhase::RepairRequired)
            | (UpgradePhase::Reconciling, UpgradePhase::Committed)
            | (UpgradePhase::Reconciling, UpgradePhase::RepairRequired)
    );
    if !legal {
        return Err(format!(
            "Illegal durable upgrade transition {:?} -> {:?}",
            intent.phase, next
        ));
    }
    intent.phase = next;
    intent.updated_at_epoch_ms = now_epoch_ms();
    intent.error = error;
    Ok(())
}

fn read_sequence(path: &Path) -> Result<SequenceState, String> {
    if !path.exists() {
        return Ok(SequenceState {
            schema_version: 1,
            highest_accepted_sequence: 0,
        });
    }
    let state: SequenceState = read_json(path)?;
    if state.schema_version != 1 {
        return Err(format!(
            "Unsupported upgrade sequence schema version {}",
            state.schema_version
        ));
    }
    Ok(state)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Result<T, String> {
    let bytes =
        fs::read(path).map_err(|error| format!("Failed to read {}: {error}", path.display()))?;
    serde_json::from_slice(&bytes)
        .map_err(|error| format!("Failed to parse {}: {error}", path.display()))
}

fn write_bytes_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| format!("Path has no parent: {}", path.display()))?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Failed to create {}: {error}", parent.display()))?;
    let temp = path.with_extension("tmp");
    let mut file = fs::File::create(&temp)
        .map_err(|error| format!("Failed to create {}: {error}", temp.display()))?;
    file.write_all(bytes)
        .map_err(|error| format!("Failed to write {}: {error}", temp.display()))?;
    file.sync_all()
        .map_err(|error| format!("Failed to flush {}: {error}", temp.display()))?;
    drop(file);
    replace_file(&temp, path).map_err(|error| {
        let _ = fs::remove_file(&temp);
        format!("Failed to replace {}: {error}", path.display())
    })
}

fn write_json_atomic(path: &Path, value: &impl Serialize) -> Result<(), String> {
    let bytes = serde_json::to_vec_pretty(value)
        .map_err(|error| format!("Failed to serialize {}: {error}", path.display()))?;
    write_bytes_atomic(path, &bytes)
}

#[cfg(not(windows))]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    fs::rename(source, target)
}

#[cfg(windows)]
fn replace_file(source: &Path, target: &Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let source_wide: Vec<u16> = source.as_os_str().encode_wide().chain(Some(0)).collect();
    let target_wide: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let moved = unsafe {
        MoveFileExW(
            source_wide.as_ptr(),
            target_wide.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if moved == 0 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn sha256_bytes(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn persist_intent(
    app: &AppHandle,
    coordinator: &UpdateCoordinator,
    intent: &UpgradeIntent,
) -> Result<(), String> {
    persist_intent_at(&upgrade_root(app), coordinator, intent)
}

fn persist_intent_at(root: &Path, coordinator: &UpdateCoordinator, intent: &UpgradeIntent) -> Result<(), String> {
    write_json_atomic(&root.join("intent.v1.json"), intent)?;
    set_intent_status(coordinator, intent);
    Ok(())
}

fn set_state(coordinator: &UpdateCoordinator, state: &str, error: Option<String>) {
    let mut status = coordinator
        .status
        .lock()
        .expect("update status mutex poisoned");
    status.state = state.into();
    status.error = error;
    // Any state transition (including into a fresh "downloading") starts from no known progress —
    // a prior attempt's leftover bytes must not bleed into an unrelated state or a new attempt.
    status.bytes_downloaded = None;
    status.bytes_total = None;
}

/// Publishes installer-download progress onto the same polled `AppUpdateStatus` channel the
/// frontend already reads via `app_update_status` — extends the existing state instead of adding a
/// second progress representation. `total` is passed through verbatim (never defaulted to `0`) so
/// an absent `Content-Length` stays an absent total on the wire.
fn set_download_progress(coordinator: &UpdateCoordinator, downloaded: u64, total: Option<u64>) {
    let mut status = coordinator
        .status
        .lock()
        .expect("update status mutex poisoned");
    status.bytes_downloaded = Some(downloaded);
    status.bytes_total = total;
}

fn apply_intent_to_status(status: &mut AppUpdateStatus, intent: &UpgradeIntent) {
    status.state = phase_state(&intent.phase).into();
    status.available_version = Some(intent.target_version.clone());
    status.release_sequence = Some(intent.release_sequence);
    status.intent_phase = Some(intent.phase.clone());
    status.attempt_id = Some(intent.attempt_id.clone());
    status.error = intent.error.clone();
}

fn set_intent_status(coordinator: &UpdateCoordinator, intent: &UpgradeIntent) {
    let mut status = coordinator
        .status
        .lock()
        .expect("update status mutex poisoned");
    apply_intent_to_status(&mut status, intent);
}

fn ensure_no_unresolved_intent(coordinator: &UpdateCoordinator) -> Result<(), String> {
    let status = coordinator.snapshot();
    match status.intent_phase {
        Some(UpgradePhase::Committed) | Some(UpgradePhase::Cancelled) | None => Ok(()),
        Some(_) => Err(format!(
            "The previous update is unresolved (state {}); repair or reconcile it before continuing",
            status.state
        )),
    }
}

fn mark_repair_required_at(
    root: &Path,
    coordinator: &UpdateCoordinator,
    mut intent: UpgradeIntent,
    error: &str,
) -> Result<(), String> {
    transition(
        &mut intent,
        UpgradePhase::RepairRequired,
        Some(error.into()),
    )?;
    persist_intent_at(root, coordinator, &intent)
}

struct InstallGuard<'a>(&'a AtomicBool);

impl<'a> InstallGuard<'a> {
    fn begin(flag: &'a AtomicBool) -> Result<Self, String> {
        flag.compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .map_err(|_| "An update installation is already in progress".to_string())?;
        Ok(Self(flag))
    }
}

impl Drop for InstallGuard<'_> {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}

fn phase_state(phase: &UpgradePhase) -> &'static str {
    match phase {
        UpgradePhase::Prepared => "prepared",
        UpgradePhase::HeadStopped => "head_stopped",
        UpgradePhase::EngineUnrecoverable => "engine_unrecoverable",
        UpgradePhase::InstallLaunching => "install_launching",
        UpgradePhase::InstallLaunched => "install_launched",
        UpgradePhase::Reconciling => "reconciling",
        UpgradePhase::Committed => "committed",
        UpgradePhase::Cancelled => "cancelled",
        UpgradePhase::RepairRequired => "repair_required",
    }
}

fn now_epoch_ms() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn durable_phase_names_are_stable() {
        let phases = [
            UpgradePhase::Prepared,
            UpgradePhase::HeadStopped,
            UpgradePhase::EngineUnrecoverable,
            UpgradePhase::InstallLaunching,
            UpgradePhase::InstallLaunched,
            UpgradePhase::Reconciling,
            UpgradePhase::Committed,
            UpgradePhase::Cancelled,
            UpgradePhase::RepairRequired,
        ];
        let json = serde_json::to_string(&phases).unwrap();
        assert_eq!(
            json,
            r#"["PREPARED","HEAD_STOPPED","ENGINE_UNRECOVERABLE","INSTALL_LAUNCHING","INSTALL_LAUNCHED","RECONCILING","COMMITTED","CANCELLED","REPAIR_REQUIRED"]"#
        );
    }

    #[test]
    fn transition_table_rejects_skipping_launch_witness() {
        let mut intent = test_intent(UpgradePhase::HeadStopped);
        assert!(transition(&mut intent, UpgradePhase::InstallLaunched, None)
            .unwrap_err()
            .contains("Illegal durable upgrade transition"));
    }

    #[test]
    fn shutdown_receipt_is_nonce_and_preparation_bound() {
        let mut receipt = test_receipt();
        validate_shutdown_receipt(&receipt, "prep-1", &"n".repeat(32)).unwrap();
        receipt.shutdown_nonce = "x".repeat(32);
        assert!(validate_shutdown_receipt(&receipt, "prep-1", &"n".repeat(32)).is_err());
    }

    #[test]
    fn final_shutdown_receipt_is_nonce_and_process_bound() {
        let receipt = test_head_receipt();
        validate_head_shutdown_receipt(&receipt, "prep-1", &"n".repeat(32), 4242).unwrap();
        assert!(validate_head_shutdown_receipt(&receipt, "prep-1", &"n".repeat(32), 7).is_err());
    }

    #[test]
    fn concrete_target_oracle_commits_exact_owner_set() {
        let request = test_reconciliation_request();
        let response = test_reconciliation_response();
        validate_reconciliation_response(&request, &response).unwrap();
        let mut intent = test_intent(UpgradePhase::Reconciling);
        apply_reconciliation_outcome(&mut intent, Ok(())).unwrap();
        assert_eq!(intent.phase, UpgradePhase::Committed);
    }

    #[test]
    fn dead_reconciliation_requires_kind_and_attempt_echo_without_nonce() {
        let mut request = test_reconciliation_request();
        request.stop_evidence_kind = "ENGINE_UNRECOVERABLE".into();
        request.shutdown_nonce.clear();
        let mut response = test_reconciliation_response();
        response.stop_evidence_kind = request.stop_evidence_kind.clone();
        response.shutdown_nonce.clear();
        validate_reconciliation_response(&request, &response).unwrap();
        response.stop_evidence_kind = "PREPARED".into();
        assert!(validate_reconciliation_response(&request, &response).is_err());
        response.stop_evidence_kind = request.stop_evidence_kind.clone();
        response.shutdown_nonce = "invented".into();
        assert!(validate_reconciliation_response(&request, &response).is_err());
        response.shutdown_nonce.clear();
        response.attempt_id = "other-attempt".into();
        assert!(validate_reconciliation_response(&request, &response).is_err());
    }

    #[test]
    fn prepared_witness_cannot_smuggle_dead_engine_evidence() {
        let mixed = serde_json::json!({"kind": "PREPARED", "enginePid": 42});
        assert!(serde_json::from_value::<StopEvidence>(mixed).is_err());
    }

    #[test]
    fn head_unavailable_reconciliation_requires_repair() {
        let mut intent = test_intent(UpgradePhase::Reconciling);
        apply_reconciliation_outcome(&mut intent, Err("Target Head is unavailable".into()))
            .unwrap();
        assert_eq!(intent.phase, UpgradePhase::RepairRequired);
        assert!(intent.error.unwrap().contains("unavailable"));
    }

    #[test]
    fn target_oracle_rejects_owner_set_mismatch() {
        let request = test_reconciliation_request();
        let mut response = test_reconciliation_response();
        response.owners[0].format_version += 1;
        assert!(validate_reconciliation_response(&request, &response)
            .unwrap_err()
            .contains("differ"));
    }

    #[test]
    fn stage_rehash_detects_tampering_and_path_escape() {
        let dir = tempdir().unwrap();
        let root = dir.path().join("staged");
        let attempt = root.join("attempt");
        fs::create_dir_all(&attempt).unwrap();
        let path = attempt.join("installer.exe");
        fs::write(&path, b"MZsafe").unwrap();
        let staged = StagedArtifact {
            path: path.to_string_lossy().into_owned(),
            sha256: sha256_bytes(b"MZsafe"),
            size: 6,
        };
        validate_staged_artifact(&staged, &root).unwrap();
        fs::write(&path, b"MZevil").unwrap();
        assert!(validate_staged_artifact(&staged, &root)
            .unwrap_err()
            .contains("post-write"));

        let outside = dir.path().join("outside.exe");
        fs::write(&outside, b"MZsafe").unwrap();
        let escaped = StagedArtifact {
            path: outside.to_string_lossy().into_owned(),
            ..staged
        };
        assert!(validate_staged_artifact(&escaped, &root)
            .unwrap_err()
            .contains("escaped"));
    }

    #[test]
    fn prepared_source_boot_is_durably_cancelled_not_deleted() {
        let fixture = evidence_fixture(UpgradePhase::Prepared);
        let mut intent = fixture.intent;
        let reconciled = reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.0.0",
            intent.clone(),
        )
        .unwrap();
        intent.phase = UpgradePhase::Cancelled;
        assert_eq!(reconciled.phase, intent.phase);
        assert!(fixture.intent_path.exists());
    }

    #[test]
    fn target_equality_only_advances_to_reconciling() {
        let fixture = evidence_fixture(UpgradePhase::InstallLaunched);
        let reconciled = reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.1.0",
            fixture.intent,
        )
        .unwrap();
        assert_eq!(reconciled.phase, UpgradePhase::Reconciling);
    }

    #[test]
    fn install_launching_boot_is_ambiguous_and_requires_repair() {
        let fixture = evidence_fixture(UpgradePhase::InstallLaunching);
        let reconciled = reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.0.0",
            fixture.intent,
        )
        .unwrap();
        assert_eq!(reconciled.phase, UpgradePhase::RepairRequired);
    }

    #[test]
    fn committed_terminal_state_survives_staged_payload_cleanup() {
        let fixture = evidence_fixture(UpgradePhase::Committed);
        fs::remove_file(&fixture.intent.staged_artifact.path).unwrap();
        fs::remove_file(&fixture.witness_path).unwrap();
        let reconciled = reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.1.0",
            fixture.intent,
        )
        .unwrap();
        assert_eq!(reconciled.phase, UpgradePhase::Committed);
    }

    #[test]
    fn cancelled_terminal_state_allows_future_update_checks() {
        let coordinator = UpdateCoordinator::default();
        coordinator.status.lock().unwrap().intent_phase = Some(UpgradePhase::Cancelled);
        assert!(ensure_no_unresolved_intent(&coordinator).is_ok());
    }

    #[test]
    fn witness_must_match_separate_durable_copy() {
        let fixture = evidence_fixture(UpgradePhase::InstallLaunched);
        let mut witness: InstallerLaunchWitness = read_json(&fixture.witness_path).unwrap();
        witness.process_id += 1;
        write_json_atomic(&fixture.witness_path, &witness).unwrap();
        assert!(reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.1.0",
            fixture.intent,
        )
        .is_err());
    }

    #[test]
    fn the_sandbox_gate_is_compile_time_not_runtime() {
        // The safety argument for the qualification autorun and the loopback allowance is that
        // both are compile-gated. Written to hold in BOTH build modes: asserting "the gate is off"
        // outright would fail the suite for the qualification build itself, which still has to be
        // testable. What must never vary is that the environment cannot flip the gate.
        let before = sandbox_test_mode();
        std::env::set_var("JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE", "1");
        let after = sandbox_test_mode();
        std::env::remove_var("JUSTSEARCH_RELEASE_SANDBOX_TEST_MODE");
        assert_eq!(
            before, after,
            "setting the variable at runtime must not change a gate resolved at compile time"
        );

        // The transport allowance tracks the gate exactly - never wider.
        assert_eq!(
            ensure_https("http://127.0.0.1:8765/release.v1.json").is_ok(),
            sandbox_test_mode(),
            "loopback HTTP is permitted if and only if this build was compiled for qualification"
        );
        // Non-loopback plaintext is refused in every build, gated or not.
        assert!(ensure_https("http://example.com/release.v1.json").is_err());
        assert!(ensure_https("https://example.com/release.v1.json").is_ok());
    }

    #[test]
    fn qualification_reports_pass_only_from_committed() {
        assert_eq!(
            qualification_step(Some(UpgradePhase::Committed)),
            QualificationStep::Report(
                "PASS",
                "Update applied and reconciled to COMMITTED".to_string()
            )
        );
    }

    #[test]
    fn qualification_attempts_only_when_no_intent_exists() {
        assert_eq!(qualification_step(None), QualificationStep::Attempt);
    }

    #[test]
    fn qualification_never_retries_over_an_existing_intent() {
        // Re-attempting from a non-terminal phase would stack a second update on an unresolved
        // one; re-attempting from a terminal failure would overwrite the evidence being captured.
        for phase in [
            UpgradePhase::Prepared,
            UpgradePhase::HeadStopped,
            UpgradePhase::InstallLaunching,
            UpgradePhase::InstallLaunched,
            UpgradePhase::Reconciling,
            UpgradePhase::RepairRequired,
            UpgradePhase::Cancelled,
        ] {
            match qualification_step(Some(phase.clone())) {
                QualificationStep::Report(verdict, _) => {
                    assert_eq!(verdict, "FAIL", "phase {phase:?} must not report PASS")
                }
                QualificationStep::Attempt => {
                    panic!("phase {phase:?} must not start a second attempt")
                }
            }
        }
    }

    #[test]
    fn head_receipt_must_match_independently_recorded_pid() {
        let fixture = evidence_fixture(UpgradePhase::InstallLaunched);
        let mut intent = fixture.intent;
        // An internally consistent receipt that describes a different process than the one this
        // attempt actually prepared. Validating a receipt against its own head_pid accepts this;
        // only the separately recorded intent.head_pid can reject it.
        let mut receipt = intent.head_shutdown_receipt.clone().unwrap();
        receipt.head_pid += 1;
        intent.head_shutdown_receipt = Some(receipt);
        assert!(reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.1.0",
            intent,
        )
        .is_err());
    }

    #[test]
    fn receipt_bearing_phase_without_recorded_pid_cannot_reconcile() {
        let fixture = evidence_fixture(UpgradePhase::InstallLaunched);
        let mut intent = fixture.intent;
        intent.head_pid = None;
        assert!(reconcile_intent(
            &fixture.intent_path,
            &fixture.witness_path,
            &fixture.staging_root,
            "1.1.0",
            intent,
        )
        .is_err());
    }

    #[test]
    fn strategy_aware_compatibility_rejects_role_change() {
        let local: LocalStoreRegister = serde_json::from_str(LOCAL_STORE_REGISTER).unwrap();
        let compatibility = local
            .durable_stores
            .iter()
            .map(|store| ReleaseStoreCompatibility {
                owner_id: store.id.clone(),
                owner: store.owner.clone(),
                role: store.recoverability.clone(),
                format_version: store.current_version,
                readable_source_versions: vec![store.current_version],
                reconciliation_strategy: store.reconciliation.clone(),
            })
            .collect();
        let mut descriptor = test_descriptor(compatibility);
        descriptor.compatibility[0].role = "DERIVED".into();
        assert!(validate_store_compatibility(&descriptor)
            .unwrap_err()
            .contains("ownership or recovery strategy"));
    }

    #[test]
    fn sequence_state_refuses_unknown_schema() {
        let dir = tempdir().unwrap();
        let path = dir.path().join("sequence.v1.json");
        fs::write(&path, r#"{"schemaVersion":2,"highestAcceptedSequence":7}"#).unwrap();
        assert!(read_sequence(&path)
            .unwrap_err()
            .contains("Unsupported upgrade sequence"));
    }

    struct EvidenceFixture {
        intent: UpgradeIntent,
        intent_path: PathBuf,
        witness_path: PathBuf,
        staging_root: PathBuf,
    }

    fn evidence_fixture(phase: UpgradePhase) -> EvidenceFixture {
        let directory = tempfile::Builder::new()
            .prefix("justsearch-updater-test")
            .tempdir()
            .unwrap()
            .keep();
        let staging_root = directory.join("staged");
        let staged_path = staging_root.join("attempt-1").join("installer.exe");
        fs::create_dir_all(staged_path.parent().unwrap()).unwrap();
        fs::write(&staged_path, b"MZfixture").unwrap();
        let staged = StagedArtifact {
            path: staged_path.to_string_lossy().into_owned(),
            sha256: sha256_bytes(b"MZfixture"),
            size: 9,
        };
        let witness = InstallerLaunchWitness {
            schema_version: 1,
            attempt_id: "attempt-1".into(),
            process_id: 42,
            launched_at_epoch_ms: 11,
            staged_path: staged.path.clone(),
            staged_sha256: staged.sha256.clone(),
            staged_size: staged.size,
        };
        let mut intent = test_intent(phase.clone());
        intent.staged_artifact = staged;
        if matches!(
            phase,
            UpgradePhase::HeadStopped
                | UpgradePhase::InstallLaunching
                | UpgradePhase::InstallLaunched
                | UpgradePhase::Reconciling
                | UpgradePhase::Committed
        ) {
            intent.shutdown_receipt = Some(test_receipt());
            intent.head_shutdown_receipt = Some(test_head_receipt());
            intent.head_pid = Some(test_head_receipt().head_pid);
        }
        if matches!(
            phase,
            UpgradePhase::InstallLaunched | UpgradePhase::Reconciling | UpgradePhase::Committed
        ) {
            intent.launch_witness = Some(witness.clone());
        }
        let intent_path = directory.join("intent.v1.json");
        let witness_path = directory.join("witness.v1.json");
        write_json_atomic(&intent_path, &intent).unwrap();
        write_json_atomic(&witness_path, &witness).unwrap();
        EvidenceFixture {
            intent,
            intent_path,
            witness_path,
            staging_root,
        }
    }

    fn test_intent(phase: UpgradePhase) -> UpgradeIntent {
        UpgradeIntent {
            stop_evidence: StopEvidence::Prepared {},
            schema_version: 1,
            phase,
            attempt_id: "attempt-1".into(),
            preparation_id: "prep-1".into(),
            shutdown_nonce: "n".repeat(32),
            shutdown_receipt: None,
            head_shutdown_receipt: None,
            head_pid: None,
            staged_artifact: StagedArtifact {
                path: "unused".into(),
                sha256: "0".repeat(64),
                size: 1,
            },
            launch_witness: None,
            owner_expectations: vec![OwnerExpectation {
                owner_id: "preferences".into(),
                format_version: 1,
            }],
            source_version: "1.0.0".into(),
            target_version: "1.1.0".into(),
            release_sequence: 3,
            updated_at_epoch_ms: 10,
            error: None,
        }
    }

    fn test_receipt() -> ShutdownReceipt {
        ShutdownReceipt {
            schema_version: 1,
            preparation_id: "prep-1".into(),
            shutdown_nonce: "n".repeat(32),
            shutdown_accepted: true,
            admission_frozen: true,
            active_lease_count: 0,
            issued_at_epoch_ms: 10,
        }
    }

    fn test_head_receipt() -> HeadShutdownReceipt {
        HeadShutdownReceipt {
            schema_version: 1,
            preparation_id: "prep-1".into(),
            shutdown_nonce: "n".repeat(32),
            head_pid: 4242,
            clean: true,
            worker_outcome: "GRACEFUL".into(),
            errors: Vec::new(),
            completed_at: "2026-07-31T00:00:00Z".into(),
        }
    }

    fn test_reconciliation_request() -> ReconciliationRequest {
        ReconciliationRequest {
            schema_version: 1,
            stop_evidence_kind: prepared_evidence_kind(),
            attempt_id: "attempt-1".into(),
            shutdown_nonce: "n".repeat(32),
            source_version: "1.0.0".into(),
            target_version: "1.1.0".into(),
            release_sequence: 3,
            head_pid: 4242,
            owners: vec![OwnerExpectation {
                owner_id: "preferences".into(),
                format_version: 1,
            }],
        }
    }

    fn test_reconciliation_response() -> ReconciliationResponse {
        ReconciliationResponse {
            schema_version: 1,
            stop_evidence_kind: prepared_evidence_kind(),
            attempt_id: "attempt-1".into(),
            shutdown_nonce: "n".repeat(32),
            target_version: "1.1.0".into(),
            head_pid: 4242,
            ready: true,
            head_ready: true,
            worker_ready: true,
            owners: vec![OwnerHealth {
                owner_id: "preferences".into(),
                format_version: 1,
                healthy: true,
            }],
        }
    }

    fn test_descriptor(compatibility: Vec<ReleaseStoreCompatibility>) -> ReleaseDescriptor {
        ReleaseDescriptor {
            schema_version: 1,
            sequence: 3,
            version: "1.1.0".into(),
            channel: "stable".into(),
            target: "windows-x86_64".into(),
            metadata_key_id: "metadata-test".into(),
            metadata_root_policy: "OFFLINE_LONG_LIVED_V1".into(),
            artifact: ReleaseArtifact {
                url: "https://updates.example/installer.exe".into(),
                sha256: "0".repeat(64),
                size: 1,
                signature: "signature".into(),
                public_key: "public-key".into(),
            },
            compatibility,
        }
    }
}

#[cfg(test)]
#[path = "updater_handoff_tests.rs"]
mod handoff_tests;
