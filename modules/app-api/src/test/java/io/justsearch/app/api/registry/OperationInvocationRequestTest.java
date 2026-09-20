/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class OperationInvocationRequestTest {

  @Test
  void argumentsPreserveExplicitNullAndSnapshotTheCallerMap() {
    var caller = new LinkedHashMap<String, Object>();
    caller.put("paths", java.util.List.of("F:/docs"));
    caller.put("collection", null);

    var request = new OperationInvocationRequest(caller, null, null);
    caller.put("collection", "changed-after-construction");

    assertEquals(java.util.List.of("F:/docs"), request.args().get("paths"));
    assertNull(request.args().get("collection"));
    assertThrows(UnsupportedOperationException.class,
        () -> request.args().put("collection", "mutated"));
  }

  @Test
  void absentArgumentsUseAnImmutableEmptyMap() {
    var request = new OperationInvocationRequest(null, null, null);

    assertEquals(Map.of(), request.args());
    assertThrows(UnsupportedOperationException.class, () -> request.args().put("paths", "x"));
  }
}
