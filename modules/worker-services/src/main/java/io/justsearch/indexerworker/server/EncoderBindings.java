/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.bgem3.BgeM3Encoder;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import java.util.Objects;

/**
 * Typed registry for the async-loaded encoders/services that {@link
 * io.justsearch.indexerworker.loop.IndexingLoop} and {@link
 * io.justsearch.indexerworker.services.SearchOrchestrator} both consume.
 *
 * <p>Tempdoc 516 P3 / Slice 5 (W7.2) — replaces the 4 IndexingLoop volatile encoder fields
 * (plus the symmetric 2 SearchOrchestrator copies) with a single shared instance held by
 * both classes. {@link io.justsearch.indexerworker.server.DefaultWorkerAppServices} creates
 * one instance and passes it to both ctors; the {@code wireX} methods become single
 * {@code bindings.bindX(...)} calls instead of fanning out across peer setters.
 *
 * <p>The complete encoder set is held in one volatile immutable snapshot. The async-load thread
 * that publishes (typically the gRPC incoming-RPC thread or the deferred-init worker thread) can
 * therefore hand off a coherent set to the indexing-loop thread and any search-handling threads
 * without external synchronization.
 *
 * <p>Concurrency contract — bind operations are publication actions; reads see either the
 * prior reference or the new one. Null is the unbound state and means "the corresponding
 * feature is disabled for this run" (e.g., SPLADE off ⇒ {@link #spladeEncoder()} returns
 * null). All readers are already null-tolerant.
 *
 * <p>P5 boundary: a concrete final class, not a strategy interface.
 */
public final class EncoderBindings {

  /** Immutable, atomically published view of all encoder and service bindings. */
  public record Snapshot(
      SpladeEncoder spladeEncoder,
      BgeM3Encoder bgeM3Encoder,
      NerService nerService,
      DisambiguationService disambiguationService) {

    public static Snapshot empty() {
      return new Snapshot(null, null, null, null);
    }
  }

  private volatile Snapshot snapshot = Snapshot.empty();

  public EncoderBindings() {
    // The empty snapshot is the unbound state.
  }

  /**
   * Publishes a complete encoder set as one atomic owner action.
   *
   * <p>Callers that load several related services should construct one snapshot and publish it
   * once. Readers obtaining {@link #snapshot()} then see either the old complete set or the new
   * complete set, never a field-by-field transition.
   */
  public synchronized void publish(Snapshot next) {
    this.snapshot = Objects.requireNonNull(next, "next");
  }

  /** Backward-compatible single-slot publication for existing model wiring callers. */
  public synchronized void bindSpladeEncoder(SpladeEncoder encoder) {
    Snapshot current = this.snapshot;
    this.snapshot =
        new Snapshot(encoder, current.bgeM3Encoder(), current.nerService(),
            current.disambiguationService());
  }

  /** Backward-compatible single-slot publication for existing model wiring callers. */
  public synchronized void bindBgeM3Encoder(BgeM3Encoder encoder) {
    Snapshot current = this.snapshot;
    this.snapshot =
        new Snapshot(current.spladeEncoder(), encoder, current.nerService(),
            current.disambiguationService());
  }

  /** Backward-compatible single-slot publication for existing model wiring callers. */
  public synchronized void bindNerService(NerService service) {
    Snapshot current = this.snapshot;
    this.snapshot =
        new Snapshot(current.spladeEncoder(), current.bgeM3Encoder(), service,
            current.disambiguationService());
  }

  /** Backward-compatible single-slot publication for existing model wiring callers. */
  public synchronized void bindDisambiguationService(DisambiguationService service) {
    Snapshot current = this.snapshot;
    this.snapshot =
        new Snapshot(current.spladeEncoder(), current.bgeM3Encoder(), current.nerService(),
            service);
  }

  /** Returns the coherent encoder set currently published by the owner. */
  public Snapshot snapshot() {
    return snapshot;
  }

  public SpladeEncoder spladeEncoder() {
    return snapshot.spladeEncoder();
  }

  public BgeM3Encoder bgeM3Encoder() {
    return snapshot.bgeM3Encoder();
  }

  public NerService nerService() {
    return snapshot.nerService();
  }

  public DisambiguationService disambiguationService() {
    return snapshot.disambiguationService();
  }
}
