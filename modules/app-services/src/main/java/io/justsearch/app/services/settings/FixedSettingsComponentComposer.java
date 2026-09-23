/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.settings;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsCommitOwner;
import io.justsearch.configuration.resolved.ResolvedConfig;
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
    Objects.requireNonNull(candidateContext, "candidateContext");
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
      for (var entry : selected.entrySet()) {
        PreparedOwner owner = Objects.requireNonNull(
            entry.getValue().prepare(candidate, desired, affected.get(entry.getKey()),
                "generative".equals(entry.getKey()) ? candidateContext
                    : io.justsearch.app.api.settings.SettingsCandidateContext.NONE),
            "Prepared owner: " + entry.getKey());
        prepared.add(owner);
        observations.put(entry.getKey(), Objects.requireNonNull(owner.observation(),
            "Prepared observation: " + entry.getKey()));
      }
      return new Composite(List.copyOf(prepared),
          java.util.Collections.unmodifiableMap(new LinkedHashMap<>(observations)), registry, lease);
    } catch (RuntimeException | Error failure) {
      int priorSuppressed = failure.getSuppressed().length;
      abortAll(prepared, failure);
      // A failed abort may still own native or process resources. Keep the apply permit held
      // until an owner can prove its cleanup; another candidate must not cross that lifetime.
      if (failure.getSuppressed().length == priorSuppressed) {
        try { lease.close(); } catch (RuntimeException | Error closeFailure) {
          failure.addSuppressed(closeFailure);
        }
      }
      throw failure;
    }
  }

  private static void abortAll(List<PreparedOwner> prepared, Throwable failure) {
    for (int i = prepared.size() - 1; i >= 0; i--) {
      try { prepared.get(i).abort(); }
      catch (RuntimeException | Error abortFailure) { failure.addSuppressed(abortFailure); }
    }
  }

  private static void throwIfFailed(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }

  private static final class Composite implements Prepared {
    private final List<PreparedOwner> owners;
    private final Map<String, EngineComponentSnapshot.Component> observations;
    private final EngineComponentRegistry registry;
    private final EngineComponentRegistry.ApplyLease lease;
    private EngineComponentRegistry.PreparedBatch batch;

    private Composite(List<PreparedOwner> owners,
        Map<String, EngineComponentSnapshot.Component> observations,
        EngineComponentRegistry registry, EngineComponentRegistry.ApplyLease lease) {
      this.owners = owners;
      this.observations = observations;
      this.registry = registry;
      this.lease = lease;
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
      RuntimeException failure = new IllegalStateException("Prepared component abort failed");
      abortAll(owners, failure);
      if (failure.getSuppressed().length == 0) {
        try { lease.close(); }
        catch (RuntimeException | Error closeFailure) { failure.addSuppressed(closeFailure); }
      }
      if (failure.getSuppressed().length > 0) throw failure;
    }
  }
}
