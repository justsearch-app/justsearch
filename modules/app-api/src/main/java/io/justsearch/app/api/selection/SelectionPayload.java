/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.selection;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;
import java.util.Objects;

/**
 * Typed wire shape for "what the user picked out" per tempdoc 526 §4.1.
 *
 * <p>Rides on chat request bodies as {@code body.selection} and feeds
 * {@code SelectionContextInjector} via Jackson's polymorphic decode (discriminator field
 * {@code "kind"}).
 *
 * <h3>Variants</h3>
 *
 * <ul>
 *   <li>{@link Item}: an addressable item — search hit, browse node, plugin-contributed.
 *   <li>{@link TextRange}: a text slice anchored by a {@link DocumentAddress}.
 *   <li>{@link Citation}: a previously-emitted citation promoted back to an active selection
 *       (G21 kind-flip).
 *   <li>{@link ResultSet}: an ordered set of doc/hit references.
 * </ul>
 *
 * <p>Tempdoc 526 §17 T1C — {@link HealthCondition} re-added when its FE producer landed
 * (JfHealthEvent click-to-select gesture). {@code ConversationTurn} remains retracted
 * pending the F23 advisor surface.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = SelectionPayload.Item.class, name = "item"),
  @JsonSubTypes.Type(value = SelectionPayload.TextRange.class, name = "text-range"),
  @JsonSubTypes.Type(value = SelectionPayload.Citation.class, name = "citation"),
  @JsonSubTypes.Type(value = SelectionPayload.ResultSet.class, name = "result-set"),
  @JsonSubTypes.Type(value = SelectionPayload.HealthCondition.class, name = "health-condition"),
  @JsonSubTypes.Type(value = SelectionPayload.SearchTrace.class, name = "search-trace")
})
public sealed interface SelectionPayload
    permits SelectionPayload.Item,
        SelectionPayload.TextRange,
        SelectionPayload.Citation,
        SelectionPayload.ResultSet,
        SelectionPayload.HealthCondition,
        SelectionPayload.SearchTrace {

  /**
   * An item the user picked from a surface. {@code itemKind} identifies the source
   * (search-hit / browse-node / plugin-item / etc.); {@code itemId} is the surface-scoped
   * identifier; {@code surfaceId} is the originating surface for routing-back.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @RecordBuilder
  record Item(String itemKind, String itemId, String surfaceId, String label)
      implements SelectionPayload {
    public Item {
      Objects.requireNonNull(itemKind, "itemKind");
      Objects.requireNonNull(itemId, "itemId");
    }
  }

  /** A range of text picked from a hostable surface (doc preview, snippet, chat message). */
  @RecordBuilder
  record TextRange(DocumentAddress address, String selectionText, HostEntity hostEntity)
      implements SelectionPayload {
    public TextRange {
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(selectionText, "selectionText");
      Objects.requireNonNull(hostEntity, "hostEntity");
    }
  }

  /** A citation promoted back to an active selection — the G21 kind-flip. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @RecordBuilder
  record Citation(SourceCitation citation, String promotedFrom) implements SelectionPayload {
    public Citation {
      Objects.requireNonNull(citation, "citation");
      promotedFrom = promotedFrom == null ? "manual" : promotedFrom;
    }
  }

  /**
   * A set of doc / hit references — replaces the parallel "selection-like bags"
   * ({@code pinnedDocIds}, legacy {@code summarizeChatState.docIds}). {@code query} carries
   * the originating query when the set came from a search.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @RecordBuilder
  record ResultSet(List<ResultRef> items, String query) implements SelectionPayload {
    public ResultSet {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /** A health condition / event the user picked for "explain this." (F6 / F21). */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  @RecordBuilder
  record HealthCondition(String conditionId, String severity, String summary)
      implements SelectionPayload {
    public HealthCondition {
      Objects.requireNonNull(conditionId, "conditionId");
      severity = severity == null ? "" : severity;
      summary = summary == null ? "" : summary;
    }
  }

  /**
   * Tempdoc 549 Slice 4 (G33/G111 LLM narration). A search-pipeline trace the user asked the
   * LLM to explain in words. {@code scope} is "query" (whole-query trace) or "hit" (a single
   * result's ranking provenance); {@code summary} is the FE-rendered trace text fed to the LLM
   * as context. Optional {@code docId} identifies the hit for source navigation only.
   * Carries no per-hit raw doc content — privacy-safe like the underlying trace.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record SearchTrace(String scope, String summary, String docId) implements SelectionPayload {
    public SearchTrace {
      scope = (scope == null || scope.isBlank()) ? "query" : scope;
      summary = summary == null ? "" : summary;
    }
  }

  /** Identifies the entity a {@link TextRange} lives inside. */
  record HostEntity(String kind, String id) {
    public HostEntity {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(id, "id");
    }
  }

  /** Element of {@link ResultSet}. */
  record ResultRef(String id, String kind) {
    public ResultRef {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(kind, "kind");
    }
  }
}
