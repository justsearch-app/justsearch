/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Routes each file to the in-process or the out-of-process sandbox by file family (tempdoc 885
 * item 14, design decision 2).
 *
 * <p>{@code process} for the families whose parsers can wedge or exhaust a heap — PDF, Office,
 * archives, images (the OCR route) and unrecognised binaries. {@code in_process} for plain text,
 * markdown, code and CSV/JSON, where the parser is a decoder and the IPC round-trip would be pure
 * overhead.
 *
 * <p>The classification is <b>not</b> a second taxonomy: it is
 * {@link IndexingDocumentOps#classifyFileKind(Path, String)}, the same function the indexing
 * pipeline already tags documents and failure metrics with. Only the process/in-process verdict
 * over its output lives here.
 */
public final class RoutingExtractionSandbox implements ExtractionSandbox {

  /** File kinds routed out of process. Everything else stays in process. */
  static final Set<String> PROCESS_KINDS = Set.of("pdf", "office", "archive", "image", "binary");

  private final ExtractionSandbox inProcess;
  private final ExtractionSandbox outOfProcess;
  private final ContentExtractorProvider mimeDetector;

  public RoutingExtractionSandbox(
      ExtractionSandbox inProcess,
      ExtractionSandbox outOfProcess,
      ContentExtractorProvider mimeDetector) {
    this.inProcess = Objects.requireNonNull(inProcess, "inProcess");
    this.outOfProcess = Objects.requireNonNull(outOfProcess, "outOfProcess");
    this.mimeDetector = Objects.requireNonNull(mimeDetector, "mimeDetector");
  }

  /** True when a file of this kind must be parsed in a child process. */
  public static boolean requiresProcessIsolation(String fileKind) {
    return fileKind != null && PROCESS_KINDS.contains(fileKind);
  }

  @Override
  public TikaExtractionPolicy policy() {
    return inProcess.policy();
  }

  @Override
  public ExtractionArtifact extract(Path file)
      throws IOException, ContentExtractor.ExtractionException {
    Objects.requireNonNull(file, "file");
    String kind = IndexingDocumentOps.classifyFileKind(file, mimeDetector.detectMimeType(file));
    return requiresProcessIsolation(kind) ? outOfProcess.extract(file) : inProcess.extract(file);
  }

  @Override
  public void close() {
    Throwable failure = null;
    try { inProcess.close(); }
    catch (RuntimeException | Error cleanup) { failure = cleanup; }
    try { outOfProcess.close(); }
    catch (RuntimeException | Error cleanup) {
      if (failure == null) failure = cleanup;
      else if (failure != cleanup) failure.addSuppressed(cleanup);
    }
    if (failure instanceof Error fatal) throw fatal;
    if (failure instanceof RuntimeException runtime) throw runtime;
  }
}
