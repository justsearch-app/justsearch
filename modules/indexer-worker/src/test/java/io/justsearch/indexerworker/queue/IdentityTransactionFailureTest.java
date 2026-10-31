/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class IdentityTransactionFailureTest {
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"import,rollback", "rekey,rollback", "import,operation", "rekey,operation", "import,commit", "rekey,commit", "import,cleanup", "rekey,cleanup"})
  void uncertainTransactionsAreDisposedAndNeverAutocommitted(String operation, String fault) throws Exception {
    Path db = temp.resolve("jobs.db");
    try (var queue = new SqliteJobQueue(db); var store = new SqliteDocumentIdentityStore(db)) {
      queue.open();
      var original = store.resolve("old", 1);
      var destination = store.resolve("dest", 2);
      var field = SqliteDocumentIdentityStore.class.getDeclaredField("connection"); field.setAccessible(true);
      Connection actual = (Connection) field.get(store);
      Connection observed = mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
      AtomicBoolean transactionStarted = new AtomicBoolean();
      doAnswer(call -> { boolean auto = call.getArgument(0); if (!auto) transactionStarted.set(true); actual.setAutoCommit(auto); return null; })
          .when(observed).setAutoCommit(anyBoolean());
      if (fault.equals("rollback") || fault.equals("operation")) {
        doAnswer(call -> {
          String sql = call.getArgument(0);
          if (sql.startsWith("UPDATE document_identity SET path_hash") || sql.startsWith("SELECT path_hash") && operation.equals("import") && transactionStarted.get()) {
            // The first import is installed before an invalid second input triggers rollback.
            if (operation.equals("rekey")) throw new SQLException("operation failed");
          }
          return actual.prepareStatement(sql);
        }).when(observed).prepareStatement(anyString());
        if (fault.equals("rollback")) doThrow(new SQLException("rollback failed")).when(observed).rollback();
      } else if (fault.equals("commit")) {
        doThrow(new SQLException("commit failed")).when(observed).commit();
      } else {
        doThrow(new SQLException("cleanup failed")).when(observed).setAutoCommit(true);
      }
      field.set(store, observed);
      RuntimeException failure = assertThrows(RuntimeException.class, () -> {
        if (operation.equals("import")) {
          var rows = fault.equals("operation") || fault.equals("rollback")
              ? List.of(new DocumentIdentityStore.ImportedIdentity("new", "uid"), new DocumentIdentityStore.ImportedIdentity("invalid", ""))
              : List.of(new DocumentIdentityStore.ImportedIdentity("new", "uid"));
          store.importExisting(rows, 3);
        } else store.rekey("old", "dest", 3);
      });
      if (fault.equals("rollback")) {
        verify(observed, never()).setAutoCommit(true);
        verify(observed).close();
        assertTrue(failure.getSuppressed().length > 0 || failure.getCause() != null);
      }
      if (fault.equals("rollback") || fault.equals("cleanup")) assertThrows(IllegalStateException.class, store::identityCount);
      else assertEquals(2, store.identityCount());
      try (var reopened = new SqliteDocumentIdentityStore(db)) {
        boolean committed = fault.equals("cleanup");
        if (operation.equals("import")) {
          assertEquals(committed, reopened.lookup("new").isPresent());
          assertEquals(original.docUid(), reopened.lookup("old").orElseThrow().docUid());
        } else if (committed) {
          assertTrue(reopened.lookup("old").isEmpty());
          assertEquals(original.docUid(), reopened.lookup("dest").orElseThrow().docUid());
        } else {
          assertEquals(original.docUid(), reopened.lookup("old").orElseThrow().docUid());
          assertEquals(destination.docUid(), reopened.lookup("dest").orElseThrow().docUid());
        }
      }
    }
  }
}
