/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

/**
 * The Engine's composition root (lane F design 3.2).
 *
 * <p>Design 3.2 places the composition root <em>outside</em> the three rings: it "binds every
 * implementation and owns the two sequences that span rings, startup and shutdown". This module
 * is the only one permitted to depend on both halves of the Engine — the application half
 * (`app-services`) and the index half (`worker-services` / `worker-core`) — which is what
 * ArchUnit rule 6b pins from the other side: nothing outside
 * {@code io.justsearch.app.engine..}, {@code io.justsearch.indexerworker..} and
 * {@code io.justsearch.adapters..} may reach into
 * {@code io.justsearch.indexerworker.{server,services,loop}..}.
 *
 * <p><b>This class has no behaviour yet, on purpose.</b> Stage A item A1 creates the module and
 * its bytecode so the layering rules, the whole-program dead-code analysis and the module graph
 * see it. Item <b>A6</b> binds the ports here: an in-process {@code SearchPort} and
 * {@code IndexingService} over {@code WorkerAppServices}, replacing
 * {@code RemoteKnowledgeClient}. Until A6 lands this class is deliberately unreferenced and is
 * carried as one accepted entry in {@code modules/dead-code-audit/archunit_store/} — the entry
 * disappears on its own (the store's shrink direction) the moment A6 gives it a caller.
 *
 * <p>Adding a port is a catalogue entry, an interface, and a binding in this class (design 3.3).
 * Nothing else in the repo may construct an implementation of a port.
 */
public final class EngineRoot {

  private EngineRoot() {}
}
