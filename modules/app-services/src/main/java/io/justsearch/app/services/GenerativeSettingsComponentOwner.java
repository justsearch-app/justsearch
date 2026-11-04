/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.chat.Hold;
import io.justsearch.app.services.vdu.OfflineCoordinator;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.phases.InferenceDecision;
import io.justsearch.app.services.chat.ChatStatusProjection;
import io.justsearch.app.services.runtimestate.RuntimeSpec;
import io.justsearch.app.services.settings.FixedSettingsComponentComposer;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bridges the Head-owned generative runtime into the fixed settings transaction. */
public final class GenerativeSettingsComponentOwner implements FixedSettingsComponentComposer.Owner {
  private static final String OWNER_UNAVAILABLE = "GENERATIVE_OWNER_UNAVAILABLE";
  private static final String PREPARATION_REFUSED = "GENERATIVE_PREPARATION_REFUSED";

  private final InferenceLifecycleManager manager;
  private final ComponentHandle observation;
  private final Path baseDir;
  private final boolean liteMode;
  private final OfflineCoordinator pictures;

  public GenerativeSettingsComponentOwner(InferenceLifecycleManager manager, ComponentHandle observation,
      Path baseDir, boolean liteMode) {
    this(manager, observation, baseDir, liteMode, null);
  }

  public GenerativeSettingsComponentOwner(InferenceLifecycleManager manager, ComponentHandle observation,
      Path baseDir, boolean liteMode, OfflineCoordinator pictures) {
    this.pictures = pictures;
    this.manager = manager;
    this.observation = Objects.requireNonNull(observation, "observation");
    this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
    this.liteMode = liteMode;
  }

  /** Keep the API and text paths alive while the unresolved generative row remains fenced. */
  public void markRecoveryUnavailable() {
    if (manager != null) manager.fenceForSettingsRecovery();
    observation.transition(ComponentState.UNAVAILABLE,
        LifecycleReasonCode.SETTINGS_RECOVERY_REQUIRED.code(),
        "committed generative settings recovery is unresolved");
  }

  @Override
  public FixedSettingsComponentComposer.PreparedOwner prepare(UiSettings candidate,
      ResolvedConfig desired, Set<String> changedKeys) {
    return prepare(candidate, desired, changedKeys, SettingsCandidateContext.NONE);
  }

  @Override
  public FixedSettingsComponentComposer.PreparedOwner prepare(UiSettings candidate,
      ResolvedConfig desired, Set<String> changedKeys, SettingsCandidateContext candidateContext) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(desired, "desired");
    Objects.requireNonNull(changedKeys, "changedKeys");
    Objects.requireNonNull(candidateContext, "candidateContext");

