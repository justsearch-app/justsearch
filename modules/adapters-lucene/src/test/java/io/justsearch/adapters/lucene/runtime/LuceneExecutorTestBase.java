/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.core.execution.TestEngineExecutors;
import org.junit.jupiter.api.AfterEach;

/** One explicit registry/bundle per component test, closed even when runtime setup fails. */
abstract class LuceneExecutorTestBase {
  private final TestEngineExecutors testExecutors = new TestEngineExecutors();
  private final LuceneExecutorRegistrations luceneExecutors =
      new LuceneExecutorRegistrations(testExecutors);

  final LuceneExecutorRegistrations testLuceneExecutors() { return luceneExecutors; }

  final LuceneRuntimeBuilder withTestExecutors(LuceneRuntimeBuilder builder) {
    return builder.withExecutorRegistrations(luceneExecutors);
  }

  @AfterEach
  final void closeTestExecutors() {
    try { luceneExecutors.close(); }
    finally { testExecutors.close(); }
  }
}
