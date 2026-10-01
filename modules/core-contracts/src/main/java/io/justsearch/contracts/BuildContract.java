/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as subject to a build-time invariant
 * (tempdoc 400 §8.6 Layer 6 tier taxonomy).
 *
 * <p>A {@code @BuildContract} is enforced by a corresponding build-time check —
 * typically an ArchUnit test in {@code modules/app-launcher/src/test/} or a
 * unit test co-located with the contract. The check must run on every CI
 * build so violations block merge.
 *
 * <p>Examples of appropriate invariants for this tier:
 *
 * <ul>
 *   <li>Closure-property / pure-encoder constraints (§7.5 in tempdoc 397).
 *   <li>Single-authority / one-owner rules (e.g. "only the configuration
 *       module reads {@link System#getenv}").
 *   <li>Layering rules forbidding cross-module reach-ins.
 *   <li>Logging-convention rules (e.g. SLF4J-only; no {@link System#err};
 *       tempdoc 289 RC1 target).
 * </ul>
 *
 * <p>This tier is chosen deliberately over sibling tiers:
 *
 * <ul>
 *   <li>{@code @BootContract} (deferred) verifies at composition-root and
 *       fails startup; appropriate when violation = dead system.
 *   <li>{@code @SampleContract} (deferred) samples at runtime with a cost
 *       budget; appropriate when violation is detectable only under load.
 *   <li>{@link AdvisoryContract} emits a log-only signal; appropriate when
 *       the invariant is aspirational or degradation-tolerant.
 * </ul>
 *
 * <p>The pair {@link BuildContract} + {@link AdvisoryContract} is the Phase 1
 * scope for tempdoc 400 LR6-a; {@code @BootContract} + {@code @SampleContract}
 * are deferred because they require runtime-harness infrastructure with no
 * codebase precedent.
 *
 * <p>Located in the dep-free {@code modules/core-contracts} module since
 * tempdoc 400 §22 Issue A: previous home {@code modules/ipc-common} pulled in
 * the retired gRPC dependency which made the annotations unreachable from {@code modules/ort-common},
 * {@code modules/worker-core}, and {@code modules/app-launcher} test sources.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface BuildContract {

  /** Short human-readable description of what the contract asserts. */
  String description();

  /**
   * Originating tempdoc reference (e.g. {@code "397 §14.25"} or
   * {@code "289 RC1"}). Used by LR6-b audit + LR6-c violation projection to
   * group contracts by their source discussion.
   */
  String tempdoc() default "";

  /**
   * Identifier of the build-time check that enforces this invariant
   * (e.g. {@code "ClosurePropertyTest"} or {@code "Slf4jOnlyRuleTest"}).
   * Optional but useful: a reader can navigate directly to the enforcer.
   */
  String enforcer() default "";
}
