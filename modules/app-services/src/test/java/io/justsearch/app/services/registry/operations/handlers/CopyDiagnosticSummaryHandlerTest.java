/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DiagnosticsService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CopyDiagnosticSummaryHandlerTest {

  @Test
  void returnsSummaryOnlyInStructuredDataWithFixedMessage() {
    String summary = "app.version: 1.2.3\nsecret-looking-test-marker";
    CopyDiagnosticSummaryHandler handler =
        new CopyDiagnosticSummaryHandler(() -> diagnosticsReturning(summary));

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(summary, result.structuredData().get(CopyDiagnosticSummaryHandler.SUMMARY_KEY));
    assertEquals(CopyDiagnosticSummaryHandler.SUCCESS_MESSAGE, result.message());
    assertFalse(result.message().contains(summary));
  }

  @Test
  void remainsAvailableWithoutWorkerOrInferenceArguments() {
    CopyDiagnosticSummaryHandler handler =
        new CopyDiagnosticSummaryHandler(() -> diagnosticsReturning("local summary"));

    assertTrue(handler.execute(null, io.justsearch.app.services.TestEngineContexts.internal()).success());
  }

  @Test
  void unavailableServiceReturnsFixedNonPayloadFailure() {
    CopyDiagnosticSummaryHandler handler = new CopyDiagnosticSummaryHandler(() -> null);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(CopyDiagnosticSummaryHandler.FAILURE_MESSAGE, result.message());
  }

  @Test
  void generationFailureDoesNotExposeExceptionMessage() {
    DiagnosticsService diagnostics =
        new DiagnosticsService() {
          @Override
          public Path exportDiagnostics(io.justsearch.core.context.EngineContext engineContext) {
            throw new UnsupportedOperationException();
          }

          @Override
          public String buildDiagnosticSummary() {
            throw new IllegalStateException("token=must-not-escape");
          }
        };
    CopyDiagnosticSummaryHandler handler = new CopyDiagnosticSummaryHandler(() -> diagnostics);

    OperationResult result = handler.execute("{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(CopyDiagnosticSummaryHandler.FAILURE_MESSAGE, result.message());
    assertFalse(result.message().contains("must-not-escape"));
  }

  private static DiagnosticsService diagnosticsReturning(String summary) {
    return new DiagnosticsService() {
      @Override
      public Path exportDiagnostics(io.justsearch.core.context.EngineContext engineContext) {
        throw new UnsupportedOperationException();
      }

      @Override
      public String buildDiagnosticSummary() {
        return summary;
      }
    };
  }
}
