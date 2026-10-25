/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.configuration.excludes.LiteralExclusion;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;

/** Fixed UI data-control owner. No agent or MCP publication and no original source-file mutations. */
public interface ForgetService {
  default boolean hasOwner() { return true; }
  ForgetPlan prepare(String path, LiteralExclusion.Scope scope, EngineContext context);
  OperationExecution execute(OperationRecordHandle record, EngineContext context);
  OperationResult retry(String operationKey, EngineContext context);
  OperationResult withdraw(String operationKey, EngineContext context);
  ForgetPage unfinished(long afterId, int limit, EngineContext context);
  Optional<ForgetOutcome> observe(String operationKey, EngineContext context);

  static ForgetService unavailable() {
    return new ForgetService() {
      @Override public boolean hasOwner() { return false; }
      private IllegalStateException unavailableOwner() {
        return new IllegalStateException("Forget is unavailable in this host");
      }
      @Override public ForgetPlan prepare(String path, LiteralExclusion.Scope scope, EngineContext context) {
        throw unavailableOwner();
      }
      @Override public OperationExecution execute(OperationRecordHandle record, EngineContext context) {
        throw unavailableOwner();
      }
      @Override public OperationResult retry(String operationKey, EngineContext context) { throw unavailableOwner(); }
      @Override public OperationResult withdraw(String operationKey, EngineContext context) { throw unavailableOwner(); }
      @Override public ForgetPage unfinished(long afterId, int limit, EngineContext context) { throw unavailableOwner(); }
      @Override public Optional<ForgetOutcome> observe(String operationKey, EngineContext context) { throw unavailableOwner(); }
    };
  }
}
