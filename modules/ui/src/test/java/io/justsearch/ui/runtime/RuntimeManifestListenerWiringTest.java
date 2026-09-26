/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeManifestListenerWiringTest {

  @Test
  void realRegistrySubscriptionProjectsAggregateAndLegacyAxes(@TempDir Path tmp)
      throws IOException {
    RuntimeManifestPublisher publisher = new RuntimeManifestPublisher(tmp);
    publisher.publishHead(54321, null);
    HeadAssembly bootstrap = mock(HeadAssembly.class);
    try (var components = TestEngineComponents.fourComponents()) {
      components.handle("api").transition(ComponentState.READY, null, null);
      components.handle("index").transition(ComponentState.READY, null, null);

      RuntimeManifestListenerWiring.wire(
          publisher, bootstrap, components, () -> tmp.resolve("index"), "full-desktop");

      assertEquals("LIFECYCLE_STATE_READY", publisher.current().lifecycle());
      assertEquals("ready", publisher.current().worker().state());
      assertEquals(components.handle("index").snapshot().stateSince().toString(),
          publisher.current().worker().readyAt());
      assertEquals(tmp.resolve("index").toString(), publisher.current().worker().indexBasePath());
      assertEquals("OFFLINE", publisher.current().ai().phase());
      assertFalse(publisher.current().ai().required());
      assertEquals("retrieval-only", publisher.current().mode().realized());

      components.handle("generative").transition(ComponentState.READY, null, null);
      assertEquals("READY", publisher.current().ai().phase());
      assertEquals("full", publisher.current().mode().realized());
      assertEquals(components.handle("generative").snapshot().stateSince().toString(),
          publisher.current().ai().readyAt());
      var workerReadyAt = publisher.current().worker().readyAt();
      var aiReadyAt = publisher.current().ai().readyAt();
      var writes = new java.util.concurrent.atomic.AtomicInteger();
      publisher.addListener(ignored -> writes.incrementAndGet());
      components.handle("index").setDesiredVersion("changed-metadata-only");
      assertEquals(0, writes.get(), "unchanged projections must not cause manifest writes");
      components.handle("api").transition(ComponentState.FAILED, "local_api.bind_failed", null);
      assertEquals("LIFECYCLE_STATE_ERROR", publisher.current().lifecycle());
      assertEquals(1, writes.get(), "an API-only change writes only the changed aggregate");
      assertEquals(workerReadyAt, publisher.current().worker().readyAt());
      assertEquals(aiReadyAt, publisher.current().ai().readyAt());
      components.handle("api").transition(ComponentState.READY, null, null);

      components.handle("index").transition(ComponentState.STARTING, "index.reloading", null);
      assertEquals("pending", publisher.current().worker().state());
      assertEquals("degraded", publisher.current().mode().realized());
      components.handle("index").transition(ComponentState.READY, null, null);
      assertEquals(components.handle("index").snapshot().stateSince().toString(),
          publisher.current().worker().readyAt(), "recovery carries the new component epoch");

      components
          .handle("encoders")
          .transition(ComponentState.UNAVAILABLE, "encoders.model_not_installed", null);
      assertEquals("LIFECYCLE_STATE_DEGRADED", publisher.current().lifecycle());
      assertEquals("ready", publisher.current().worker().state());
    } finally {
      publisher.close();
    }
  }
}
