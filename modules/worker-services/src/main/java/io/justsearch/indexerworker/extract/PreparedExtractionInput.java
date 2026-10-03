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
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.apache.tika.Tika;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.utils.XMLReaderUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Private bounded snapshot: container inspection and parsers consume the same source bytes.
 * Non-containers up to 1 MiB stay in memory; larger inputs and ZIP candidates spill to disk.
 * Detection uses the captured bytes, including signatures after a preamble, never the extension.
 */
final class PreparedExtractionInput implements AutoCloseable {
  static final int MAX_IN_MEMORY_BYTES = 1024 * 1024;

  /**
   * These existing AutoDetectParser text routes need only prefix/name detection and stream
   * parsing, not container random access. ZIP candidates always use file-backed detection,
   * even with a text extension. All other types conservatively materialize a private file.
   */
  static final Set<String> STREAM_ONLY_MIME_TYPES = Set.of(
      "text/plain", "text/x-web-markdown", "text/csv", "text/html",
      "application/xml", "application/json");

  private static final Map<String, String> OFFICE_MAIN_TYPES = Map.ofEntries(
      Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
      Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml",
          "application/vnd.openxmlformats-officedocument.wordprocessingml.template"),
      Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.template.main+xml",
          "application/vnd.openxmlformats-officedocument.spreadsheetml.template"),
      Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
      Map.entry("application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml",
          "application/vnd.openxmlformats-officedocument.presentationml.slideshow"),
      Map.entry("application/vnd.openxmlformats-officedocument.presentationml.template.main+xml",
          "application/vnd.openxmlformats-officedocument.presentationml.template"),
      Map.entry("application/vnd.ms-word.document.macroEnabled.main+xml",
          "application/vnd.ms-word.document.macroenabled.12"),
      Map.entry("application/vnd.ms-word.template.macroEnabledTemplate.main+xml",
          "application/vnd.ms-word.template.macroenabled.12"),
      Map.entry("application/vnd.ms-excel.sheet.macroEnabled.main+xml",
          "application/vnd.ms-excel.sheet.macroenabled.12"),
      Map.entry("application/vnd.ms-excel.sheet.binary.macroEnabled.main",
          "application/vnd.ms-excel.sheet.binary.macroenabled.12"),
      Map.entry("application/vnd.ms-excel.template.macroEnabled.main+xml",
          "application/vnd.ms-excel.template.macroenabled.12"),
      Map.entry("application/vnd.ms-excel.addin.macroEnabled.main+xml",
          "application/vnd.ms-excel.addin.macroenabled.12"),
      Map.entry("application/vnd.ms-powerpoint.presentation.macroEnabled.main+xml",
          "application/vnd.ms-powerpoint.presentation.macroenabled.12"),
      Map.entry("application/vnd.ms-powerpoint.slideshow.macroEnabled.main+xml",
          "application/vnd.ms-powerpoint.slideshow.macroenabled.12"),
      Map.entry("application/vnd.ms-powerpoint.template.macroEnabled.main+xml",
          "application/vnd.ms-powerpoint.template.macroenabled.12"),
      Map.entry("application/vnd.ms-powerpoint.addin.macroEnabled.main+xml",
          "application/vnd.ms-powerpoint.addin.macroenabled.12"));

  private final Path source;
  private final Factory factory;
  private final List<byte[]> chunks;
  private final long size;
  private final ContainerExpansionBudget expansion;
  private final boolean zipCandidate;
  private Path snapshot;

  private PreparedExtractionInput(Path source, Factory factory, List<byte[]> chunks,
      long size, Path snapshot, ContainerExpansionBudget expansion, boolean zipCandidate) {
    this.source = source;
    this.factory = factory;
    this.chunks = chunks;
    this.size = size;
    this.snapshot = snapshot;
    this.expansion = expansion;
    this.zipCandidate = zipCandidate;
  }

  boolean isDiskBacked() { return snapshot != null; }
  Path source() { return source; }
  String name() { return source.getFileName().toString(); }
  long size() { return size; }
  ContainerExpansionBudget expansion() { return expansion; }

  Metadata metadata() {
    Metadata metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name());
    metadata.set(Metadata.CONTENT_LENGTH, Long.toString(size));
    return metadata;
  }

  String detect(Tika tika) throws IOException {
    if (!zipCandidate && !isDiskBacked()) {
      try (InputStream stream = openStream()) {
        String mime = tika.detect(stream, metadata());
        if (STREAM_ONLY_MIME_TYPES.contains(mime)) return mime;
      }
    }
    // Match Tika.detect(Path)'s full content-aware detection, preserving the original name
    // rather than the generated snapshot basename. Never detect the mutable source again.
    try (TikaInputStream stream = TikaInputStream.get(file())) {
      String mime = tika.detect(stream, metadata());
      // Every ZIP candidate's declared OPC main type decides Office admission, whatever label
      // the detector chose (a generic OOXML label must not skip the conflict check).
      return zipCandidate ? detectOfficePackage(mime) : mime;
    }
  }

  private String detectOfficePackage(String fallback) throws IOException {
    // POI can reject an OPC package because an unrelated part lacks a content type, causing
    // even file-backed Tika detection to return ZIP. Its declared Office main part must still
    // receive Office admission limits. Also reject conflicting declared main types before a
    // detector's choice of one family can bypass admission of another. Preparation already
    // bounded every inflated ZIP member.
    // Read the captured content-types member with Tika's entity-safe streaming XML reader.
    ZipFile opened;
    try {
      opened = new ZipFile(file().toFile());
    } catch (java.util.zip.ZipException notAZip) {
      return fallback; // A ZIP-magic false positive is not an OPC package.
    }
    try (ZipFile zip = opened) {
      var entry = zip.getEntry("[Content_Types].xml");
      if (entry == null) return fallback;
      String[] officeMime = {null};
      DefaultHandler handler = new DefaultHandler() {
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
          if (!"http://schemas.openxmlformats.org/package/2006/content-types".equals(uri)
              || !"Override".equals(localName)) return;
          String type = OFFICE_MAIN_TYPES.get(attributes.getValue("ContentType"));
          String part = attributes.getValue("PartName");
          if (type != null && part != null && part.startsWith("/")
              && zip.getEntry(part.substring(1)) != null) {
            if (officeMime[0] != null && !type.equals(officeMime[0])) {
              throw new SAXException("Conflicting Office main types");
            }
            officeMime[0] = type;
          }
        }
      };
      try (InputStream stream = zip.getInputStream(entry)) {
        XMLReaderUtils.parseSAX(stream, handler, new ParseContext());
      } catch (org.apache.tika.exception.TikaException | SAXException malformed) {
        throw new IOException("Could not inspect Office content types", malformed);
      }
      return officeMime[0] == null ? fallback : officeMime[0];
    }
  }

  InputStream openParserStream(Tika tika) throws IOException {
    String mime = detect(tika);
    if (!zipCandidate && STREAM_ONLY_MIME_TYPES.contains(mime)) return openStream();
    return TikaInputStream.get(file());
  }

  InputStream openStream() throws IOException {
    if (snapshot != null) return Files.newInputStream(snapshot);
    List<InputStream> streams = new ArrayList<>(chunks.size());
    for (byte[] chunk : chunks) streams.add(new ByteArrayInputStream(chunk));
    return new SequenceInputStream(Collections.enumeration(streams));
  }

  /** Materialize once from captured bytes for consumers that require a private file. */
  synchronized Path file() throws IOException {
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
    if (snapshot != null) Files.deleteIfExists(snapshot);
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
      boolean zipCandidate = false;
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
          for (int i = 0; i < read; i++) {
            signature = (signature << 8) | (buffer[i] & 0xff);
            // Inspect every offset, covering preambles and chunk boundaries. Any ZIP
            // accepted by ZipFile has a central directory/EOCD. False positives spill.
            if (signature == 0x504b0304 || signature == 0x504b0102
                || signature == 0x504b0506 || signature == 0x504b0606) zipCandidate = true;
          }
          if (snapshot == null) {
            if (zipCandidate || copied > MAX_IN_MEMORY_BYTES) {
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
        return new PreparedExtractionInput(source, this, chunks, copied, snapshot, expansion, zipCandidate);
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
