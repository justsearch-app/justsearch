/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import java.util.Objects;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

/**
 * Packs encoder calls against a {@link GpuArenaPlan} (Spec E revision 4 §2.2 "Call shapes"). The
 * coefficients and the budget come from the plan the caller read for this call, and nothing here
 * holds state.
 *
 * <p>A call over the next items, in order, is admitted when {@code paddedCount(n) ×
 * f(role, paddedLength(longest of the n))} is within the role's packing budget and n is within
 * today's count cap. Without a plan, on a profile that keeps today's constants, or for a role whose
 * activation was not measured, the call takes today's count: {@code min(countCap, remaining)}.
 * A single item is always taken; {@link #acquireForCall} sends an infeasible GPU shape to the CPU.
 */
public final class GpuCallPacker {
  private GpuCallPacker() {}

  /**
   * The number of the next items one call takes.
   *
   * @param plan the plan read for this call; empty when none is applied
   * @param role the encoder role making the call
   * @param countCap today's count cap of the call site
   * @param remaining items left, at least one
   * @param itemLength the length in tokens of the item at an offset from the first remaining item
   * @param paddedLength the length a call pads its longest item to
   * @param paddedCount the item count a call of n items pads to
   * @return the number of items, at least one and at most {@code min(countCap, remaining)}
   */
  public static int itemsPerCall(
      Optional<GpuArenaPlan> plan,
      EncoderRole role,
      int countCap,
      int remaining,
      IntUnaryOperator itemLength,
      IntUnaryOperator paddedLength,
      IntUnaryOperator paddedCount) {
    Objects.requireNonNull(plan, "plan");
    if (countCap < 1 || remaining < 1) {
      throw new IllegalArgumentException("a call needs at least one item");
    }
    int limit = Math.min(countCap, remaining);
    Optional<GpuArenaPlan.Role> packed = packedRole(plan, role);
    if (packed.isEmpty()) return limit;
    GpuArenaPlan.Role budget = packed.get();
    GpuArenaPlan.Activation activation = budget.activation().orElseThrow();
    limit = Math.min(limit, budget.countCap());
    int longest = 0;
    int admitted = 0;
    for (int n = 1; n <= limit; n++) {
      longest = Math.max(longest, itemLength.applyAsInt(n - 1));
      double cost = paddedCount.applyAsInt(n) * activation.perItemMiB(paddedLength.applyAsInt(longest));
      if (cost > budget.packingMiB()) break;
      admitted = n;
    }
    // An item that cannot fit on the GPU still runs once, on a CPU lease selected below.
    return Math.max(1, admitted);
  }

  /** {@link #itemsPerCall} for a call that pads to its longest item and does not pad its count. */
  public static int itemsPerCall(
      Optional<GpuArenaPlan> plan, EncoderRole role, int countCap, int remaining,
      IntUnaryOperator itemLength) {
    return itemsPerCall(plan, role, countCap, remaining, itemLength, IntUnaryOperator.identity(),
        IntUnaryOperator.identity());
  }

  /**
   * Acquires the session for the actual padded call shape. A GPU budget limits GPU work only:
   * when it cannot retain the weights or hold this shape, the same work runs on the CPU session.
   */
  public static SessionHandle.Lease acquireForCall(
      SessionHandle sessions,
      SessionAcquisitionRequest request,
      EncoderRole role,
      int paddedCount,
      int paddedLength) {
    Objects.requireNonNull(sessions, "sessions");
    return acquireForCall(sessions, request, role, paddedCount, paddedLength, sessions.arenaPlan());
  }

  /** Uses the same packing-plan snapshot for lease selection and a retained lease's later calls. */
  public static SessionHandle.Lease acquireForCall(
      SessionHandle sessions,
      SessionAcquisitionRequest request,
      EncoderRole role,
      int paddedCount,
      int paddedLength,
      Optional<GpuArenaPlan> plan) {
    Objects.requireNonNull(sessions, "sessions");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(plan, "plan");
    if (paddedCount < 1 || paddedLength < 1) {
      throw new IllegalArgumentException("a call needs at least one padded item and token");
    }
    Optional<GpuArenaPlan.Role> budget = plan.filter(GpuArenaPlan::planned)
        .flatMap(snapshot -> snapshot.role(role));
    boolean cpu = (budget.isEmpty() && sessions.requiresGpuPacking())
        || budget.filter(rolePlan -> rolePlan.capMiB() <= rolePlan.weightsMiB()
            || rolePlan.activation().filter(activation -> paddedCount
                * activation.perItemMiB(paddedLength) > rolePlan.packingMiB()).isPresent()).isPresent();
    return cpu ? sessions.acquireCpu(request) : sessions.acquire(request);
  }

  /**
   * The longest late-chunking input one call may take: the plan's lateMax on a planned profile,
   * otherwise {@code today}.
   */
  public static int lateChunkingMaxTokens(Optional<GpuArenaPlan> plan, int today) {
    Objects.requireNonNull(plan, "plan");
    return packedRole(plan, EncoderRole.EMBEDDING).isPresent()
        ? Math.min(today, plan.get().lateMaxTokens()) : today;
  }

  /**
   * Whether the plan sends {@code role}'s bulk calls to the CPU, with the reason; empty when the
   * plan has no reason to (and always without a plan).
   */
  public static Optional<String> bulkCpuReason(Optional<GpuArenaPlan> plan, EncoderRole role) {
    Objects.requireNonNull(plan, "plan");
    return plan.filter(GpuArenaPlan::planned)
        .flatMap(p -> p.role(role))
        .flatMap(GpuArenaPlan.Role::bulkCpuReason);
  }

  private static Optional<GpuArenaPlan.Role> packedRole(
      Optional<GpuArenaPlan> plan, EncoderRole role) {
    return plan.filter(GpuArenaPlan::planned)
        .flatMap(p -> p.role(role))
        .filter(r -> r.activation().isPresent());
  }
}
