/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import ai.onnxruntime.OrtException;
import io.justsearch.aibackend.backend.AiBackend;
import io.justsearch.aibackend.backend.BackendException;
import io.justsearch.aibackend.backend.BackendRequest;
import io.justsearch.aibackend.backend.BackendResponse;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingRequest;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.EmbeddingResult;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2.Provenance;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link AiBackend} adapter wrapping {@link OnnxEmbeddingEncoder}.
 *
 * <p>Only the {@link Session#embed} method is functional. Text generation ({@link #translate}) is
 * not supported and will throw.
 */
public final class OnnxEmbeddingBackend implements AiBackend {

  private final OnnxEmbeddingEncoder encoder;
  private final int gpuLayers;
  private final int maxSeqLen;

  public OnnxEmbeddingBackend(OnnxEmbeddingEncoder encoder, int gpuLayers, int maxSeqLen) {
    this.encoder = encoder;
    this.gpuLayers = gpuLayers;
    this.maxSeqLen = maxSeqLen;
  }

  @Override
  public BackendResponse translate(BackendRequest request) throws BackendException {
    throw new BackendException("ONNX embedding backend does not support text generation");
  }

  @Override
  public Session createSession() {
    return createSession(io.justsearch.indexerworker.inference.LocalSessionAcquisition.foreground());
  }

  /** Query sessions retain the caller's authority across chunking and native fallback. */
  public Session createSession(io.justsearch.ort.SessionAcquisitionRequest acquisition) {
    return new OnnxEmbeddingSession(acquisition);
  }

  @Override
  public Provenance provenance() {
    int dim = encoder.embeddingDimension();
    return new Provenance("", "onnx", gpuLayers, dim > 0 ? dim : 768, maxSeqLen);
  }

  /** Returns the encoder for status access. */
  public OnnxEmbeddingEncoder encoder() {
    return encoder;
  }


  @Override
  public void close() throws BackendException {
    encoder.close();
  }

  private final class OnnxEmbeddingSession implements Session {

    private final io.justsearch.ort.SessionAcquisitionRequest acquisition;

    private OnnxEmbeddingSession(io.justsearch.ort.SessionAcquisitionRequest acquisition) {
      this.acquisition = java.util.Objects.requireNonNull(acquisition, "acquisition");
    }

    @Override
    public BackendResponse translate(BackendRequest request) throws BackendException {
      throw new BackendException("ONNX embedding backend does not support text generation");
    }

    @Override
    public EmbeddingResult embed(EmbeddingRequest request) throws BackendException {
      try {
        OnnxEmbeddingEncoder.EmbedResult result = encoder.embed(request.text(), acquisition);

        // Convert float[] to List<Double> (matches EmbeddingResult contract)
        List<Double> vector = toDoubleList(result.vector());

        List<List<Double>> chunkVectors;
        if (result.chunkCount() > 1 && !result.chunkVectors().isEmpty()) {
          chunkVectors = new ArrayList<>(result.chunkVectors().size());
          for (float[] cv : result.chunkVectors()) {
            chunkVectors.add(toDoubleList(cv));
          }
        } else {
          chunkVectors = List.of();
        }

        return new EmbeddingResult(
            vector,
            chunkVectors,
            result.vector().length,
            result.chunkCount(),
            false,
            "ok",
            Map.of());

      } catch (OrtException e) {
        throw new BackendException("ONNX embedding inference failed: " + e.getMessage(), e);
      }
    }

    @Override
    public List<EmbeddingResult> embedBatch(List<EmbeddingRequest> requests)
        throws BackendException {
      if (requests.size() <= 1) {
        // Single-item fast path: use chunking-aware embed()
        List<EmbeddingResult> results = new ArrayList<>(1);
        results.add(embed(requests.get(0)));
        return results;
      }

      try {
        List<String> texts = new ArrayList<>(requests.size());
        for (EmbeddingRequest req : requests) {
          texts.add(req.text());
        }
        List<OnnxEmbeddingEncoder.EmbedResult> embedResults =
            encoder.embedBatchWithChunking(texts);

        List<EmbeddingResult> results = new ArrayList<>(embedResults.size());
        for (OnnxEmbeddingEncoder.EmbedResult er : embedResults) {
          List<List<Double>> chunkVectors;
          if (er.chunkCount() > 1 && !er.chunkVectors().isEmpty()) {
            chunkVectors = new ArrayList<>(er.chunkVectors().size());
            for (float[] cv : er.chunkVectors()) {
              chunkVectors.add(toDoubleList(cv));
            }
          } else {
            chunkVectors = List.of();
          }
          results.add(
              new EmbeddingResult(
                  toDoubleList(er.vector()),
                  chunkVectors,
                  er.vector().length,
                  er.chunkCount(),
                  false,
                  "ok",
                  Map.of()));
        }
        return results;
      } catch (OrtException e) {
        throw new BackendException("ONNX batch embedding failed: " + e.getMessage(), e);
      }
    }

    private static List<Double> toDoubleList(float[] arr) {
      List<Double> list = new ArrayList<>(arr.length);
      for (float v : arr) {
        list.add((double) v);
      }
      return list;
    }
  }
}
