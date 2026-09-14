/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import java.io.IOException;
import java.util.List;

/** Narrow process-owner contract. A mutation returns only after the canonical manifest persists. */
public interface ManagedChildRegistry {
  List<ManagedChild> snapshot();

  void register(ManagedChild child) throws IOException;

  void remove(String childId) throws IOException;

  static ManagedChildRegistry noop() {
    return new ManagedChildRegistry() {
      @Override
      public List<ManagedChild> snapshot() {
        return List.of();
      }

      @Override
      public void register(ManagedChild child) {}

      @Override
      public void remove(String childId) {}
    };
  }
}
