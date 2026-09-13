/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BuildState;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SoftDeletesMetrics;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.TelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexing.runtime.IndexOpenGuard;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Fluent builder for sealed phase-typed Lucene runtimes — tempdoc 406
 * substrate.
 *
 * <p>The builder captures intent (path, config overrides, telemetry sinks,
 * initial build state) and constructs a configured runtime via
 * {@link #open()} / {@link #openReadOnly()} / {@link #openDeferred()}.
 * Builders are reusable: each call to an {@code open*()} method returns
 * an independent, started runtime against the configured path.
 *
 * <p>Phase 2 internal: {@code open*()} returns sealed phase values
 * ({@link RunningRuntime} / {@link ReadOnlyRuntime} / {@link DeferredRuntime}),
 * each currently wrapping a {@link LuceneLifecycleManager} delegate. Phase 2
 * Step 4 will swap the delegate to a {@code RuntimeSession} value.
 */
public final class LuceneRuntimeBuilder {

  private final IndexSchema schema;
  private final Path indexPath; // null = ephemeral
  private Path fallbackIndexPath;
  private ResolvedConfig resolvedConfigOverride;
  private TelemetryEvents telemetryEvents;
  private SoftDeletesMetrics softDeletesMetrics;
  private IndexOpenGuard indexOpenGuardOverride;
  private BuildState initialBuildState = BuildState.COMPLETE;
  private Components prebuiltComponentsForTests; // package-private test injection
  private LuceneExecutorRegistrations executorRegistrations;

  /**
   * Whether a foreground (user-facing) RPC is in flight. Gates the reopen-on-demand refresh in
   * {@link SearcherBridge} so that background enrichment reads — which reach Lucene through the
   * SAME bridge — cannot trigger one (tempdoc 885 item 19 live window: they tripled reopen count
   * and cost 15% indexing throughput).
   *
   * <p>Defaults to "always foreground", i.e. the pre-fix behaviour: an unwired runtime errs
   * toward freshness rather than toward serving a stale searcher. {@code index.nrt.mode} is
   * {@code continuous} by default, where this predicate is not consulted at all, so an unwired
   * runtime is unaffected until an operator opts in.
   */
  private java.util.function.BooleanSupplier foregroundActive = () -> true;

  // ==========================================================================
  // Package-private accessors for RuntimeSession ctor
  // ==========================================================================

  IndexSchema schema() {
    return schema;
  }

  Path indexPath() {
    return indexPath;
  }

  Path fallbackIndexPath() {
    return fallbackIndexPath;
  }

  ResolvedConfig resolvedConfigOverride() {
    return resolvedConfigOverride;
  }

  TelemetryEvents telemetryEvents() {
    return telemetryEvents;
  }

  SoftDeletesMetrics softDeletesMetrics() {
    return softDeletesMetrics;
  }

  java.util.function.BooleanSupplier foregroundActive() {
    return foregroundActive;
  }

  IndexOpenGuard indexOpenGuardOverride() {
    return indexOpenGuardOverride;
  }

  BuildState initialBuildState() {
    return initialBuildState;
  }

  Components prebuiltComponentsForTests() {
    return prebuiltComponentsForTests;
  }

  LuceneExecutorRegistrations executorRegistrations() {
    return executorRegistrations;
  }

  /** Package-private — for {@code ComponentsInjectionTest} only. */
  LuceneRuntimeBuilder withPrebuiltComponentsForTests(Components components) {
    this.prebuiltComponentsForTests = components;
    return this;
  }

  /** Package-private — instantiated via {@link IndexSchema#atPath(Path)} or {@link IndexSchema#ephemeral()}. */
  LuceneRuntimeBuilder(IndexSchema schema, Path indexPath) {
    this.schema = Objects.requireNonNull(schema, "schema");
    this.indexPath = indexPath; // may be null for ephemeral
  }

  public LuceneRuntimeBuilder withFallbackIndexPath(Path fallbackIndexPath) {
    this.fallbackIndexPath = fallbackIndexPath;
    return this;
  }

  public LuceneRuntimeBuilder withConfig(ResolvedConfig resolvedConfig) {
    this.resolvedConfigOverride = resolvedConfig;
    return this;
  }

  public LuceneRuntimeBuilder withTelemetry(TelemetryEvents telemetryEvents) {
    this.telemetryEvents = telemetryEvents;
    return this;
  }

  public LuceneRuntimeBuilder withSoftDeletesMetrics(SoftDeletesMetrics softDeletesMetrics) {
    this.softDeletesMetrics = softDeletesMetrics;
    return this;
  }

  /**
   * Supplies the foreground-RPC predicate the reopen-on-demand seam gates on. The Worker passes
   * {@code () -> foregroundLoad.inFlight() > 0} — item 3's gauge, which is the only component
   * that knows a search-family RPC is running. Null restores the default.
   */
  public LuceneRuntimeBuilder withForegroundActive(
      java.util.function.BooleanSupplier foregroundActive) {
    this.foregroundActive = foregroundActive != null ? foregroundActive : () -> true;
    return this;
  }

  public LuceneRuntimeBuilder withIndexOpenGuard(IndexOpenGuard indexOpenGuard) {
    this.indexOpenGuardOverride = indexOpenGuard;
    return this;
  }

  /**
   * Pre-flight finding: {@code BuildState} is a runtime mode (set once at
   * construction), not a per-commit attribute. Default {@code COMPLETE}.
   */
  public LuceneRuntimeBuilder withBuildState(BuildState initialBuildState) {
    this.initialBuildState = Objects.requireNonNull(initialBuildState, "initialBuildState");
    return this;
  }

  /** Supplies the bounded executor registrations owned by each opened runtime session. */
  public LuceneRuntimeBuilder withExecutorRegistrations(
      LuceneExecutorRegistrations executorRegistrations) {
    this.executorRegistrations = Objects.requireNonNull(executorRegistrations, "executorRegistrations");
    return this;
  }

  /** Open a read-write runtime. Returns a typed {@link RunningRuntime}. */
  public RunningRuntime open() {
    Objects.requireNonNull(executorRegistrations, "executorRegistrations");
    return new RunningRuntime(schema, this, new RuntimeSession(this, RuntimeSession.Mode.RUNNING));
  }

  /** Open a read-only runtime. Returns a typed {@link ReadOnlyRuntime}. */
  public ReadOnlyRuntime openReadOnly() {
    Objects.requireNonNull(executorRegistrations, "executorRegistrations");
    return new ReadOnlyRuntime(schema, this, new RuntimeSession(this, RuntimeSession.Mode.READ_ONLY));
  }

  /**
   * Open a deferred-writer runtime: starts read-only, transitions to
   * read-write via {@link DeferredRuntime#upgradeWriter()} on a background
   * thread.
   */
  public DeferredRuntime openDeferred() {
    Objects.requireNonNull(executorRegistrations, "executorRegistrations");
    return new DeferredRuntime(schema, this, new RuntimeSession(this, RuntimeSession.Mode.DEFERRED));
  }
}
