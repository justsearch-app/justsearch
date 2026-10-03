/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Private bounded snapshot: container inspection and parsers consume the same source bytes.
 * Non-containers up to 1 MiB stay in memory; larger inputs and ZIP candidates spill to disk.
 * Detection uses the captured bytes, including signatures after a preamble, never the extension.
 */
final class PreparedExtractionInput implements AutoCloseable {
  static final int MAX_IN_MEMORY_BYTES = 1024 * 1024;

  private final Path source;
  private final Factory factory;
  private final List<byte[]> chunks;
  private final long size;
  private final ContainerExpansionBudget expansion;
  private Path snapshot;
  private boolean ownsFactory;

  private PreparedExtractionInput(Path source, Factory factory, List<byte[]> chunks,
      long size, Path snapshot, ContainerExpansionBudget expansion) {
    this.source = source;
    this.factory = factory;
    this.chunks = chunks;
    this.size = size;
    this.snapshot = snapshot;
    this.expansion = expansion;
  }

  static PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
      throws IOException, ContentExtractor.ExtractionException {
    Factory factory = new Factory();
    try {
      PreparedExtractionInput input = factory.prepare(source, policy);
      input.ownsFactory = true;
      return input;
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException e) {
      try { factory.close(); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
      throw e;
    }
  }

  Path file() { return snapshot == null ? source : snapshot; }
  boolean isDiskBacked() { return snapshot != null; }
  Path source() { return source; }
  long size() { return size; }
  ContainerExpansionBudget expansion() { return expansion; }

  InputStream openStream() throws IOException {
    if (snapshot != null) return Files.newInputStream(snapshot);
    List<InputStream> streams = new ArrayList<>(chunks.size());
    for (byte[] chunk : chunks) streams.add(new ByteArrayInputStream(chunk));
    return new SequenceInputStream(Collections.enumeration(streams));
  }

  /** Materialize only at a native, file-based OCR boundary, from the already bounded bytes. */
  Path materialize() throws IOException {
    if (snapshot == null) {
      Path created = factory.createSnapshot(source);
      try (OutputStream output = Files.newOutputStream(created)) {
        for (byte[] chunk : chunks) output.write(chunk);
      } catch (IOException | RuntimeException e) {
        Files.deleteIfExists(created);
        throw e;
      }
      snapshot = created;
      chunks.clear();
    }
    return snapshot;
  }

  @Override
  public void close() throws IOException {
    chunks.clear();
    try {
      if (snapshot != null) Files.deleteIfExists(snapshot);
    } finally {
      if (ownsFactory) factory.close();
    }
  }

  /** One lazy private directory per extractor; individual snapshots are deleted promptly. */
  static class Factory implements AutoCloseable {
    private Path directory;

    InputStream openSource(Path source) throws IOException {
      return Files.newInputStream(source);
    }

    synchronized Path createSnapshot(Path source) throws IOException {
      if (directory == null) {
        directory = Files.createTempDirectory("justsearch-parse-input-");
        // Standalone legacy extractors need not be closed; retain a process-exit backstop.
        directory.toFile().deleteOnExit();
      }
      String name = source.getFileName().toString();
      int dot = name.lastIndexOf('.');
      return Files.createTempFile(directory, "input-", dot < 0 ? ".bin" : name.substring(dot));
    }

    PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
        throws IOException, ContentExtractor.ExtractionException {
      if (source.getFileName() == null) throw new IOException("Extraction input has no filename");
      List<byte[]> chunks = new ArrayList<>();
      Path snapshot = null;
      OutputStream output = null;
      long copied = 0;
      int signature = 0;
      boolean retained = false;
      try (InputStream input = openSource(source)) {
        // Files.size is only an early refusal, never the accounting authority.
        if (Files.size(source) > policy.maxInputBytes()) throw tooLarge();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer, 0, (int) Math.min(buffer.length,
            policy.maxInputBytes() - copied < buffer.length
                ? policy.maxInputBytes() - copied + 1 : buffer.length))) != -1) {
          copied += read;
          if (copied > policy.maxInputBytes()) throw tooLarge();
          if (snapshot == null) {
            boolean zip = false;
            for (int i = 0; i < read; i++) {
              signature = (signature << 8) | (buffer[i] & 0xff);
              // Inspect every offset, covering preambles and chunk boundaries. Any ZIP
              // accepted by ZipFile has a central directory/EOCD. False positives spill.
              if (signature == 0x504b0304 || signature == 0x504b0102
                  || signature == 0x504b0506 || signature == 0x504b0606) zip = true;
            }
            if (zip || copied > MAX_IN_MEMORY_BYTES) {
              snapshot = createSnapshot(source);
              output = Files.newOutputStream(snapshot);
              for (byte[] chunk : chunks) output.write(chunk);
              chunks.clear();
            }
          }
          if (output != null) output.write(buffer, 0, read);
          else chunks.add(java.util.Arrays.copyOf(buffer, read));
        }
        if (output != null) { output.close(); output = null; }
        var expansion = new ContainerExpansionBudget(policy, copied);
        if (snapshot != null) expansion.inspect(snapshot);
        retained = true;
        return new PreparedExtractionInput(source, this, chunks, copied, snapshot, expansion);
      } catch (IOException | ContentExtractor.ExtractionException | RuntimeException e) {
        retained = false;
        throw e;
      } finally {
        try {
          if (output != null) output.close();
        } finally {
          if (!retained && snapshot != null) Files.deleteIfExists(snapshot);
        }
      }
    }

    private static ContentExtractor.BudgetExceededException tooLarge() {
      return new ContentExtractor.BudgetExceededException(
          "Input exceeds policy size limit", "INPUT_TOO_LARGE");
    }

    @Override
    public synchronized void close() throws IOException {
      if (directory != null) {
        Files.deleteIfExists(directory);
        directory = null;
      }
    }
  }
}
