/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationKind;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

final class OperationDescriptorPreparationTest {
  @Test
  void identityBoundCountsUtf8BytesRatherThanJavaCharacters() {
    String identity = "{\"metadata\":\"" + "é".repeat(131072) + "\"}";
    org.junit.jupiter.api.Assertions.assertTrue(identity.length() < 262144);
    assertThrows(IllegalArgumentException.class,
        () -> new OperationDescriptor(OperationKind.OPERATION, "core.test", identity));
  }
}
