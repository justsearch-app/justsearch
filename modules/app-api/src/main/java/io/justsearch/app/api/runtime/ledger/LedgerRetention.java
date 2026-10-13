/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded historical evidence: 128 MiB per family plus a 1 MiB loss journal. */
final class LedgerRetention {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  static final long RETAIN_BYTES = 128L * 1024 * 1024;
  static final int LOSS_BYTES = 1024 * 1024;

  private LedgerRetention() {}

  static List<Path> files(Path current) throws IOException {
    Path dir = current.getParent();
    if (!Files.exists(dir)) return List.of();
    String stem = current.getFileName().toString().replace(".jsonl", ".");
    List<Path> archives;
    try (var paths = Files.list(dir)) {
      archives = paths.filter(p -> p.getFileName().toString().matches(
          java.util.regex.Pattern.quote(stem) + "[0-9]+\\.jsonl"))
          .sorted(Comparator.comparingLong(LedgerRetention::generation)).toList();
    }
    List<Path> result = new ArrayList<>(archives.stream().filter(p -> generation(p) > 1).toList());
    Path newest = dir.resolve(stem + "1.jsonl");
    if (Files.exists(newest)) result.add(newest);
    Path loss = lossPath(current);
    if (Files.exists(loss)) result.add(loss);
    if (Files.exists(current)) result.add(current);
    return result;
  }

  private static long generation(Path path) {
    String[] parts = path.getFileName().toString().split("\\.");
    return Long.parseLong(parts[parts.length - 2]);
  }

  static Path lossPath(Path current) {
    return current.resolveSibling(current.getFileName().toString().replace(".jsonl", ".lost.jsonl"));
  }

  static void rotate(Path current, Path rotated, long bound) throws IOException {
    List<Path> before = files(current);
    if (Files.exists(rotated)) {
      long next = before.stream().filter(p -> p.toString().matches(".*\\.[0-9]+\\.jsonl"))
          .mapToLong(LedgerRetention::generation).max().orElse(1) + 1;
      Files.move(rotated, rotated.resolveSibling(rotated.getFileName().toString()
          .replace(".1.jsonl", "." + next + ".jsonl")));
    }
    Files.move(current, rotated);
    List<Path> candidates = files(current).stream().filter(p -> !p.equals(lossPath(current))).toList();
    long size = 0;
    for (Path file : candidates) size += Files.size(file);
    for (Path file : candidates) {
      if (size <= bound || file.equals(rotated)) break;
      long bytes = Files.size(file);
      recordLoss(current, file, bytes); // persist before deleting evidence
      Files.delete(file);
      size -= bytes;
    }
  }

  /**
   * Bounds describe the original evidence, not the later time it was evicted; an unknown range
   * keeps no bound, and the row's {@code at} (its eviction time) remains its upper bound.
   */
  private static void scope(List<ObjectNode> records, ObjectNode target) {
    var sessions = new java.util.TreeSet<String>();
    boolean complete = !records.isEmpty(), rangeComplete = !records.isEmpty();
    java.time.Instant from = null, through = null;
    for (ObjectNode record : records) {
      boolean loss = "EVIDENCE_LOST".equals(record.path("reason").asString());
      String session = record.path("incarnation").path("session").asString(null);
      if (session != null) sessions.add(session);
      var affected = record.path("affectedSessions");
      if (affected.isArray()) for (var item : affected) if (item.isString()) sessions.add(item.asString());
      complete &= session != null || (loss && record.path("scopeComplete").asBoolean(false));
      try {
        var low = java.time.Instant.parse(record.path(loss ? "evidenceFrom" : "at").asString());
        var high = java.time.Instant.parse(record.path(loss ? "evidenceThrough" : "at").asString());
        from = from == null || low.isBefore(from) ? low : from;
        through = through == null || high.isAfter(through) ? high : through;
      } catch (RuntimeException unknown) { rangeComplete = false; }
    }
    if (sessions.size() > 128 || sessions.toString().length() > 2048) { sessions.clear(); complete = false; }
    target.set("affectedSessions", JSON.valueToTree(sessions));
    target.put("scopeComplete", complete);
    target.put("evidenceRangeComplete", rangeComplete);
    target.remove("evidenceFrom"); target.remove("evidenceThrough");
    if (rangeComplete) {
      target.put("evidenceFrom", from.toString()); target.put("evidenceThrough", through.toString());
    }
  }

