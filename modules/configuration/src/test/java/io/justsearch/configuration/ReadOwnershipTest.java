/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReadOwnershipTest {
  @TempDir Path directory;

  @Test void offlineSourcesArePublicUnlessUntaggedAndHistoryShaped() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    var previous = ConfigStore.globalOrNull();
    var config = new ConfigStore(ResolvedConfig.builder().putDefault("justsearch.data.dir", data.toString()).build());
    ConfigStore.setGlobal(config);
    try {
      var reads = new ReadOwnership();
      var cleanup = new ProtectedContentPolicy(data, data.resolve("help"));
      for (String collection : Arrays.asList(null, "", "personal")) {
        for (String name : List.of("offline/notes.txt", "offline/agent-history-notes/run.md", "offline/agent-history")) {
          Path source = directory.resolve(name);
          Map<String, String> facts = new HashMap<>();
          facts.put("path", source.toString());
          if (collection != null) facts.put("collection", collection);
          assertEquals(ReadOwnership.Owner.PUBLIC, reads.owner(source.toString(), facts, ignored -> Map.of()));
          assertEquals(ProtectedContentPolicy.Verdict.ORDINARY, cleanup.classify(source, source, collection).verdict());
        }
      }
      Path suspect = directory.resolve("alias/agent-history/run.md");
      assertEquals(ReadOwnership.Owner.UNCERTAIN, reads.owner(suspect.toString(), Map.of(), null));
      assertEquals(ProtectedContentPolicy.Verdict.UNCERTAIN, cleanup.classify(suspect, suspect, null).verdict());
      assertEquals(ReadOwnership.Owner.PUBLIC, reads.owner(suspect.toString(), Map.of("collection", "personal"), null));
      assertEquals(ProtectedContentPolicy.Verdict.ORDINARY, cleanup.classify(suspect, suspect, "personal").verdict());
      assertEquals(ReadOwnership.Owner.PUBLIC, reads.owner("ordinary-chunk",
          Map.of("parent_doc_id", directory.resolve("offline/notes.txt").toString()), ignored -> Map.of()));
      assertEquals(ReadOwnership.Owner.UNCERTAIN, reads.owner("legacy-chunk",
          Map.of("parent_doc_id", suspect.toString()), ignored -> Map.of()));
      Map<String, String> publicParent = Map.of("collection", "personal", "path", suspect.toString());
      Map<String, String> publicChild = Map.of("parent_doc_id", "public-parent", "path", suspect.toString());
      assertEquals(ReadOwnership.Owner.PUBLIC, reads.owner("public-child", publicChild, ignored -> publicParent));
      var transported = new HashMap<>(publicChild);
      transported.put(ReadOwnership.METADATA, "PUBLIC");
      assertEquals(ReadOwnership.Owner.PUBLIC, reads.owner("public-child", transported, null));
      assertEquals(ReadOwnership.Owner.HISTORY, reads.owner("tagged-child",
          Map.of("parent_doc_id", "tagged-parent"), ignored -> Map.of("collection", "agent-history")));
      assertEquals(ReadOwnership.Owner.HISTORY, reads.owner("tagged-history", Map.of("collection", "agent-history"), null));
      assertEquals(ProtectedContentPolicy.Verdict.PROTECTED,
          cleanup.classify(directory.resolve("offline/private.key"), directory.resolve("offline"), "personal").verdict());
    } finally { ConfigStore.restoreGlobal(config, previous); }
  }

  @Test void legacyHistoryShapedAliasIntoAnyAppDataIsHistory() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    Files.writeString(data.resolve("run.md"), "private");
    Path alias = Files.createSymbolicLink(directory.resolve("agent-history"), data);
    var previous = ConfigStore.globalOrNull();
    var config = new ConfigStore(ResolvedConfig.builder().putDefault("justsearch.data.dir", data.toString()).build());
    ConfigStore.setGlobal(config);
    try {
      String source = alias.resolve("run.md").toString();
      assertEquals(ReadOwnership.Owner.HISTORY, new ReadOwnership().owner(source, Map.of(), null));
      Files.delete(alias);
      assertEquals(ReadOwnership.Owner.UNCERTAIN, new ReadOwnership().owner(source, Map.of(), null));
    } finally { ConfigStore.restoreGlobal(config, previous); }
  }

  @Test void aResolvedOrdinaryHistoryNamedFolderIsPublicButAnAliasIntoAppDataIsPrivate() throws Exception {
    Path data = Files.createDirectory(directory.resolve("data"));
    Path transcript = Files.writeString(Files.createDirectory(data.resolve("agent-history")).resolve("run.md"), "private");
    Path alias = Files.createSymbolicLink(directory.resolve("alias"), data);
    Path ordinary = Files.writeString(Files.createDirectory(directory.resolve("agent-history")).resolve("run.md"), "ordinary");
    var previous = ConfigStore.globalOrNull();
    var config = new ConfigStore(ResolvedConfig.builder().putDefault("justsearch.data.dir", data.toString()).build());
    ConfigStore.setGlobal(config);
    try {
      var cleanup = new ProtectedContentPolicy(data, data.resolve("help"));
      for (String collection : Arrays.asList(null, "personal")) {
        Map<String, String> facts = collection == null ? Map.of() : Map.of("collection", collection);
        assertEquals(ReadOwnership.Owner.PUBLIC, new ReadOwnership().owner(ordinary.toString(), facts, null));
        assertEquals(ProtectedContentPolicy.Verdict.ORDINARY, cleanup.classify(ordinary, ordinary, collection).verdict());
        Path source = alias.resolve("agent-history/run.md");
        assertEquals(ReadOwnership.Owner.HISTORY, new ReadOwnership().owner(source.toString(), facts, null));
        var classified = cleanup.classify(source, source, collection);
        assertEquals(ProtectedContentPolicy.Verdict.PROTECTED, classified.verdict());
        assertEquals(transcript.toRealPath(), classified.source());
      }
    } finally { ConfigStore.restoreGlobal(config, previous); }
  }
}
