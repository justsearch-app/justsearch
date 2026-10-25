/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit catalog permission to open an older index missing optional stored keywords. */
public final class CompatibleFieldAdditions {
  private static final ObjectMapper JSON = new ObjectMapper();

  private CompatibleFieldAdditions() {}

  /** A digest exception needs the actual renderings it certifies, not unrelated input JSON. */
  public static boolean certifies(String digest, String inputs) {
    if (digest == null || inputs == null) return false;
    try {
      return digest.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(inputs.getBytes(StandardCharsets.UTF_8))));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Permission applies only to additions, never removals or changes to a field already present. */
  public static List<IndexFingerprint.InputDifference> remaining(
      List<IndexFingerprint.InputDifference> differences, String expectedInputs) {
    if (differences.isEmpty() || expectedInputs == null) return differences;
    Set<String> optional = new HashSet<>();
    try {
      var root = JustSearchConfigurationLoader.repoRootStatic();
      if (root == null) return differences;
      JsonNode catalog = JSON.readTree(root.resolve("SSOT/catalogs/fields.v1.json").toFile());
      JsonNode expected = JSON.readTree(expectedInputs);
      for (JsonNode field : catalog.path("fields")) {
        if (!field.path("compatibleAddition").asBoolean(false)) continue;
        for (JsonNode shape : expected.path("fields")) {
          // Conservative structural guard: role-bearing, indexed and vector additions still
          // require rebuilding, even if someone mistakenly marks them compatible in the catalog.
          if (field.path("id").equals(shape.path("id"))
              && optionalKeyword(field, "docValues", "multiValued")
              && optionalKeyword(shape, "doc_values", "multi_valued")) {
            optional.add("fields[" + shape.path("id").asText() + "]");
          }
        }
      }
    } catch (RuntimeException unreadable) {
      return differences; // No readable permission means no compatibility exception.
    }
    return differences.stream().filter(difference ->
        !"<absent>".equals(difference.stored()) || !optional.contains(difference.path())).toList();
  }

  private static boolean optionalKeyword(JsonNode field, String docValues, String multiValued) {
    return "keyword".equals(field.path("type").asText())
        && field.path("stored").asBoolean(false)
        && !field.path(docValues).asBoolean(false)
        && !field.path(multiValued).asBoolean(false)
        && field.path("roles").isArray() && field.path("roles").isEmpty()
        && field.path("vector").isMissingNode()
        && (field.path("analyzer").isMissingNode() || field.path("analyzer").isNull());
  }
}
