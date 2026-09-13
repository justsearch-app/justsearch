/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OperationApprovalPreviewTest {
  @Test void fullTargetsAreBoundedByBytesWithoutSilentTruncation() {
    String full = "x".repeat(8192);
    assertEquals(full, new OperationApprovalPreview(full).summary());
    assertThrows(IllegalArgumentException.class, () -> new OperationApprovalPreview(full + "x"));
    assertThrows(IllegalArgumentException.class, () -> new OperationApprovalPreview("界".repeat(2731)));
    assertThrows(IllegalArgumentException.class, () -> new OperationApprovalPreview(" "));
    assertThrows(NullPointerException.class, () -> new OperationApprovalPreview(null));
    assertFalse(new OperationApprovalPreview("private target path").toString().contains("private target path"));
  }
}
