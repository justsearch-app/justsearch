package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for utility methods in KnowledgeServer.
 *
 * <p>These are pure functions or simple gauge helpers that can be tested in isolation.
 * Uses reflection to access private methods.
 */
@DisplayName("KnowledgeServer utility methods")
class KnowledgeServerUtilsTest {

  // ==================== padRight tests ====================

  @Nested
  @DisplayName("padRight()")
  class PadRightTests {

    @Test
    @DisplayName("null string becomes 'null' and pads to width")
    void nullString_returnsNullPaddedToWidth() throws Exception {
      String result = invokePadRight(null, 10);
      assertEquals("null      ", result);
      assertEquals(10, result.length());
    }

    @Test
    @DisplayName("shorter string pads with spaces")
    void shorterThanWidth_padsWithSpaces() throws Exception {
      String result = invokePadRight("abc", 6);
      assertEquals("abc   ", result);
      assertEquals(6, result.length());
    }

    @Test
    @DisplayName("exact width string returns unchanged")
    void exactWidth_returnsUnchanged() throws Exception {
      String result = invokePadRight("abcdef", 6);
      assertEquals("abcdef", result);
    }

    @Test
    @DisplayName("longer string truncates with ellipsis prefix")
    void longerThanWidth_truncatesWithEllipsis() throws Exception {
      // "abcdefghij" (10 chars) with width 6 should be "...hij"
      String result = invokePadRight("abcdefghij", 6);
      assertEquals("...hij", result);
      assertEquals(6, result.length());
    }

    @Test
    @DisplayName("string much longer than width truncates correctly")
    void muchLongerThanWidth_truncatesCorrectly() throws Exception {
      // "/very/long/path/to/some/file.txt" with width 15
      String result = invokePadRight("/very/long/path/to/some/file.txt", 15);
      assertEquals("...ome/file.txt", result);
      assertEquals(15, result.length());
    }

    @Test
    @DisplayName("width of 4 with long string shows only ellipsis and 1 char")
    void minimalWidth_showsEllipsisAndOneChar() throws Exception {
      String result = invokePadRight("abcdefgh", 4);
      assertEquals("...h", result);
    }

    @Test
    @DisplayName("empty string pads to width")
    void emptyString_padsToWidth() throws Exception {
      String result = invokePadRight("", 5);
      assertEquals("     ", result);
      assertEquals(5, result.length());
    }

    @Test
    @DisplayName("single char pads to width")
    void singleChar_padsToWidth() throws Exception {
      String result = invokePadRight("x", 4);
      assertEquals("x   ", result);
    }
  }

  // ==================== parseMigrationState tests ====================

  @Nested
  @DisplayName("parseMigrationState()")
  class ParseMigrationStateTests {

