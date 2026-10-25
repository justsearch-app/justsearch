/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import java.io.IOException;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import java.util.function.Supplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** One bounded full-pipeline health proof per failed batch; no index document or durable identity. */
final class PipelineCanary implements BooleanSupplier, AutoCloseable {
  private final TimeboxedContentExtractor extractor;
  private final Supplier<DocumentIdentityStore> identities;
  private final Runnable pathProbe;
  private final Runnable writerProbe;
  private Path input;
  private Boolean healthy;
  private boolean prepared;

  PipelineCanary(TimeboxedContentExtractor extractor, Supplier<DocumentIdentityStore> identities,
      Runnable pathProbe, Runnable writerProbe) {
    this.extractor = extractor;
    this.identities = identities;
    this.pathProbe = pathProbe;
    this.writerProbe = writerProbe;
  }

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
      identities.get().probe();
      pathProbe.run();
      writerProbe.run();
      healthy = true;
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException failure) {
      healthy = false;
    }
    return healthy;
  }

  @Override
  public void close() throws IOException { if (input != null) Files.deleteIfExists(input); }
}
