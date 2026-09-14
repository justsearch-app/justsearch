/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import java.util.UUID;

/** Test-only access to the owning codec, avoiding a duplicate accepted-envelope representation. */
public final class RecordedParentFixture {
  private RecordedParentFixture() {}

  public static OperationStore.Preparation prepare(OperationAttemptRunner.Request request, RecordedRootPlan plan) {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    UUID nonce = UUID.randomUUID();
    var preparation = new OperationPreparation("{}", RecordedRootPlan.SCHEMA,
        plan.toReplayPayload(), OperationPreparation.Content.METADATA);
    return new OperationStore.Preparation(nonce, codec.encode(codec.freeze(request.key(), nonce,
        request.descriptor(), preparation, request.context(), request.provenance())));
  }
}
