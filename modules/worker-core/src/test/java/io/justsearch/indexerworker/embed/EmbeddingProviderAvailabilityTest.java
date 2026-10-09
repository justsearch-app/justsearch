/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class EmbeddingProviderAvailabilityTest {
  @Test void placeholderStatesAreExplicitAndRetainLoadFailure() {
    assertEquals(EmbeddingProvider.Availability.State.ABSENT,
        NoOpEmbeddingProvider.INSTANCE.availability().state());
    assertFalse(NoOpEmbeddingProvider.INSTANCE.isAvailable());
    var starting = NoOpEmbeddingProvider.starting();
    assertEquals(EmbeddingProvider.Availability.State.STARTING, starting.availability().state());
    assertFalse(starting.isAvailable());
    var cause = new IllegalStateException("model load failed");
    var failed = NoOpEmbeddingProvider.failed(cause);
    assertEquals(EmbeddingProvider.Availability.State.FAILED, failed.availability().state());
    assertSame(cause, failed.availability().failure());
    assertFalse(failed.isAvailable());
    assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingProvider.Availability(EmbeddingProvider.Availability.State.FAILED, null));
    assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingProvider.Availability(EmbeddingProvider.Availability.State.STARTING, cause));
  }

  @Test void constructedBackendIsReadyAndClosedBackendIsAbsent() {
    var config = new EmbeddingConfig(true, java.nio.file.Path.of("model"), "onnx",
        false, 0, 0, 2048, false, 0);
    var service = EmbeddingService.createWithBackend(
        new EmbeddingServiceTelemetryEmitTest.MockBackend(), config);
    assertEquals(EmbeddingProvider.Availability.State.READY, service.availability().state());
    assertTrue(service.isAvailable());
    service.close();
    assertEquals(EmbeddingProvider.Availability.State.ABSENT, service.availability().state());
    assertFalse(service.isAvailable());
  }
}
