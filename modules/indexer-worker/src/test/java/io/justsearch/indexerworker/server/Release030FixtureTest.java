/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

final class Release030FixtureTest {
  @Test
  void authenticFoldersRetainReleaseStateAndRelocateTogether(@TempDir Path temporary) throws Exception {
    for (String name : java.util.List.of("small", "migrating", "switching")) {
      Path root = assertDoesNotThrow(() -> Release030Fixture.unpack(name,
          temporary.resolve("release-fixture").resolve(name)));
      var manifest = new ObjectMapper().readTree(root.resolve("fixture.json").toFile());
      assertEquals("76871d924", manifest.get("commit").asText());
      try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
          var query = connection.createStatement()) {
        try (var rows = query.executeQuery("PRAGMA user_version")) {
          assertTrue(rows.next());
          assertEquals(13, rows.getInt(1), "the helper must not migrate the released database");
        }
        try (var rows = query.executeQuery("SELECT path FROM jobs")) {
          while (rows.next()) assertTrue(Files.isRegularFile(Path.of(rows.getString(1))));
        }
        var counts = new java.util.TreeMap<String, Long>();
        try (var rows = query.executeQuery("SELECT state,count(*) FROM jobs GROUP BY state")) {
          while (rows.next()) counts.put(rows.getString(1), rows.getLong(2));
        }
        var capturedCounts = new java.util.TreeMap<String, Long>();
        for (var count : manifest.get("jobs")) capturedCounts.put(count.get(0).asText(), count.get(1).asLong());
        assertEquals(capturedCounts, counts, "path relocation must preserve every job state");
        try (var rows = query.executeQuery("SELECT p.path_hash,p.normalized_path,d.doc_uid "
            + "FROM path_resolution p JOIN document_identity d ON d.path_hash=p.path_hash")) {
          while (rows.next()) {
            assertEquals(Release030Fixture.sha256(rows.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                rows.getString(1), "the identity and reverse lookup must use the relocated path hash");
            assertNotNull(rows.getString(3));
          }
        }
        if (name.equals("small")) {
          try (var rows = query.executeQuery("SELECT state,attempts,retry_after-last_updated "
              + "FROM jobs WHERE last_outcome_class='WRITE_FAILED' AND state='PENDING'")) {
            assertTrue(rows.next());
            assertEquals("PENDING", rows.getString(1));
            assertEquals(4, rows.getInt(2));
            assertTrue(rows.getLong(3) >= 21_600_000L && rows.getLong(3) <= 21_601_000L);
          }
          try (var rows = query.executeQuery("SELECT count(*) FROM jobs WHERE state='PROCESSING'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
          }
        }
        if (name.equals("switching")) {
          try (var rows = query.executeQuery("SELECT op,payload FROM switch_buffer")) {
            var operations = new java.util.HashSet<String>();
            while (rows.next()) {
              operations.add(rows.getString(1));
              assertFalse(rows.getString(2).contains("/fixture/"));
            }
            assertTrue(operations.containsAll(java.util.Set.of("UPSERT", "DELETE")));
          }
        }
      }
      var state = new ObjectMapper().readTree(root.resolve("data/index/default/state.json").toFile());
      assertEquals(manifest.get("state").get("migration_state").asText(),
          state.get("migration_state").asText());
      assertEquals(java.util.Map.of("small", "IDLE", "migrating", "MIGRATING", "switching", "SWITCHING").get(name),
          state.get("migration_state").asText(), "archive names must describe the actual captured state");
      Path generation = root.resolve("data/index/default/indices").resolve(state.get("active_generation").asText());
      assertTrue(Files.isRegularFile(generation.resolve(".justsearch-generation.sentinel")));
      assertTrue(Files.isRegularFile(generation.resolve(".justsearch-index-generation.json")));
      try (var directory = FSDirectory.open(generation); var reader = DirectoryReader.open(directory)) {
        assertEquals("020da33add4ee9bb5fc6a944b6efc5bb611a1d226dd09d3fea8c66377a245e2a",
            reader.getIndexCommit().getUserData().get("index_fingerprint"),
            "the captured release identity must survive relocation, rather than becoming today's identity");
        assertNotNull(reader.getIndexCommit().getUserData().get("index_fingerprint_inputs"));
        assertTrue(reader.numDocs() >= 30);
        for (var leaf : reader.leaves()) {
          for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
            var document = leaf.reader().storedFields().document(doc);
            String path = document.get("path");
            if (path != null) {
              Path file = Path.of(path);
              assertTrue(Files.isRegularFile(file), path);
              String sourceHash = document.get("source_sha256");
              if (sourceHash != null && sourceHash.equals(Release030Fixture.sha256(Files.readAllBytes(file)))) {
                assertEquals(document.getField("modified_at").numericValue().longValue(),
                    Files.getLastModifiedTime(file).toMillis(), "unpacking must preserve unchanged-file freshness");
              }
            }
          }
        }
      }
      var settings = new ObjectMapper().readTree(root.resolve("home/ui/settings.json").toFile());
      assertEquals(2, settings.get("schemaVersion").asInt());
    }
  }
}
