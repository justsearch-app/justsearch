/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.component.ComposeEvidence;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.component.EngineComponentSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fixed boot composition for owner-created settings candidates and one registry publication. */
public final class FixedSettingsComponentComposer implements SettingsComponentComposer {
  private static final Logger LOG = LoggerFactory.getLogger(FixedSettingsComponentComposer.class);
  // Apply relationships are distinct from captured-value/digest dependencies. In particular,
  // query model paths appear in the index's boot projection, but QueryRoleSet replacement is
  // owned by encoders and retains the serving Lucene runtime and index-time EncoderSet.
  public interface Owner {
    PreparedOwner prepare(UiSettings candidate, ResolvedConfig desired, Set<String> changedKeys);

    default PreparedOwner prepare(UiSettings candidate, ResolvedConfig desired,
        Set<String> changedKeys,
        io.justsearch.app.api.settings.SettingsCandidateContext candidateContext) {
      if (io.justsearch.app.api.settings.SettingsCandidateContext.NONE.equals(candidateContext)) {
        return prepare(candidate, desired, changedKeys);
      }
      throw new UnsupportedOperationException("Transient settings candidate context is unavailable");
    }
  }

  public interface PreparedOwner extends SettingsComponentComposer.Prepared {
    /** Prebuilt observation of the physical candidate, installed with the other owners. */
    EngineComponentSnapshot.Component observation();
  }

  /** The encoder owner alone can supply the exact selection persisted by the settings owner. */
  public interface QueryRolePreparedOwner extends PreparedOwner {
    QueryRoleSelection selection();
    @Override java.util.Optional<ComposeEvidence> composition();
  }

  private final EngineComponentRegistry registry;
  private final Map<String, Owner> owners = new LinkedHashMap<>();
  private boolean sealed;

  public FixedSettingsComponentComposer(EngineComponentRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** One-time boot registration; component names and owner identities never change after seal. */
  public synchronized void register(String name, Owner owner) {
    if (sealed) throw new IllegalStateException("Component owners are sealed");
    if (owners.putIfAbsent(Objects.requireNonNull(name, "name"),
        Objects.requireNonNull(owner, "owner")) != null) {
      throw new IllegalArgumentException("Duplicate component owner: " + name);
    }
  }

  public synchronized void seal() {
    sealed = true;
  }

  @Override
  public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
      Map<String, Set<String>> affected) {
    return prepare(candidate, desired, affected,
        io.justsearch.app.api.settings.SettingsCandidateContext.NONE);
  }

