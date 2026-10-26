/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Retirement needs positive committed identity; native opening retains its uncertainty rules. */
final class CommittedRetirementCertificateTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void completeDigestCertifiesKnownAbsentModelsWithOrWithoutDiagnosticInputs(boolean diagnostic,
      @TempDir Path index) throws Exception {
    var expected = expected(true);
    var stored = new HashMap<>(expected);
    if (!diagnostic) stored.remove("index_fingerprint_inputs");
    seed(index, stored);
    assertTrue(IndexMetadataParityGuard.certifiesCommittedCompatibility(index, expected, List.of()),
        "NOT_CONFIGURED nulls inside a complete digest are determinate");
  }

  @Test
  void resolvedInputsWithoutDigestCertifyCompatibility(@TempDir Path index) throws Exception {
    var expected = expected(false);
    var stored = new HashMap<>(expected);
    stored.remove("index_fingerprint");
    seed(index, stored);
    assertTrue(IndexMetadataParityGuard.certifiesCommittedCompatibility(index, expected, List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "absent"})
  void unknownStoredInputsAllowNativeOpeningButCannotCertifyRetirement(String form,
      @TempDir Path index) throws Exception {
    var expected = expected(false);
    var stored = new HashMap<>(expected);
    var json = new tools.jackson.databind.ObjectMapper();
    var inputs = (tools.jackson.databind.node.ObjectNode) json.readTree(
        (String) stored.get("index_fingerprint_inputs"));
    if (form.equals("null")) inputs.putNull("embedding_model_sha256");
    else inputs.remove("embedding_model_sha256");
    stored.remove("index_fingerprint");
    stored.put("index_fingerprint_inputs", json.writeValueAsString(inputs));
    seed(index, stored);
    assertFalse(ParityDiagnostics.requiresRebuild(IndexMetadataParityGuard.inspectCommittedParity(
        index, () -> expected, List::of)), "ordinary native uncertainty is still tolerated");
    assertFalse(IndexMetadataParityGuard.certifiesCommittedCompatibility(index, expected, List.of()));
  }

  @Test
  void currentUncertaintyCannotBorrowACompleteStoredIdentity(@TempDir Path index) throws Exception {
    var expected = expected(false);
    seed(index, expected);
    assertFalse(IndexMetadataParityGuard.certifiesCommittedCompatibility(
        index, expected, List.of("embedding_model_sha256")));
  }

  @Test
  void completeNotConfiguredIdentityBecomingPresentIsAGenuineChange(@TempDir Path index) throws Exception {
    var stored = expected(true);
    var present = expected(false);
    seed(index, stored);
    assertTrue(IndexMetadataParityGuard.certifiesCommittedCompatibility(index, stored, List.of()),
        "the same complete NOT_CONFIGURED identity is positive evidence");
    assertFalse(IndexMetadataParityGuard.certifiesCommittedCompatibility(index, present, List.of()),
        "newly present model digests genuinely change a complete committed identity");
    assertTrue(ParityDiagnostics.requiresRebuild(IndexMetadataParityGuard.inspectCommittedParity(
        index, () -> present, List::of)), "ordinary native handling still detects the genuine change");
  }

  private static Map<String, Object> expected(boolean absent) {
    var model = absent ? IndexFingerprint.ModelFingerprint.notConfigured()
        : IndexFingerprint.ModelFingerprint.present("a".repeat(64));
    return new SsotCommitMetadataSource(ResolvedConfig.builder().contributeEnvRegistry().build(),
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(768, model, model, model)).build();
  }

  private static void seed(Path index, Map<String, Object> metadata) throws Exception {
    try (var directory = FSDirectory.open(index);
        var writer = new IndexWriter(directory, new IndexWriterConfig())) {
      var document = new org.apache.lucene.document.Document();
      document.add(new org.apache.lucene.document.StringField("doc_id", "certificate", org.apache.lucene.document.Field.Store.YES));
      writer.addDocument(document);
      writer.setLiveCommitData(metadata.entrySet().stream()
          .map(entry -> Map.entry(entry.getKey(), entry.getValue().toString())).toList());
      writer.commit();
    }
  }
}
