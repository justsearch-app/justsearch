/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import io.justsearch.indexerworker.embed.onnx.EmbeddingAssembly;
import io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder;
import io.justsearch.indexerworker.ner.BertNerInference;
import io.justsearch.ort.EncoderRole;
import io.justsearch.ort.OrtSessionAssembler;
import io.justsearch.ort.SessionHandle;
import io.justsearch.ort.telemetry.OrtSessionTelemetryEvents;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.CrossEncoderReranker;
import io.justsearch.reranker.RerankerAssembly;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class InferenceCompositionRootQueryFailureOwnershipTest {
  @Test
  void indexAndQueryRefusalsRemainOwnedAfterBootPartition(@TempDir Path dir) throws Exception {
    assertQueryFailureOwnership(dir, true, null, true);
  }

  @ParameterizedTest
  @EnumSource(value = EncoderRole.class, names = {"RERANKER", "CITATION"})
  void queryOnlyRefusedCleanupRetainsAllCreatedHandles(EncoderRole failedRole, @TempDir Path dir)
      throws Exception {
    assertQueryFailureOwnership(dir, false, failedRole, true);
  }

  @ParameterizedTest
  @EnumSource(value = EncoderRole.class, names = {"RERANKER", "CITATION"})
  void queryOnlySilentRefusalRetainsAllCreatedHandles(EncoderRole failedRole, @TempDir Path dir)
      throws Exception {
    assertQueryFailureOwnership(dir, false, failedRole, false);
  }

  private static void assertQueryFailureOwnership(Path dir, boolean fullComposition,
      EncoderRole failedRole, boolean throwOnRefusal) throws Exception {
    var entries = new HashMap<String, String>();
    entries.put(EnvRegistry.AI_EMBED_ENABLED.configKey(), Boolean.toString(fullComposition));
    entries.put(EnvRegistry.NER_ENABLED.configKey(), Boolean.toString(fullComposition));
    entries.put(EnvRegistry.BGE_M3_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.SPLADE_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.EMBED_GPU_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.NER_GPU_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.RERANK_GPU_ENABLED.configKey(), "false");
    entries.put(EnvRegistry.RERANK_ENABLED.configKey(), "true");
    entries.put(EnvRegistry.CITATION_SCORER_ENABLED.configKey(), "true");
    var sessions = new EnumMap<EncoderRole, SessionHandle>(EncoderRole.class);
    var failures = new EnumMap<EncoderRole, IllegalArgumentException>(EncoderRole.class);
    var roles = fullComposition
        ? List.of(EncoderRole.EMBEDDING, EncoderRole.NER, EncoderRole.RERANKER, EncoderRole.CITATION)
        : List.of(EncoderRole.RERANKER, EncoderRole.CITATION);
    for (EncoderRole role : roles) {
      Path modelDir = Files.createDirectories(dir.resolve(role.consumerName()));
      Files.writeString(modelDir.resolve("model.onnx"), "model-witness");
      Files.writeString(modelDir.resolve("tokenizer.json"), "tokenizer-witness");
      String modelKey = switch (role) {
        case EMBEDDING -> EnvRegistry.EMBED_ONNX_MODEL_PATH.configKey();
        case NER -> EnvRegistry.NER_MODEL_PATH.configKey();
        case RERANKER -> EnvRegistry.RERANK_MODEL_PATH.configKey();
        case CITATION -> EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey();
        default -> throw new AssertionError(role);
      };
      entries.put(modelKey, modelDir.toString());
      boolean fails = role == EncoderRole.NER || role == failedRole
          || (fullComposition && role != EncoderRole.EMBEDDING);
      sessions.put(role, handleRefusingFirstClose(fails, throwOnRefusal));
      if (fails) failures.put(role, new IllegalArgumentException(role + " assembly failed"));
    }
    var projection = EncoderConfigurationProjection.from(
        TestResolvedConfigHelper.fromEntries(entries));
    var selection = new QueryRoleSelection(selectedQueryRole(dir, EncoderRole.RERANKER),
        selectedQueryRole(dir, EncoderRole.CITATION));
    try (var assembler = mockStatic(OrtSessionAssembler.class);
        var embedding = mockStatic(OnnxEmbeddingEncoder.class);
        var ner = mockStatic(BertNerInference.class);
        var reranker = mockStatic(CrossEncoderReranker.class);
        var citation = mockStatic(CitationScorer.class)) {
      assembler.when(() -> OrtSessionAssembler.buildManager(any(), any(), any(), any()))
          .thenAnswer(invocation -> {
            String consumer = invocation.getArgument(0);
            return sessions.entrySet().stream()
                .filter(entry -> entry.getKey().consumerName().equals(consumer))
                .findFirst().orElseThrow().getValue();
          });
      if (fullComposition) {
        embedding.when(() -> OnnxEmbeddingEncoder.buildAssembly(any(), any(), anyInt(), anyInt(),
            anyBoolean(), any())).thenReturn(new EmbeddingAssembly(
                sessions.get(EncoderRole.EMBEDDING), null, null, null));
        ner.when(() -> BertNerInference.buildAssembly(any(), any(), anyInt(), anyBoolean(), any()))
            .thenThrow(failures.get(EncoderRole.NER));
      }
      reranker.when(() -> CrossEncoderReranker.buildAssembly(any(), any(), any(), anyInt()))
          .thenAnswer(ignored -> {
            if (failures.containsKey(EncoderRole.RERANKER)) throw failures.get(EncoderRole.RERANKER);
            return new RerankerAssembly(sessions.get(EncoderRole.RERANKER), null, null);
          });
      citation.when(() -> CitationScorer.buildAssembly(any(), any(), any(), anyInt()))
          .thenAnswer(ignored -> {
            if (failures.containsKey(EncoderRole.CITATION)) throw failures.get(EncoderRole.CITATION);
            return new RerankerAssembly(sessions.get(EncoderRole.CITATION), null, null);
          });

      InferenceSurface surface = fullComposition
          ? InferenceCompositionRoot.compose(projection, HardwareProfile.cpuOnly(), null, null,
              () -> false, OrtSessionTelemetryEvents.NOOP, null, selection)
          : InferenceCompositionRoot.composeQueryRoles(projection, selection,
              HardwareProfile.cpuOnly(), () -> false, OrtSessionTelemetryEvents.NOOP);

      assertEquals(roles.stream().map(sessions::get).toList(), surface.handles());
      assertEquals(SessionHandle.RetirementStatus.REFUSED, surface.retirementStatus());
      assertEquals(failures.keySet(), surface.componentObservation().missingRoles());
      assertEquals(selection, surface.componentObservation().querySelection().orElseThrow());
      assertEquals(sessions.keySet(), surface.policies().models().keySet());
      for (EncoderRole role : roles) {
        if (failures.containsKey(role)) {
          verify(sessions.get(role)).close();
          assertEquals(SessionHandle.RetirementStatus.REFUSED, sessions.get(role).retirementStatus());
          assertEquals(throwOnRefusal ? 1 : 0, failures.get(role).getSuppressed().length);
        } else {
          verify(sessions.get(role), never()).close();
          assertEquals(SessionHandle.RetirementStatus.ACTIVE, sessions.get(role).retirementStatus());
        }
      }
      if (fullComposition) {
        assertTrue(surface.embedding().isPresent(), "the earlier usable index assembly survives");
        var partition = surface.partitionQueryRoles(projection);
        var ownedHandles = new ArrayList<>(partition.index().handles());
        ownedHandles.addAll(partition.query().handles());
        assertEquals(surface.handles(), ownedHandles, "boot must transfer every retained handle");
        var absent = IndexFingerprint.ModelFingerprint.notConfigured();
        var indexOwner = new EncoderSet(partition.index(),
            new EncoderSet.ModelIdentity(absent, absent, absent, false, 768));
        var queryOwner = new QueryRoleSet(partition.query());
        indexOwner.close();
        queryOwner.close();
        assertTrue(indexOwner.isClosed());
        assertTrue(queryOwner.isClosed());
      } else {
        var owner = new QueryRoleSet(surface);
        assertFalse(owner.isClosed());
        owner.close();
        assertTrue(owner.isClosed());
      }
      for (EncoderRole role : roles) {
        verify(sessions.get(role), times(failures.containsKey(role) ? 2 : 1)).close();
        assertEquals(SessionHandle.RetirementStatus.RETIRED, sessions.get(role).retirementStatus());
      }
      assertEquals(SessionHandle.RetirementStatus.RETIRED, surface.retirementStatus());
    }
  }

  private static QueryRoleSelection.Role selectedQueryRole(Path dir, EncoderRole role)
      throws Exception {
    Path modelDir = dir.resolve(role.consumerName());
    return QueryRoleSelection.Role.selected("model.onnx",
        GenerationModelSelection.captureIdentity(modelDir.resolve("model.onnx")),
        GenerationModelSelection.captureIdentity(modelDir.resolve("tokenizer.json")),
        ModelPrecision.FP32, ExecutionProvider.CPU);
  }

  private static SessionHandle handleRefusingFirstClose(boolean refuse, boolean throwOnRefusal) {
    var handle = mock(SessionHandle.class);
    var status = new AtomicReference<>(SessionHandle.RetirementStatus.ACTIVE);
    var closes = new AtomicInteger();
    when(handle.retirementStatus()).thenAnswer(ignored -> status.get());
    doAnswer(ignored -> {
      if (closes.incrementAndGet() == 1 && refuse) {
        status.set(SessionHandle.RetirementStatus.REFUSED);
        if (throwOnRefusal) throw new IllegalStateException("native close refused");
      } else {
        status.set(SessionHandle.RetirementStatus.RETIRED);
      }
      return null;
    }).when(handle).close();
    return handle;
  }
}
