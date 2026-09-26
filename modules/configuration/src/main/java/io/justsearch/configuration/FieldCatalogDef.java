/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable POJO representing the SSOT field catalog definition.
 *
 * <p>This is the "currency" passed between the configuration loader and
 * low-level libraries like adapters-lucene. It contains no runtime logic,
 * only data.
 *
 * <p>Corresponds to {@code SSOT/catalogs/fields.v1.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FieldCatalogDef {

    private final String version;
    private final List<FieldDef> fields;
    private final Map<String, FieldDef> byId;

    @JsonCreator
    public FieldCatalogDef(
            @JsonProperty("version") String version,
            @JsonProperty("fields") List<FieldDef> fields) {
        this.version = version;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.byId = this.fields.stream()
                .collect(Collectors.toUnmodifiableMap(FieldDef::id, f -> f));
    }

    public String version() {
        return version;
    }

    public List<FieldDef> fields() {
        return fields;
    }

    public Map<String, FieldDef> byId() {
        return byId;
    }

    public FieldDef field(String id) {
        return byId.get(id);
    }

    /**
     * Returns the vector dimension from the 'vector' field, or null if not defined.
     */
    public Integer vectorDimension() {
        FieldDef vec = byId.get("vector");
        return vec != null ? vec.vectorDimension() : null;
    }

    /**
     * Creates a minimal test catalog with the given vector dimension.
     *
     * <p>This is a convenience factory for unit tests that need to avoid
     * loading the production SSOT.
     *
     * @param vectorDim the vector dimension (e.g., 4 for tests, 768 for production)
     * @return a minimal field catalog suitable for testing
     */
    public static FieldCatalogDef forTesting(int vectorDim) {
        return new FieldCatalogDef("test", List.of(
                new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
                // Align with SSOT: doc_uid is stored (used for debug + tie-break tracing).
                new FieldDef("doc_uid", "keyword", true, true, List.of("sort", "tiebreak"), null, null, false),
                new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("title", "text", true, false, List.of("highlight"), null, "icu", false),
                // Align with SSOT: content is stored (preview + RAG fallback rely on stored extracted text).
                new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
                // Tempdoc 931 §C.6: the parent content revision feedback is keyed against.
                new FieldDef("content_sha256", "keyword", true, false, List.of(), null, null, false),
                new FieldDef("projection_source_id", "keyword", true, false, List.of(), null, null, false),
                new FieldDef("projection_source_revision", "keyword", true, false, List.of(), null, null, false),
                new FieldDef("projection_digest", "keyword", true, false, List.of(), null, null, false),
                new FieldDef("content_preview", "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldDef("modified_at", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("indexed_at", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("size_bytes", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("mime", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("mime_base", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("file_kind", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("language", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("parent_token_count", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_status", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_processed", "boolean", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_enrichment", "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldDef("vdu_page_count", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("vector", "vector", false, false, List.of("vector"),
                        new VectorSpec(vectorDim), null, false, "preserve-reread-or-reset:embedding_status")
        ));
    }

    /**
     * Creates a test catalog with chunk-related fields for RAG testing.
     *
     * <p>Extends {@link #forTesting(int)} with is_chunk, parent_doc_id, chunk_index,
     * chunk_total, and chunk_content fields needed for chunk lifecycle tests. Chunk content remains
     * indexed but is reconstructed from its stored parent and offsets when read.
     *
     * @param vectorDim the vector dimension (e.g., 4 for tests)
     * @return a field catalog suitable for chunk/RAG testing
     */
    public static FieldCatalogDef forChunkTesting(int vectorDim) {
        return new FieldCatalogDef("chunk-test", List.of(
                new FieldDef("doc_id", "keyword", true, true, List.of("id", "sort"), null, null, false),
                // Align with SSOT: doc_uid is stored (used for debug + tie-break tracing).
                new FieldDef("doc_uid", "keyword", true, true, List.of("sort", "tiebreak"), null, null, false),
                new FieldDef("path", "keyword", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("title", "text", true, false, List.of("highlight"), null, "icu", false),
                // Align with SSOT: content is stored (preview + RAG fallback rely on stored extracted text).
                new FieldDef("content", "text", true, false, List.of("highlight"), null, "icu", false),
                // Tempdoc 931 §C.6: the parent content revision feedback is keyed against.
                new FieldDef("content_sha256", "keyword", true, false, List.of(), null, null, false),
                new FieldDef("content_preview", "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldDef("modified_at", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("indexed_at", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("size_bytes", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("mime", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("mime_base", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("file_kind", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("language", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                // Align with SSOT: the collection scope tag (585 D4b). Chunk docs inherit their
                // parent's value (tempdoc 811 item 3), so chunk-branch scoping tests need it here.
                new FieldDef("collection", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("parent_token_count", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_status", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_processed", "boolean", true, true, List.of("filter"), null, null, false),
                new FieldDef("vdu_enrichment", "text", true, false, List.of("highlight"), null, "icu", false),
                new FieldDef("vdu_page_count", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("vector", "vector", false, false, List.of("vector"),
                        new VectorSpec(vectorDim), null, false, "preserve-reread-or-reset:embedding_status"),
                // Chunk-related fields (RAG + citations)
                new FieldDef("is_chunk", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("parent_doc_id", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("chunk_index", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("chunk_total", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("chunk_content", "text", false, false, List.of("highlight"), null, "icu", false,
                        "rederive-parent-slice"),
                // Span offsets into parent extracted content (0-based, end exclusive) for click-to-verify UI.
                new FieldDef("chunk_start_char", "long", true, true, List.of("filter", "sort"), null, null, false),
                new FieldDef("chunk_end_char", "long", true, true, List.of("filter", "sort"), null, null, false),
                // Tempdoc 931 §C.1: the parent content revision the offsets above address.
                new FieldDef("chunk_parent_content_sha256", "keyword", true, false, List.of(), null, null, false),
                // Phase 6: chunk embeddings
                new FieldDef("chunk_vector", "vector", false, false, List.of("chunk_vector"),
                        new VectorSpec(vectorDim), null, false, "preserve-reread-or-reset:chunk_embedding_status"),
                new FieldDef("chunk_embedding_status", "keyword", true, true, List.of("filter"), null, null, false),
                new FieldDef("chunk_embedding_retry_count", "long", true, true, List.of("filter", "sort"), null, null, false),
                // Frontmatter metadata fields (362)
                new FieldDef("meta_source", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("meta_author", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("meta_category", "keyword", true, true, List.of("filter", "facet"), null, null, false),
                new FieldDef("meta_published_at", "long", true, true, List.of("filter", "sort"), null, null, false)
        ));
    }

    /**
     * Creates a chunk-testing catalog that also includes {@code vdu_retry_count}.
     *
     * <p>Useful for ingest/VDU tests that need deterministic retry-policy assertions.
     * The base {@link #forChunkTesting(int)} catalog intentionally omits this field.
     */
    public static FieldCatalogDef forChunkTestingWithVduRetryCount(int vectorDim) {
        FieldCatalogDef base = forChunkTesting(vectorDim);
        if (base.field("vdu_retry_count") != null) {
            return base;
        }
        List<FieldDef> fields = new ArrayList<>(base.fields());
        fields.add(new FieldDef("vdu_retry_count", "long", true, true, List.of("filter", "sort"), null, null, false));
        return new FieldCatalogDef(base.version() + "+vdu-retry", fields);
    }

    /**
     * Creates a copy of this catalog with vector fields overridden to the given dimension.
     *
     * <p>This is used when switching between embedding models that produce different-dimension
     * vectors (e.g., 768 for nomic-embed, 1024 for BGE-M3). The resulting catalog has a different
     * content hash, which triggers the schema migration machinery on startup.
     *
     * @param newDim the new vector dimension
     * @return a new catalog with updated vector dimensions
     */
    public FieldCatalogDef withVectorDimension(int newDim) {
        Integer currentDim = vectorDimension();
        if (currentDim != null && currentDim == newDim) {
            return this;
        }
        List<FieldDef> updated = new ArrayList<>(fields.size());
        for (FieldDef f : fields) {
            if ("vector".equals(f.type()) && f.vector() != null) {
                updated.add(new FieldDef(
                        f.id(), f.type(), f.stored(), f.docValues(),
                        f.roles(), new VectorSpec(newDim), f.analyzer(), f.multiValued(), f.rmwPolicy()));
            } else {
                updated.add(f);
            }
        }
        return new FieldCatalogDef(version, updated);
    }

    /**
     * Definition of a single field in the catalog.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class FieldDef {
        private final String id;
        private final String type;
        private final boolean stored;
        private final boolean docValues;
        private final List<String> roles;
        private final VectorSpec vector;
        private final String analyzer;
        private final boolean multiValued;
        private final String rmwPolicy;

        @JsonCreator
        public FieldDef(
                @JsonProperty("id") String id,
                @JsonProperty("type") String type,
                @JsonProperty("stored") boolean stored,
                @JsonProperty("docValues") boolean docValues,
                @JsonProperty("roles") List<String> roles,
                @JsonProperty("vector") VectorSpec vector,
                @JsonProperty("analyzer") String analyzer,
                @JsonProperty("multiValued") boolean multiValued,
                @JsonProperty("rmwPolicy") String rmwPolicy) {
            this.id = Objects.requireNonNull(id, "id");
            this.type = Objects.requireNonNull(type, "type");
            this.stored = stored;
            this.docValues = docValues;
            this.roles = roles == null ? List.of() : List.copyOf(roles);
            this.vector = vector;
            this.analyzer = analyzer;
            this.multiValued = multiValued;
            this.rmwPolicy = rmwPolicy;
        }

        /** Convenience constructor for callers that do not declare an {@code rmwPolicy}. */
        public FieldDef(
                String id,
                String type,
                boolean stored,
                boolean docValues,
                List<String> roles,
                VectorSpec vector,
                String analyzer,
                boolean multiValued) {
            this(id, type, stored, docValues, roles, vector, analyzer, multiValued, null);
        }

        public String id() { return id; }
        public String type() { return type; }
        public boolean stored() { return stored; }
        public boolean docValues() { return docValues; }
        public List<String> roles() { return roles; }
        public VectorSpec vector() { return vector; }
        public String analyzer() { return analyzer; }
        public boolean multiValued() { return multiValued; }

        /** Read-modify-write disposition (tempdoc 711); null unless declared. */
        public String rmwPolicy() { return rmwPolicy; }

        /**
         * Returns the vector dimension, or null if this is not a vector field.
         */
        public Integer vectorDimension() {
            return vector != null ? vector.dimension() : null;
        }

        public boolean hasRole(String role) {
            return roles.contains(role);
        }
    }

    /**
     * Vector field specification.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class VectorSpec {
        private final int dimension;

        @JsonCreator
        public VectorSpec(@JsonProperty("dimension") int dimension) {
            this.dimension = dimension;
        }

        public int dimension() { return dimension; }
    }
}
