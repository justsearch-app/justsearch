/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import io.justsearch.indexerworker.ingest.IngestionServiceUnavailableException;
import java.sql.Connection;
import java.sql.SQLException;

/** Availability checks called only while the owning store holds its connection lock. */
final class SqliteServiceAvailability {
  private SqliteServiceAvailability() {}

  static Connection requireOpen(Connection connection, boolean closed) throws SQLException {
    if (closed || connection == null || connection.isClosed()) {
      throw new IngestionServiceUnavailableException("SQLite ingestion service is closed");
    }
    return connection;
  }

  static boolean unavailable(SQLException cause) {
    return switch (cause.getErrorCode() & 0xff) {
      // Fixed SQL/schema/permission failures, ABORT, BUSY, LOCKED, NOMEM, READONLY, INTERRUPT, IOERR, CORRUPT, FULL, CANTOPEN,
      // PROTOCOL, SCHEMA, MISUSE, AUTH, NOTADB. Constraints/types/oversize values stay per-file.
      case 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 21, 22, 23, 24, 26, 27, 28 -> true;
      default -> false;
    };
  }

  static IllegalStateException failure(String message, SQLException cause) {
    return unavailable(cause) ? new IngestionServiceUnavailableException(message, cause)
        : new IllegalStateException(message, cause);
  }
}
