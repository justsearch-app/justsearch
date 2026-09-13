package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void isEmptyReportsRegistryState() {
    HandlerRegistry registry = new HandlerRegistry();
    assertTrue(registry.isEmpty());
    registry.register(new OperationRef("core.x"), stub("x"));
    assertFalse(registry.isEmpty());
  }
}
