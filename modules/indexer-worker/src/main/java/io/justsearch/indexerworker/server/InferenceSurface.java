/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.bgem3.BgeM3Assembly;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.ner.NerAssembly;
import io.justsearch.indexerworker.splade.SpladeAssembly;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.PolicySnapshot;
import io.justsearch.ort.SessionHandle;
import io.justsearch.reranker.RerankerAssembly;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Typed bundle returned by {@link InferenceCompositionRoot#compose} — the §7.6 single-entry-point
 * composition output (tempdoc 397 §14.26 T2-C1).
 *
 * <p>Every encoder is {@link Optional}: composition failures (missing model files, degraded
 * hardware, tokenizer load errors) surface as {@link Optional#empty()} rather than throwing, so a
 * single failure doesn't abort boot. The {@code policies} snapshot reflects only the roles whose
 * {@code VariantSelection} resolved — matching today's {@code SessionPoliciesController} omit-on-
 * unresolved semantic.
 *
 * <p>{@link #handles()} collects every live {@link SessionHandle}; {@link #close()} attempts to
 * retire all of them before propagating an aggregate failure. Refused handles remain available
 * for a later retry, and {@link #retirementStatus()} exposes their aggregate disposition.
 *
 * <p>Sparse-model selection (bge-m3 vs splade) means at most one of {@link #splade()} and
 * {@link #bgeM3()} is populated: BGE-M3 wins when {@code cfg.ai().sparseModel() == "bge-m3"} and
 * its assembly succeeds; SPLADE wins otherwise. Both empty = no sparse retrieval.
 *
 * @param embedding dense embedding encoder (skipped when BGE-M3 is active)
 * @param ner NER inference + label mapping
 * @param reranker cross-encoder reranker assembly
 * @param citation citation-scorer assembly (CPU-only; shares the reranker shape)
 * @param splade sparse encoder (SPLADE); empty when BGE-M3 is active or unavailable
 * @param bgeM3 unified dense+sparse encoder; empty unless selected + available
 * @param policies snapshot of {@link PolicySnapshot} for the roles whose variant resolved
 * @param handles every {@link SessionHandle} the surface owns; iterated for shutdown
 * @param componentObservation config digest and requested/missing-role composition evidence
 */
public record InferenceSurface(
    Optional<EmbeddingAssembly> embedding,
    Optional<NerAssembly> ner,
    Optional<RerankerAssembly> reranker,
    Optional<RerankerAssembly> citation,
    Optional<SpladeAssembly> splade,
    Optional<BgeM3Assembly> bgeM3,
    PolicySnapshot policies,
    List<SessionHandle> handles,
    ComponentObservation componentObservation)
    implements AutoCloseable {

  /** Back-compatible test constructor. Its observation is explicitly unknown, never ready. */
  public InferenceSurface(
      Optional<EmbeddingAssembly> embedding,
      Optional<NerAssembly> ner,
      Optional<RerankerAssembly> reranker,
      Optional<RerankerAssembly> citation,
      Optional<SpladeAssembly> splade,
      Optional<BgeM3Assembly> bgeM3,
      PolicySnapshot policies,
      List<SessionHandle> handles) {
    this(
        embedding,
        ner,
        reranker,
        citation,
        splade,
        bgeM3,
        policies,
        handles,
        ComponentObservation.unknown());
  }

  public InferenceSurface {
    handles = List.copyOf(handles);
    Objects.requireNonNull(componentObservation, "componentObservation");
  }

  /** Immutable evidence used by the physical owner after it completes service wiring. */
  public record ComponentObservation(
      Optional<String> configurationDigest,
      Set<EncoderRole> requestedRoles,
      Set<EncoderRole> missingRoles) {

    public ComponentObservation {
      configurationDigest = Optional.ofNullable(configurationDigest).orElseGet(Optional::empty);
      requestedRoles = immutableRoles(requestedRoles);
      missingRoles = immutableRoles(missingRoles);
      if (!requestedRoles.containsAll(missingRoles)) {
        throw new IllegalArgumentException("missing roles must be a subset of requested roles");
      }
    }

    public static ComponentObservation unknown() {
      return new ComponentObservation(Optional.empty(), Set.of(), Set.of());
    }

    static ComponentObservation composed(
        String appliedVersion, Set<EncoderRole> requestedRoles, Set<EncoderRole> presentRoles) {
      Set<EncoderRole> missingRoles = EnumSet.noneOf(EncoderRole.class);
      missingRoles.addAll(requestedRoles);
      missingRoles.removeAll(presentRoles);
      return new ComponentObservation(
          Optional.of(appliedVersion), requestedRoles, missingRoles);
    }

    public boolean hasRequestedRoles() {
      return !requestedRoles.isEmpty();
    }

    public boolean compositionSatisfied() {
      return configurationDigest.isPresent() && hasRequestedRoles() && missingRoles.isEmpty();
    }

    private static Set<EncoderRole> immutableRoles(Set<EncoderRole> roles) {
      Objects.requireNonNull(roles, "roles");
      EnumSet<EncoderRole> copy = EnumSet.noneOf(EncoderRole.class);
      copy.addAll(roles);
      return Collections.unmodifiableSet(copy);
    }
  }

  /**
   * Returns the aggregate native-retirement disposition for all handles owned by this surface.
   * Refusal takes precedence over work still retiring, followed by handles that remain active.
   * A surface with no handles is already retired.
   */
  public SessionHandle.RetirementStatus retirementStatus() {
    SessionHandle.RetirementStatus aggregate = SessionHandle.RetirementStatus.RETIRED;
    for (SessionHandle handle : handles) {
      SessionHandle.RetirementStatus status = handle.retirementStatus();
      if (status == SessionHandle.RetirementStatus.REFUSED) {
        return status;
      }
      if (status == SessionHandle.RetirementStatus.RETIRING) {
        aggregate = status;
      } else if (status == SessionHandle.RetirementStatus.ACTIVE
          && aggregate == SessionHandle.RetirementStatus.RETIRED) {
        aggregate = status;
      }
    }
    return aggregate;
  }

  @Override
  public void close() {
    IllegalStateException aggregateFailure = null;
    for (SessionHandle handle : handles) {
      try {
        handle.close();
      } catch (RuntimeException failure) {
        aggregateFailure = addFailure(aggregateFailure, failure);
      }
      SessionHandle.RetirementStatus status = handle.retirementStatus();
      if (status != SessionHandle.RetirementStatus.RETIRED) {
        aggregateFailure =
            addFailure(
                aggregateFailure,
                new IllegalStateException("Native session handle has not retired: " + status));
      }
    }
    if (aggregateFailure != null) {
      throw aggregateFailure;
    }
  }

  private static IllegalStateException addFailure(
      IllegalStateException aggregateFailure, RuntimeException failure) {
    IllegalStateException aggregate = aggregateFailure;
    if (aggregate == null) {
      aggregate = new IllegalStateException("Inference surface retirement was not quiescent");
    }
    aggregate.addSuppressed(failure);
    return aggregate;
  }
}
