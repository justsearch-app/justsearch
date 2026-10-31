/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.store.FSDirectory;
import tools.jackson.databind.ObjectMapper;

/** Released data, never a hand-written replacement schema. See resources/upgrade-030/README.md. */
public final class Release030Fixture {
  // Match JustSearchConfigurationLoader: omitted catalog booleans default to false.
  private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
      .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();

  private Release030Fixture() {}

  /** Unpacks and relocates an isolated copy; the archived release bytes remain untouched. */
  public static Path unpack(String name, Path destination) throws Exception {
    Path root = destination.toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (var input = Release030Fixture.class.getResourceAsStream("/upgrade-030/" + name + ".zip")) {
      if (input == null) throw new IOException("Missing authentic 0.3.0 fixture: " + name);
      try (var zip = new ZipInputStream(input)) {
        for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
          Path target = root.resolve(entry.getName()).normalize();
          if (!target.startsWith(root)) throw new IOException("Fixture entry escapes destination");
          if (entry.isDirectory()) {
            Files.createDirectories(target);
          } else {
            Files.createDirectories(target.getParent());
            Files.copy(zip, target);
          }
        }
      }
    }
    verifyCapture(root);
    relocateDatabase(root);
    try (var paths = Files.walk(root)) {
      for (Path file : paths.filter(p -> p.toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().equals("fixture.json")).toList()) {
        Files.writeString(file, JSON.writeValueAsString(relocateJson(JSON.readTree(file.toFile()), root)));
      }
    }
    try (var paths = Files.list(root.resolve("data/index/default/indices"))) {
      for (Path generation : paths.filter(Files::isDirectory).toList()) {
        if (Files.exists(generation.resolve("segments.gen")) || hasCommit(generation)) {
          relocateIndex(generation, root);
        }
      }
    }
    return root;
  }

  /** Keep native-path preservation scenarios native: A has a genuine analyzer mismatch. */
  static Path unpackNativeMigration(String name, Path destination) throws Exception {
    Path root = unpack(name, destination);
    Path active = Release030CompatibilityTest.generation(root);
    var metadata = new HashMap<>(Release030CompatibilityTest.readMetadata(active));
    var inputs = (tools.jackson.databind.node.ObjectNode) JSON.readTree(metadata.get("index_fingerprint_inputs"));
    inputs.put("analyzer_fp", "b".repeat(64));
    metadata.put("index_fingerprint_inputs", JSON.writeValueAsString(inputs));
    metadata.remove("index_fingerprint");
    try (var directory = FSDirectory.open(active);
        var writer = new org.apache.lucene.index.IndexWriter(directory,
            new org.apache.lucene.index.IndexWriterConfig().setOpenMode(
                org.apache.lucene.index.IndexWriterConfig.OpenMode.APPEND))) {
      writer.setLiveCommitData(metadata.entrySet());
      writer.commit();
    }
    return root;
  }

  private static boolean hasCommit(Path generation) throws IOException {
    try (var files = Files.list(generation)) {
      return files.anyMatch(p -> p.getFileName().toString().startsWith("segments_"));
    }
  }

  private static void verifyCapture(Path root) throws Exception {
    var manifest = JSON.readTree(root.resolve("fixture.json").toFile());
    for (var entry : manifest.get("files").properties()) {
      Path file = root.resolve(entry.getKey()).normalize();
      if (!file.startsWith(root) || !sha256(Files.readAllBytes(file)).equals(entry.getValue().asText())) {
        throw new IOException("Captured file checksum differs: " + entry.getKey());
      }
    }
  }

  static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static String relocate(String value, Path root) {
    String portable = root.toString().replace('\\', '/');
    return java.util.regex.Pattern.compile("/(?:fixture|release)(?=/|\"|$)").matcher(value)
        .replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
            match.group().equals("/fixture") ? portable : portable + "/release"));
  }

  private static String relocatePath(String value, Path root) {
    return PathNormalizer.normalizePath(relocate(value, root));
  }

  private static tools.jackson.databind.JsonNode relocateJson(tools.jackson.databind.JsonNode node, Path root) {
    if (node.isString()) {
      String value = node.asText();
      return JSON.valueToTree(value.startsWith("/fixture") || value.startsWith("/release")
          ? relocatePath(value, root) : value);
    }
    if (node instanceof tools.jackson.databind.node.ObjectNode object) {
      for (var entry : object.properties()) object.set(entry.getKey(), relocateJson(entry.getValue(), root));
    } else if (node instanceof tools.jackson.databind.node.ArrayNode array) {
      for (int index = 0; index < array.size(); index++) array.set(index, relocateJson(array.get(index), root));
    }
    return node;
  }

  private static void relocateDatabase(Path root) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"))) {
      Map<String, String> hashes = new HashMap<>();
      try (var query = connection.createStatement();
          var rows = query.executeQuery("SELECT path_hash,normalized_path FROM path_resolution")) {
        while (rows.next()) {
          hashes.put(rows.getString(1), sha256(relocatePath(rows.getString(2), root)
              .getBytes(StandardCharsets.UTF_8)));
        }
      }
      List<String> tables = new ArrayList<>();
      try (var query = connection.createStatement(); var rows = query.executeQuery(
          "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
        while (rows.next()) tables.add(rows.getString(1));
      }
      connection.setAutoCommit(false);
      for (String table : tables) {
        try (var query = connection.createStatement();
            var rows = query.executeQuery("SELECT rowid,* FROM " + identifier(table))) {
          int columns = rows.getMetaData().getColumnCount();
          while (rows.next()) {
            for (int column = 2; column <= columns; column++) {
              Object value = rows.getObject(column);
              if (!(value instanceof String original)) continue;
              String replacement = hashes.getOrDefault(original, original);
              if (original.startsWith("/fixture") || original.startsWith("/release")) {
                replacement = relocatePath(original, root);
              } else if (original.startsWith("path:/fixture") || original.startsWith("path:/release")) {
                replacement = "path:" + relocatePath(original.substring(5), root);
              } else if (table.equals("switch_buffer") && rows.getMetaData().getColumnName(column).equals("payload")
                  && original.startsWith("{")) {
                replacement = JSON.writeValueAsString(relocateJson(JSON.readTree(original), root));
              }
              if (original.equals(replacement)) continue;
              try (var update = connection.prepareStatement("UPDATE " + identifier(table) + " SET "
                  + identifier(rows.getMetaData().getColumnName(column)) + "=? WHERE rowid=?")) {
                update.setString(1, replacement);
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
              }
            }
          }
        }
      }
      connection.commit();
      // upgrade-030/README.md: unpacking must not invent edits after captured admissions.
      // Indexed unchanged sources recover their more precise saved mtime in relocateIndex.
      try (var query = connection.createStatement();
          var rows = query.executeQuery("SELECT path,last_updated FROM jobs")) {
        while (rows.next()) {
          Path file = Path.of(rows.getString("path"));
          if (file.startsWith(root) && Files.isRegularFile(file)) {
            Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(rows.getLong("last_updated")));
          }
        }
      }
    }
  }

  private static String identifier(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  static FieldCatalogDef releaseCatalog() throws IOException {
    try (var input = Release030Fixture.class.getResourceAsStream("/upgrade-030/fields.v1.json")) {
      if (input == null) throw new IOException("Missing released field catalog");
      return JSON.readValue(input, FieldCatalogDef.class);
    }
  }

  static Map<String, Object> releaseMetadata(Map<String, String> stored) throws IOException {
    Map<String, Object> metadata = new HashMap<>(stored);
    // Lucene stringifies commit data; restore the two typed values the commit schema requires.
    if (stored.containsKey("template_ver")) {
      metadata.put("template_ver", JSON.readValue(stored.get("template_ver"), Long.class));
    }
    if (stored.containsKey("grammar_on")) {
      metadata.put("grammar_on", JSON.readValue(stored.get("grammar_on"), Boolean.class));
    }
    return metadata;
  }

  /** Re-encodes paths with the RELEASE catalog and commit data, never today's catalog/identity. */
  private static void relocateIndex(Path generation, Path root) throws Exception {
    Path relocated = generation.resolveSibling(generation.getFileName() + ".relocated");
    var catalog = releaseCatalog();
    try (var directory = FSDirectory.open(generation);
        var reader = DirectoryReader.open(directory);
        var executors = new TestEngineExecutors();
        var registrations = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors)) {
      Map<String, Object> metadata = releaseMetadata(reader.getIndexCommit().getUserData());
      try (var runtime = IndexSchema.fromCatalog(catalog, () -> metadata,
          new JsonSchemaCommitMetadataValidator()).atPath(relocated)
          .withExecutorRegistrations(registrations).open()) {
        Map<String, String> parentContent = new HashMap<>();
        List<Map<String, Object>> documents = documents(reader, catalog);
        for (var document : documents) {
          if (!"true".equals(document.get("is_chunk"))) {
            parentContent.put((String) document.get("doc_id"), (String) document.get("content"));
          }
        }
        for (var document : documents) {
          if ("true".equals(document.get("is_chunk"))) {
            String content = parentContent.get((String) document.get("parent_doc_id"));
            int start = ((Number) document.get("chunk_start_char")).intValue();
            int end = ((Number) document.get("chunk_end_char")).intValue();
            document.put("chunk_content", content.substring(start, end));
          }
          for (String field : List.of("path", "doc_id", "parent_doc_id")) {
            if (document.get(field) instanceof String value) document.put(field, relocatePath(value, root));
          }
          if (document.get("path") instanceof String path
              && document.get("modified_at") instanceof Number modified
              && document.get("source_sha256") instanceof String hash) {
            Path file = Path.of(path);
            if (Files.isRegularFile(file) && sha256(Files.readAllBytes(file)).equals(hash)) {
              Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modified.longValue()));
            }
          }
          runtime.indexingCoordinator().indexSingle(new IndexDocument(document));
        }
        runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
      }
    }
    // Generation ownership is outside Lucene's commit file set and must survive re-encoding.
    try (var files = Files.list(generation)) {
      for (Path file : files.filter(p -> p.getFileName().toString().startsWith(".")).toList()) {
        Files.copy(file, relocated.resolve(file.getFileName()));
      }
    }
    deleteTree(generation);
    Files.move(relocated, generation);
    // A test-side path rewrite must not turn a killed Engine into a clean shutdown.
    Files.deleteIfExists(io.justsearch.adapters.lucene.runtime.CleanShutdownMarker.pathFor(generation));
    Files.deleteIfExists(io.justsearch.adapters.lucene.runtime.CleanShutdownMarker.pathFor(relocated));
  }

  static List<Map<String, Object>> documents(DirectoryReader reader, FieldCatalogDef catalog)
      throws IOException {
    List<Map<String, Object>> documents = new ArrayList<>();
    for (var leaf : reader.leaves()) {
      for (var info : leaf.reader().getFieldInfos()) {
        if (info.hasVectorValues() || info.name.equals("splade")) {
          throw new IOException("Fixture relocation requires a text-only capture; found " + info.name);
        }
      }
      var stored = leaf.reader().storedFields();
      for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
        if (leaf.reader().getLiveDocs() != null && !leaf.reader().getLiveDocs().get(doc)) continue;
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var field : stored.document(doc)) {
          Object value = field.numericValue() == null ? field.stringValue() : field.numericValue();
          if (fields.containsKey(field.name())) {
            throw new IOException("Unexpected multi-valued capture field: " + field.name());
          }
          fields.put(field.name(), value);
        }
        for (var field : catalog.fields()) {
          var info = leaf.reader().getFieldInfos().fieldInfo(field.id());
          if (field.stored() || info == null) continue;
          if (info.getDocValuesType() == DocValuesType.NUMERIC) {
            var values = leaf.reader().getNumericDocValues(field.id());
            if (values.advanceExact(doc)) fields.put(field.id(), values.longValue());
          } else if (info.getDocValuesType() == DocValuesType.SORTED) {
            var values = leaf.reader().getSortedDocValues(field.id());
            if (values.advanceExact(doc)) fields.put(field.id(), values.lookupOrd(values.ordValue()).utf8ToString());
          }
        }
        documents.add(fields);
      }
    }
    return documents;
  }

  static void deleteTree(Path path) throws IOException {
    try (var files = Files.walk(path)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
    }
  }
}
