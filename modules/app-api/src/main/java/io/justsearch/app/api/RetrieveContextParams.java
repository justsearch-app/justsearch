/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parameters for RAG context retrieval, supporting entity/temporal/content filters
 * and context format selection.
 *
 * <p>This is the rich alternative to the positional-parameter {@link
 * DocumentService#retrieveContextWithMeta} methods. New callers (REST API, MCP tools)
 * should use this; existing callers can migrate incrementally.
 */
@io.soabase.recordbuilder.core.RecordBuilder
public record RetrieveContextParams(
    String question,
    Set<String> docIds,
    int topK,
    int maxContextTokens,
    List<String> entityPersons,
    List<String> entityOrganizations,
    List<String> entityLocations,
    TimeRange modifiedAt,
    boolean freshnessEnabled,
    String pathPrefix,
    List<String> fileKind,
    boolean autoEntityExtract,
    ContextFormat contextFormat,
    List<String> metaSource,
    List<String> metaAuthor,
    List<String> metaCategory,
    TimeRange metaPublishedAt,
    boolean returnFullDocuments,
    List<String> excludedSourceIds,
    // Tempdoc 821 §3-C2 — the collection scope for RAG retrieval. Non-empty = a POSITIVE include of
    // exactly those collections; EMPTY = the DEFAULT scope (the 585-D4b MUST_NOT agent-history
    // exclusion the Worker applies for null/empty filters), never "match nothing".
    List<String> collection,
    io.justsearch.app.api.knowledge.KnowledgeSearchRequest.ByteRange sizeBytes)
    implements RetrieveContextParamsBuilder.With {

  public RetrieveContextParams(
      String question, Set<String> docIds, int topK, int maxContextTokens,
      List<String> entityPersons, List<String> entityOrganizations, List<String> entityLocations,
      TimeRange modifiedAt, boolean freshnessEnabled, String pathPrefix, List<String> fileKind,
      boolean autoEntityExtract, ContextFormat contextFormat, List<String> metaSource,
      List<String> metaAuthor, List<String> metaCategory, TimeRange metaPublishedAt,
      boolean returnFullDocuments, List<String> excludedSourceIds, List<String> collection) {
    this(question, docIds, topK, maxContextTokens, entityPersons, entityOrganizations,
        entityLocations, modifiedAt, freshnessEnabled, pathPrefix, fileKind, autoEntityExtract,
        contextFormat, metaSource, metaAuthor, metaCategory, metaPublishedAt, returnFullDocuments,
        excludedSourceIds, collection, null);
  }

  public boolean hasRestrictions() {
    return !pathPrefix.isEmpty() || modifiedAt.isSet()
        || (sizeBytes != null && (sizeBytes.fromBytes() != null || sizeBytes.toBytes() != null));
  }

  public RetrieveContextParams withRestrictions(
      io.justsearch.app.api.knowledge.KnowledgeSearchRequest.Filters filters) {
    var date = filters.modifiedAt();
    return withPathPrefix(filters.pathPrefix()).withModifiedAt(date == null ? TimeRange.UNSET
        : new TimeRange(date.fromMs() == null ? 0 : date.fromMs(),
            date.toMs() == null ? 0 : date.toMs())).withSizeBytes(filters.sizeBytes());
  }

  /** Time range filter (inclusive bounds, epoch millis). */
  public record TimeRange(long fromMs, long toMs) {
    public static final TimeRange UNSET = new TimeRange(0, 0);

    public boolean isSet() {
      return fromMs > 0 || toMs > 0;
    }
  }

  /** Format for assembled RAG context string. */
  public enum ContextFormat {
    LABELED,
    XML,
    PLAIN
  }

  /** Compact constructor with defaults. */
  public RetrieveContextParams {
    question = question == null ? "" : question;
    // Tempdoc 731 I1: Set.copyOf() does not preserve iteration order (its Javadoc leaves order
    // unspecified, and empirically it doesn't) — it would silently discard the pre-search's rank
    // order before docIds ever reaches the wire. LinkedHashSet + unmodifiableSet preserves it.
    docIds = docIds == null
        ? Set.of()
        : Collections.unmodifiableSet(new LinkedHashSet<>(docIds));
    topK = topK <= 0 ? 5 : Math.min(topK, 20);
    entityPersons = entityPersons == null ? List.of() : List.copyOf(entityPersons);
    entityOrganizations = entityOrganizations == null ? List.of() : List.copyOf(entityOrganizations);
    entityLocations = entityLocations == null ? List.of() : List.copyOf(entityLocations);
    modifiedAt = modifiedAt == null ? TimeRange.UNSET : modifiedAt;
    pathPrefix = pathPrefix == null ? "" : pathPrefix;
    fileKind = fileKind == null ? List.of() : List.copyOf(fileKind);
    contextFormat = contextFormat == null ? ContextFormat.LABELED : contextFormat;
    metaSource = metaSource == null ? List.of() : List.copyOf(metaSource);
    metaAuthor = metaAuthor == null ? List.of() : List.copyOf(metaAuthor);
    metaCategory = metaCategory == null ? List.of() : List.copyOf(metaCategory);
    metaPublishedAt = metaPublishedAt == null ? TimeRange.UNSET : metaPublishedAt;
    excludedSourceIds = excludedSourceIds == null ? List.of() : List.copyOf(excludedSourceIds);
    collection = collection == null ? List.of() : List.copyOf(collection);
  }

  /** Minimal builder for the common case: question + token budget. */
  public static RetrieveContextParams of(String question, int topK, int maxContextTokens) {
    return new RetrieveContextParams(
        question, Set.of(), topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, List.of(), List.of());
  }

  /**
   * Tempdoc 610 §J.3 — builder for the chat RAG path: question + token budget + (optional) scoped
   * docIds + the hidden-source exclusion set. Tempdoc 821 §3-C2 added {@code collection}: the chat
   * SPI carries the caller's scope; an empty list is the default scope, i.e. unchanged behavior.
   */
  public static RetrieveContextParams of(
      String question, int topK, int maxContextTokens,
      Set<String> docIds, List<String> excludedSourceIds, List<String> collection) {
    return new RetrieveContextParams(
        question, docIds, topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, excludedSourceIds, collection);
  }

  /** Builder from the existing positional parameters (backward compatibility). */
  public static RetrieveContextParams fromLegacy(
      String question, Set<String> docIds, int topK, int maxContextTokens) {
    return new RetrieveContextParams(
        question, docIds, topK, maxContextTokens,
        List.of(), List.of(), List.of(),
        TimeRange.UNSET, false, "", List.of(),
        false, ContextFormat.LABELED,
        List.of(), List.of(), List.of(), TimeRange.UNSET, false, List.of(), List.of());
  }
}
