/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Process ownership vocabulary and contracts (ADR-0051): how the Engine starts, stops and accounts
 * for the child processes it holds.
 *
 * <p>These enums are the single wire authorities for the stop and exit vocabulary. The generated
 * JSON Schemas, the exit ledger and the status surface project them and never restate them. The
 * implementation of {@link io.justsearch.app.api.runtime.child.ChildControl} belongs to the
 * process-ownership work; this package carries contracts only.
 */
package io.justsearch.app.api.runtime.child;
