/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Hashes an owner's typed applied values; source traces and accepted revisions are not inputs. */
public final class AppliedConfigurationVersion {
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private AppliedConfigurationVersion() {}

  /**
   * Projects only declared dependencies. Owners must supply their actual normalized values,
   * including explicit null for an absent optional value, rather than raw resolution strings.
   * The returned digest is the only public projection; values must not be logged or exposed.
   */
  public static String digest(Set<String> dependencies, Map<String, ?> appliedValues) {
    Objects.requireNonNull(dependencies, "dependencies");
    Objects.requireNonNull(appliedValues, "appliedValues");
    var selected = new TreeMap<String, Object>();
    for (String key : dependencies) {
      if (key == null || key.isBlank() || !appliedValues.containsKey(key)) {
        throw new IllegalArgumentException("Applied values must cover every declared dependency");
      }
      selected.put(key, appliedValues.get(key));
    }
    // Strict serialization deliberately has no raw-input fallback: an invalid value cannot
    // certify a composed component. Map ordering is recursive, including nested owner values.
    String canonical = JSON.writeValueAsString(selected);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
