/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.runtime.child.LedgerSink;
import io.justsearch.app.api.runtime.child.OwnedHelper;
import io.justsearch.app.api.runtime.ledger.EngineSelfIntentWriter.EngineIdentity;
import io.justsearch.indexerworker.TestWorkerExecutorRegistrations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.tika.parser.AutoDetectParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

/**
 * Task b6d536 b101: no helper window where Tesseract cannot launch, and none at construction. The
 * flat-text fallback after a structured parser failure builds its own extractor per document and
 * cannot launch Tesseract; constructing an extractor launches nothing once the Tesseract runtime is
 * recorded (its one-time check is a real launch with its own window). Neither writes a window.
 */
final class ExtractionFallbackWindowTest {
  @TempDir Path dir;
  private final List<String> types = new CopyOnWriteArrayList<>();
  private Object saved;

  @BeforeEach void record() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    saved = field.get(null);
    LedgerSink sink = (type, fields) -> {
      types.add(type.token());
      return true;
    };
    OwnedHelper.recordTo(sink, new EngineIdentity(ProcessHandle.current().pid(), Optional.empty(), "fallback-windows"));
  }

  @AfterEach void restore() throws Exception {
    var field = OwnedHelper.class.getDeclaredField("recorder");
    field.setAccessible(true);
    field.set(null, saved);
  }

  private long windows() {
    return types.stream().filter(type -> type.startsWith("helper-window-")).count();
  }

  @Test void textParsedThroughTheFallbackWritesNoWindow() throws Exception {
    try (var extractor = new StructuredContentExtractor()) {
      var parser = mock(AutoDetectParser.class);
      doThrow(new SAXException("structured parser failure")).when(parser).parse(any(), any(), any(), any());
      var field = StructuredContentExtractor.class.getDeclaredField("parser");
      field.setAccessible(true);
      field.set(extractor, parser);
      types.clear(); // construction has its own check below
      for (int i = 0; i < 3; i++) {
        Path text = dir.resolve("fallback-" + i + ".txt");
        Files.writeString(text, "Harbor customs ledger manifest " + i, StandardCharsets.UTF_8);
        assertTrue(extractor.extract(text).content().contains("Harbor customs ledger"),
            "the flat-text fallback still extracts the text");
      }
      assertEquals(0, windows(), "three fallback parses that cannot launch Tesseract write no window: " + types);
    }
  }

  @Test void constructingExtractorsWritesNoWindow() throws Exception {
    TikaOcrRuntime.resolve(); // the runtime's one-time check, a real launch, is not construction
    types.clear();
    new ContentExtractor(10_000).close();
    new StructuredContentExtractor().close();
    new PolicyDrivenTikaExtractor(TestWorkerExecutorRegistrations.ocrFactory(),
        TikaExtractionPolicy.defaults(), OcrRoutingConfig.defaults()).close();
    PolicyDrivenTikaExtractor.TESSERACT_WINDOW.close();
    assertEquals(0, windows(), "constructing an extractor launches nothing and writes no window: " + types);
  }
}