  @Override
  public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
      Map<String, Set<String>> affected,
      io.justsearch.app.api.settings.SettingsCandidateContext candidateContext) {
    return prepare(candidate, desired, affected, candidateContext, ignored -> {});
  }

  @Override
  public Prepared prepare(UiSettings candidate, ResolvedConfig desired,
      Map<String, Set<String>> affected,
      io.justsearch.app.api.settings.SettingsCandidateContext candidateContext,
      java.util.function.Consumer<String> afterOwnerPrepared) {
    Objects.requireNonNull(candidateContext, "candidateContext");
    Objects.requireNonNull(afterOwnerPrepared, "afterOwnerPrepared");
    Map<String, Owner> selected = new LinkedHashMap<>();
    synchronized (this) {
      if (!sealed) throw new IllegalStateException("Component owners are not sealed");
      affected.keySet().stream().sorted().forEach(name -> {
        Owner owner = owners.get(name);
        if (owner == null) {
          throw new SettingsCommitOwner.Refused(OperationResult.failure(
              "Runtime component owner is unavailable: " + name + " for " + affected.get(name),
              "COMPONENT_PREPARATION_REQUIRED",
              Map.of("component", name, "keys", affected.get(name)), true));
        }
        selected.put(name, owner);
      });
    }
    EngineComponentRegistry.ApplyAttempt attempt = registry.tryApply();
    if (attempt instanceof EngineComponentRegistry.ApplyAttempt.Refused refusal) {
      String code = refusal.reason() == EngineComponentRegistry.ApplyAttempt.Reason.CLOSED
          ? "ENGINE_CLOSING" : "RECONFIGURE_IN_PROGRESS";
      throw new SettingsCommitOwner.Refused(OperationResult.failure(
          "Component apply refused: " + refusal.reason(),
          code, Map.of("reason", refusal.reason().name()), true));
    }
    var lease = ((EngineComponentRegistry.ApplyAttempt.Acquired) attempt).lease();
    var prepared = new ArrayList<PreparedOwner>();
    try {
      Map<String, EngineComponentSnapshot.Component> observations = new LinkedHashMap<>();
      QueryRoleSelection queryRoles = null;
      ComposeEvidence composition = null;
      for (var entry : selected.entrySet()) {
        PreparedOwner owner = Objects.requireNonNull(
            entry.getValue().prepare(candidate, desired, affected.get(entry.getKey()),
                "generative".equals(entry.getKey()) ? candidateContext
                    : io.justsearch.app.api.settings.SettingsCandidateContext.NONE),
            "Prepared owner: " + entry.getKey());
        prepared.add(owner);
        if ("encoders".equals(entry.getKey())) {
          if (!(owner instanceof QueryRolePreparedOwner queryOwner)) {
            throw new IllegalStateException("Encoder owner has no prepared query-role selection");
          }
          queryRoles = Objects.requireNonNull(queryOwner.selection(), "Query-role selection");
          composition = queryOwner.composition().orElseThrow(
              () -> new IllegalStateException("Encoder owner has no query composition evidence"));
        }
        var observation = Objects.requireNonNull(owner.observation(),
            "Prepared observation: " + entry.getKey());
        if ("encoders".equals(entry.getKey())) {
          observation = withComposition(observation, composition);
        }
        observations.put(entry.getKey(), observation);
        afterOwnerPrepared.accept(entry.getKey());
      }
      return new Composite(List.copyOf(prepared), observations, queryRoles, composition,
          registry, lease);
    } catch (RuntimeException | Error failure) {
      boolean aborted = abortAll(prepared, failure);
      // A failed abort may still own native or process resources. Keep the apply permit held
      // until an owner can prove its cleanup; another candidate must not cross that lifetime.
      if (aborted) {
        try { lease.close(); } catch (RuntimeException | Error closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  private static EngineComponentSnapshot.Component withComposition(
      EngineComponentSnapshot.Component observation, ComposeEvidence composition) {
    return new EngineComponentSnapshot.Component(observation.spec(), observation.state(),
        observation.reasonCode(), observation.stateSince(), observation.stateSinceMonotonicNanos(),
        observation.appliedVersion(), observation.desiredVersion(), composition,
        observation.recoveryAttempts(), observation.evidence());
  }

  private static boolean abortAll(List<PreparedOwner> prepared, Throwable failure) {
    boolean succeeded = true;
    for (int i = prepared.size() - 1; i >= 0; i--) {
      try { prepared.get(i).abort(failure); }
      catch (RuntimeException | Error abortFailure) {
        succeeded = false;
        failure.addSuppressed(abortFailure);
      }
    }
    return succeeded;
  }

  private static void throwIfFailed(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }

  private static final class Composite implements Prepared {
    private final List<PreparedOwner> owners;
    private final Map<String, EngineComponentSnapshot.Component> observations;
    private final QueryRoleSelection queryRoles;
    private final ComposeEvidence composition;
    private final EngineComponentRegistry registry;
    private final EngineComponentRegistry.ApplyLease lease;
    private EngineComponentRegistry.PreparedBatch batch;

    private Composite(List<PreparedOwner> owners,
        Map<String, EngineComponentSnapshot.Component> observations,
        QueryRoleSelection queryRoles, ComposeEvidence composition,
        EngineComponentRegistry registry, EngineComponentRegistry.ApplyLease lease) {
      this.owners = owners;
      this.observations = new LinkedHashMap<>(observations);
      this.queryRoles = queryRoles;
      this.composition = composition;
      this.registry = registry;
      this.lease = lease;
    }
    @Override public java.util.Optional<QueryRoleSelection> queryRoleSelection() {
      return java.util.Optional.ofNullable(queryRoles);
    }
    @Override public java.util.Optional<ComposeEvidence> composition() {
      return java.util.Optional.ofNullable(composition);
    }
    @Override public void withOwnerLocks(Runnable publication) {
      underOwnerLocks(0, publication);
    }

    private void underOwnerLocks(int index, Runnable publication) {
      if (index == owners.size()) {
        publication.run();
      } else {
        owners.get(index).withOwnerLocks(() -> underOwnerLocks(index + 1, publication));
      }
    }

    @Override public void includeObservation(EngineComponentSnapshot.Component observation) {
      Objects.requireNonNull(observation, "observation");
      if (batch != null || observations.putIfAbsent(observation.spec().name(), observation) != null) {
        throw new IllegalStateException("Generation component observation is already prepared");
      }
    }

    @Override public void validate() {
      owners.forEach(PreparedOwner::validate);
      // Take the registry's complete observation only after the publication writer excludes
      // unrelated state changes. The batch is built before settings persistence, then install
      // below remains assignment-only.
      batch = registry.prepareBatch(observations);
      batch.validate();
    }

    @Override public void install() {
      owners.forEach(PreparedOwner::install);
      batch.install();
    }

    @Override public void notifyObservers() {
      batch.notifyObservers();
      for (var owner : owners) {
        try { owner.notifyObservers(); }
        catch (RuntimeException failure) {
          LOG.warn("Committed component observer failed", failure);
        }
      }
    }

    @Override public void retire() {
      Throwable failure = null;
      for (var owner : owners) {
        try { owner.retire(); }
        catch (RuntimeException | Error closeFailure) {
          if (failure == null) failure = closeFailure;
          else failure.addSuppressed(closeFailure);
        }
      }
      if (failure == null) {
        try { lease.close(); }
        catch (RuntimeException | Error closeFailure) { failure = closeFailure; }
      }
      throwIfFailed(failure);
    }

    @Override public void abort() {
      abort(new IllegalStateException("Prepared component abort failed"));
    }

    @Override public void abort(Throwable cause) {
      Objects.requireNonNull(cause, "abort cause");
      RuntimeException cleanup = new IllegalStateException("Prepared component abort failed");
      for (int i = owners.size() - 1; i >= 0; i--) {
        try { owners.get(i).abort(cause); }
        catch (RuntimeException | Error abortFailure) { cleanup.addSuppressed(abortFailure); }
      }
      if (cleanup.getSuppressed().length == 0) {
        try { lease.close(); }
        catch (RuntimeException | Error closeFailure) { cleanup.addSuppressed(closeFailure); }
      }
      if (cleanup.getSuppressed().length > 0) throw cleanup;
    }
  }
}
