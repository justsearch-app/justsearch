/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.core.execution.TestEngineExecutors;
import org.junit.jupiter.api.AfterEach;

/** One explicit registry/bundle per component test, closed even when runtime setup fails. */
public abstract class LuceneExecutorTestBase {
  private final TestEngineExecutors testExecutors = new TestEngineExecutors();
  private final LuceneExecutorRegistrations luceneExecutors =
      new LuceneExecutorRegistrations(testExecutors);

  protected final LuceneExecutorRegistrations testLuceneExecutors() { return luceneExecutors; }

  protected final LuceneRuntimeBuilder withTestExecutors(LuceneRuntimeBuilder builder) {
    return builder.withExecutorRegistrations(luceneExecutors);
  }

  @AfterEach
  protected final void closeTestExecutors() {
    try { luceneExecutors.close(); }
    finally { testExecutors.close(); }
  }
}
