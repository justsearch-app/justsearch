/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.bootstrap.phases.InferenceDecision;
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

  public GenerativeSettingsComponentOwner(InferenceLifecycleManager manager, ComponentHandle observation,
      Path baseDir, boolean liteMode) {
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
      Path mmproj = modelsDir.resolve(profile.mmprojFile());
      inference = new InferenceConfig(inference.serverExecutable(), model,
          Files.isRegularFile(mmproj) ? mmproj : null, inference.serverPort(),
          inference.contextSize(), inference.gpuLayers(), inference.vduMode(), profile.id());
    }
    EngineComponentSnapshot.Component preparedObservation =
        preparedObservation(desired, inference, enabled);

    if (manager == null) {
      if (enabled) {
        throw refused(OWNER_UNAVAILABLE,
            "Managed generative inference was not composed at process boot",
            Map.of("changedKeys", Set.copyOf(changedKeys), "chatEnabled", true), null);
      }
      return new AbsentPreparedOwner(preparedObservation);
    }

    EngineComponentSnapshot.Component before = observation.snapshot();
    ComponentState stagingState = before.state() == ComponentState.READY
        ? ComponentState.RELOADING : ComponentState.STARTING;
    if (!observation.transitionIfUnchanged(before, stagingState,
        LifecycleReasonCode.INFERENCE_STARTING.code(), "preparing managed generative candidate")) {
      throw refused(PREPARATION_REFUSED,
          "Generative observation changed before candidate preparation",
          Map.of("changedKeys", Set.copyOf(changedKeys)), null);
    }
    EngineComponentSnapshot.Component staging = observation.snapshot();
    try {
      var prepared = manager.prepareResolvedConfig(inference, desired, enabled);
      return new ManagedPreparedOwner(prepared, preparedObservation, observation, before, staging);
    } catch (ModeTransitionException failure) {
      restoreObservation(before, staging);
      throw refused(PREPARATION_REFUSED,
          "Managed generative candidate was refused before settings commit",
          Map.of(
              "changedKeys", Set.copyOf(changedKeys),
              "reason", failure.reason().name(),
              "inferenceCode", failure.failure().wireCode()),
          failure);
    } catch (RuntimeException failure) {
      restoreObservation(before, staging);
      throw failure;
    }
  }

  private void restoreObservation(EngineComponentSnapshot.Component before,
      EngineComponentSnapshot.Component staging) {
    if (!observation.transitionIfUnchanged(staging, before.state(),
        before.reasonCode(), before.evidence())) {
      throw new IllegalStateException("Generative observation changed during candidate rollback");
    }
  }

  private EngineComponentSnapshot.Component preparedObservation(ResolvedConfig desired,
      InferenceConfig inference, boolean enabled) {
    EngineComponentSnapshot.Component previous = observation.snapshot();
    String version = enabled
        ? HeadAssembly.generativeAppliedVersion(inference)
        : HeadAssembly.generativeAbsentVersion(desired, liteMode);
    return new EngineComponentSnapshot.Component(
        previous.spec(),
        enabled ? ComponentState.READY : ComponentState.ABSENT,
        enabled ? null : LifecycleReasonCode.INFERENCE_DEACTIVATED.code(),
        previous.stateSince(),
        previous.stateSinceMonotonicNanos(),
        version,
        version,
        new ComposeEvidence(
            ComposeEvidence.Mode.IN_PLACE,
            enabled ? "managed candidate verified" : "generative intent disabled",
            null,
            null),
        0,
        enabled
            ? "settings candidate verified managed generative serving state"
            : "settings candidate disables generative serving state");
  }

  private static SettingsCommitOwner.Refused refused(String code, String message,
      Map<String, Object> details, Throwable cause) {
    var refusal = new SettingsCommitOwner.Refused(
        OperationResult.failure(message, code, details, false));
    if (cause != null) refusal.initCause(cause);
    return refusal;
  }

  private record AbsentPreparedOwner(EngineComponentSnapshot.Component observation)
      implements FixedSettingsComponentComposer.PreparedOwner {
    @Override public void validate() {}
    @Override public void install() {}
    @Override public void notifyObservers() {}
    @Override public void retire() {}
    @Override public void abort() {}
  }

  private record ManagedPreparedOwner(
      InferenceLifecycleManager.PreparedConfigApply candidate,
      EngineComponentSnapshot.Component observation,
      ComponentHandle handle,
      EngineComponentSnapshot.Component before,
      EngineComponentSnapshot.Component staging)
      implements FixedSettingsComponentComposer.PreparedOwner {
    private ManagedPreparedOwner {
      Objects.requireNonNull(candidate, "candidate");
      Objects.requireNonNull(observation, "observation");
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
      }
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
      }
    }
  }
}
