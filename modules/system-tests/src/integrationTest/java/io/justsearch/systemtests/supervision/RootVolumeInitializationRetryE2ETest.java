/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.systemtests.supervision;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import tools.jackson.databind.ObjectMapper;

/** Installed HTTP regression for a failed boot write, recovered by the same Engine. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(7 * 60)
final class RootVolumeInitializationRetryE2ETest {
  static final String SCENARIO = "root-volume-initialization-retry";

  @Test
  void pendingLedgerLockRecoversOriginalOperationWithoutRestartOrResubmission() throws Exception {
    EngineSupervisedRecoveryE2ETest.runScenario(SCENARIO);
  }

  /** One exact OS lock, acquired before the supervised fixture starts and released on its proof. */
  static final class Hold implements AutoCloseable {
    private final Path work;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean released;

    Hold(Path work) throws IOException {
      this.work = work;
      Path target = work.resolve("index/root-volumes-pending.properties.tmp");
      Files.createDirectories(target.getParent());
      channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.READ,
          StandardOpenOption.WRITE);
      try {
        lock = channel.tryLock();
        if (lock == null) throw new IOException("The private pending-ledger lock was already held");
        Files.writeString(work.resolve("root-volume-lock-held"), target.toString());
      } catch (IOException | RuntimeException failure) {
        channel.close();
        throw failure;
      }
    }

    void releaseIfRequested() throws IOException {
      if (!released && Files.isRegularFile(work.resolve("root-volume-lock-release"))) {
        assertTrue(lock.isValid(), "the fault must hold its OS lock until the refusal proof");
        close();
        Files.writeString(work.resolve("root-volume-lock-released"), "released the original OS lock");
      }
    }

    @Override
    public void close() throws IOException {
      if (!released) {
        released = true;
        try { lock.release(); } finally { channel.close(); }
      }
    }
  }

  static void assertProof(String output, Path work) throws Exception {
    assertTrue(output.contains("ROOT_VOLUME_INITIALIZATION_REFUSED "), output);
    String prefix = "ROOT_VOLUME_INITIALIZATION_RETRY_PASS ";
    String payload = output.lines().filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length())).findFirst().orElseThrow();
    var proof = new ObjectMapper().readTree(payload);
    assertTrue(proof.path("initialFailure").asText().contains(
        "Upgrade seeding of root volumes could not start; roots stay unconfirmed"), output);
    String exactTarget = work.resolve("index/root-volumes-pending.properties.tmp")
        .toAbsolutePath().normalize().toString();
    assertEquals(exactTarget, proof.path("locked").asText(), output);
    assertEquals(exactTarget, Files.readString(work.resolve("root-volume-lock-held")), output);
    var expectedPaths = java.util.Set.of(
        PathNormalizer.normalizeKey(work.resolve("root-volume-corpus/seed-retry-0.txt")),
        PathNormalizer.normalizeKey(work.resolve("root-volume-corpus/seed-retry-1.txt")));
    assertFalse(proof.path("refused").isEmpty(), output);
    for (var row : proof.path("refused")) {
      assertTrue(expectedPaths.contains(row.path("path").asText()), output);
      assertEquals("PENDING", row.path("state").asText(), output);
      assertEquals("ROOT_UNAVAILABLE", row.path("last_reason_code").asText(), output);
    }
    for (String field : new String[] {"runId", "pid", "instanceId", "incarnation"}) {
      assertEquals(proof.path("submitted").path(field), proof.path("recovered").path(field), output);
      assertFalse(proof.path("submitted").path(field).isMissingNode(), output);
    }
    assertEquals("/api/operation-history/" + proof.path("operationKey").asText(),
        proof.path("outcomeEndpoint").asText(), output);
    assertEquals("complete", proof.path("outcome").path("state").asText(), output);
    assertEquals("SUCCESS", proof.path("outcome").path("result").path("code").asText(), output);
    assertEquals(0, proof.path("outcome").path("unitsFailed").asInt(-1), output);
    assertEquals(2, proof.path("matchedCount").asInt(), output);
    assertTrue(Files.isRegularFile(work.resolve("root-volume-lock-released")), output);
  }
}
