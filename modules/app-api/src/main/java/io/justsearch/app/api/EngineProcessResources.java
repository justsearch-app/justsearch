/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import io.justsearch.core.component.EngineComponentRegistry;
import io.justsearch.core.execution.EngineExecutorRegistry;

/** One process lifetime for admission, execution and component observations from one policy. */
public interface EngineProcessResources extends AutoCloseable {
  EngineExecutorRegistry executors();

  EngineComponentRegistry components();

  EngineAdmissionService admission();

  OperationLeaseService operationLeases();

  /** Close after all component owners and their work have drained. */
  @Override
  void close();
}