    @Test
    @DisplayName("null returns IDLE")
    void null_returnsIdle() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.IDLE, invokeParseMigrationState(null));
    }

    @Test
    @DisplayName("empty string returns IDLE")
    void emptyString_returnsIdle() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.IDLE, invokeParseMigrationState(""));
    }

    @Test
    @DisplayName("blank string returns IDLE")
    void blankString_returnsIdle() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.IDLE, invokeParseMigrationState("   "));
    }

    @Test
    @DisplayName("'IDLE' returns IDLE")
    void idleUppercase_returnsIdle() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.IDLE, invokeParseMigrationState("IDLE"));
    }

    @Test
    @DisplayName("'idle' (lowercase) returns IDLE")
    void idleLowercase_returnsIdle() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.IDLE, invokeParseMigrationState("idle"));
    }

    @Test
    @DisplayName("'Migrating' (mixed case) returns MIGRATING")
    void migratingMixedCase_returnsMigrating() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.MIGRATING, invokeParseMigrationState("Migrating"));
    }

    @Test
    @DisplayName("'MIGRATING' returns MIGRATING")
    void migratingUppercase_returnsMigrating() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.MIGRATING, invokeParseMigrationState("MIGRATING"));
    }

    @Test
    @DisplayName("'SWITCHING' returns SWITCHING")
    void switching_returnsSwitching() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.SWITCHING, invokeParseMigrationState("SWITCHING"));
    }

    @Test
    @DisplayName("'FAILED' returns FAILED")
    void failed_returnsFailed() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.FAILED, invokeParseMigrationState("FAILED"));
    }

    @Test
    @DisplayName("value with whitespace trims and parses")
    void valueWithWhitespace_trimsAndParses() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.MIGRATING,
          invokeParseMigrationState("  migrating  "));
    }

    @Test
    @DisplayName("invalid value returns FAILED")
    void invalidValue_returnsFailed() throws Exception {
      assertEquals(
          IndexGenerationManager.MigrationState.FAILED, invokeParseMigrationState("UNKNOWN"));
    }

    @Test
    @DisplayName("numeric value returns FAILED")
    void numericValue_returnsFailed() throws Exception {
      assertEquals(IndexGenerationManager.MigrationState.FAILED, invokeParseMigrationState("123"));
    }

    @Test
    @DisplayName("all valid enum values parse correctly")
    void allValidValues_parseCorrectly() throws Exception {
      for (IndexGenerationManager.MigrationState state :
          IndexGenerationManager.MigrationState.values()) {
        assertEquals(state, invokeParseMigrationState(state.name()));
        assertEquals(state, invokeParseMigrationState(state.name().toLowerCase(Locale.ROOT)));
      }
    }
  }

  // ==================== safeJobQueueDepth tests ====================

  @Nested
  @DisplayName("safeJobQueueDepth()")
  class SafeJobQueueDepthTests {

    @Test
    @DisplayName("null queue returns zero")
    void nullQueue_returnsZero() throws Exception {
      KnowledgeServer server = createServerWithJobQueue(null);
      assertEquals(0L, invokeSafeJobQueueDepth(server));
    }

    @Test
    @DisplayName("working queue returns depth")
    void workingQueue_returnsDepth() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      queue.setQueueDepth(42L);
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(42L, invokeSafeJobQueueDepth(server));
    }

    @Test
    @DisplayName("negative depth returns zero (clamped)")
    void negativeDepth_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      queue.setQueueDepth(-5L);
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafeJobQueueDepth(server));
    }

    @Test
    @DisplayName("throwing queue returns zero")
    void throwingQueue_returnsZero() throws Exception {
      StubJobQueue queue = new StubJobQueue();
      queue.setShouldThrow(true);
      KnowledgeServer server = createServerWithJobQueue(queue);
      assertEquals(0L, invokeSafeJobQueueDepth(server));
    }
  }

  // ==================== Reflection helpers ====================

  private static String invokePadRight(String s, int n) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("padRight", String.class, int.class);
    method.setAccessible(true);
    return (String) method.invoke(null, s, n);
  }

  private static IndexGenerationManager.MigrationState invokeParseMigrationState(String raw)
      throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("parseMigrationState", String.class);
    method.setAccessible(true);
    return (IndexGenerationManager.MigrationState) method.invoke(null, raw);
  }

  private static long invokeSafeJobQueueDepth(KnowledgeServer server) throws Exception {
    Method method = KnowledgeServer.class.getDeclaredMethod("safeJobQueueDepth");
    method.setAccessible(true);
    return (long) method.invoke(server);
  }

  /**
   * Creates a KnowledgeServer with only the jobQueue field set for testing safe* methods. Uses
   * Mockito to allocate instance without calling constructor, then injects the field.
   */
  private static KnowledgeServer createServerWithJobQueue(JobQueue jobQueue) throws Exception {
    KnowledgeServer server =
        org.mockito.Mockito.mock(KnowledgeServer.class, org.mockito.Mockito.CALLS_REAL_METHODS);

    // Inject jobQueue field
    setField(server, "jobQueue", jobQueue);

    return server;
  }

  private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
    for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // Not declared at this level of the hierarchy; fall through and try the superclass.
      }
    }
    throw new NoSuchFieldException(name);
  }

  private static void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = findField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  // ==================== Stub implementations ====================

  /** Stub JobQueue for testing safe* gauge methods. */
  private static final class StubJobQueue implements JobQueue {
    private long queueDepth = 0L;
    private boolean shouldThrow = false;

    void setQueueDepth(long depth) {
      this.queueDepth = depth;
    }

    void setShouldThrow(boolean shouldThrow) {
      this.shouldThrow = shouldThrow;
    }

    @Override
    public long queueDepth() {
      if (shouldThrow) {
        throw new RuntimeException("Simulated failure");
      }
      return queueDepth;
    }

    @Override
    public void open() throws SQLException, IOException {}

    @Override
    public void returnUnfinishedClaims(java.util.Collection<IndexJob> claims) {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return 0;
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
