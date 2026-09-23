/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.EngineProcessResources;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Shared process substrate; physical composition owners register their own components. */
public final class DefaultEngineProcessResources implements EngineProcessResources {
  private final EngineResourcePolicy policy;
  private final ReentrantReadWriteLock publication;
  private final EngineAdmissionController admission;
  private final EngineExecutorRegistry executors;
  private final EngineComponentRegistry components;

  public DefaultEngineProcessResources() {
    this(EngineResourcePolicy.load(), new ReentrantReadWriteLock());
  }

  /** Creates process resources around the composition root's shared publication lock. */
  public DefaultEngineProcessResources(ReentrantReadWriteLock publication) {
    this(EngineResourcePolicy.load(), publication);
  }

  DefaultEngineProcessResources(EngineResourcePolicy policy) {
    this(policy, new ReentrantReadWriteLock());
  }

  DefaultEngineProcessResources(EngineResourcePolicy policy, ReentrantReadWriteLock publication) {
    this.policy = java.util.Objects.requireNonNull(policy, "policy");
    this.publication = java.util.Objects.requireNonNull(publication, "publication");
    admission = new EngineAdmissionController(policy, publication);
    executors = new DefaultEngineExecutorRegistry(policy);
    components = new DefaultEngineComponentRegistry(policy.retained(), publication);
  }

  EngineResourcePolicy policy() { return policy; }

  public ReentrantReadWriteLock publicationLock() { return publication; }

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
