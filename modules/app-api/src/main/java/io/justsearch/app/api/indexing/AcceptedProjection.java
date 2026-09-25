/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.indexing;

import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** One source-owned projection revision, independent of a filesystem path. */
public record AcceptedProjection(
    String sourceId, String documentId, long sourceRevision, Kind kind, String fieldsJson) {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final int PAYLOAD_VERSION = 1;

  public enum Kind { UPSERT, DELETE }

  public AcceptedProjection {
    if (sourceId == null || sourceId.isBlank() || sourceId.length() > 256
        || documentId == null || documentId.isBlank() || documentId.length() > 2048) {
      throw new IllegalArgumentException("Projection requires bounded source and document identities");
    }
    if (sourceRevision < 0) {
      throw new IllegalArgumentException("Projection source revision must be nonnegative");
    }
    Objects.requireNonNull(kind, "kind");
    if (kind == Kind.DELETE) {
      if (fieldsJson != null) throw new IllegalArgumentException("Delete has no document fields");
    } else {
      if (fieldsJson == null || fieldsJson.isBlank()) {
        throw new IllegalArgumentException("Upsert requires document fields");
      }
      JsonNode fields = JSON.readTree(fieldsJson);
      if (!fields.isObject()) throw new IllegalArgumentException("Projection fields must be an object");
      fieldsJson = JSON.writeValueAsString(canonical(fields));
    }
  }

  /** Encodes the accepted source revision inside the existing generation switch buffer. */
  public String encode() {
    var node = JSON.createObjectNode();
    node.put("version", PAYLOAD_VERSION);
    node.put("source_id", sourceId);
    node.put("document_id", documentId);
    node.put("source_revision", sourceRevision);
    node.put("kind", kind.name());
    if (fieldsJson != null) node.set("fields", JSON.readTree(fieldsJson));
    return JSON.writeValueAsString(node);
  }

  public static AcceptedProjection decode(String payload) {
    JsonNode node = JSON.readTree(Objects.requireNonNull(payload, "payload"));
    if (!node.isObject() || !node.path("version").isIntegralNumber()
        || node.path("version").intValue() != PAYLOAD_VERSION
        || !node.path("source_revision").isIntegralNumber()
        || !node.path("source_revision").canConvertToLong()) {
      throw new IllegalArgumentException("Unsupported projection payload");
    }
    String kindText = requiredText(node, "kind");
    Kind kind;
    try {
      kind = Kind.valueOf(kindText);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("Unsupported projection kind", unknown);
    }
    JsonNode fields = node.get("fields");
    if (kind == Kind.UPSERT && (fields == null || !fields.isObject())
        || kind == Kind.DELETE && fields != null) {
      throw new IllegalArgumentException("Projection fields do not match kind");
    }
    return new AcceptedProjection(requiredText(node, "source_id"),
        requiredText(node, "document_id"), node.path("source_revision").longValue(), kind,
        fields == null ? null : JSON.writeValueAsString(fields));
  }

  private static String requiredText(JsonNode node, String name) {
    JsonNode value = node.get(name);
    if (value == null || !value.isString()) {
      throw new IllegalArgumentException("Projection payload requires " + name);
    }
    return value.stringValue();
  }

  /** A key cannot collide with the existing file path namespace. */
  public String journalKey() {
    return "projection:" + sourceId.length() + ":" + sourceId + ":" + documentId;
  }

  /** The Worker reserves this namespace for no-file documents in every generation. */
  public String indexId() {
    return journalKey();
  }

  public String documentUid() {
    return "projection-" + sha256(indexId());
  }

  /** Equal source revision must denote the same exact accepted effect. */
  public boolean sameEffect(AcceptedProjection other) {
    return other != null && sourceId.equals(other.sourceId)
        && documentId.equals(other.documentId) && sourceRevision == other.sourceRevision
        && kind == other.kind && Objects.equals(fieldsJson, other.fieldsJson);
  }

  /** Exact accepted fields witness, independent of JSON object insertion order. */
  public String fieldsDigest() {
    if (kind != Kind.UPSERT) throw new IllegalStateException("Delete has no fields digest");
    return sha256(fieldsJson);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 unavailable", unavailable);
    }
  }

  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      var sorted = JSON.createObjectNode();
      node.properties().stream().sorted(Map.Entry.comparingByKey())
          .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue())));
      return sorted;
    }
    if (node.isArray()) {
      var ordered = JSON.createArrayNode();
      for (JsonNode item : node) ordered.add(canonical(item));
      return ordered;
    }
    return node;
  }
}
