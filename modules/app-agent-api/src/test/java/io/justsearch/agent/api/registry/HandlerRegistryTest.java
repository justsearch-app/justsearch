package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HandlerRegistry} per tempdoc 429 §E.4 + §F closure.
 *
 * <p>Map-backed boot-time registry: register / resolve / duplicate-rejection.
 */
final class HandlerRegistryTest {

  private static OperationHandler stub(String marker) {
    return (argumentsJson, context) -> OperationResult.success(marker);
  }

  @Test
  void resolveReturnsRegisteredHandler() {
    HandlerRegistry registry = new HandlerRegistry();
    OperationRef id = new OperationRef("core.example");
    OperationHandler handler = stub("ok");

    registry.register(id, handler);

    assertTrue(registry.resolve(id).isPresent());
    assertEquals(handler, registry.resolve(id).get());
  }

  @Test
  void resolveOfUnknownReturnsEmpty() {
    HandlerRegistry registry = new HandlerRegistry();
    assertTrue(registry.resolve(new OperationRef("core.nonexistent")).isEmpty());
  }

  @Test
  void duplicateRegistrationThrows() {
    HandlerRegistry registry = new HandlerRegistry();
    OperationRef id = new OperationRef("core.dup");
    registry.register(id, stub("first"));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(id, stub("second")));
    assertTrue(ex.getMessage().contains("Duplicate"));
  }

  @Test
  void registeredIdsExposesAllRegisteredEntries() {
    HandlerRegistry registry = new HandlerRegistry();
    OperationRef a = new OperationRef("core.a");
    OperationRef b = new OperationRef("core.b");
    registry.register(a, stub("a"));
    registry.register(b, stub("b"));

    java.util.Set<OperationRef> ids = registry.registeredIds();
    assertTrue(ids.contains(a));
    assertTrue(ids.contains(b));
    assertEquals(2, ids.size());
  }

  @Test
  void lateRegistrationBecomesVisibleToConcurrentReaders() throws Exception {
    HandlerRegistry registry = new HandlerRegistry();
    OperationRef id = new OperationRef("core.late");
    OperationHandler handler = stub("late");
    CountDownLatch readerStarted = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      var resolved =
          executor.submit(
              () -> {
                readerStarted.countDown();
                while (!Thread.currentThread().isInterrupted()) {
                  var candidate = registry.resolve(id);
                  if (candidate.isPresent()) {
                    return candidate.orElseThrow();
                  }
                  Thread.onSpinWait();
                }
                throw new InterruptedException("reader stopped before late registration");
              });

      assertTrue(readerStarted.await(5, TimeUnit.SECONDS));
      registry.register(id, handler);

      assertSame(handler, resolved.get(5, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void registeredIdsIsStableInsertionOrderSnapshot() {
    HandlerRegistry registry = new HandlerRegistry();
    OperationRef a = new OperationRef("core.a");
    OperationRef b = new OperationRef("core.b");
    OperationRef later = new OperationRef("core.later");
    registry.register(a, stub("a"));
    registry.register(b, stub("b"));

    var snapshot = registry.registeredIds();
    registry.register(later, stub("later"));

    assertEquals(List.of(a, b), new ArrayList<>(snapshot));
    assertEquals(List.of(a, b, later), new ArrayList<>(registry.registeredIds()));
    assertThrows(UnsupportedOperationException.class, () -> snapshot.add(later));
  }

  @Test
  void isEmptyReportsRegistryState() {
    HandlerRegistry registry = new HandlerRegistry();
    assertTrue(registry.isEmpty());
    registry.register(new OperationRef("core.x"), stub("x"));
    assertFalse(registry.isEmpty());
  }
}
