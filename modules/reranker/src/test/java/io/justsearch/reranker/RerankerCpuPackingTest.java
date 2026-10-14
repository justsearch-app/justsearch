/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.ort.DeviceArenaPlans;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.GpuArenaPlan;
import io.justsearch.ort.SessionHandle;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class RerankerCpuPackingTest {
  @Test void gpuLeaseKeepsItsPackingPlanWhenTheDevicePlanIsWithdrawn() throws Exception {
    int device = 18_990;
    var facts = new GpuArenaPlan.Facts(OptionalDouble.of(12_282),
        Map.of(EncoderRole.RERANKER, 600.3), Set.of(ChatModelProfile.STANDARD), Map.of(),
        GpuArenaPlan.ExternalChat.NONE, 1);
    var plan = GpuArenaPlan.resolve(facts, new GpuArenaPlan.Settings(Optional.empty(),
        OptionalInt.empty(), OptionalInt.empty(), Map.of(EncoderRole.RERANKER, 800.3), false));
    assertTrue(plan.planned());
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(lease.isCpu()).thenReturn(false);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.environment()).thenReturn(mock(OrtEnvironment.class));
    when(sessions.arenaPlan()).thenAnswer(_ -> DeviceArenaPlans.current(device));
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(new long[20][512], new long[20][512], new long[20][512],
            20, 512, 0, 512));
    Map<OnnxTensor, long[]> tensorShapes = new IdentityHashMap<>();
    List<Integer> paddedRows = new ArrayList<>();
    when(lease.run(anyMap())).thenAnswer(call -> {
      Map<String, OnnxTensor> inputs = call.getArgument(0);
      int rows = Math.toIntExact(tensorShapes.get(inputs.get("input_ids"))[0]);
      paddedRows.add(rows);
      if (paddedRows.size() == 1) DeviceArenaPlans.withdraw(device);
      var scores = mock(OnnxTensor.class);
      when(scores.getValue()).thenReturn(new float[rows][1]);
      var result = mock(OrtSession.Result.class);
      when(result.get(0)).thenReturn(scores);
      return result;
    });
    DeviceArenaPlans.publish(device, plan);
    try (var tensors = mockStatic(OnnxTensor.class);
        var reranker = new CrossEncoderReranker(sessions, new RerankerShape(512, false), tokenizer)) {
      tensors.when(() -> OnnxTensor.createTensor(any(OrtEnvironment.class),
          any(LongBuffer.class), any(long[].class))).thenAnswer(call -> {
        var tensor = mock(OnnxTensor.class);
        tensorShapes.put(tensor, ((long[]) call.getArgument(2)).clone());
        return tensor;
      });
      var documents = IntStream.range(0, 20).mapToObj(i -> "document " + i).toList();
      var output = reranker.rerank("query", documents, 30_000);
      assertEquals(Collections.nCopies(20, 0.0f), output.scores());
      assertTrue(paddedRows.size() > 1, "the fixture must retain a GPU lease across several calls");
      assertEquals(List.of(4, 4, 4, 4, 4), paddedRows,
          "withdrawing the device plan must keep every retained GPU call at four padded rows");
      verify(sessions, never()).acquireCpu(any());
    } finally {
      DeviceArenaPlans.withdraw(device);
    }
  }

  @Test void one512TokenPairReturnsCpuScoresUnderAnInfeasibleGpuCap() throws Exception {
    var sessions = mock(SessionHandle.class);
    var lease = mock(SessionHandle.Lease.class);
    when(lease.isCpu()).thenReturn(true);
    when(sessions.acquire(any())).thenReturn(lease);
    when(sessions.acquireCpu(any())).thenReturn(lease);
    when(sessions.environment()).thenReturn(mock(OrtEnvironment.class));
    var facts = new GpuArenaPlan.Facts(OptionalDouble.of(12_282),
        Map.of(EncoderRole.RERANKER, 600.3), Set.of(ChatModelProfile.STANDARD), Map.of(),
        GpuArenaPlan.ExternalChat.NONE, 1);
    var plan = GpuArenaPlan.resolve(facts, new GpuArenaPlan.Settings(Optional.empty(),
        OptionalInt.empty(), OptionalInt.empty(), Map.of(EncoderRole.RERANKER, 100.0), false));
    when(sessions.arenaPlan()).thenReturn(Optional.of(plan));
    var tokenizer = mock(RerankerTokenizer.class);
    when(tokenizer.encodePairs(anyString(), any(String[].class))).thenReturn(
        new RerankerTokenizer.EncodedBatch(new long[1][512], new long[1][512], new long[1][512],
            1, 512, 0, 512));
    var result = mock(OrtSession.Result.class);
    var score = mock(OnnxTensor.class);
    when(score.getValue()).thenReturn(new float[][] {{0.8f}, {0}, {0}, {0}});
    when(result.get(0)).thenReturn(score);
    when(lease.run(anyMap())).thenReturn(result);
    try (var tensors = mockStatic(OnnxTensor.class);
        var reranker = new CrossEncoderReranker(sessions, new RerankerShape(512, false), tokenizer)) {
      tensors.when(() -> OnnxTensor.createTensor(any(OrtEnvironment.class),
          any(LongBuffer.class), any(long[].class))).thenReturn(mock(OnnxTensor.class));
      var output = assertDoesNotThrow(() -> reranker.rerank("query", List.of("512 token fixture"), 30_000),
          "CPU scoring must not throw because its unused GPU cap is too small");
      assertEquals(List.of(0.8f), output.scores());
      verify(sessions).acquireCpu(any());
    }
  }
}