    boolean switchingPictures = changedKeys.contains("pictureReadingEnabled");
    if (switchingPictures) {
      var override = desired.resolution("justsearch.mmproj.model");
      if (override != null && override.isResolved() && Set.of("env_var", "jvm_arg", "yaml").contains(override.sourceName())) {
        throw refused("PICTURE_READING_OVERRIDDEN",
            "Text in scans and images is controlled by an operator override; change that override first",
            Map.of("source", override.sourceName()), null);
      }
      if (manager != null && manager.isUsingExternalLlamaServer()) {
        throw refused("PICTURE_READING_EXTERNAL_SERVER",
            "Text in scans and images cannot be switched while using a user-run answer server",
            Map.of(), null);
      }
    }
    Hold fence = switchingPictures && pictures != null ? pictures.fencePictureReading() : null;
    Hold convergence;
    try {
      convergence = switchingPictures && manager != null && manager.chatHolds() != null
          ? manager.chatHolds().freezeConvergence("picture-reading-settings") : null;
    } catch (RuntimeException | Error failure) {
      if (fence != null) fence.close();
      throw failure;
    }
    Hold transition = new Hold() {
      @Override public String owner() { return "picture-reading-settings"; }
      @Override public void close() {
        try { if (convergence != null) convergence.close(); }
        finally { if (fence != null) fence.close(); }
      }
    };
    try {
      return prepareCandidate(candidate, desired, changedKeys, candidateContext,
          switchingPictures, transition);
    } catch (RuntimeException | Error failure) {
      try { transition.close(); }
      catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private FixedSettingsComponentComposer.PreparedOwner prepareCandidate(UiSettings candidate,
      ResolvedConfig desired, Set<String> changedKeys, SettingsCandidateContext candidateContext,
      boolean switchingPictures, Hold transition) {
    // Capture every candidate input before starting physical work. The nullable UI bit resolves
    // through the same RuntimeSpec projection used by the boot/runtime reconciler.
    boolean chatEnabled = RuntimeSpec.fromSettings(candidate).chatEnabled();
    boolean enabled = chatEnabled && InferenceDecision.decideInferenceConfigured(desired, liteMode);
    if (candidateContext.hasChatProfile()) {
      var modelSource = desired.resolution("justsearch.llm.model_path");
      if (modelSource != null && modelSource.isResolved() && modelSource.sourceOrdinal() >= 400) {
        throw refused(PREPARATION_REFUSED,
            "Chat profile cannot override an operator-selected model path",
            Map.of("source", modelSource.sourceName()), null);
      }
    }
    InferenceConfig inference = InferenceConfig.fromResolvedConfig(desired, baseDir);
    if (candidateContext.hasChatProfile()) {
      var profile = candidateContext.chatProfile();
      Path resolvedBase = ResolvedPathResolver.resolveBaseDir(desired,
          baseDir.toAbsolutePath().normalize().toString());
      Path modelsDir = ResolvedPathResolver.resolveModelsDir(desired, resolvedBase);
      Path model = modelsDir.resolve(profile.modelFile());
      if (!Files.isRegularFile(model)) {
        throw refused(PREPARATION_REFUSED, "Chat profile model is unavailable",
            Map.of("profile", profile.id(), "model", model.toString()), null);
      }
      inference = new InferenceConfig(inference.serverExecutable(), model,
          inference.mmprojPath(), inference.serverPort(),
          inference.contextSize(), inference.gpuLayers(), inference.vduMode(), profile.id(),
          inference.chatPromptCacheMiB());
    }
    inference = inference.withPictureReading(desired);
    if (switchingPictures && Boolean.TRUE.equals(candidate.getPictureReadingEnabled())) {
      requireVerifiedProjector(inference);
    }
    EngineComponentSnapshot.Component before = observation.snapshot();
    EngineComponentSnapshot.Component nonServingObservation =
        preparedObservation(desired, inference, enabled, false, before);
    EngineComponentSnapshot.Component servingObservation = enabled
        ? preparedObservation(desired, inference, true, true, before) : nonServingObservation;

    if (manager == null) {
      if (enabled) {
        throw refused(OWNER_UNAVAILABLE,
            "Managed generative inference was not composed at process boot",
            Map.of("changedKeys", Set.copyOf(changedKeys), "chatEnabled", true), null);
      }
      return new AbsentPreparedOwner(nonServingObservation, transition);
    }

    ComponentState stagingState = before.state() == ComponentState.READY
        ? ComponentState.RELOADING : ComponentState.STARTING;
    if (!observation.transitionIfUnchanged(before, stagingState,
        LifecycleReasonCode.INFERENCE_STARTING.code(), "preparing managed generative candidate")) {
      throw refused(PREPARATION_REFUSED,
          "Generative observation changed before candidate preparation",
          Map.of("changedKeys", Set.copyOf(changedKeys)), null, true);
    }
    EngineComponentSnapshot.Component staging = observation.snapshot();
    try {
      // The manager resolves RESTART_IF_ONLINE while holding its lifecycle lock. Selecting one
      // of these prebuilt observations after preparation does not allocate or inspect runtime,
      // beyond reading the window the candidate was launched with.
      var prepared = manager.prepareResolvedConfig(inference, desired, enabled,
          candidateContext.forceGenerativeRefresh() || switchingPictures);
      return new ManagedPreparedOwner(prepared,
          prepared.targetsOnline() ? withWindowNotice(servingObservation) : nonServingObservation,
          observation, before, staging, transition);
    } catch (ModeTransitionException failure) {
      restoreObservation(before, staging);
      throw refused(PREPARATION_REFUSED,
          "Managed generative candidate was refused before settings commit",
          Map.of(
              "changedKeys", Set.copyOf(changedKeys),
              "reason", failure.reason().name(),
              "inferenceCode", failure.failure().wireCode()),
          failure, isTransition(failure));
    } catch (RuntimeException failure) {
      restoreObservation(before, staging);
      throw failure;
    }
  }

  private static void requireVerifiedProjector(InferenceConfig inference) {
    Path projector = inference.mmprojPath();
    if (projector == null || !Files.isRegularFile(projector)) {
      throw refused("PICTURE_READING_FILES_MISSING",
          "Picture reading files are missing. Review Install AI to download them.", Map.of(), null);
    }
    var registry = io.justsearch.configuration.model.ModelRegistryLoader
        .loadFromClasspath("ai/model-registry.v2.json");
    var companion = registry.packages().stream()
        .filter(pkg -> pkg.variants().stream().anyMatch(variant ->
            inference.modelPath().getFileName().toString().equals(variant.filename())))
        .flatMap(pkg -> pkg.supportingFiles().stream())
        .filter(file -> projector.getFileName().toString().equals(file.filename()))
        .findFirst().orElse(null);
    try {
      if (companion == null || Files.size(projector) != companion.sizeBytes()
          || !io.justsearch.app.services.ai.install.DownloadExecutor.sha256(projector)
              .equalsIgnoreCase(companion.sha256())) {
        throw refused("PICTURE_READING_FILES_MISMATCHED",
            "Picture reading files do not match the answer model. Repair AI before switching on.",
            Map.of(), null);
      }
    } catch (SettingsCommitOwner.Refused refusal) { throw refusal; }
    catch (Exception failure) {
      throw refused("PICTURE_READING_FILES_UNREADABLE",
          "Picture reading files could not be verified. Repair AI before switching on.",
          Map.of(), failure);
    }
  }

  private void restoreObservation(EngineComponentSnapshot.Component before,
      EngineComponentSnapshot.Component staging) {
    if (!observation.transitionIfUnchanged(staging, before.state(),
        before.reasonCode(), before.evidence())) {
      throw new IllegalStateException("Generative observation changed during candidate rollback");
    }
  }

  /**
   * Spec A §7.2: a candidate the manager launched one rung lower (a step-down that stays sticky for
   * an unchanged configuration) serves with the context_reduced notice, like any other READY
   * landing of that server.
   */
  private EngineComponentSnapshot.Component withWindowNotice(
      EngineComponentSnapshot.Component serving) {
    var window = manager.launchedContextWindow();
    String reasonCode = ChatStatusProjection.ready(window == null ? null : window.reason())
        .reasonCode();
    if (reasonCode == null) return serving;
    return new EngineComponentSnapshot.Component(serving.spec(), serving.state(), reasonCode,
        serving.stateSince(), serving.stateSinceMonotonicNanos(), serving.appliedVersion(),
        serving.desiredVersion(), serving.lastCompose(), serving.recoveryAttempts(),
        serving.evidence());
  }

  private EngineComponentSnapshot.Component preparedObservation(ResolvedConfig desired,
      InferenceConfig inference, boolean enabled, boolean startManaged,
      EngineComponentSnapshot.Component previous) {
    String version = enabled ? HeadAssembly.generativeAppliedVersion(inference, desired)
        : HeadAssembly.generativeAbsentVersion(desired, liteMode);
    boolean configuredOffline = enabled && !startManaged;
    return new EngineComponentSnapshot.Component(
        previous.spec(),
        startManaged ? ComponentState.READY : ComponentState.ABSENT,
        startManaged ? null : LifecycleReasonCode.INFERENCE_DEACTIVATED.code(),
        previous.stateSince(),
        previous.stateSinceMonotonicNanos(),
        configuredOffline ? previous.appliedVersion() : version,
        version,
        new ComposeEvidence(
            ComposeEvidence.Mode.IN_PLACE,
            startManaged ? "managed candidate verified"
                : configuredOffline ? "generative configuration retained while offline"
                    : "generative intent disabled",
            null,
            null),
        0,
        startManaged
            ? "settings candidate verified managed generative serving state"
            : configuredOffline ? "settings candidate configured for the next Online turn"
                : "settings candidate disables generative serving state");
  }

  /**
   * True when the chat server refused because it is busy changing state (a reply still draining,
   * another transition or a superseding intent): the same candidate can succeed once that ends.
   */
  private static boolean isTransition(ModeTransitionException failure) {
    return failure.reason() == ModeTransitionException.Reason.ALREADY_TRANSITIONING
        || failure.reason() == ModeTransitionException.Reason.INTERRUPTED;
  }

  private static SettingsCommitOwner.Refused refused(String code, String message,
      Map<String, Object> details, Throwable cause) {
    return refused(code, message, details, cause, false);
  }

  private static SettingsCommitOwner.Refused refused(String code, String message,
      Map<String, Object> details, Throwable cause, boolean retryable) {
    var refusal = new SettingsCommitOwner.Refused(
        OperationResult.failure(message, code, details, retryable));
    if (cause != null) refusal.initCause(cause);
    return refusal;
  }

  private record AbsentPreparedOwner(EngineComponentSnapshot.Component observation, Hold transition)
      implements FixedSettingsComponentComposer.PreparedOwner {
    @Override public void includeObservation(EngineComponentSnapshot.Component unexpected) {
      throw new UnsupportedOperationException("Absent generative owner has no component projection");
    }
    @Override public void withOwnerLocks(Runnable publication) { publication.run(); }
    @Override public void validate() {}
    @Override public void install() {}
    @Override public void notifyObservers() {}
    @Override public void retire() { transition.close(); }
    @Override public void abort() { transition.close(); }
  }

  private record ManagedPreparedOwner(
      InferenceLifecycleManager.PreparedConfigApply candidate,
      EngineComponentSnapshot.Component observation,
      ComponentHandle handle,
      EngineComponentSnapshot.Component before,
      EngineComponentSnapshot.Component staging,
      Hold transition)
      implements FixedSettingsComponentComposer.PreparedOwner {
    private ManagedPreparedOwner {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(observation, "observation");
    }

    @Override public void includeObservation(EngineComponentSnapshot.Component unexpected) {
      throw new UnsupportedOperationException("Generative owner has no generation projection");
    }

    @Override public void withOwnerLocks(Runnable publication) {
      candidate.withLifecycleLock(publication);
    }

    @Override public void validate() {
      candidate.validateForCommit();
    }

    @Override public void install() {
      candidate.installAfterSettingsCommit();
    }

    @Override public void notifyObservers() {
      candidate.notifyAfterSettingsCommit();
    }

    @Override public void retire() {
      try {
        candidate.retireAfterSettingsCommit();
      } catch (ModeTransitionException failure) {
        throw new IllegalStateException("Committed generative candidate retirement failed", failure);
      } finally { transition.close(); }
    }

    @Override public void abort() {
      try {
        candidate.abort();
        if (!handle.transitionIfUnchanged(staging, before.state(),
            before.reasonCode(), before.evidence())) {
          throw new IllegalStateException("Generative observation changed during candidate rollback");
        }
      } catch (ModeTransitionException failure) {
        throw new IllegalStateException("Generative candidate rollback failed", failure);
      } finally { transition.close(); }
    }
  }
}
