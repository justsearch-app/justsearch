/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scheduler-owned document-ID cursors. Deadline and text deferrals retain their suffix. */
public final class EnrichmentSelection {
  private final Map<String, String> cursors = new HashMap<>();
  private final Map<String, DocumentFieldOps.PendingIdPage> pages = new HashMap<>();
  private final Map<String, Set<String>> examined = new HashMap<>();

  public void clear() {
    cursors.clear();
    begin();
  }

  void begin() {
    pages.clear();
    examined.clear();
  }

  private List<String> readPage(DocumentFieldOps fields, String stage, int limit, boolean excludeChunks)
      throws IOException {
    if (pages.containsKey(stage)) throw new IllegalStateException("Stage already paged: " + stage);
    var page = fields.queryPendingPage(stage, limit, excludeChunks, cursors.get(stage));
    pages.put(stage, page);
    if (page.ids().isEmpty()) cursors.remove(stage);
    return page.ids();
  }

  void examined(Set<String> ids) {
    pages.keySet().forEach(stage -> examined.computeIfAbsent(stage, ignored -> new HashSet<>()).addAll(ids));
    advance();
  }

  void collected(Map<String, DocumentFieldOps.EnrichmentInput> inputs, List<String> consumed,
      Set<String> excluded) {
    Set<String> read = new HashSet<>(consumed);
    pages.forEach((stage, page) -> {
      var seen = examined.computeIfAbsent(stage, ignored -> new HashSet<>());
      for (String id : page.ids()) {
        var input = inputs.get(id);
        if (excluded.contains(id) || (read.contains(id)
            && (input == null || !"PENDING".equals(input.fields().get(stage))))) seen.add(id);
      }
    });
    advance();
  }

  void examined(String id, EnrichmentAccounting.Stage stage) {
    examined.computeIfAbsent(stage.status, ignored -> new HashSet<>()).add(id);
    advance();
  }

  private void advance() {
    pages.forEach((stage, page) -> {
      Set<String> seen = examined.getOrDefault(stage, Set.of());
      for (String id : page.ids()) {
        if (!seen.contains(id)) return;
        cursors.put(stage, id);
      }
      // Exact hit metadata identifies the sweep end even when the last page is full.
      if (page.exhausted()) cursors.remove(stage);
    });
  }

  static List<String> page(EnrichmentSelection selection, DocumentFieldOps fields,
      String stage, int limit, boolean excludeChunks) throws IOException {
    if (selection == null) return fields.queryPendingPage(stage, limit, excludeChunks, null).ids();
    return selection.readPage(fields, stage, limit, excludeChunks);
  }
}
