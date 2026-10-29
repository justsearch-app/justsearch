/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;

/**
 * Resolves parent metadata on the same searcher snapshot as the chunk query. This rewrite runs
 * before any chunk candidate budget, without a parent-count cap or new persisted chunk fields.
 */
final class ParentMetadataQuery extends Query {
  private final Query parents;

  ParentMetadataQuery(Query parents) {
    this.parents = parents;
  }

  @Override
  public Query rewrite(IndexSearcher searcher) throws IOException {
    Set<BytesRef> paths = new HashSet<>();
    searcher.search(parents, new SimpleCollector() {
      private StoredFields stored;

      @Override
      protected void doSetNextReader(LeafReaderContext context) throws IOException {
        stored = context.reader().storedFields();
      }

      @Override
      public void collect(int doc) throws IOException {
        String id = stored.document(doc, Set.of(SchemaFields.DOC_ID)).get(SchemaFields.DOC_ID);
        if (id != null) paths.add(new BytesRef(id));
      }

      @Override
      public ScoreMode scoreMode() {
        return ScoreMode.COMPLETE_NO_SCORES;
      }
    });
    return new TermInSetQuery(SchemaFields.PATH, paths);
  }

  @Override
  public void visit(QueryVisitor visitor) {
    visitor.visitLeaf(this);
  }

  @Override
  public String toString(String field) {
    return "parentMetadata(" + parents.toString(field) + ")";
  }

  @Override
  public boolean equals(Object other) {
    return sameClassAs(other) && parents.equals(((ParentMetadataQuery) other).parents);
  }

  @Override
  public int hashCode() {
    return 31 * classHash() + parents.hashCode();
  }
}
