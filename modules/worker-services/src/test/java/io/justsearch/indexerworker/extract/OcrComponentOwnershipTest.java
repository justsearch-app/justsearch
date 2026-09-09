/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class OcrComponentOwnershipTest {
  @Test void childCacheRetainsFailedCloseAndClosesBeforeReplacement() {
    var first = mock(PdfOcrEngine.class);
    var second = mock(PdfOcrEngine.class);
    doThrow(new IllegalStateException("child still alive")).doNothing().when(first).close();
    try (var factory = mockStatic(PdfOcrEngine.class);
        var cache = new ExtractionSandboxChild.ExtractorCache()) {
      factory.when(() -> PdfOcrEngine.create(any(), any(), any())).thenReturn(first, second);
      var policy = TikaExtractionPolicy.defaults();
      var old = cache.extractor(policy, OcrRoutingConfig.disabled());
      var changed = new OcrRoutingConfig(true, List.of("eng"), 10_000, null, null, null, null, null);
      assertThrows(IllegalStateException.class, () -> cache.extractor(policy, changed));
      factory.verify(() -> PdfOcrEngine.create(any(), any(), any()), times(1));
      assertSame(old, cache.extractor(policy, OcrRoutingConfig.disabled()));
      assertNotSame(old, cache.extractor(policy, changed));
      verify(first, times(2)).close();
    }
    verify(second).close();
  }

  @Test void childEofClosesTheCachedExtractor(@TempDir Path directory) throws Exception {
    Path input = directory.resolve("input.txt");
    Files.writeString(input, "Structured extraction survives the child loop.");
    var engine = mock(PdfOcrEngine.class);
    var framed = new ByteArrayOutputStream();
    var request = new SandboxExtractionRequest(1, input.toString(), TikaExtractionPolicy.defaults());
    SandboxFrames.write(framed, JsonMapper.builder().build().writeValueAsBytes(request));
    try (var factory = mockStatic(PdfOcrEngine.class)) {
      factory.when(() -> PdfOcrEngine.create(any(), any(), any())).thenReturn(engine);
      var output = new ByteArrayOutputStream();
      ExtractionSandboxChild.serve(new ByteArrayInputStream(framed.toByteArray()), output);
      assertTrue(output.size() > 0, "the request must be processed before EOF cleanup");
      verify(engine).close();
    }
  }

  @Test void composedInProcessOwnerRetriesFailureAndClosesOtherSandbox() {
    var first = mock(PolicyDrivenTikaExtractor.class);
    var second = mock(PolicyDrivenTikaExtractor.class);
    var registry = ExtractorContributionRegistry.withCoreTika(first);
    registry.install(ExtractorContributionRegistry.ExtractorContribution.catchAll("other",
        ExtractorContributionRegistry.ExtractorTrust.TRUSTED, second));
    var process = mock(ExtractionSandbox.class);
    var routing = new RoutingExtractionSandbox(new InProcessExtractionSandbox(registry), process, registry);
    doThrow(new IllegalStateException("OCR child live")).doNothing().when(first).close();
    assertThrows(IllegalStateException.class, routing::close);
    verify(second).close();
    verify(process).close();
    assertEquals(List.of("core.tika"), registry.ids(), "failed provider stays owned for retry");
    routing.close();
    assertTrue(registry.ids().isEmpty());
    verify(first, times(2)).close();
    verify(second, times(1)).close();
  }
}
