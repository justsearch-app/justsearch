/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.engine.ShutdownRequest.Reason;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage B item B2 — the shutdown request file's shape and its refusal to guess.
 *
 * <p>Three writers will produce this file — this class, the Tauri supervisor in Rust, and the
 * dev-runner in Node — and only the first can be type-checked against the parser. So the field set
 * is pinned here explicitly: a writer that spells a field differently produces a file the Engine
 * ignores, and an ignored shutdown request is a shutdown that silently does not happen. The pin is
 * what turns that into a test failure instead of a support ticket.
 */
@DisplayName("ShutdownRequest — the out-of-band shutdown channel (stage B item B2)")
final class ShutdownRequestTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Path runtimeDir(Path tempDir) throws Exception {
    return Files.createDirectories(tempDir.resolve("runtime"));
  }

  @Test
  @DisplayName("the serialised field set is exactly the contract, and the wire names are lower-case")
  void serialisedShapeIsPinned(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.UPGRADE, 1_725_000_000_000L, "n-42", "updater", "prep-7")
        .writeTo(runtime);

    JsonNode root = JSON.readTree(Files.readString(ShutdownRequest.pathIn(runtime)));
    Set<String> fields = new LinkedHashSet<>();
    root.propertyNames().forEach(fields::add);

    assertEquals(
        Set.of("reason", "deadlineEpochMs", "nonce", "issuedBy", "preparationId"),
        fields,
        "the Rust and Node writers agree with this parser by SHAPE and nothing else — a field"
            + " added, removed or renamed here without updating them produces a file the Engine"
            + " rejects, which reads to a user as a shutdown request that did nothing");
    assertEquals("upgrade", root.get("reason").stringValue(), "the wire form is lower-case");
    assertEquals(1_725_000_000_000L, root.get("deadlineEpochMs").asLong());
    assertEquals(
        "prep-7",
        root.get("preparationId").stringValue(),
        "item B6 carries the preparation id through the file so the receipt written on the far side"
            + " is still nonce- AND preparation-bound");
  }

  @Test
  @DisplayName("the optional fields are omitted rather than written as null")
  void optionalFieldsAreOmitted(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.QUIT, 1L, null, null, null).writeTo(runtime);

    JsonNode root = JSON.readTree(Files.readString(ShutdownRequest.pathIn(runtime)));
    Set<String> fields = new LinkedHashSet<>();
    root.propertyNames().forEach(fields::add);
    assertEquals(Set.of("reason", "deadlineEpochMs"), fields);
  }

  @Test
  @DisplayName("every reason round-trips")
  void everyReasonRoundTrips(@TempDir Path tempDir) throws Exception {
    for (Reason reason : Reason.values()) {
      Path runtime = runtimeDir(tempDir.resolve(reason.wire()));
      new ShutdownRequest(reason, 7L, null, "test", null).writeTo(runtime);
      Optional<ShutdownRequest> read = ShutdownRequest.read(runtime);
      assertTrue(read.isPresent(), reason + " must round-trip");
      assertEquals(reason, read.get().reason());
      assertEquals(7L, read.get().deadlineEpochMs());
    }
  }

  @Test
  @DisplayName("an absent file is empty, not an error")
  void absentFileIsEmpty(@TempDir Path tempDir) throws Exception {
    assertTrue(ShutdownRequest.read(runtimeDir(tempDir)).isEmpty());
    assertTrue(ShutdownRequest.read(null).isEmpty());
  }

  @Test
  @DisplayName("malformed, unknown-reason and wrong-typed files are refused, never guessed")
  void malformedFilesAreRefused(@TempDir Path tempDir) throws Exception {
    // Each of these must yield empty. Guessing a reason from a corrupt file means a file caught
    // mid-write could stop the product.
    String[] bad = {
      "",
      "not json at all",
      "[]",
      "{}",
      "{\"reason\":\"quit\"}",
      "{\"deadlineEpochMs\":1}",
      "{\"reason\":\"explode\",\"deadlineEpochMs\":1}",
      "{\"reason\":null,\"deadlineEpochMs\":1}",
      "{\"reason\":\"quit\",\"deadlineEpochMs\":\"soon\"}",
      "{\"reason\":\"quit\",\"deadlineEpochMs\":1.5}",
      "{\"reason\":\"quit\",\"deadlineEpochMs\":1,",
    };
    for (String body : bad) {
      Path runtime = Files.createDirectories(tempDir.resolve("bad-" + Math.abs(body.hashCode())));
      Files.writeString(ShutdownRequest.pathIn(runtime), body, StandardCharsets.UTF_8);
      assertTrue(
          ShutdownRequest.read(runtime).isEmpty(),
          "must refuse to act on: " + (body.isEmpty() ? "(empty file)" : body));
    }
  }

  @Test
  @DisplayName("a reason is matched case-insensitively but never partially")
  void reasonMatchingIsExact(@TempDir Path tempDir) throws Exception {
    Path ok = Files.createDirectories(tempDir.resolve("ok"));
    Files.writeString(
        ShutdownRequest.pathIn(ok), "{\"reason\":\" QUIT \",\"deadlineEpochMs\":1}");
    assertEquals(Reason.QUIT, ShutdownRequest.read(ok).orElseThrow().reason());

    Path notOk = Files.createDirectories(tempDir.resolve("not-ok"));
    Files.writeString(
        ShutdownRequest.pathIn(notOk), "{\"reason\":\"quitting\",\"deadlineEpochMs\":1}");
    assertTrue(
        ShutdownRequest.read(notOk).isEmpty(),
        "a prefix match would let 'quitting' stop the Engine; the match is on the whole token");
  }

  @Test
  @DisplayName("clear() removes a consumed request and tolerates an absent one")
  void clearRemovesTheRequest(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.RESTART, 1L, null, null, null).writeTo(runtime);
    assertTrue(Files.exists(ShutdownRequest.pathIn(runtime)));

    ShutdownRequest.clear(runtime);
    assertFalse(
        Files.exists(ShutdownRequest.pathIn(runtime)),
        "a request left behind is re-read on the next poll and shuts the Engine down again");
    ShutdownRequest.clear(runtime); // must not throw
  }

  @Test
  @DisplayName("the write is atomic: no staging file is left behind")
  void writeLeavesNoStagingFile(@TempDir Path tempDir) throws Exception {
    Path runtime = runtimeDir(tempDir);
    new ShutdownRequest(Reason.HANG, 5L, null, "supervisor", null).writeTo(runtime);

    try (var entries = Files.list(runtime)) {
      assertEquals(
          java.util.List.of(ShutdownRequest.FILENAME),
          entries.map(p -> p.getFileName().toString()).sorted().toList(),
          "the reader polls, so a half-written file would be read as malformed and the shutdown"
              + " would be ignored — the write stages and renames, and leaves nothing else");
    }
  }
}
