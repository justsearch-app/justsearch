/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared argument binding for consent capsules and durable operation identity. This preserves
 * the capsule's existing recursive key sort and raw-input fallback for malformed JSON. It is
 * identity, not validation: the executor still validates before invoking a handler.
 */
public final class CanonicalOperationArguments {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
  private CanonicalOperationArguments() {}

  public static String digest(String argumentsJson) {
    String canonical;
    try { canonical = JSON.writeValueAsString(JSON.readValue(argumentsJson, Object.class)); }
    catch (RuntimeException notJson) { canonical = argumentsJson; }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
