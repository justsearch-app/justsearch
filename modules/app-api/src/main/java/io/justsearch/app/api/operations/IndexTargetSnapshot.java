/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Opaque projection of the physical index target selected by the Worker. */
public record IndexTargetSnapshot(String fingerprint, String canonicalInputsJson) {
  public static final int MAX_CANONICAL_INPUTS_BYTES = 262_144;

  public IndexTargetSnapshot {
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Index target fingerprint must be lowercase SHA-256 hex");
    }
    if (canonicalInputsJson == null) {
      throw new IllegalArgumentException("Canonical index inputs are required");
    }
    byte[] inputs = canonicalInputsJson.getBytes(StandardCharsets.UTF_8);
    if (inputs.length > MAX_CANONICAL_INPUTS_BYTES) {
      throw new IllegalArgumentException("Canonical index inputs exceed the byte limit");
    }
    if (!fingerprint.equals(sha256Hex(inputs))) {
      throw new IllegalArgumentException("Index target fingerprint does not match its canonical inputs");
    }
  }

  private static String sha256Hex(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }
}
