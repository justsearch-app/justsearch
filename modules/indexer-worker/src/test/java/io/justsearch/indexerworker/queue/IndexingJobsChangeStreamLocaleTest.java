/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.Closeable;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

final class IndexingJobsChangeStreamLocaleTest {
  @Test
  @ResourceLock("java.util.Locale.default")
  void windowSqlExecutesWhenDefaultLocaleUsesNonAsciiDigits() throws Exception {
    Locale previous = Locale.getDefault(Locale.Category.FORMAT);
    try {
      Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
      // Reinitialize this class's SQL under the chosen locale, independently of test order.
      String name = IndexingJobsChangeStream.class.getName();
      ClassLoader isolated = new ClassLoader(getClass().getClassLoader()) {
        @Override
        protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
          if (!requested.equals(name) && !requested.startsWith(name + "$")) {
            return super.loadClass(requested, resolve);
          }
          synchronized (getClassLoadingLock(requested)) {
            Class<?> loaded = findLoadedClass(requested);
            if (loaded == null) {
              try (var bytes = getResourceAsStream(requested.replace('.', '/') + ".class")) {
                if (bytes == null) throw new ClassNotFoundException(requested);
                byte[] code = bytes.readAllBytes();
                loaded = defineClass(requested, code, 0, code.length);
              } catch (IOException e) {
                throw new ClassNotFoundException(requested, e);
              }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
          }
        }
      };
      var constructor = Class.forName(name, true, isolated).getDeclaredConstructor(
          Connection.class, ReentrantLock.class, Runnable.class);
      constructor.setAccessible(true);
      try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
          var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE jobs(path TEXT PRIMARY KEY, state TEXT, attempts INTEGER, "
            + "last_updated INTEGER, error_message TEXT, retry_after INTEGER, collection TEXT, scan_id TEXT)");
        statement.execute("CREATE INDEX idx_jobs_state_updated ON jobs(state,last_updated)");
        statement.executeUpdate("INSERT INTO jobs VALUES('/locale-test','FAILED',1,1,NULL,NULL,'default',NULL)");
        try (var feed = (Closeable) constructor.newInstance(connection, new ReentrantLock(), (Runnable) () -> {})) {
          var snapshot = ((IndexingJobChangeFeed) feed).subscribeWithSnapshot(delta -> {});
          assertEquals(1, snapshot.items().size());
          assertEquals("FAILED", snapshot.items().getFirst().state());
          assertEquals(64, snapshot.items().getFirst().pathHash().length());
          snapshot.subscription().close();
        }
      }
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, previous);
    }
  }
}
