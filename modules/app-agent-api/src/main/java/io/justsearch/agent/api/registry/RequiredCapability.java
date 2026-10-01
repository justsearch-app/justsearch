/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Backend-side capability required for an Operation to be invocable.
 *
 * <p>Per tempdoc 429 §6 + §A.7 ExecutorBindingValidator: the executor verifies declared
 * capabilities exist before dispatch; missing capabilities surface as a typed denial
 * rather than silent failure.
 *
 * <p>Sealed type permits a closed V1 vocabulary; new variants land additively as
 * subsystems publish capability handles. {@code IndexedRoot} and {@code GpuAvailable} were
 * removed per tempdoc 737 §8a/§12d: required by no operation anywhere, with resolver arms that
 * lied about what they resolved (dead vocabulary).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = RequiredCapability.WorkerOnline.class, name = "worker-online"),
    @JsonSubTypes.Type(value = RequiredCapability.InferenceOnline.class, name = "inference-online")
})
public sealed interface RequiredCapability
    permits RequiredCapability.WorkerOnline, RequiredCapability.InferenceOnline {

  /** Operation requires the Engine index component to be available through its port. */
  record WorkerOnline() implements RequiredCapability {
    public static final WorkerOnline INSTANCE = new WorkerOnline();
  }

  /** Operation requires the Inference (Brain) process to be reachable via HTTP. */
  record InferenceOnline() implements RequiredCapability {
    public static final InferenceOnline INSTANCE = new InferenceOnline();
  }
}
