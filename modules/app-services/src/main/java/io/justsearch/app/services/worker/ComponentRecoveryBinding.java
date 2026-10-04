/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentRecoveryAction;
import java.util.Objects;

/** Composition-root projection of an exact registered handle and its optional physical effect. */
public record ComponentRecoveryBinding(ComponentHandle handle, ComponentRecoveryAction action) {
  public ComponentRecoveryBinding {
    Objects.requireNonNull(handle, "handle");
    // Null explicitly means this registered component currently has no physical recovery owner.
  }
}
