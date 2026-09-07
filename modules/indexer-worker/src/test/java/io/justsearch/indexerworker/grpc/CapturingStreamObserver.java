/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.grpc;

import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link StreamObserver} that records exactly what the transport adapter delivered, and in what
 * order.
 *
 * <p>Shared by {@code WorkerServiceCallsTest} and the two {@code Delegating*ServiceDelegationTest}
 * classes: all three need the same three facts — the frames, the terminal signal, and the count of
 * {@code onCompleted} calls. {@code completedCount} is a count rather than a boolean on purpose:
 * "completed once" and "completed twice" are different wire behaviours, and the difference between
 * {@code streamThenComplete} and {@code streamOpen} is precisely a count of zero versus one.
 */
final class CapturingStreamObserver<T> implements StreamObserver<T> {

  private final List<T> values = new ArrayList<>();
  private final List<String> order = new ArrayList<>();
  private Throwable error;
  private int completedCount;

  /** Every value handed to {@link #onNext}, in delivery order. */
  List<T> values() {
    return List.copyOf(values);
  }

  /** The callback names in the order they arrived, e.g. {@code [onNext, onNext, onCompleted]}. */
  List<String> order() {
    return List.copyOf(order);
  }

  /** The terminal error, or {@code null} if the call did not fail. */
  Throwable error() {
    return error;
  }

  /** How many times {@link #onCompleted} was called. */
  int completedCount() {
    return completedCount;
  }

  @Override
  public void onNext(T value) {
    values.add(value);
    order.add("onNext");
  }

  @Override
  public void onError(Throwable t) {
    error = t;
    order.add("onError");
  }

  @Override
  public void onCompleted() {
    completedCount++;
    order.add("onCompleted");
  }
}
