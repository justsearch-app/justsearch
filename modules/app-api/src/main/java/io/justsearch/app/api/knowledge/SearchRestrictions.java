/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import java.math.BigDecimal;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Exact parsing shared by Search and retrieval-backed Ask, before either admits work. */
public final class SearchRestrictions {
  public static final long MAX_DATE_MS = 253_402_300_799_999L;
  public static final long MAX_SIZE_BYTES = 9_007_199_254_740_991L;
  private static final JsonMapper EXACT_JSON = JsonMapper.builder()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private SearchRestrictions() {}

  /** Read only the restrictions with exact decimal semantics; unrelated request parsing stays intact. */
  public static KnowledgeSearchRequest.Filters fromJson(String json) {
    if (json == null || json.isBlank()) return parse(null);
    return parse(EXACT_JSON.readValue(json, Map.class).get("filters"));
  }

  public static KnowledgeSearchRequest.Filters parse(Object raw) {
    if (raw == null) return KnowledgeSearchRequestFiltersBuilder.builder().build();
    if (!(raw instanceof Map<?, ?> map)) throw invalid("filters", "must be an object");
    String path = null;
    Object folder = map.get("pathPrefix");
    if (folder != null) {
      if (!(folder instanceof String s) || s.isEmpty() || s.length() > 4096) {
        throw invalid("pathPrefix", "must be a non-empty absolute path of at most 4096 characters");
      }
      path = (String) folder;
      try {
        if (!Path.of(path).isAbsolute()) throw invalid("pathPrefix", "must be absolute");
      } catch (InvalidPathException e) {
        throw invalid("pathPrefix", "must be an absolute path");
      }
    }
    Map<?, ?> date = range(map.get("modifiedAt"), "modifiedAt");
    Long fromMs = bound(date, "fromMs", "modifiedAt", MAX_DATE_MS, true);
    Long toMs = bound(date, "toMs", "modifiedAt", MAX_DATE_MS, true);
    ordered(fromMs, toMs, "modifiedAt");
    Map<?, ?> size = range(map.get("sizeBytes"), "sizeBytes");
    Long fromBytes = bound(size, "fromBytes", "sizeBytes", MAX_SIZE_BYTES, false);
    Long toBytes = bound(size, "toBytes", "sizeBytes", MAX_SIZE_BYTES, false);
    ordered(fromBytes, toBytes, "sizeBytes");
    return KnowledgeSearchRequestFiltersBuilder.builder().pathPrefix(path)
        .modifiedAt(fromMs == null && toMs == null ? null
            : new KnowledgeSearchRequest.TimeRangeMs(fromMs, toMs))
        .sizeBytes(fromBytes == null && toBytes == null ? null
            : new KnowledgeSearchRequest.ByteRange(fromBytes, toBytes)).build();
  }

  public static boolean active(KnowledgeSearchRequest.Filters filters) {
    return filters.pathPrefix() != null || filters.modifiedAt() != null || filters.sizeBytes() != null;
  }

  private static Map<?, ?> range(Object raw, String field) {
    if (raw == null) return Map.of();
    if (raw instanceof Map<?, ?> map) return map;
    throw invalid(field, "must be an object");
  }

  private static Long bound(Map<?, ?> map, String key, String field, long max, boolean zeroUnset) {
    Object raw = map.get(key);
    if (raw == null) return null;
    if (!(raw instanceof Number)) throw invalid(field + "." + key, "must be an integer");
    long value;
    try {
      value = new BigDecimal(raw.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw invalid(field + "." + key, "must be an integer in [0, " + max + "]");
    }
    if (value < 0 || value > max) {
      throw invalid(field + "." + key, "must be in [0, " + max + "]");
    }
    return zeroUnset && value == 0 ? null : value;
  }

  private static void ordered(Long from, Long to, String field) {
    if (from != null && to != null && from > to) {
      throw invalid(field, "lower bound must not exceed upper bound");
    }
  }

  public static final class Invalid extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private Invalid(String message) {
      super(message);
    }
  }

  private static Invalid invalid(String field, String message) {
    return new Invalid(field + ": " + message);
  }
}
