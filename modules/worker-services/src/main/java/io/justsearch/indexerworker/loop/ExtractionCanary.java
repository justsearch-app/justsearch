/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** One tiny known-good parse per ambiguous pause step, shared by the batch's services. */
final class ExtractionCanary implements BooleanSupplier, AutoCloseable {
  private final TimeboxedContentExtractor extractor;
  private Path input;
  private Boolean healthy;
  private boolean prepared;

  ExtractionCanary(TimeboxedContentExtractor extractor) { this.extractor = extractor; }

  void beginBatch() { healthy = null; }

  @Override
  public boolean getAsBoolean() {
    if (healthy != null) return healthy;
    try {
      if (input != null && !Files.exists(input)) { input = null; prepared = false; }
      if (input == null) input = Files.createTempFile("justsearch-extraction-canary-", ".rtf");
      if (!prepared) {
        // RTF requires a private snapshot even for this tiny input and routes through the sandbox.
        Files.writeString(input, "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0\\fnil Arial;}}\\pard\\f0 JustSearch healthy extraction canary.\\par}");
        prepared = true;
      }
      extractor.probeArtifact(input);
      healthy = true;
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException failure) {
      healthy = false;
    }
    return healthy;
  }

  @Override
  public void close() throws IOException { if (input != null) Files.deleteIfExists(input); }
}
