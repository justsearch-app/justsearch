/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.intent;

import io.justsearch.core.context.EngineContext;

import io.justsearch.agent.api.registry.Operation;

/**
 * Tempdoc 875 C.3 — the argument-scope half of "a durable consent may only cover invocations within
 * the scope the user could foresee when granting it".
 *
 * <p>{@link DurableGrantStore} answers <em>is there a grant?</em>; this collaborator answers the
 * second, independent question: <em>do these arguments fall inside the containment the grant was
 * granted against?</em> The two are separate because a durable grant is deliberately args-INdependent
 * (the store's own model) — a simplification that is defensible for an operation whose reach is fixed
 * and indefensible for one that takes a filesystem path as an argument.
 *
 * <p>It is supplied TOGETHER with the store —
 * {@code OperationExecutorImpl.setDurableGrantStore(store, scope)} — so durable grants cannot be wired
 * without a scope.
 *
 * <p><b>Contract.</b> {@code false} means "this grant does not apply to this invocation", NOT "deny":
 * the gate simply falls through to the capsule path and the user gets the ordinary confirm dialog,
 * which already renders the arguments. So the capability is preserved (tempdoc 811 C-2a: "agents
 * ingesting arbitrary paths is the point"); only the blanket-consent shortcut is removed. An
 * implementation MUST therefore fail closed — every uncertainty returns {@code false}, because the
 * cost of a false {@code false} is one prompt and the cost of a false {@code true} is a silent
 * unforeseen action.
 */
@FunctionalInterface
public interface DurableGrantScope {

  /**
   * Whether a durable grant on {@code op} extends to this specific invocation's arguments.
   *
   * @param op the operation being dispatched (never null at the call site).
   * @param argumentsJson the raw arguments JSON as dispatched; may be null or unparseable.
   * @return {@code true} when containment holds (or is not a defined concept for this operation);
   *     {@code false} whenever containment cannot be PROVEN — which costs a confirmation, never a
   *     silent action.
   */
  boolean coversArguments(Operation op, String argumentsJson, EngineContext engineContext);
}
