/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Process ownership vocabulary and contracts (ADR-0051): how the Engine starts, stops and accounts
 * for the child processes it holds.
 *
 * <p>These enums are the single wire authorities for the stop and exit vocabulary. The generated
 * JSON Schemas, the exit ledger and the status surface project them and never restate them.
 *
 * <p>{@link io.justsearch.app.api.runtime.child.DefaultChildControl} is the Engine's implementation:
 * it records every held child in {@code children.v1.jsonl} and stops children only through its
 * {@link io.justsearch.app.api.runtime.child.ProcessTerminator}. {@link
 * io.justsearch.app.api.runtime.child.LedgerReconciler} closes what an earlier Engine left open and
 * never kills.
 */
package io.justsearch.app.api.runtime.child;
