/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps.PendingDocumentKind;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scheduler-owned document-ID cursors. Deadline and text deferrals retain their suffix. */
public final class EnrichmentSelection {
  private record Lane(String stage, PendingDocumentKind kind) {}

  private final Map<Lane, String> cursors = new HashMap<>();
  private final Map<Lane, DocumentFieldOps.PendingIdPage> pages = new HashMap<>();
  private final Map<Lane, Set<String>> examined = new HashMap<>();

  public void clear() {
    cursors.clear();
    begin();
  }

  void begin() {
    pages.clear();
    examined.clear();
  }

  private List<String> readPage(DocumentFieldOps fields, String stage, int limit, PendingDocumentKind kind)
      throws IOException {
    var lane = new Lane(stage, kind);
    if (pages.containsKey(lane)) throw new IllegalStateException("Stage already paged: " + stage);
    var page = kind == PendingDocumentKind.CHUNK
        ? fields.queryPendingPage(stage, limit, kind, cursors.get(lane))
        : fields.queryPendingPage(stage, limit, kind == PendingDocumentKind.PARENT, cursors.get(lane));
    pages.put(lane, page);
    if (page.ids().isEmpty()) cursors.remove(lane);
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
            && (input == null || !"PENDING".equals(input.fields().get(stage.stage()))))) seen.add(id);
      }
    });
    advance();
  }

  void examined(String id, EnrichmentAccounting.Stage stage) {
    pages.keySet().stream().filter(lane -> lane.stage().equals(stage.status))
        .forEach(lane -> examined.computeIfAbsent(lane, ignored -> new HashSet<>()).add(id));
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
    return selection.readPage(fields, stage, limit, excludeChunks
        ? PendingDocumentKind.PARENT : PendingDocumentKind.ALL);
  }
  static List<String> page(EnrichmentSelection selection, DocumentFieldOps fields,
      String stage, int limit, PendingDocumentKind kind) throws IOException {
    if (selection == null) return fields.queryPendingPage(stage, limit, kind, null).ids();
    return selection.readPage(fields, stage, limit, kind);
  }
}
