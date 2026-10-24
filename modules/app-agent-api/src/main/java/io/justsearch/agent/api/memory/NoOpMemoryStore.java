/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.memory;

import java.util.List;

/** Tempdoc 561 P-E — Null Object {@link MemoryStore} for unconfigured environments + test mocks. */
final class NoOpMemoryStore implements MemoryStore {

  static final NoOpMemoryStore INSTANCE = new NoOpMemoryStore();

  private NoOpMemoryStore() {}

  @Override
  public void remember(MemoryRecord record) {}

  @Override
  public List<MemoryRecord> whatItKnows() {
    return List.of();
  }

  @Override
  public void forget(String id) {}

  @Override
  public ForgetResult forgetIf(String id, String revision) {
    return new ForgetResult(false, null);
  }

  @Override
  public void clear() {}
}
