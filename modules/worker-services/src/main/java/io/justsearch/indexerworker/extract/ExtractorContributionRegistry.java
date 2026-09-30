/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionException;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.substrate.ContributionComposer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The Worker-process composer for content-extractor contributions (tempdoc 560 §4.4/§6) — the
 * content extractor as a real first consumer of the extension substrate, alongside the Head's
 * MCP-host. The Worker cannot import the Head's {@code ContributionRegistry} (Head/Worker process
 * boundary), so it reuses the same four-substrate <em>pattern</em> in Worker-domain types — exactly
 * as the MCP-host is the Head's composer of the same pattern. Two real loaders, one pattern.
 *
 * <p>It composes {@link ContentExtractorProvider}s as installable contributions and is itself a
 * {@link ContentExtractorProvider}, dispatching each file to the contribution that handles it. The
 * four substrates (§4.3), realized here:
 *
 * <ul>
 *   <li><b>Lifecycle</b> — atomic {@link #install} (id-collision rejected before any state change) +
 *       ownership-keyed {@link #uninstall}.
 *   <li><b>Trust</b> — host owns the reserved {@code core.*} id namespace; a non-CORE extractor may
 *       not claim a {@code core.*} id.
 *   <li><b>Boundary</b> — the {@link ExtractorTrust} of a contribution selects its isolation: an
 *       UNTRUSTED extractor is only admissible behind the {@link PersistentExtractionSandbox} (P2), the
 *       same posture the Head's composer takes — refused, never silently downgraded.
 *   <li><b>Dispatch</b> — {@link #select} routes a file to the first installed contribution (install
 *       order) whose predicate matches; the catch-all is last-resort.
 * </ul>
 *
 * <p><b>Behavior preservation.</b> {@link #withCoreTika} builds the default Worker composition — a
 * single CORE catch-all that handles every file via the existing Tika extractor — so routing the
 * in-process extraction path through this registry is behaviorally identical to the pre-560 direct
 * {@code PolicyDrivenTikaExtractor} delegate.
 */
public final class ExtractorContributionRegistry implements ContentExtractorProvider, AutoCloseable {

  /** Trust of an extractor contribution — the Worker-domain input to the Boundary substrate. */
  public enum ExtractorTrust {
    CORE,
    TRUSTED,
    UNTRUSTED
  }

  /** One declared, installable extractor contribution. */
  public record ExtractorContribution(
      String id, ExtractorTrust trust, Predicate<Path> handles, ContentExtractorProvider provider) {
    public ExtractorContribution {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(trust, "trust");
      Objects.requireNonNull(handles, "handles");
      Objects.requireNonNull(provider, "provider");
      if (id.isBlank()) {
        throw new IllegalArgumentException("extractor id must be non-blank");
      }
    }

    /** A contribution that handles every file (the Tika default's shape). */
    public static ExtractorContribution catchAll(
        String id, ExtractorTrust trust, ContentExtractorProvider provider) {
      return new ExtractorContribution(id, trust, f -> true, provider);
    }
  }

  /**
   * The ONE shared composer (tempdoc 560 §4.3) — the SAME {@code ContributionComposer} the Head's
   * {@code ContributionRegistry} uses, reused here over content extractors. Each extractor is its own
   * owner (owner id == extractor id == key), so Lifecycle/Boundary/Trust come from the composer, not a
   * Worker-local re-implementation.
   */
  private final ContributionComposer<String, ExtractorContribution> composer =
      new ContributionComposer<>(id -> id);
  private boolean closed;

  /**
   * Install a contribution through the shared composer: it rejects an id collision (Lifecycle) and a
   * non-core contributor claiming the reserved {@code core.*} namespace (Trust). The Worker admits all
   * tiers at registry level (Boundary) — process isolation is applied later by the
   * {@code ExtractionSandboxFactory}, not at install time — so {@code boundaryAdmissible} is true.
   *
   * @throws IllegalStateException on id collision or a {@code core.*} mint by a non-core contributor
   */
  public synchronized void install(ExtractorContribution contribution) {
    if (closed) throw new IllegalStateException("Extractor registry is closed");
    Objects.requireNonNull(contribution, "contribution");
    composer.install(
        new ContributionComposer.Installation<>(
            contribution.id(),
            "extractor " + contribution.id(),
            contribution.trust() == ExtractorTrust.CORE,
            true,
            null,
            Map.of(contribution.id(), contribution)));
  }

  /** Lifecycle substrate: revoke a contribution by id. Returns false if it was not installed. */
  public synchronized boolean uninstall(String id) {
    if (closed) throw new IllegalStateException("Extractor registry is closed");
    return composer.uninstall(id).wasInstalled();
  }

  /** The installed contribution ids, in install order. */
  public synchronized List<String> ids() {
    return composer.keys();
  }

  /** Dispatch substrate: the first installed contribution (install order) whose predicate matches. */
  private synchronized ContentExtractorProvider select(Path file) {
    if (closed) throw new IllegalStateException("Extractor registry is closed");
    for (ExtractorContribution c : composer.values()) {
      if (c.handles().test(file)) {
        return c.provider();
      }
    }
    throw new IllegalStateException("No content-extractor contribution handles file: " + file);
  }

  @Override
  public ExtractionResult extract(Path file) throws IOException, ExtractionException {
    return select(file).extract(file);
  }

  public ExtractionArtifact extractArtifact(Path file) throws IOException, ExtractionException {
    ContentExtractorProvider provider = select(file);
    if (provider instanceof PolicyDrivenTikaExtractor policyDriven) {
      return policyDriven.extractArtifact(file);
    }
    String parserId = provider.getClass().getSimpleName();
    if (parserId == null || parserId.isBlank()) {
      parserId = provider.getClass().getName();
    }
    return ExtractionArtifact.full(provider.extract(file), parserId);
  }

  @Override
  public String detectMimeType(Path file) {
    return select(file).detectMimeType(file);
  }

  @Override
  public void close() {
    List<ExtractorContribution> installed;
    synchronized (this) {
      closed = true;
      installed = List.copyOf(composer.values());
    }
    Throwable failure = null;
    var visited = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<ContentExtractorProvider, Boolean>());
    for (ExtractorContribution contribution : installed) {
      ContentExtractorProvider provider = contribution.provider();
      if (!visited.add(provider)) continue;
      try {
        if (provider instanceof AutoCloseable owner) owner.close();
        synchronized (this) {
          for (ExtractorContribution alias : installed) {
            if (alias.provider() == provider) composer.uninstall(alias.id());
          }
        }
      } catch (Exception | Error cleanup) {
        if (failure == null) failure = cleanup;
        else if (failure != cleanup) failure.addSuppressed(cleanup);
      }
    }
    if (failure instanceof Error fatal) throw fatal;
    if (failure != null) throw new IllegalStateException("Extractor resources remain owned", failure);
  }

  /**
   * The default Worker composition: a single CORE catch-all over the existing Tika extractor —
   * behaviorally identical to the pre-560 direct delegate.
   */
  public static ExtractorContributionRegistry withCoreTika(ContentExtractorProvider tika) {
    ExtractorContributionRegistry registry = new ExtractorContributionRegistry();
    registry.install(ExtractorContribution.catchAll("core.tika", ExtractorTrust.CORE, tika));
    return registry;
  }
}
