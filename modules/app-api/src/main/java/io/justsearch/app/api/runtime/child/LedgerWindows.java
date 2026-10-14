/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The helper windows other processes record in the shared children ledger (task b6d536 b101): the
 * extraction child opens its Tesseract window in its own JVM, so the Engine's witness reads it here.
 *
 * <p>Reads only what was appended since the last read, from the ledger's end when the Engine
 * attached; follows one rotation. A window counts only when the process that wrote it is its owner.
 * Not thread-safe: {@link ProcessIntents} calls it under its lock.
 */
final class LedgerWindows {
  private static final int MAX_WINDOWS = 256;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Path dir;
  private long offset;
  private final Map<String, ProcessIntents.Window> windows = new LinkedHashMap<>() {
    @Override protected boolean removeEldestEntry(Map.Entry<String, ProcessIntents.Window> eldest) {
      return size() > MAX_WINDOWS;
    }
  };

  LedgerWindows(Path dir) {
    this.dir = dir;
    this.offset = Math.max(0, size(LedgerFamily.CHILDREN.current(dir)));
  }

  /** The windows recorded so far, after reading what was appended since the last call. */
  Collection<ProcessIntents.Window> read() {
    Path current = LedgerFamily.CHILDREN.current(dir);
    long size = size(current);
    if (size < 0) return windows.values(); // unreadable now; the next read retries
    if (size < offset) {
      // Rotated: the renamed file holds the rest of what was current; the new file starts at zero.
      readFrom(LedgerFamily.CHILDREN.rotated(dir), offset);
      offset = 0;
    }
    if (size > offset) offset = readFrom(current, offset);
    return windows.values();
  }

  /** Reads complete lines from {@code from}; returns the position after the last one read. */
  private long readFrom(Path file, long from) {
    byte[] bytes;
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long size = channel.size();
      if (size <= from) return from;
      ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(Integer.MAX_VALUE - 16, size - from));
      int read = 0;
      while (read >= 0 && buffer.hasRemaining()) read = channel.read(buffer, from + buffer.position());
      bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
    } catch (IOException e) {
      return from; // unreadable now; the next read retries from the same place
    }
    int start = 0;
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] != '\n') continue;
      line(new String(bytes, start, i - start, StandardCharsets.UTF_8));
      start = i + 1;
    }
    return from + start;
  }

  private void line(String text) {
    if (!text.contains("\"helper-window-")) return;
    JsonNode node;
    try {
      node = JSON.readTree(text);
    } catch (RuntimeException corrupt) {
      return;
    }
    String type = text(node, "type");
    JsonNode owner = node.path("owner");
    String id = text(node, "intentId");
    JsonNode writerPid = node.path("writer").path("pid");
    if (id == null || type == null || !owner.path("pid").isIntegralNumber() || !writerPid.isIntegralNumber()
        || writerPid.longValue() != owner.path("pid").longValue()) {
      return; // only the owner itself can declare its window
    }
    try {
      if (type.equals("helper-window-start")) {
        String image = text(node, "image");
        String reason = text(node, "reason");
        String start = text(node, "requestedAt");
        String deadline = text(node, "deadlineAt");
        if (image == null || reason == null || reason.isEmpty() || start == null || deadline == null) return;
        windows.put(id, new ProcessIntents.Window(image, reason, Instant.parse(start), Instant.parse(deadline),
            owner.path("pid").longValue(), text(owner, "creationFileTime")));
      } else if (type.equals("helper-window-end") && windows.containsKey(id) && text(node, "closedAt") != null) {
        windows.get(id).close(Instant.parse(text(node, "closedAt")));
      }
    } catch (java.time.format.DateTimeParseException malformed) {
      // a window without valid bounds admits nothing
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  private static long size(Path file) {
    try {
      return Files.size(file);
    } catch (NoSuchFileException absent) {
      return 0;
    } catch (IOException unreadable) {
      return -1;
    }
  }
}
