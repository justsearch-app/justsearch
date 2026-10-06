/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.runtime.RuntimeManifest.IncarnationInfo;
import io.justsearch.app.engine.ShutdownRequest.Ownership;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec F §4.3 (RV2 F14): a request names the incarnation it addresses, and only that one honours it. */
@DisplayName("ShutdownRequest ownership")
final class ShutdownRequestOwnershipTest {

  private static final IncarnationInfo SELF = new IncarnationInfo("run-q", 2);

  private static ShutdownRequest request(String incarnation, String requestNonce) {
    return new ShutdownRequest(Reason.QUIT, 1L, null, "dev-runner", null, incarnation, requestNonce);
  }

  @Test
  void aRequestForThisIncarnationWithANonceIsItsOwn() {
    assertEquals(Ownership.THIS_INCARNATION, request("run-q/2", "a-nonce").ownership(SELF));
  }

  @Test
  void aRequestForAnotherIncarnationIsStale() {
    assertEquals(Ownership.STALE, request("run-q/1", "a-nonce").ownership(SELF));
    assertEquals(Ownership.STALE, request("other-run/2", "a-nonce").ownership(SELF));
    assertEquals(Ownership.STALE, request("not an incarnation", "a-nonce").ownership(SELF));
  }

  @Test
  void aRequestForThisIncarnationWithoutANonceIsStale() {
    assertEquals(Ownership.STALE, request("run-q/2", null).ownership(SELF));
    assertEquals(Ownership.STALE, request("run-q/2", " ").ownership(SELF));
  }

  @Test
  void anEngineWithoutAnIncarnationCannotOwnAnAddressedRequest() {
    assertEquals(Ownership.STALE, request("run-q/2", "a-nonce").ownership(null));
  }

  @Test
  void aRequestNamingNoIncarnationIsLegacy() {
    assertEquals(Ownership.LEGACY, request(null, null).ownership(SELF));
    assertEquals(Ownership.LEGACY, request(null, null).ownership(null));
    assertEquals(Ownership.LEGACY,
        new ShutdownRequest(Reason.RESTART, 1L, null, "shell", null).ownership(SELF));
  }

  @Test
  void theIncarnationAndNonceAreRead(@TempDir Path tempDir) throws Exception {
    Path runtime = Files.createDirectories(tempDir.resolve("runtime"));
    Files.writeString(ShutdownRequest.pathIn(runtime), "{\"schemaVersion\":1,\"reason\":\"quit\","
        + "\"deadlineEpochMs\":7,\"issuedBy\":\"dev-runner\",\"incarnation\":\"run-q/2\","
        + "\"requestNonce\":\"4f1c\"}");
    var read = ShutdownRequest.read(runtime).orElseThrow();
    assertEquals("run-q/2", read.incarnation());
    assertEquals("4f1c", read.requestNonce());
    assertEquals(Ownership.THIS_INCARNATION, read.ownership(SELF));
  }
}
