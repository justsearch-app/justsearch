/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.settings.SettingsResetPreparation;
import io.justsearch.core.context.EngineContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Uses the production codec and preparation/acceptance ports for settings owner fault tests. */
public final class SettingsResetTestSupport {
  private SettingsResetTestSupport() {}

  public static OperationAttemptRunner.PreparedAttempt accept(OperationAttemptRunner runner, OperationPreparation preparation) {
    var context = EngineProvenance.internal("settings-reset-owner-test",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, Instant.EPOCH, Optional.empty());
    var request = new OperationAttemptRunner.Request(null, OperationDescriptor.invocation(
        OperationKind.SETTINGS_APPLY, SettingsResetPreparation.OPERATION_ID, preparation.argumentsJson(), false),
        context, provenance);
    return runner.withPreparation(request, scope -> {
      UUID nonce = UUID.randomUUID();
      var codec = new PreparedInvocationCodec(StoreCipher.disabled());
      var envelope = codec.freeze(scope.request().key(), nonce, scope.request().descriptor(), preparation, context, provenance);
      runner.savePreparation(scope.request(), new OperationStore.Preparation(nonce, codec.encode(envelope)));
      return new Prepared(scope.request(), nonce);
    }).accept(runner);
  }

  private record Prepared(OperationAttemptRunner.Request request, UUID nonce) {
    OperationAttemptRunner.PreparedAttempt accept(OperationAttemptRunner runner) { return runner.acceptPrepared(request, nonce); }
  }
}
