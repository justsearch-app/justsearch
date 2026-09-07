/* SPDX-License-Identifier: Apache-2.0 */
/**
 * The Engine's composition root — lane F design 3.2.
 *
 * <p>Everything in this package sits outside the three rings (API front / core / edges). It binds
 * implementations to the ports catalogued in design 3.3 and owns the two ring-spanning sequences,
 * startup and shutdown. It is the only package in the repo that may depend on both the
 * application half and the index half of the Engine.
 *
 * <p>The complementary pin is ArchUnit rule 6b in
 * {@code modules/app-launcher/src/test/java/io/justsearch/app/launcher/LayeringEnforcementTest.java}:
 * only this package, {@code io.justsearch.indexerworker..} and {@code io.justsearch.adapters..}
 * may depend on {@code io.justsearch.indexerworker.{server,services,loop}..}. Sharing a JVM does
 * not license application code to reach past a port into Lucene.
 *
 * <p>Stage A of lane F populates this package: A1 creates it, A6 binds the search and indexing
 * ports, A15 records the decision as ADR-0049.
 */
package io.justsearch.app.engine;
