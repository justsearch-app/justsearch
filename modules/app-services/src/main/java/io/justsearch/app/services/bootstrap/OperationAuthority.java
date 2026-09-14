/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.intent.DurableGrantStore;
import io.justsearch.app.services.intent.IndexedRootGrantScope;
import io.justsearch.app.services.intent.IntentGateEvaluator;
import io.justsearch.app.services.registry.executor.GlobalHardStop;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.settings.UiSettingsStore.PersistenceMode;
import io.justsearch.app.services.worker.WatchedRootsState;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/** One preloaded authorization owner shared by recovery and subsequent request handling. */
public final class OperationAuthority {
  private final WatchedRootsState roots;
  private final DurableGrantStore grants;
  private final ConsentCapsuleService capsules = new ConsentCapsuleService();
  private final GlobalHardStop hardStop = new GlobalHardStop();
  private final IntentSourceCatalog sources = CoreIntentSourceCatalog.catalog();
  private final TrustEvaluator trust = new CoreTrustEvaluator();
  private final IntentGateEvaluator evaluator = new IntentGateEvaluator(trust, sources);
  private final IndexedRootGrantScope scope;

  private OperationAuthority(WatchedRootsState roots, DurableGrantStore grants) {
    this.roots = Objects.requireNonNull(roots, "roots");
    this.grants = Objects.requireNonNull(grants, "grants");
    scope = new IndexedRootGrantScope(Set.of(AgentToolsOperationCatalog.INGEST_FILES));
    scope.bindIndexedRoots(context -> roots.watchedPaths());
    evaluator.setHardStopSignal(hardStop::isEngaged);
    hardStop.setOnEngage(() -> {
      capsules.revokeNonUser();
      grants.revokeNonUser();
    });
  }

  /** Load the process directory before launching any index bootstrap work. */
  public static OperationAuthority load(Path dataDirectory) {
    return load(dataDirectory, PersistenceMode.resolveMode());
  }

  static OperationAuthority load(Path dataDirectory, PersistenceMode mode) {
    Path directory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    WatchedRootsState roots = WatchedRootsState.load(directory);
    Path grantFile = Objects.requireNonNull(mode, "mode").isWritable()
        ? directory.resolve("ui").resolve("durable-grants.json") : null;
    return new OperationAuthority(roots, new DurableGrantStore(Clock.systemUTC(), grantFile));
  }

  /** Explicit isolated authority for test compositions without durable state. */
  public static OperationAuthority inMemory() {
    return new OperationAuthority(WatchedRootsState.inMemory(), new DurableGrantStore());
  }

  public WatchedRootsState roots() { return roots; }
  public DurableGrantStore grants() { return grants; }
  public ConsentCapsuleService capsules() { return capsules; }
  public GlobalHardStop hardStop() { return hardStop; }
  public IntentSourceCatalog sources() { return sources; }
  public TrustEvaluator trust() { return trust; }
  public IntentGateEvaluator evaluator() { return evaluator; }
  public IndexedRootGrantScope scope() { return scope; }
}
