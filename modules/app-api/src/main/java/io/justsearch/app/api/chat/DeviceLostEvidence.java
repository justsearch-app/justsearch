/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

import java.util.Objects;

/**
 * Evidence that the GPU was lost. {@code code} is one status code from the closed device-lost list
 * that the GPU co-residence work defines; CUDA out-of-memory is never device loss.
 */
public record DeviceLostEvidence(Source source, String code) {
  public DeviceLostEvidence {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(code, "code");
    if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
  }

  /** Where the evidence came from. */
  public enum Source {
    /** An ONNX Runtime CUDA device error on the encoder side. */
    ORT_CUDA,
    /** A chat server exit classified as a suspected device fault. */
    LLAMA_EXIT,
    /** A failed GPU probe after the machine resumed. */
    RESUME_PROBE
  }
}
