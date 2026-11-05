/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.justsearch.indexerworker.tokenizer.HuggingFaceNativeRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Token counting owns only a tokenizer, never an inference session or query encoder. */
public final class SpladeTokenCounter implements ToLongFunction<String>, AutoCloseable {
  private final HuggingFaceTokenizer tokenizer;
  private boolean closed;

  public SpladeTokenCounter(Path modelDirectory) throws IOException {
    HuggingFaceNativeRuntime.prepareCpuCache();
    tokenizer = HuggingFaceTokenizer.newInstance(modelDirectory.resolve("tokenizer.json"),
        Map.of("truncation", "false", "padding", "false"));
  }

  @Override public synchronized long applyAsLong(String text) {
    if (closed) throw new IllegalStateException("Token counter retired");
    return text == null || text.isBlank() ? 0L : tokenizer.encode(text).getIds().length;
  }

  @Override public synchronized void close() {
    if (closed) return;
    tokenizer.close();
    closed = true;
  }
}
