/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Best-effort PDF page evidence for visual extraction routing. */
final class PdfVisualAnalyzer {
  private static final Logger log = LoggerFactory.getLogger(PdfVisualAnalyzer.class);

  private PdfVisualAnalyzer() {}

  static StructuredDocumentSummary enrich(PreparedExtractionInput input, StructuredDocumentSummary base) {
    StructuredDocumentSummary summary = base == null ? StructuredDocumentSummary.empty() : base;
    try (var stream = input.openStream();
        var bytes = new org.apache.pdfbox.io.RandomAccessReadBuffer(stream);
        PDDocument document = Loader.loadPDF(bytes)) {
      PdfPageEvidence evidence = analyze(document);
      return summary.withPdfPageSignals(evidence.pageCount(), evidence.readablePages(), evidence.imagePages());
    } catch (IOException | RuntimeException e) {
      log.debug("PDF visual evidence unavailable for {}: {}", input.source(), e.getMessage());
      return summary;
    }
  }

  static StructuredDocumentSummary enrich(Path file, StructuredDocumentSummary base) {
    PdfPageEvidence evidence = analyze(file);
    StructuredDocumentSummary summary = base == null ? StructuredDocumentSummary.empty() : base;
    if (evidence == null) {
      return summary;
    }
    return summary.withPdfPageSignals(evidence.pageCount(), evidence.readablePages(), evidence.imagePages());
  }

  static PdfPageEvidence analyze(Path file) {
    if (file == null) {
      return null;
    }
    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      return analyze(document);
    } catch (IOException | RuntimeException e) {
      log.debug("PDF visual evidence unavailable for {}: {}", file.getFileName(), e.getMessage());
      return null;
    }
  }

  private static PdfPageEvidence analyze(PDDocument document) throws IOException {
    int pageCount = document.getNumberOfPages();
    Set<Integer> readablePages = readableTextPages(document);
    Set<Integer> imagePages = imagePages(document);
    Set<Integer> missingReadableTextPages = new HashSet<>();
    for (int i = 0; i < pageCount; i++) {
      if (!readablePages.contains(i)) {
        missingReadableTextPages.add(i);
      }
    }
    return new PdfPageEvidence(pageCount, readablePages, missingReadableTextPages, imagePages);
  }

  private static Set<Integer> readableTextPages(PDDocument document) throws IOException {
    Set<Integer> readable = new HashSet<>();
    PDFTextStripper stripper = new PDFTextStripper();
    for (int page = 1; page <= document.getNumberOfPages(); page++) {
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      String text = stripper.getText(document);
      if (VisualPageEvidencePolicy.hasReadableText(text)) {
        readable.add(page - 1);
      }
    }
    return readable;
  }

  private static Set<Integer> imagePages(PDDocument document) throws IOException {
    Set<Integer> pages = new HashSet<>();
    for (int i = 0; i < document.getNumberOfPages(); i++) {
      PDPage page = document.getPage(i);
      if (page.getResources() == null) {
        continue;
      }
      for (COSName name : page.getResources().getXObjectNames()) {
        PDXObject xObject = page.getResources().getXObject(name);
        if (xObject instanceof PDImageXObject) {
          pages.add(i);
          break;
        }
      }
    }
    return pages;
  }

  record PdfPageEvidence(
      int pageCount, Set<Integer> readablePages, Set<Integer> missingReadableTextPages, Set<Integer> imagePages) {
    boolean mixed() {
      return pageCount > 1 && !readablePages.isEmpty() && !missingReadableTextPages.isEmpty();
    }
  }
}
