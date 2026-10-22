/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** A read-only identity database is unavailable independently of document content. */
final class ReadOnlyStoreDeferralTest {
  @Test
  void readOnlyStoreRemainsUncountedBeyondAmbiguousGrace() {
    var service = new BatchServiceFailures();
    var deferred = new ArrayList<Boolean>();
    var failure = new IllegalStateException("identity store is read-only",
        new SQLException("SQLITE_READONLY", null, 8));
    for (int batch = 0; batch < 8; batch++) {
      service.begin();
      service.failure(failure, deferred::add);
      service.finish();
    }
    assertEquals(Collections.nCopies(8, true), deferred,
        "a read-only shared store must not turn into counted document retries");
  }
}
