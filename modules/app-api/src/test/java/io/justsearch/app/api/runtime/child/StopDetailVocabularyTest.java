/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ledger.ExitLedgerReader;
import io.justsearch.app.api.runtime.ledger.LedgerFamily;
import io.justsearch.app.api.runtime.ledger.LedgerReadResult;
import io.justsearch.app.api.runtime.ledger.LedgerRecord;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stop-detail vocabulary grows without breaking readers (Spec F step V): the three owner-side
 * stop details round-trip on the wire, and a reader meets a token it does not know as {@code
 * UNKNOWN} with the raw token kept, while the strict writer parse still refuses it.
 *
 * <p>The reader API is reached reflectively so that this class compiles against a build that lacks
 * it, where the checks then fail as assertions rather than as a compile error.
 */
@DisplayName("stop-detail vocabulary")
final class StopDetailVocabularyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NEWER_TOKEN = "from_a_newer_build";

  @TempDir Path dataDir;

  /** session_end, takeover and interrupted round-trip on the wire. */
  @Test
  void ownerStopDetailsRoundTrip() {
    for (String token : List.of("session_end", "takeover", "interrupted")) {
      assertTrue(StopDetail.fromToken(token).isPresent(), token + " is a stop detail");
      StopDetail parsed = assertDoesNotThrow(
          () -> JSON.readValue("\"" + token + "\"", StopDetail.class), token);
      assertEquals(token, parsed.token());
      assertEquals("\"" + token + "\"", JSON.writeValueAsString(parsed));
    }
  }

  /** An unknown token reads as UNKNOWN, keeps its raw text, and the strict parse still refuses it. */
  @Test
  void unknownTokenReadsUnknownWithRaw() throws Exception {
    Method tolerant = assertDoesNotThrow(
        () -> StopDetail.class.getMethod("fromWireOrUnknown", String.class),
        "readers have a tolerant parse");
    assertEquals("UNKNOWN", ((StopDetail) tolerant.invoke(null, NEWER_TOKEN)).name());
    assertEquals("session_end", ((StopDetail) tolerant.invoke(null, "session_end")).token());
    assertThrows(Exception.class,
        () -> JSON.readValue("\"" + NEWER_TOKEN + "\"", StopDetail.class),
        "the strict wire parse still refuses an unknown token");

    Path dir = LedgerFamily.directory(dataDir);
    Files.createDirectories(dir);
    Files.writeString(LedgerFamily.ENGINE.current(dir),
        "{\"schemaVersion\":1,\"type\":\"engine-intent\",\"seq\":1,"
            + "\"at\":\"2026-10-06T08:00:01.000Z\",\"writer\":{\"role\":\"shell\",\"pid\":7,"
            + "\"startedAt\":\"2026-10-06T08:00:00Z\"},\"incarnation\":{\"session\":\"s\",\"n\":1},"
            + "\"intentId\":\"i-1\",\"reason\":\"QUIT\",\"detail\":\"" + NEWER_TOKEN + "\","
            + "\"mode\":\"COOPERATIVE\",\"channel\":\"HTTP_SHUTDOWN\","
            + "\"requestedAt\":\"2026-10-06T08:00:01.000Z\",\"deadlineAt\":null}\n");
    LedgerReadResult read = ExitLedgerReader.read(dir, LedgerFamily.ENGINE);
    assertEquals(0, read.skippedLines(), "an unknown detail does not make the record unreadable");
    assertEquals(1, read.records().size());
    LedgerRecord record = read.records().get(0);

    Method detail = assertDoesNotThrow(
        () -> LedgerRecord.class.getMethod("detail"), "the ledger record exposes its detail");
    Method detailRaw = assertDoesNotThrow(
        () -> LedgerRecord.class.getMethod("detailRaw"), "and the raw token");
    assertEquals(Optional.of("UNKNOWN"),
        ((Optional<?>) detail.invoke(record)).map(d -> ((StopDetail) d).name()));
    assertEquals(Optional.of(NEWER_TOKEN), detailRaw.invoke(record));
  }
}
