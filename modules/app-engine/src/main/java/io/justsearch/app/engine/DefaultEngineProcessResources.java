/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;

/** Shared process substrate; physical composition owners register their own components. */
public final class DefaultEngineProcessResources implements EngineProcessResources {
  private final EngineResourcePolicy policy;
  private final EngineAdmissionController admission;
  private final EngineExecutorRegistry executors;
  private final EngineComponentRegistry components;

  public DefaultEngineProcessResources() {
    this(EngineResourcePolicy.load());
  }

  DefaultEngineProcessResources(EngineResourcePolicy policy) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    admission = new EngineAdmissionController(policy);
    executors = new DefaultEngineExecutorRegistry(policy);
    components = new DefaultEngineComponentRegistry(policy.retained());
  }

  EngineResourcePolicy policy() { return policy; }

  @Override
  public EngineExecutorRegistry executors() { return executors; }

  @Override
  public EngineComponentRegistry components() { return components; }

  @Override
  public EngineAdmissionController admission() { return admission; }

  @Override
  public OperationLeaseService operationLeases() { return admission; }

  @Override
  public void close() {
    // A live apply lease refuses teardown; its work must keep the executors until retry.
    components.close();
    executors.close();
  }
}
