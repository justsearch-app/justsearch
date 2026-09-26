/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Lucene runtime — phase-typed lifecycle owners and their per-session ops collaborators.
 *
 * <h2>Design (tempdoc 406)</h2>
 *
 * <p>Service identity (the consumer / holder) is separated from the open period (the
 * single-shot phase value). Phase values follow the {@link java.lang.AutoCloseable}
 * contract strictly: <em>open → use → close, terminal</em>. Restart is a consumer-side
 * holder swap, not a value method.
 *
 * <h2>Building a runtime</h2>
 *
 * <pre>{@code
 * IndexSchema schema = IndexSchema.fromCatalog(catalog);
 * RunningRuntime rt = schema.atPath(indexPath).withConfig(rc).open();
 * // ... use rt.indexingCoordinator(), rt.readPathOps(), rt.commitOps() ...
 * rt.close();
 * }</pre>
 *
 * <h2>Phase types</h2>
 *
 * <p>The sealed {@link io.justsearch.adapters.lucene.runtime.LuceneRuntime} interface
 * permits exactly three concrete final classes:
 *
 * <ul>
 *   <li>{@link io.justsearch.adapters.lucene.runtime.RunningRuntime} — read + write.
 *       Exposes {@link io.justsearch.adapters.lucene.runtime.IndexingCoordinator},
 *       {@link io.justsearch.adapters.lucene.runtime.WritePathOps},
 *       {@link io.justsearch.adapters.lucene.runtime.PruneOps} in addition to the
 *       read-side ops shared by all phases.
 *   <li>{@link io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime} — search-only.
 *       No write-side ops; calling {@code indexingCoordinator()} is a compile error.
 *   <li>{@link io.justsearch.adapters.lucene.runtime.DeferredRuntime} — read-only with
 *       a one-shot {@link io.justsearch.adapters.lucene.runtime.DeferredRuntime#prepareWriterUpgrade()}
 *       transition that consumes self and returns a {@code RunningRuntime}. Used for
 *       fast-boot paths where the writer can come up later.
 * </ul>
 *
 * <h2>Holder swap</h2>
 *
 * <pre>{@code
 * private volatile RunningRuntime ingestRuntime;
 *
 * // Initial open
 * this.ingestRuntime = schema.atPath(activePath).open();
 *
 * // Cycle (config reload, blue/green, etc.)
 * RunningRuntime old = this.ingestRuntime;
 * old.session().draining = true;                          // pause writes
 * this.ingestRuntime = null;                              // route writers to no-op
 * old.drainAndClose(java.time.Duration.ofSeconds(5));     // wait + commit + close
 * this.ingestRuntime = schema.atPath(activePath).open();  // open fresh on freed lock
 * }</pre>
 *
 * <h2>Internals</h2>
 *
 * <p>{@link io.justsearch.adapters.lucene.runtime.RuntimeSession} is the package-private
 * holder for all per-session state (Lucene resources, ops collaborators, config-derived
 * knobs, atomic counters). Single composition site (the production constructor) and
 * single release site ({@code close()}). Adding a new per-session field requires
 * editing exactly two places — that's the audit-symmetry property tempdoc 406
 * delivers.
 *
 * <h2>See also</h2>
 *
 * <ul>
 *   <li>{@code docs/future-features/service-identity-lifecycle-pattern.md} — pattern
 *       guide for repo-wide rollout.
 *   <li>{@code docs/tempdocs/406-lucene-lifecycle-manager-restart-refactor.md} —
 *       the design and history of this refactor.
 * </ul>
 */
package io.justsearch.adapters.lucene.runtime;
