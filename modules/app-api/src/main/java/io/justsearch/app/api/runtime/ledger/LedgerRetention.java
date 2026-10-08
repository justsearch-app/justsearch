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

  private static void recordLoss(Path current, Path dropped, long bytes) throws IOException {
    Path loss = lossPath(current);
    StringBuilder lines = new StringBuilder(Files.exists(loss) ? Files.readString(loss, StandardCharsets.UTF_8) : "");
    for (String line : Files.readAllLines(dropped, StandardCharsets.UTF_8)) {
      ObjectNode node;
      try { node = (ObjectNode) JSON.readTree(line); } catch (RuntimeException corrupt) { continue; }
      if (node == null || !node.has("pid") || !node.has("creationFileTime")) continue;
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
    summary.put("droppedBytes", bytes); summary.put("detail", "archive dropped at retention bound");
    ProcessHandle self = ProcessHandle.current();
    summary.set("writer", JSON.valueToTree(java.util.Map.of("role", "engine", "pid", self.pid(),
        "startedAt", self.info().startInstant().orElse(java.time.Instant.now()).toString())));
    lines.append(JSON.writeValueAsString(summary)).append('\n');
    if (lines.toString().getBytes(StandardCharsets.UTF_8).length > LOSS_BYTES / 2) {
      // At the journal bound retain birth ranges, explicitly lossy, never ownership evidence.
      ObjectNode first = null;
      java.math.BigInteger low = null, high = null;
      long count = 0;
      for (String line : lines.toString().split("\n")) {
        try {
          ObjectNode n = (ObjectNode) JSON.readTree(line);
          if (first == null) first = n.deepCopy();
          String a = n.has("birthFrom") ? n.get("birthFrom").asString() : n.get("creationFileTime").asString();
          String b = n.has("birthThrough") ? n.get("birthThrough").asString() : a;
          var start = new java.math.BigInteger(a); var end = new java.math.BigInteger(b);
          low = low == null ? start : low.min(start); high = high == null ? end : high.max(end);
          count += n.has("count") ? n.get("count").asLong() : 1;
        } catch (RuntimeException ignored) { /* retained journal describes readable identities */ }
      }
      if (first != null) {
        first.remove("pid"); first.remove("creationFileTime"); first.remove("owner"); first.remove("incarnation");
        if (low != null) { first.put("birthFrom", low.toString()); first.put("birthThrough", high.toString()); }
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
