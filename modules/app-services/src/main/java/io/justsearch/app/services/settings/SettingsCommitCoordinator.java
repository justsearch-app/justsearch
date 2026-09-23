/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationReceipt;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.executor.RecordedInstallerGenerationPlanResolver;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigApplyScopes;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/** One physical settings-file/config owner; the operation runner alone terminalizes its row. */
public final class SettingsCommitCoordinator implements SettingsCommitOwner {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SettingsCommitCoordinator.class);

  /** Boot's exact file-only roll-forward before a ConfigStore or native model can be constructed. */
  public static UiSettings rollForwardInstallerBoot(UiSettingsStore store, UiSettings candidate,
      SettingsWitness successor, RecordedInstallerGenerationPlan plan) throws IOException {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(successor, "successor");
    Objects.requireNonNull(plan, "plan");
    var prepared = store.prepareExact(candidate, successor);
    try { store.replacePrepared(prepared); }
    catch (IOException | RuntimeException moveFailure) {
      try {
        var after = store.inspect();
        if (!successor.equals(after.witness())) {
          throw new IOException("Committed installer generation settings roll-forward failed", moveFailure);
        }
        requireAcceptedInstallerSettings(plan, after.settings());
      } catch (RuntimeException unreadable) {
        throw new IOException("Committed installer generation settings roll-forward is uncertain", unreadable);
      }
    }
    var after = store.inspect();
    if (!successor.equals(after.witness())) {
      throw new IOException("Committed installer generation settings roll-forward lost its witness");
    }
    requireAcceptedInstallerSettings(plan, after.settings());
    return after.settings();
  }

  /** The already-moved boot branch must match the accepted full candidate exactly. */
  public static void requireAcceptedInstallerSettings(RecordedInstallerGenerationPlan plan,
      UiSettings settings) throws IOException {
    try {
      var actual = RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
          tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(settings));
      if (!plan.candidateSettings().equals(actual)) {
        throw new IOException("Installer generation successor settings differ from the accepted candidate");
      }
    } catch (RuntimeException invalid) {
      throw new IOException("Installer generation successor settings are unreadable", invalid);
    }
  }

  @FunctionalInterface
  interface Replacement {
    void replace(UiSettingsStore.PreparedSettings prepared) throws IOException;
  }

  private enum Phase { PREPARING, COMMITTED, UNCERTAIN }

  private static final class SettingsCommitFence implements Reservation {
    private final long id;
    private final String key;
    private final SettingsWitness prior;
    private final UiSettings priorSettings;
    private Phase phase = Phase.PREPARING;
    private boolean preparationStarted;
    private boolean restartRequired;
    private final String quarantineFingerprint;
    private final boolean reset;
    @Override public long expectedRevision() { return prior.acceptedRevision(); }
    private boolean recoveryReset() { return quarantineFingerprint != null; }

    private SettingsCommitFence(long id, String key, SettingsWitness prior) {
      this(id, key, prior, null, null, false);
    }
    private SettingsCommitFence(long id, String key, SettingsWitness prior,
        UiSettings priorSettings, String fingerprint, boolean reset) {
      this.id = id; this.key = key; this.prior = prior;
      this.priorSettings = priorSettings;
      this.quarantineFingerprint = fingerprint; this.reset = reset;
    }
  }

  private final UiSettingsStore store;
  private final ConfigStore config;
  private final Runnable restart;
  private final Function<UiSettings, ResolvedConfig> prepareConfig;
  private final Function<UiSettings, OperationResult> prepareResponse;
  private final Replacement replacement;
  private final BooleanSupplier processClosing;
  private final SettingsComponentComposer components;
  private final ReentrantLock mutex = new ReentrantLock();
  private final CompletableFuture<RecoveryIssue> recoveryIssue = new CompletableFuture<>();
  private volatile SettingsCommitFence fence;
  private volatile RecoveryIssue issue;
  private boolean inspected;
  private boolean blocked;
  private boolean restartIssued;
  private Long recoveredId;
  private OperationAttemptRunner.Reconciliation recoveredDecision;
  private io.justsearch.app.api.settings.SettingsCandidateContext recoveredContext;

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse) {
    this(store, config, restart, prepareResponse, () -> false);
  }

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse, BooleanSupplier processClosing) {
    this(store, config, restart, prepareResponse, processClosing, unavailableComponents());
  }

  public SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, OperationResult> prepareResponse, BooleanSupplier processClosing,
      SettingsComponentComposer components) {
    this(store, config, restart, ConfigStoreRebuilder::prepare, prepareResponse,
        store::replacePrepared, processClosing, components);
  }

  /** Fault seams stay package-private; production always uses the strict store replacement. */
  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement) {
    this(store, config, restart, prepareConfig, prepareResponse, replacement, () -> false);
  }

  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement,
      BooleanSupplier processClosing) {
    this(store, config, restart, prepareConfig, prepareResponse, replacement, processClosing,
        unavailableComponents());
  }

  SettingsCommitCoordinator(UiSettingsStore store, ConfigStore config, Runnable restart,
      Function<UiSettings, ResolvedConfig> prepareConfig,
      Function<UiSettings, OperationResult> prepareResponse, Replacement replacement,
      BooleanSupplier processClosing, SettingsComponentComposer components) {
    this.store = Objects.requireNonNull(store, "store");
    this.config = Objects.requireNonNull(config, "config");
    this.restart = Objects.requireNonNull(restart, "restart");
    this.prepareConfig = Objects.requireNonNull(prepareConfig, "prepareConfig");
    this.prepareResponse = Objects.requireNonNull(prepareResponse, "prepareResponse");
    this.replacement = Objects.requireNonNull(replacement, "replacement");
    this.processClosing = Objects.requireNonNull(processClosing, "processClosing");
    this.components = Objects.requireNonNull(components, "components");
  }

  private static SettingsComponentComposer unavailableComponents() {
    return (candidate, desired, affected) -> {
      throw refused("COMPONENT_PREPARATION_REQUIRED",
          "The affected runtime components cannot yet be committed atomically",
          Map.of("components", affected));
    };
  }

  @Override public CompletionStage<RecoveryIssue> recoveryIssue() { return recoveryIssue.minimalCompletionStage(); }

  @Override
  public Reservation reserve(long id, String key, SettingsWitness expected) {
    if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    try {
      return reserveOwned(id, key, expected, false);
    } finally {
      mutex.unlock();
      publishIssue();
    }
  }

  /** Shared reservation checks; both public entry points hold the physical mutex. */
  private SettingsCommitFence reserveOwned(long id, String key, SettingsWitness expected, boolean reset) {
    if (!inspected || blocked) throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings recovery is unresolved", Map.of());
    // Keep the existing in-progress precedence for all contenders. Only the armed installer
    // row recovered at boot may proceed to the exact-witness check below.
    if (fence != null && (reset || !Objects.equals(recoveredId, id) || fence.id != id
        || !fence.key.equals(key) || fence.phase != Phase.PREPARING
        || fence.preparationStarted)) {
      throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    }
    if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled", Map.of());
    OperationKeys.timestampMillis(key);
    Objects.requireNonNull(expected, "expected settings witness");
    if (id <= 0 || expected.acceptedRevision() == Long.MAX_VALUE) {
      throw new IllegalArgumentException("Invalid settings reservation");
    }
    final UiSettingsStore.Snapshot snapshot;
    try { snapshot = store.inspect(); }
    catch (RuntimeException failure) {
      block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, id));
      throw refused("SETTINGS_RECOVERY_REQUIRED", "The settings revision cannot be verified", Map.of());
    }
    SettingsWitness prior = snapshot.witness();
    if (!prior.equals(expected)) {
      throw refused("VERSION_CONFLICT", "Settings changed since this candidate was read",
          Map.of("currentRevision", prior.acceptedRevision()));
    }
    // Recovery reserves an armed installer row before the runner resumes it. Reuse that
    // reservation only for the exact same row and source witness; another operation must
    // still be refused, and a successor settings witness cannot be re-prepared as A.
    if (fence != null) {
      if (fence.prior.equals(prior)) {
        return fence;
      }
      throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    }
    var reserved = new SettingsCommitFence(id, key, prior, snapshot.settings(), null, reset);
    fence = reserved;
    return reserved;
  }

  @Override
  public Reservation reserveReset(OperationRecord row, OperationStore.Preparation accepted) {
    var intent = SettingsResetPreparation.decode(row, accepted);
    if (!intent.recovery()) {
      // The common checks create the final reset fence once under this mutex.
      if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
      try {
        return reserveOwned(row.id(), row.key(), intent.expected(), true);
      } finally { mutex.unlock(); publishIssue(); }
    }
    if (!mutex.tryLock()) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
    try {
      if (!inspected || recoveredId != null
          || (blocked && (issue == null || issue.reason() != RecoveryReason.UNREADABLE_WITNESS))) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings recovery is unresolved", Map.of());
      }
      if (fence != null) throw refused("RECONFIGURE_IN_PROGRESS", "Another settings transaction is active", Map.of());
      if (!store.mode().isWritable()) throw refused("SETTINGS_READ_ONLY", "Settings persistence is disabled", Map.of());
      if (row.id() <= 0) throw new IllegalArgumentException("Invalid settings reservation");
      if (!matchesQuarantine(intent.quarantineFingerprint())) {
        throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings quarantine evidence changed", Map.of());
      }
      block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, row.id()));
      fence = new SettingsCommitFence(row.id(), row.key(), new SettingsWitness(0, null),
          null, intent.quarantineFingerprint(), true);
      return fence;
    } finally { mutex.unlock(); publishIssue(); }
  }

  @Override
  public void verifyCandidatePreparation(OperationRecord row,
      java.util.Optional<OperationStore.Preparation> accepted,
      io.justsearch.app.api.settings.SettingsCandidateContext context) {
    Objects.requireNonNull(context, "candidate context");
    boolean preparedRow = SettingsCandidatePreparation.OPERATION_REF.equals(row.descriptor().operationRef());
    if (preparedRow != !io.justsearch.app.api.settings.SettingsCandidateContext.NONE.equals(context)) {
      throw new IllegalArgumentException("Settings candidate intent does not match its accepted row");
    }
    if (preparedRow && !context.equals(SettingsCandidatePreparation.decode(row, accepted.orElseThrow()))) {
      throw new IllegalArgumentException("Settings candidate preparation changed after acceptance");
    }
  }

  @Override
  public void verifyInstallerGenerationPreparation(OperationRecord row,
      java.util.Optional<OperationStore.Preparation> accepted,
      RecordedInstallerGenerationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    RecordedInstallerGenerationPlan exact = new RecordedInstallerGenerationPlanResolver()
        .resolve(row, accepted.orElseThrow(
            () -> new IllegalArgumentException("Installer activation preparation disappeared")));
    if (!exact.equals(plan)) {
      throw new IllegalArgumentException("Installer activation settings differ from the accepted row");
    }
  }

  @Override
  public PreparedGenerationProjection prepareInstallerGenerationProjection(Reservation reservation,
      UiSettings candidate, AttemptControl control) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(control, "control");
    SettingsComponentComposer.Prepared preparedComponents = null;
    mutex.lock();
    try {
      SettingsCommitFence active = requireFence(reservation);
      if (active.reset || active.preparationStarted) {
        throw new IllegalStateException("Installer generation settings preparation already started");
      }
      active.preparationStarted = true;
      var successor = new SettingsWitness(Math.addExact(active.prior.acceptedRevision(), 1), active.key);
      // Exact bytes matter: the accepted generation plan already froze this detached settings
      // candidate, including ordinary settings that accompanied the model paths.
      var preparedSettings = store.prepareExact(candidate, successor);
      ResolvedConfig desired = Objects.requireNonNull(prepareConfig.apply(preparedSettings.settings()),
          "Prepared installer configuration");
      ResolvedConfig serving = config.get();
      var changed = ConfigApplyScopes.classify(serving, desired);
      if (!changed.restartRequired().isEmpty()
          && !java.util.Set.of("justsearch.api.port").containsAll(changed.restartRequired())) {
        throw refused("RESTART_SOURCE_DRIFT", "A process source changed during model activation",
            Map.of("keys", List.copyOf(changed.restartRequired())));
      }
      boolean restartRequired = !changed.restartRequired().isEmpty();
      ResolvedConfig published = restartRequired ? desired.retainingApiPortFrom(serving) : desired;
      if (!changed.component().isEmpty()) {
        preparedComponents = Objects.requireNonNull(components.prepare(preparedSettings.settings(),
            desired, changed.component()), "Prepared installer components");
      }
      ConfigStore.PreparedSwap preparedConfig = config.prepareSwap(published);
      OperationResult response = prepareResponse.apply(preparedSettings.settings());
      if (restartRequired) response = withRestartScheduled(response);
      var receipt = new Receipt(active.key, successor.acceptedRevision(), response);
      return new InstallerGenerationProjection(active, control, preparedSettings, preparedConfig,
          preparedComponents, receipt, restartRequired);
    } catch (RuntimeException | Error failure) {
      if (preparedComponents != null) {
        try { preparedComponents.abort(); }
        catch (RuntimeException | Error cleanup) {
          control.uncertain();
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    } finally { mutex.unlock(); publishIssue(); }
  }

  @Override public boolean installerGenerationProjected(RecordedInstallerGenerationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    try {
      var snapshot = store.inspect();
      var expected = new SettingsWitness(
          Math.addExact(plan.settingsWitness().acceptedRevision(), 1), plan.operationKey());
      if (!expected.equals(snapshot.witness())) return false;
      var actual = RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
          tools.jackson.databind.json.JsonMapper.builder().build()
              .writeValueAsString(snapshot.settings()));
      return plan.candidateSettings().equals(actual);
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  // The fixed settings owner alone reports this composite projection to the runner. Keeping
  // these calls on the outer owner also preserves the architecture gate for nested helpers.
  private void committedInstallerGeneration(AttemptControl control, Receipt receipt) {
    control.committed(receipt);
  }

  private void uncertainInstallerGeneration(AttemptControl control) {
    control.uncertain();
  }

  private final class InstallerGenerationProjection implements PreparedGenerationProjection {
    private final SettingsCommitFence active;
    private final AttemptControl control;
    private final UiSettingsStore.PreparedSettings settings;
    private final ConfigStore.PreparedSwap configSwap;
    private final SettingsComponentComposer.Prepared componentSwap;
    private final Receipt receipt;
    private final boolean restartRequired;
    private boolean admitted;
    private boolean committed;
    private boolean retired;

    private InstallerGenerationProjection(SettingsCommitFence active, AttemptControl control,
        UiSettingsStore.PreparedSettings settings, ConfigStore.PreparedSwap configSwap,
        SettingsComponentComposer.Prepared componentSwap, Receipt receipt, boolean restartRequired) {
      this.active = active;
      this.control = control;
      this.settings = settings;
      this.configSwap = configSwap;
      this.componentSwap = componentSwap;
      this.receipt = receipt;
      this.restartRequired = restartRequired;
    }

    @Override public void withOwnerLocks(Runnable publication) {
      if (componentSwap == null) publication.run();
      else componentSwap.withOwnerLocks(publication);
    }

    @Override public void admitBeforePointer() {
      // The caller holds the shared publication writer after its runtime and generation guards.
      // The logical reservation excludes other official settings commits throughout this cut.
      if (active != fence || active.phase != Phase.PREPARING || admitted || processClosing.getAsBoolean()) {
        throw refused("ENGINE_CLOSING", "Model activation settings can no longer commit", Map.of());
      }
      SettingsWitness current = store.inspect().witness();
      if (!active.prior.equals(current)) {
        throw refused("VERSION_CONFLICT", "Settings changed before model activation",
            Map.of("currentRevision", current.acceptedRevision()));
      }
      config.validatePrepared(configSwap);
      if (componentSwap != null) componentSwap.validate();
      if (!control.admitCommit(receipt)) {
        throw new CancellationException("Model activation was cancelled before pointer commitment");
      }
      admitted = true;
    }

    @Override public void afterPointerCommitted() throws IOException {
      if (!admitted || committed || active != fence) {
        throw new IllegalStateException("Installer projection has no admitted pointer commitment");
      }
      try { replacement.replace(settings); }
      catch (IOException | RuntimeException moveFailure) {
        final UiSettingsStore.Snapshot observed;
        try { observed = store.inspect(); }
        catch (RuntimeException unreadable) {
          uncertainInstallerGeneration(control);
          throw new IOException("Committed generation has unreadable settings projection", unreadable);
        }
        final boolean exactCandidate;
        try {
          var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
          var observedCandidate = RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
              mapper.writeValueAsString(observed.settings()));
          var preparedCandidate = RecordedInstallerGenerationPlan.CandidateSettings.fromJson(
              mapper.writeValueAsString(settings.settings()));
          exactCandidate = preparedCandidate.equals(observedCandidate);
        } catch (RuntimeException invalid) {
          uncertainInstallerGeneration(control);
          throw new IOException("Committed generation has unreadable settings candidate", invalid);
        }
        if (!receipt.operationKey().equals(observed.witness().lastCommittedOperationKey())
            || observed.witness().acceptedRevision() != receipt.acceptedRevision()
            || !exactCandidate) {
          uncertainInstallerGeneration(control);
          throw new IOException("Committed generation awaits exact settings roll-forward", moveFailure);
        }
        // An atomic move may report failure after it has installed the exact accepted candidate.
      }
      committed = true;
      active.phase = Phase.COMMITTED;
      active.restartRequired = restartRequired;
      committedInstallerGeneration(control, receipt);
      try {
        config.installPrepared(configSwap);
        if (componentSwap != null) componentSwap.install();
      } catch (RuntimeException | Error publicationFailure) {
        uncertainInstallerGeneration(control);
        throw publicationFailure;
      }
    }

    @Override public void afterRuntimePublished() {
      if (!committed || retired) return;
      Throwable notificationFailure = null;
      try {
        config.notifyListeners(configSwap.event());
        if (componentSwap != null) componentSwap.notifyObservers();
      } catch (RuntimeException | Error failure) {
        notificationFailure = failure;
      }
      if (componentSwap != null) {
        try { componentSwap.retire(); }
        catch (RuntimeException | Error retirementFailure) {
          uncertainInstallerGeneration(control);
          if (notificationFailure == null) notificationFailure = retirementFailure;
          else notificationFailure.addSuppressed(retirementFailure);
        }
      }
      if (notificationFailure == null) retired = true;
      if (notificationFailure instanceof RuntimeException runtime) throw runtime;
      if (notificationFailure instanceof Error error) throw error;
    }

    @Override public void abortBeforePointer() {
      if (committed) {
        throw new IllegalStateException("Committed generation projection cannot be abandoned");
      }
      // The generation owner calls this only after its strict promotion witness proves A stayed
      // unchanged. Admission alone cannot turn that proven precommit failure into commitment.
      if (componentSwap != null) componentSwap.abort();
    }
  }

  @Override
  public void applyReset(Reservation reservation, AttemptControl control) {
    applyOwned(reservation, null, control, true,
        io.justsearch.app.api.settings.SettingsCandidateContext.NONE);
  }

  @Override
  public void apply(Reservation reservation, UiSettings candidate, AttemptControl control) {
    applyOwned(reservation, candidate, control, false,
        io.justsearch.app.api.settings.SettingsCandidateContext.NONE);
  }

  @Override
  public void apply(Reservation reservation, UiSettings candidate, AttemptControl control,
      io.justsearch.app.api.settings.SettingsCandidateContext candidateContext) {
    applyOwned(reservation, candidate, control, false,
        Objects.requireNonNull(candidateContext, "candidateContext"));
  }

  private void applyOwned(Reservation reservation, UiSettings candidate, AttemptControl control,
      boolean reset, io.justsearch.app.api.settings.SettingsCandidateContext candidateContext) {
    Objects.requireNonNull(control, "control");
    ConfigChangedEvent[] event = new ConfigChangedEvent[1];
    SettingsComponentComposer.Prepared preparedComponents = null;
    boolean[] committed = {false};
    Throwable failed = null;
    mutex.lock();
    try {
      SettingsCommitFence active = requireFence(reservation);
      if (active.reset != reset) throw new IllegalArgumentException("Settings reservation purpose mismatch");
      if (active.preparationStarted) throw new IllegalStateException("Settings preparation already started");
      active.preparationStarted = true;
      if (reset) {
        if (active.recoveryReset()) {
          if (!matchesQuarantine(active.quarantineFingerprint)) {
            throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings quarantine evidence changed", Map.of());
          }
          candidate = new UiSettings();
        } else {
          final UiSettingsStore.Snapshot snapshot;
          try { snapshot = store.inspect(); }
          catch (RuntimeException unreadable) {
            block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, active.id));
            throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings witness became unreadable", Map.of());
          }
          if (!active.prior.equals(snapshot.witness())) {
            throw refused("VERSION_CONFLICT", "Settings changed since reset was prepared", Map.of());
          }
          candidate = snapshot.settings();
          SettingsResetDefaults.applyTo(candidate);
        }
      }
      var next = new SettingsWitness(Math.addExact(active.prior.acceptedRevision(), 1), active.key);
      var prepared = store.prepare(candidate, next);
      // A recovery reset starts from quarantined bytes with no readable prior settings.
      // Its committed defaults are applied by the already-required successor boot.
      boolean chatComponentChanged = !active.recoveryReset() && !Objects.equals(
          active.priorSettings.getChatEnabled(), prepared.settings().getChatEnabled());
      ResolvedConfig resolved = Objects.requireNonNull(prepareConfig.apply(prepared.settings()), "Prepared config");
      ResolvedConfig serving = config.get();
      var changedKeys = active.recoveryReset()
          ? new ConfigApplyScopes.ChangedKeys(java.util.Set.of(), Map.of(),
              java.util.Set.of(), java.util.Set.of())
          : ConfigApplyScopes.classify(serving, resolved);
      if (!changedKeys.generationBound().isEmpty()) {
        throw refused("GENERATION_BOUND_REQUIRES_REINDEX",
            "Generation-bound settings require a separate reindex operation",
            Map.of("keys", List.copyOf(changedKeys.generationBound()),
                "operation", "core.bulk-reindex"));
      }
      boolean restartRequired = !changedKeys.restartRequired().isEmpty();
      // API_PORT is the only restart-required value this settings candidate can write. If an
      // unrelated process source drifted since boot, refuse before touching a component owner.
      if (!java.util.Set.of("justsearch.api.port").containsAll(changedKeys.restartRequired())) {
        throw refused("RESTART_SOURCE_DRIFT",
            "A restart-required process source changed; restart before applying settings",
            Map.of("keys", List.copyOf(changedKeys.restartRequired())));
      }
      ResolvedConfig servingResolved = changedKeys.restartRequired().contains("justsearch.api.port")
          ? resolved.retainingApiPortFrom(serving) : resolved;
      if (!changedKeys.component().isEmpty() || chatComponentChanged
          || candidateContext.hasChatProfile() || candidateContext.forceGenerativeRefresh()) {
        var affected = new java.util.TreeMap<String, java.util.Set<String>>(changedKeys.component());
        if (chatComponentChanged) {
          affected.merge("generative", java.util.Set.of("ui.chatEnabled"), (left, right) -> {
            var keys = new java.util.TreeSet<>(left);
            keys.addAll(right);
            return java.util.Set.copyOf(keys);
          });
        }
        if (candidateContext.hasChatProfile()) {
          affected.merge("generative", java.util.Set.of("chatProfile"), (left, right) -> {
            var keys = new java.util.TreeSet<>(left);
            keys.addAll(right);
            return java.util.Set.copyOf(keys);
          });
        }
        if (candidateContext.forceGenerativeRefresh()) {
          affected.merge("generative", java.util.Set.of("modelRefresh"), (left, right) -> {
            var keys = new java.util.TreeSet<>(left);
            keys.addAll(right);
            return java.util.Set.copyOf(keys);
          });
        }
        preparedComponents = Objects.requireNonNull(
            components.prepare(prepared.settings(), resolved, Map.copyOf(affected), candidateContext),
            "Prepared component transaction");
      }
      OperationResult preparedResponse = prepareResponse.apply(prepared.settings());
      if (restartRequired) preparedResponse = withRestartScheduled(preparedResponse);
      var receipt = new Receipt(active.key, next.acceptedRevision(), preparedResponse);
      ConfigStore.PreparedSwap preparedConfig = config.prepareSwap(servingResolved);
      var publication = config.publicationLock();
      SettingsComponentComposer.Prepared preparedForPublish = preparedComponents;
      Runnable publish = () -> {
        publication.writeLock().lock();
        try {
          requireFence(reservation);
          if (active.recoveryReset()) {
            if (!matchesQuarantine(active.quarantineFingerprint)) {
              throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings quarantine evidence changed", Map.of());
            }
          } else {
            final SettingsWitness current;
            try { current = store.inspect().witness(); }
            catch (RuntimeException unreadable) {
              block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, active.id));
              throw refused("SETTINGS_RECOVERY_REQUIRED", "Settings witness became unreadable", Map.of());
            }
            if (!active.prior.equals(current)) {
              throw refused("VERSION_CONFLICT", "Settings changed during component preparation",
                  Map.of("currentRevision", current.acceptedRevision()));
            }
          }
          config.validatePrepared(preparedConfig);
          if (preparedForPublish != null) preparedForPublish.validate();
          if (processClosing.getAsBoolean()) {
            throw refused("ENGINE_CLOSING", "Engine shutdown has closed settings admission", Map.of());
          }
          if (!control.admitCommit(receipt)) {
            throw new CancellationException("Settings apply was cancelled before commit admission");
          }
          try {
            replacement.replace(prepared);
          } catch (IOException | RuntimeException failure) {
            final SettingsWitness observed;
            try { observed = store.inspect().witness(); }
            catch (RuntimeException inspectionFailure) {
              if (active.recoveryReset() && matchesQuarantine(active.quarantineFingerprint)) {
                throw new IllegalStateException("Settings recovery replacement did not commit", failure);
              }
              if (failure != inspectionFailure) failure.addSuppressed(inspectionFailure);
              uncertain(active, control, RecoveryReason.UNREADABLE_WITNESS);
              throw new IllegalStateException("Settings replacement has no readable witness", failure);
            }
            if (!next.equals(observed)) {
              if (!active.recoveryReset() && active.prior.equals(observed)) throw new IllegalStateException("Settings replacement did not commit", failure);
              uncertain(active, control, RecoveryReason.CONTRADICTORY_WITNESS);
              throw new IllegalStateException("Settings replacement has a contradictory witness", failure);
            }
            // A move may report failure after committing. The exact file witness resolves that ambiguity.
          }
          active.phase = Phase.COMMITTED;
          committed[0] = true;
          active.restartRequired = restartRequired;
          control.committed(receipt);
          try {
            config.installPrepared(preparedConfig);
            if (preparedForPublish != null) preparedForPublish.install();
          } catch (RuntimeException | Error publicationFailure) {
            // The durable witness already names B. A broken prepared installer must not be
            // reported as a clean serving commit; boot reconstructs from that witness.
            control.uncertain();
            throw publicationFailure;
          }
          event[0] = preparedConfig.event();
        } finally {
          publication.writeLock().unlock();
        }
      };
      if (preparedForPublish == null) publish.run();
      else preparedForPublish.withOwnerLocks(publish);
    } catch (RuntimeException | Error failure) {
      failed = failure;
      throw failure;
    } finally {
      mutex.unlock();
      publishIssue();
      if (!committed[0] && preparedComponents != null) {
        SettingsCommitFence active = fence;
        if (active != null && active.phase == Phase.PREPARING) {
          try { preparedComponents.abort(); }
          catch (RuntimeException | Error cleanupFailure) {
            // A refused close leaves a physical owner alive. Preserve the armed row for boot
            // reconciliation and ask the runner for ordered recovery instead of reporting a
            // clean precommit failure while the registry permit remains held.
            control.uncertain();
            if (failed != null) failed.addSuppressed(cleanupFailure);
            else LOG.error("Prepared component abort failed without a commit failure", cleanupFailure);
          }
        }
      }
    }
    // Arbitrary notification code is outside the physical mutex, with the logical fence retained.
    Throwable postCommitFailure = null;
    try {
      config.notifyListeners(event[0]);
      if (preparedComponents != null) preparedComponents.notifyObservers();
    } catch (RuntimeException | Error notificationFailure) {
      postCommitFailure = notificationFailure;
    }
    if (preparedComponents != null) {
      try { preparedComponents.retire(); }
      catch (RuntimeException | Error retirementFailure) {
        // The file and service publication are committed. Keep that receipt authoritative,
        // retain the apply permit, and schedule an ordered successor after the row completes.
        mutex.lock();
        try {
          SettingsCommitFence active = fence;
          if (active != null && active.phase == Phase.COMMITTED) active.restartRequired = true;
        } finally { mutex.unlock(); }
        if (postCommitFailure == null) postCommitFailure = retirementFailure;
        else postCommitFailure.addSuppressed(retirementFailure);
      }
    }
    if (postCommitFailure instanceof RuntimeException runtime) throw runtime;
    if (postCommitFailure instanceof Error error) throw error;
  }

  private SettingsCommitFence requireFence(Reservation reservation) {
    if (!(reservation instanceof SettingsCommitFence active) || active != fence
        || active.phase != Phase.PREPARING || (blocked && !active.recoveryReset())) {
      throw new IllegalArgumentException("Settings reservation is foreign, retired or unresolved");
    }
    return active;
  }

  private void uncertain(SettingsCommitFence active, AttemptControl control, RecoveryReason reason) {
    active.phase = Phase.UNCERTAIN;
    control.uncertain();
    block(new RecoveryIssue(reason, active.id));
  }

  @Override
  public void releaseAfterTerminal(long id) {
    // A refused contender never owned this fence; its terminal publication must not wait
    // for another transaction's preparation merely to perform unrelated cleanup.
    SettingsCommitFence observed = fence;
    if (observed == null || observed.id != id) return;
    boolean clear = false;
    boolean request = false;
    mutex.lock();
    try {
      if (fence != null && fence.id == id && fence.phase != Phase.UNCERTAIN) {
        clear = fence.phase == Phase.COMMITTED;
        if (clear && fence.recoveryReset()) blocked = false;
        if (clear && (fence.recoveryReset() || fence.restartRequired) && !restartIssued) {
          restartIssued = true;
          request = true;
        }
        fence = null;
        if (Objects.equals(recoveredId, id)) recoveredId = null;
      }
    } finally { mutex.unlock(); }
    // Only durable COMPLETE may clear recovery; callback faults cannot undo that outcome.
    Error fatal = null;
    try {
      if (clear) store.notifyRecoveryCleared();
    } catch (RuntimeException notificationFailure) {
      LOG.warn("Settings recovery notification failed after durable completion", notificationFailure);
    } catch (Error failure) {
      fatal = failure;
      throw failure;
    } finally {
      if (request) {
        try { restart.run(); }
        catch (RuntimeException | Error restartFailure) {
          if (fatal == null) throw restartFailure;
          if (fatal != restartFailure) fatal.addSuppressed(restartFailure);
        }
      }
    }
  }

  @Override
  public void retainForRestart(long id, Throwable failure) {
    SettingsCommitFence observed = fence;
    if (observed == null || observed.id != id) return;
    boolean request = false;
    mutex.lock();
    try {
      if (fence != null && fence.id == id) {
        fence.phase = Phase.UNCERTAIN;
        blocked = true;
        if (!restartIssued) { restartIssued = true; request = true; }
      }
    } finally { mutex.unlock(); }
    if (request) {
      LOG.error("Retaining settings operation {} for ordered restart", id, failure);
      restart.run();
    }
  }

  @Override
  public void inspectRecovery(List<RecoveryInput> rows) {
    mutex.lock();
    try {
      if (inspected || fence != null) throw new IllegalStateException("Settings recovery already inspected");
      inspected = true;
      var armed = List.copyOf(rows).stream().filter(input -> input.row().expectedSettingsRevision() != null).toList();
      if (armed.size() > 1) {
        block(new RecoveryIssue(RecoveryReason.MULTIPLE_ARMED_ROWS, null));
        return;
      }
      if (armed.isEmpty()) return;
      OperationRecord row = armed.getFirst().row();
      recoveredId = row.id();
      recoveredDecision = new OperationAttemptRunner.Reconciliation.Wait();
      if (!store.mode().isWritable()) {
        block(new RecoveryIssue(RecoveryReason.PERSISTENCE_DISABLED, row.id()));
        return;
      }
      final SettingsWitness witness;
      try { witness = store.inspect().witness(); }
      catch (RuntimeException failure) {
        var reset = recoveryIntent(armed.getFirst());
        if (reset != null && matchesQuarantine(reset.quarantineFingerprint())) {
          recoveredDecision = precommitFailure();
          fence = new SettingsCommitFence(row.id(), row.key(), new SettingsWitness(0, null),
              null, reset.quarantineFingerprint(), true);
        }
        block(new RecoveryIssue(RecoveryReason.UNREADABLE_WITNESS, row.id()));
        return;
      }
      long expected = row.expectedSettingsRevision();
      if (expected < 0 || expected == Long.MAX_VALUE) {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, row.id()));
        return;
      }
      if (RecordedInstallerGenerationPlan.OPERATION_ID.equals(row.descriptor().operationRef())) {
        if (row.descriptor().kind() != OperationKind.REINDEX) {
          block(new RecoveryIssue(RecoveryReason.INVALID_PREPARATION, row.id()));
          return;
        }
        // This REINDEX commits at the generation pointer. The settings file may still be A
        // after that pointer moved, or may already be B before runtime publication. Neither
        // witness alone decides its terminal outcome; the recorded-ingestion owner must inspect
        // the accepted candidate, pointer, settings projection and reconstructed runtime.
        fence = new SettingsCommitFence(row.id(), row.key(), witness);
        return;
      }
      if (row.key().equals(witness.lastCommittedOperationKey()) && witness.acceptedRevision() == expected + 1) {
        if (SettingsCandidatePreparation.OPERATION_REF.equals(row.descriptor().operationRef())) {
          try {
            recoveredContext = SettingsCandidatePreparation.decode(row,
                armed.getFirst().preparation().orElseThrow());
          } catch (RuntimeException unavailable) {
            block(new RecoveryIssue(RecoveryReason.INVALID_PREPARATION, row.id()));
            return;
          }
          // The file is B, but the transient physical target is still unproven. Keep RUNNING
          // until the fixed owner has been composed and boot installs that exact target.
        } else {
          recoveredDecision = new OperationAttemptRunner.Reconciliation.Complete(new OperationReceipt("SUCCESS", null));
        }
      } else if (witness.acceptedRevision() == expected
          && normalResetBaseMatches(armed.getFirst(), witness)) {
        recoveredDecision = precommitFailure();
      } else {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, row.id()));
        return;
      }
      // Keep the bounded witness reserved until the runner persists the recovery result.
      fence = new SettingsCommitFence(row.id(), row.key(), witness);
      if (row.key().equals(witness.lastCommittedOperationKey())) fence.phase = Phase.COMMITTED;
    } finally {
      mutex.unlock();
      publishIssue();
    }
  }

  @Override
  public OperationAttemptRunner.Reconciliation reconcile(OperationRecord row) {
    mutex.lock();
    try {
      if (!inspected) throw new IllegalStateException("Settings recovery must inspect the complete row set first");
      if (row.expectedSettingsRevision() == null) return precommitFailure();
      return Objects.equals(recoveredId, row.id()) ? recoveredDecision : new OperationAttemptRunner.Reconciliation.Wait();
    } finally { mutex.unlock(); }
  }

  @Override
  public boolean recoverCommittedComposition() {
    SettingsComponentComposer.Prepared prepared = null;
    boolean installed = false;
    mutex.lock();
    try {
      if (!inspected) throw new IllegalStateException("Settings recovery has not been inspected");
      if (blocked && issue != null && issue.reason() == RecoveryReason.COMPOSITION_FAILED) return false;
      if (recoveredContext == null) {
        if (issue != null && issue.reason() == RecoveryReason.INVALID_PREPARATION) {
          return false;
        }
        return true;
      }
      SettingsCommitFence active = fence;
      if (active == null || active.phase != Phase.COMMITTED || !Objects.equals(recoveredId, active.id)) {
        throw new IllegalStateException("Committed settings recovery fence is unavailable");
      }
      var snapshot = store.inspect();
      // The recovery fence's prior field is the already committed file witness on boot.
      if (!active.prior.equals(snapshot.witness())) {
        block(new RecoveryIssue(RecoveryReason.CONTRADICTORY_WITNESS, active.id));
        throw new IllegalStateException("Committed settings witness changed during boot recovery");
      }
      ResolvedConfig desired = Objects.requireNonNull(prepareConfig.apply(snapshot.settings()), "Recovered config");
      var affected = Map.<String, java.util.Set<String>>of("generative",
          recoveredContext.hasChatProfile() ? java.util.Set.of("chatProfile") : java.util.Set.of("modelRefresh"));
      prepared = Objects.requireNonNull(components.prepare(snapshot.settings(), desired, affected,
          recoveredContext), "Recovered component transaction");
      SettingsComponentComposer.Prepared chosen = prepared;
      var publication = config.publicationLock();
      chosen.withOwnerLocks(() -> {
        publication.writeLock().lock();
        try {
          if (!active.prior.equals(store.inspect().witness())) {
            throw new IllegalStateException("Committed settings witness changed during recovery publication");
          }
          chosen.validate();
          chosen.install();
        } finally { publication.writeLock().unlock(); }
      });
      installed = true;
    } catch (RuntimeException failure) {
      block(new RecoveryIssue(RecoveryReason.COMPOSITION_FAILED, recoveredId));
      if (!installed && prepared != null) {
        try { prepared.abort(); }
        catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      }
      LOG.error("Committed settings component recovery remains unresolved", failure);
      return false;
    } catch (Error fatal) {
      block(new RecoveryIssue(RecoveryReason.COMPOSITION_FAILED, recoveredId));
      if (!installed && prepared != null) {
        try { prepared.abort(); }
        catch (RuntimeException | Error cleanup) { fatal.addSuppressed(cleanup); }
      }
      throw fatal;
    } finally {
      mutex.unlock();
      publishIssue();
    }
    // A completed install is externally visible. Arbitrary observation and retirement run
    // outside the settings mutex and the shared publication writer.
    try { prepared.notifyObservers(); }
    catch (RuntimeException notificationFailure) {
      LOG.warn("Recovered generative component notification failed", notificationFailure);
    }
    try { prepared.retire(); }
    catch (RuntimeException retirementFailure) {
      mutex.lock();
      try { block(new RecoveryIssue(RecoveryReason.COMPOSITION_FAILED, recoveredId)); }
      finally { mutex.unlock(); publishIssue(); }
      LOG.error("Recovered settings component retirement remains unresolved", retirementFailure);
      return false;
    }
    mutex.lock();
    try {
      recoveredDecision = new OperationAttemptRunner.Reconciliation.Complete(new OperationReceipt("SUCCESS", null));
      recoveredContext = null;
    } finally { mutex.unlock(); }
    return true;
  }

  private static boolean normalResetBaseMatches(RecoveryInput input, SettingsWitness witness) {
    if (!SettingsResetPreparation.OPERATION_ID.equals(input.row().descriptor().operationRef())) return true;
    if (input.preparation().isEmpty()) return false;
    try {
      var intent = SettingsResetPreparation.decode(input.row(), input.preparation().orElseThrow());
      return !intent.recovery() && witness.equals(intent.expected());
    } catch (IllegalArgumentException unavailable) { return false; }
  }

  private static SettingsResetPreparation.Intent recoveryIntent(RecoveryInput input) {
    if (input.preparation().isEmpty()) return null;
    try {
      var intent = SettingsResetPreparation.decode(input.row(), input.preparation().orElseThrow());
      return intent.recovery() ? intent : null;
    } catch (IllegalArgumentException unavailable) {
      return null;
    }
  }

  private boolean matchesQuarantine(String fingerprint) {
    try { return fingerprint.equals(store.recoveryFingerprint()); }
    catch (IOException | RuntimeException unavailable) { return false; }
  }

  private static OperationAttemptRunner.Reconciliation precommitFailure() {
    return new OperationAttemptRunner.Reconciliation.Failed(new OperationReceipt("interrupted_before_settings_commit", null));
  }

  private void block(RecoveryIssue recovery) {
    blocked = true;
    if (issue == null) issue = recovery;
  }

  private void publishIssue() {
    // A nested refused call may have released only one reentrant hold. Let the outer owner
    // publish after its final unlock rather than invoking Health callbacks under that hold.
    if (mutex.isHeldByCurrentThread()) return;
    RecoveryIssue observed = issue;
    if (observed != null) recoveryIssue.complete(observed);
  }

  private static Refused refused(String code, String message, Map<String, Object> details) {
    return new Refused(OperationResult.failure(message, code, details, false));
  }

  private static OperationResult withRestartScheduled(OperationResult response) {
    var data = new java.util.LinkedHashMap<String, Object>(response.structuredData());
    data.put("restartScheduled", true);
    return new OperationResult(response.success(), response.message(), response.executionId(), data,
        response.errorCode(), response.errorDetails(), response.retryable());
  }
}
