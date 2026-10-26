/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.execution.InferenceRequest;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.QueryDevice;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The query prewarm makes the CPU session of each eligible query encoder serving on the GPU
 * warm, after it has reported the encoders ready, so a query the device turns away has a warm CPU
 * session to run on. Encoders that already serve queries on the CPU are not warmed twice, and a
 * failing CPU warm-up does not hold readiness back.
 *
 * <p>Real seam: the production {@link QueryEncoderPrewarm} runner on a real scheduled executor;
 * the targets are the encoders' warmth, device and warm-up hooks.
 */
@Timeout(60)
final class QueryEncoderPrewarmCpuWarmTest {

  @Test
  void gpuQueryEncodersGetTheirCpuSessionWarmedAfterReady() throws Exception {
    List<String> order = new CopyOnWriteArrayList<>();
    List<InferenceRequest> cpuWarmRequests = new CopyOnWriteArrayList<>();
    CountDownLatch cpuWarmed = new CountDownLatch(2);
    var embedding =
        new QueryEncoderPrewarm.Target(
            EncoderRole.EMBEDDING, new Object(), () -> true, () -> QueryDevice.GPU,
            listener -> {}, (text, request) -> true,
            request -> {
              order.add("cpu-warm EMBEDDING");
              cpuWarmRequests.add(request);
              cpuWarmed.countDown();
              return true;
            });
    var bgeGpu =
        new QueryEncoderPrewarm.Target(
            EncoderRole.BGE_M3, new Object(), () -> true, () -> QueryDevice.GPU,
            listener -> {}, (text, request) -> true,
            request -> {
              order.add("cpu-warm BGE_M3 GPU");
              cpuWarmed.countDown();
              throw new IllegalStateException("CPU session could not be created");
            });
    var cpuServed =
        new QueryEncoderPrewarm.Target(
            EncoderRole.CITATION, new Object(), () -> true, () -> QueryDevice.CPU,
            listener -> {}, (text, request) -> true,
            request -> {
              order.add("cpu-warm CITATION");
              return true;
            });

    try (var prewarm =
        new QueryEncoderPrewarm(
            () -> new QueryEncoderPrewarm.Selection(List.of(embedding, bgeGpu, cpuServed), () -> {}),
            Duration.ofSeconds(10),
            Executors.newSingleThreadScheduledExecutor())) {
      CountDownLatch ready = new CountDownLatch(1);
      prewarm.addListener(
          new QueryEncoderPrewarm.QueryPrewarmListener() {
            @Override
            public void onReady(Set<EncoderRole> warmed, QueryDevice device) {
              order.add("ready");
              ready.countDown();
            }

            @Override
            public void onFailed(EncoderRole role, String reason) {
              order.add("failed " + role);
            }
          });
      prewarm.start();

      assertTrue(ready.await(10, TimeUnit.SECONDS), "the encoders were reported ready");
      assertTrue(cpuWarmed.await(10, TimeUnit.SECONDS), () -> "CPU sessions not warmed: " + order);
      assertEquals(
          List.of("ready", "cpu-warm EMBEDDING", "cpu-warm BGE_M3 GPU"),
          order.subList(0, 3),
          "readiness first, then the CPU sessions of the GPU encoders, never a CPU-served one");
      assertTrue(
          order.stream().noneMatch(step -> step.equals("cpu-warm CITATION")),
          () -> "a CPU-served encoder was warmed again: " + order);
      assertTrue(
          order.stream().noneMatch(step -> step.startsWith("failed")),
          () -> "a failed CPU warm-up failed the prewarm: " + order);
      InferenceRequest request = cpuWarmRequests.get(0);
      assertEquals(InferenceRequest.Urgency.FOREGROUND, request.urgency());
    }
  }
}
