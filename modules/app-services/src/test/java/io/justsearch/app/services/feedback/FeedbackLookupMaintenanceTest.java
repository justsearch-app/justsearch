/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.core.execution.TestEngineExecutors;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class FeedbackLookupMaintenanceTest {
  @Test
  void transientFailureRetainsOneBackgroundRetryWithoutAnotherCapture(@TempDir Path dir)
      throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, new ObjectMapper().writeValueAsString(new FeatureSnapshot(
        "historical", "q", 1L, List.of(
            new FeatureSnapshot.HitFeatures("uid", "path", 1, 1f, 0f, 0f, 1f, null)))) + "\n");
    var cipher = spy(StoreCipher.disabled());
    var failed = new CountDownLatch(1);
    var releaseFailure = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    doAnswer(invocation -> {
      if (attempts.incrementAndGet() == 1) {
        failed.countDown();
        assertTrue(releaseFailure.await(5, TimeUnit.SECONDS));
        throw new UncheckedIOException(new IOException("temporary read failure"));
      }
      return invocation.callRealMethod();
    }).when(cipher).open(anyString());
    try (var registry = TestEngineExecutors.awaitingTermination();
        var maintenance = new FeedbackLookupMaintenance(registry, archive, cipher, DataKeyManager.disabled())) {
      try {
        assertTrue(failed.await(2, TimeUnit.SECONDS));
        assertFalse(maintenance.ready().isDone());
      } finally {
        releaseFailure.countDown();
      }
      maintenance.ready().get(5, TimeUnit.SECONDS);
      assertEquals(2, attempts.get(), "the same obligation must retry the previously failed handle");
      assertEquals(Optional.of("uid"),
          new NdjsonAppendStore<>(archive, FeatureSnapshot.class, cipher).resolveStableDocId("historical", "path"));
    }
  }

  @Test
  void closeCancelsTheRetainedRetry(@TempDir Path dir) throws Exception {
    Path archive = dir.resolve("feature-snapshots.ndjson");
    Files.writeString(archive, "unreadable\n");
    var failed = new CountDownLatch(1);
    var cipher = spy(StoreCipher.disabled());
    doAnswer(invocation -> {
      failed.countDown();
      throw new UncheckedIOException(new IOException("temporary read failure"));
    }).when(cipher).open(anyString());
    try (var registry = TestEngineExecutors.awaitingTermination();
        var maintenance = new FeedbackLookupMaintenance(registry, archive, cipher, DataKeyManager.disabled())) {
      assertTrue(failed.await(2, TimeUnit.SECONDS));
      maintenance.close();
      assertTrue(maintenance.ready().isCompletedExceptionally());
      verify(cipher, atLeastOnce()).open(anyString());
      clearInvocations(cipher);
      Thread.sleep(1100);
      verify(cipher, never()).open(anyString());
    }
  }
}
