/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.runtime.ManagedChild;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class MutableManagedChildRegistryTest {
  private static ManagedChild child(String id, long pid) {
    return new ManagedChild(
        id, ManagedChild.Kind.EXTRACTION, pid, "2026-09-08T12:00:00Z",
        "c:\\java.exe", "stdio", null, null, "argv");
  }

  @Test
  void mutationBecomesVisibleOnlyAfterPersistenceSucceeds() throws Exception {
    var persisted = new AtomicReference<List<ManagedChild>>();
    var registry = new MutableManagedChildRegistry();
    registry.seed(List.of(child("old", 1)));
    registry.installWriter(persisted::set);

    registry.register(child("new", 2));

    assertEquals(List.of("old", "new"), registry.snapshot().stream().map(ManagedChild::id).toList());
    assertEquals(registry.snapshot(), persisted.get());
  }

  @Test
  void persistenceFailureRetainsPreviousRegistryState() {
    var registry = new MutableManagedChildRegistry();
    registry.seed(List.of(child("old", 1)));
    registry.installWriter(ignored -> { throw new IOException("disk full"); });

    assertThrows(IOException.class, () -> registry.register(child("new", 2)));

    assertEquals(List.of("old"), registry.snapshot().stream().map(ManagedChild::id).toList());
  }
}
