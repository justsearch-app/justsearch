/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.ReadOwnership;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;

/** Joins stored ownership on one immutable reader, shared by bounded result checks and direct document reads. */
final class IndexReadOwnership {
  static final java.util.Set<String> FIELDS = java.util.Set.of(SchemaFields.DOC_ID, SchemaFields.PATH,
      SchemaFields.COLLECTION, SchemaFields.PARENT_DOC_ID);
  private final IndexSearcher searcher;
  private final ReadOwnership ownership = new ReadOwnership();
  private final Map<String, Map<String, ?>> parents = new HashMap<>();
  IndexReadOwnership(IndexSearcher searcher) { this.searcher = searcher; }

  ReadOwnership.Owner owner(int doc) throws IOException {
    var fields = facts(doc);
    return ownership.owner((String) fields.get(SchemaFields.DOC_ID), fields, this::parent);
  }
  ReadOwnership.Owner owner(String id) { return ownership.owner(id, parent(id), this::parent); }

  private Map<String, ?> parent(String id) {
    return parents.computeIfAbsent(id, key -> {
      try {
        var hits = searcher.search(new TermQuery(new Term(SchemaFields.DOC_ID, key)), 2);
        if (hits.scoreDocs.length == 0) return Map.of();
        return hits.scoreDocs.length == 1 ? facts(hits.scoreDocs[0].doc)
            : Map.of(ReadOwnership.METADATA, ReadOwnership.Owner.UNCERTAIN.name());
      } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    });
  }

  private Map<String, String> facts(int doc) throws IOException {
    var stored = searcher.storedFields().document(doc, FIELDS);
    Map<String, String> result = new HashMap<>();
    var leaves = searcher.getIndexReader().leaves();
    var leaf = leaves.get(ReaderUtil.subIndex(doc, leaves));
    int local = doc - leaf.docBase;
    for (String field : FIELDS) {
      String value = stored.get(field);
      if (value != null) result.put(field, value);
      if (stored.getFields(field).length > 1) result.put(ReadOwnership.METADATA, ReadOwnership.Owner.UNCERTAIN.name());
      var info = leaf.reader().getFieldInfos().fieldInfo(field);
      if (info == null || info.getDocValuesType() == DocValuesType.NONE) continue;
      String column = null;
      if (info.getDocValuesType() == DocValuesType.SORTED) {
        var values = DocValues.getSorted(leaf.reader(), field);
        if (values.advanceExact(local)) column = values.lookupOrd(values.ordValue()).utf8ToString();
      } else if (info.getDocValuesType() == DocValuesType.SORTED_SET) {
        var values = DocValues.getSortedSet(leaf.reader(), field);
        if (values.advanceExact(local)) {
          if (values.docValueCount() == 1) column = values.lookupOrd(values.nextOrd()).utf8ToString();
          else result.put(ReadOwnership.METADATA, ReadOwnership.Owner.UNCERTAIN.name());
        }
      } else result.put(ReadOwnership.METADATA, ReadOwnership.Owner.UNCERTAIN.name());
      if (column != null) {
        if (value != null && !value.equals(column)) result.put(ReadOwnership.METADATA, ReadOwnership.Owner.UNCERTAIN.name());
        result.put(field, column);
      }
    }
    return result;
  }
}
