/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.contracts;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as subject to a composition-root-time invariant
 * (tempdoc 402 §3.2 Layer 6 tier taxonomy).
 *
 * <p>A {@code @BootContract} is enforced at application startup by a
 * {@link BootContractValidator} registered via {@link java.util.ServiceLoader}
 * ({@code META-INF/services/io.justsearch.contracts.BootContractValidator}).
 * {@link BootContractRunner#validateAll()} is invoked from each composition
 * root's {@code main()} before any service starts; the first violation logs
 * at {@code ERROR} and terminates the JVM with a non-zero exit code.
 *
 * <p>Examples of appropriate invariants for this tier:
 *
 * <ul>
 *   <li>Supplier wiring that must be non-null after composition
 *       (e.g. {@code SearchOrchestrator.setActiveGenerationSupplier}).
 *   <li>Single-owner runtime guards whose absence silently corrupts state
 *       (e.g. "only one VduStatus emitter holds the ingest lock").
 *   <li>Composition-root singletons whose duplicate instantiation is
 *       only detectable once the system is fully wired.
 * </ul>
 *
 * <p>This tier is chosen deliberately over sibling tiers:
 *
 * <ul>
 *   <li>{@link BuildContract} is enforced at build time by ArchUnit or a
 *       co-located unit test; appropriate when the invariant is a static
 *       structural rule detectable from bytecode alone.
 *   <li>{@code @SampleContract} (tempdoc 402 §3.3, deferred) samples at
 *       runtime with a cost budget; appropriate when the violation is only
 *       detectable under load and a per-call cost is affordable.
 *   <li>{@link AdvisoryContract} emits a log-only signal; appropriate when
 *       the invariant is aspirational or degradation-tolerant.
 * </ul>
 *
 * <p>Fail-fast semantics are deliberate: tempdoc 402 §3.2 rationale —
 * "partial-violation startup is worse than no startup." An invariant that
 * fails quietly at boot sets up a downstream runtime failure whose root
 * cause is untraceable.
 *
 * <p>Located in the dep-free {@code modules/core-contracts} module so that
 * ort-common, worker-core, and app-launcher test sources can all reach the
 * annotation without pulling a protocol or framework dependency chain.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface BootContract {

  /** Short human-readable description of what the contract asserts. */
  String description();

  /**
   * Originating tempdoc reference (e.g. {@code "397 §14.27 U1"} or
   * {@code "400 §6.7"}). Used by LR6-b audit + LR6-c violation projection to
   * group contracts by their source discussion. Required — callers must
   * point at the discussion that justifies the invariant.
   */
  String tempdoc();

  /**
   * Fully-qualified class name of the {@link BootContractValidator} that
   * enforces this invariant at startup. The validator must also be
   * registered via
   * {@code META-INF/services/io.justsearch.contracts.BootContractValidator}
   * so {@link BootContractRegistry} can discover it via
   * {@link java.util.ServiceLoader}.
   *
   * <p>This attribute is declarative — the runtime does not dispatch through
   * it — but a later audit check can assert every annotated site names a
   * validator that is present on the ServiceLoader list.
   */
  String validator();
}
