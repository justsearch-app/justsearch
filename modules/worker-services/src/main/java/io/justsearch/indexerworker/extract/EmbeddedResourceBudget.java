/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/** Per-document budget retained across nested parsing and the flat fallback attempt. */
final class EmbeddedResourceBudget implements EmbeddedDocumentExtractor {
  private final TikaExtractionPolicy policy;
  private final long maxExpandedBytes;
  private final ParsingEmbeddedDocumentExtractor delegate;
  private final ContainerExpansionBudget expansion;
  private long expandedBytes;
  private int resources;
  private int depth;
  private int maxDepth;
  private String exceeded;

  EmbeddedResourceBudget(TikaExtractionPolicy policy, long inputBytes, ParseContext context) {
    this(policy, inputBytes, context, new ContainerExpansionBudget(policy, inputBytes));
  }

  EmbeddedResourceBudget(TikaExtractionPolicy policy, long inputBytes, ParseContext context,
      ContainerExpansionBudget expansion) {
    this.policy = policy;
    // Bound cumulative expansion work as well as expansion relative to the original input.
    this.maxExpandedBytes = (long) Math.min(
        policy.maxInputBytes(), Math.max(1, inputBytes) * policy.maxCompressionRatio());
    this.delegate = new ParsingEmbeddedDocumentExtractor(context);
    this.expansion = expansion;
    context.set(EmbeddedDocumentExtractor.class, this);
  }

  @Override
  public boolean shouldParseEmbedded(Metadata metadata) {
    requireAvailable();
    if (resources >= policy.maxEmbeddedResources()) fail("EMBEDDED_RESOURCE_LIMIT");
    if (depth >= policy.maxEmbeddedDepth()) fail("EMBEDDED_DEPTH_LIMIT");
    return delegate.shouldParseEmbedded(metadata);
  }

  @Override
  public void parseEmbedded(InputStream input, ContentHandler handler, Metadata metadata,
      boolean outputHtml) throws IOException, SAXException {
    shouldParseEmbedded(metadata);
    resources++;
    depth++;
    maxDepth = Math.max(maxDepth, depth);
    Path spool = null;
    try {
      // Materialize through the budget before handing a seekable stream to a parser. Otherwise
      // Tika's container detection/getFile can expand an entry outside a counting stream.
      spool = Files.createTempFile("justsearch-embedded-", ".bin");
      try (OutputStream output = Files.newOutputStream(spool)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer, 0,
            (int) Math.min(buffer.length, maxExpandedBytes - expandedBytes + 1))) != -1) {
          expandedBytes += read;
          if (expandedBytes > maxExpandedBytes) fail("ARCHIVE_EXPANSION_LIMIT");
          output.write(buffer, 0, read);
        }
      }
      try {
        expansion.inspect(spool);
      } catch (ContentExtractor.BudgetExceededException limit) {
        fail(limit.reasonCode());
      }
      try (TikaInputStream bounded = TikaInputStream.get(spool)) {
        delegate.parseEmbedded(bounded, handler, metadata, outputHtml);
      }
      requireAvailable();
    } finally {
      depth--;
      if (spool != null) Files.deleteIfExists(spool);
    }
  }

  int resources() { return resources; }

  int maxDepth() { return maxDepth; }

  void check() throws ContentExtractor.BudgetExceededException {
    if (exceeded != null) {
      throw new ContentExtractor.BudgetExceededException(
          "Embedded extraction exceeds policy: " + exceeded, exceeded);
    }
  }

  private void requireAvailable() {
    if (exceeded != null) throw new LimitReached();
  }

  private void fail(String reason) {
    exceeded = reason;
    throw new LimitReached();
  }

  // shouldParseEmbedded has no checked exception contract. The latched reason is checked even
  // when a parser wraps or swallows this exception; it can never trigger an unbudgeted fallback.
  private static final class LimitReached extends RuntimeException {}
}
