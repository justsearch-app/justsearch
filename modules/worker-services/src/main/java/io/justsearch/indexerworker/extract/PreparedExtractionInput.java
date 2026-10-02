/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Private bounded snapshot: container inspection and parsers consume the same source bytes. */
record PreparedExtractionInput(Path file, ContainerExpansionBudget expansion, Path directory)
    implements AutoCloseable {
  static PreparedExtractionInput prepare(Path source, TikaExtractionPolicy policy)
      throws IOException, ContentExtractor.ExtractionException {
    if (Files.size(source) > policy.maxInputBytes()) {
      throw new ContentExtractor.BudgetExceededException(
          "Input exceeds policy size limit", "INPUT_TOO_LARGE");
    }
    Path name = source.getFileName();
    if (name == null) throw new IOException("Extraction input has no filename");
    Path directory = Files.createTempDirectory("justsearch-parse-input-");
    Path snapshot = directory.resolve(name.toString());
    boolean retained = false;
    try {
      long copied = 0;
      try (InputStream input = Files.newInputStream(source);
          OutputStream output = Files.newOutputStream(snapshot)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer, 0,
            (int) Math.min(buffer.length, policy.maxInputBytes() - copied + 1))) != -1) {
          copied += read;
          if (copied > policy.maxInputBytes()) {
            throw new ContentExtractor.BudgetExceededException(
                "Input exceeds policy size limit", "INPUT_TOO_LARGE");
          }
          output.write(buffer, 0, read);
        }
      }
      var expansion = new ContainerExpansionBudget(policy, copied);
      expansion.inspect(snapshot);
      retained = true;
      return new PreparedExtractionInput(snapshot, expansion, directory);
    } finally {
      if (!retained) {
        Files.deleteIfExists(snapshot);
        Files.deleteIfExists(directory);
      }
    }
  }

  @Override
  public void close() throws IOException {
    Files.deleteIfExists(file);
    Files.deleteIfExists(directory);
  }
}
