/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.app.api.indexing.AcceptedProjection;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Worker-owned mapping of a source projection into reserved Lucene identity and witness fields. */
public final class ProjectionDocumentMapper {
  private static final ObjectMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private static final Set<String> RESERVED = Set.of(
      SchemaFields.DOC_ID, SchemaFields.DOC_UID,
      SchemaFields.PROJECTION_SOURCE_ID, SchemaFields.PROJECTION_SOURCE_REVISION,
      SchemaFields.PROJECTION_DIGEST);

  private ProjectionDocumentMapper() {}

  public static IndexDocument toIndexDocument(AcceptedProjection projection) {
    if (projection.kind() != AcceptedProjection.Kind.UPSERT) {
      throw new IllegalArgumentException("Only an upsert has index document fields");
    }
    Object parsed = JSON.readValue(projection.fieldsJson(), Map.class);
    if (!(parsed instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Projection fields must be an object");
    }
    Map<String, Object> fields = new HashMap<>();
    for (var entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String name)) {
        throw new IllegalArgumentException("Projection field name must be a string");
      }
      if (RESERVED.contains(name)) {
        throw new IllegalArgumentException("Projection cannot provide reserved identity field: " + name);
      }
      fields.put(name, entry.getValue());
    }
    fields.put(SchemaFields.DOC_ID, projection.indexId());
    fields.put(SchemaFields.DOC_UID, projection.documentUid());
    fields.put(SchemaFields.PROJECTION_SOURCE_ID, projection.sourceId());
    fields.put(SchemaFields.PROJECTION_SOURCE_REVISION,
        Long.toString(projection.sourceRevision()));
    fields.put(SchemaFields.PROJECTION_DIGEST, projection.fieldsDigest());
    return new IndexDocument(fields);
  }
}