  /**
   * Lines split on LF and decoded one by one with replacement, as the ledger reader and Node do:
   * malformed UTF-8 (a write cut off inside a non-ASCII path) costs that line, never the file.
   */
  private static List<String> lenientLines(Path file) throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i <= bytes.length; i++) {
      if (i < bytes.length && bytes[i] != '\n') continue;
      lines.add(new String(bytes, start, i - start, StandardCharsets.UTF_8));
      start = i + 1;
    }
    return lines;
  }

  private static void recordLoss(Path current, Path dropped, long bytes) throws IOException {
    Path loss = lossPath(current);
    StringBuilder lines = new StringBuilder();
    if (Files.exists(loss)) {
      for (String line : lenientLines(loss)) {
        if (!line.isBlank()) lines.append(line).append('\n');
      }
    }
    List<ObjectNode> droppedRecords = new ArrayList<>();
    int corruptLines = 0;
    for (String line : lenientLines(dropped)) {
      if (line.isBlank()) continue;
      ObjectNode node;
      try { node = (ObjectNode) JSON.readTree(line); } catch (RuntimeException corrupt) { node = null; }
      if (node == null) {
        corruptLines++;
        droppedRecords.add(JSON.createObjectNode()); // unreadable: its scope is unknown, not absent
        continue;
      }
      droppedRecords.add(node.deepCopy());
      if (!node.has("pid") || !node.has("creationFileTime")) continue;
      scope(List.of(node.deepCopy()), node);
      node.put("type", "ledger-gap");
      node.put("reason", "EVIDENCE_LOST");
      node.put("count", 1);
      node.set("since", node.get("at"));
      node.put("droppedFile", dropped.getFileName().toString());
      node.put("droppedBytes", bytes);
      // Keep identity and owner/session binding; drop argv and unrelated payload.
      for (String field : List.of("executable", "purpose", "expectedCode", "code", "exitClass")) node.remove(field);
      lines.append(JSON.writeValueAsString(node)).append('\n');
    }
    ObjectNode summary = JSON.createObjectNode();
    String now = java.time.Instant.now().toString();
    summary.put("schemaVersion", 1); summary.put("type", "ledger-gap"); summary.put("seq", 1);
    summary.put("at", now); summary.put("since", now); summary.put("reason", "EVIDENCE_LOST");
    summary.put("count", 1); summary.put("droppedFile", dropped.getFileName().toString());
    summary.put("droppedBytes", bytes); summary.put("corruptLines", corruptLines);
    summary.put("detail", "archive dropped at retention bound");
    ProcessHandle self = ProcessHandle.current();
    summary.set("writer", JSON.valueToTree(java.util.Map.of("role", "engine", "pid", self.pid(),
        "startedAt", self.info().startInstant().orElse(java.time.Instant.now()).toString())));
    scope(droppedRecords, summary);
    lines.append(JSON.writeValueAsString(summary)).append('\n');
    if (lines.toString().getBytes(StandardCharsets.UTF_8).length > LOSS_BYTES / 2) {
      // At the journal bound retain birth ranges, explicitly lossy, never ownership evidence.
      ObjectNode first = null;
      List<ObjectNode> losses = new ArrayList<>();
      java.math.BigInteger low = null, high = null;
      long count = 0;
      for (String line : lines.toString().split("\n")) {
        try {
          ObjectNode n = (ObjectNode) JSON.readTree(line);
          if (first == null) first = n.deepCopy();
          losses.add(n);
          count += n.has("count") ? n.get("count").asLong() : 1;
          String a = n.has("birthFrom") ? n.get("birthFrom").asString() : n.get("creationFileTime").asString();
          String b = n.has("birthThrough") ? n.get("birthThrough").asString() : a;
          var start = new java.math.BigInteger(a); var end = new java.math.BigInteger(b);
          low = low == null ? start : low.min(start); high = high == null ? end : high.max(end);
        } catch (RuntimeException ignored) { /* retained journal describes readable identities */ }
      }
      if (first != null) {
        first.remove("pid"); first.remove("creationFileTime"); first.remove("owner"); first.remove("incarnation");
        if (low != null) { first.put("birthFrom", low.toString()); first.put("birthThrough", high.toString()); }
        scope(losses, first);
        // `at` stays the newest eviction time: the upper bound of everything evicted, which a
        // reader uses when the merged range is unknown.
        first.put("at", now); first.put("since", now);
        first.put("count", count); first.put("detail", "loss journal compacted; identity detail and membership unknown");
        lines = new StringBuilder(JSON.writeValueAsString(first)).append('\n');
      }
    }
    Path temp = loss.resolveSibling(loss.getFileName() + ".pending");
    Files.writeString(temp, lines, StandardCharsets.UTF_8);
    try (var channel = java.nio.channels.FileChannel.open(temp, java.nio.file.StandardOpenOption.WRITE)) { channel.force(true); }
    Files.move(temp, loss, StandardCopyOption.REPLACE_EXISTING);
  }
}
