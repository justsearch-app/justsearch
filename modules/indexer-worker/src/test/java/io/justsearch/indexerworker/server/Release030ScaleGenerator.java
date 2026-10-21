/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import tools.jackson.databind.ObjectMapper;

/** On-demand real-schema/real-codec scale expansion; invoked by scripts/fixtures/generate-upgrade-030.sh. */
public final class Release030ScaleGenerator {
  private static final int INDEXED_PARENTS = 200_001;
  private static final int QUEUED_JOBS = 700_000;

  private Release030ScaleGenerator() {}

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]).toAbsolutePath().normalize();
    if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
    long started = System.nanoTime();
    Path root = Release030Fixture.unpack("small", output);
    var json = new ObjectMapper();
    var state = json.readTree(root.resolve("data/index/default/state.json").toFile());
    Path generation = root.resolve("data/index/default/indices")
        .resolve(state.get("active_generation").asText());
    Map<String, Object> metadata;
    Map<String, Object> template;
    var catalog = Release030Fixture.releaseCatalog();
    try (var directory = FSDirectory.open(generation); var reader = DirectoryReader.open(directory)) {
      metadata = Release030Fixture.releaseMetadata(reader.getIndexCommit().getUserData());
      template = Release030Fixture.documents(reader, catalog).stream()
          .filter(document -> String.valueOf(document.get("path")).replace('\\', '/').endsWith("/alpha/file-01.txt"))
          .findFirst().orElseThrow();
    }
    byte[] source = Files.readAllBytes(root.resolve("roots/alpha/file-01.txt"));
    long modified = ((Number) template.get("modified_at")).longValue();
    try (var executors = new TestEngineExecutors();
        var registrations = new io.justsearch.adapters.lucene.runtime.LuceneExecutorRegistrations(executors);
        var runtime = IndexSchema.fromCatalog(catalog, () -> metadata,
            new JsonSchemaCommitMetadataValidator()).atPath(generation)
            .withExecutorRegistrations(registrations).open();
        var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var job = connection.prepareStatement("INSERT INTO jobs(path,state,attempts,last_updated,collection,size_bytes) "
            + "VALUES(?,'PENDING',0,?,'default',?)");
        var identity = connection.prepareStatement("INSERT INTO document_identity "
            + "(path_hash,doc_uid,first_seen_at,last_seen_at) VALUES(?,?,?,?)");
        var resolution = connection.prepareStatement("INSERT INTO path_resolution "
            + "(path_hash,normalized_path,last_seen_at) VALUES(?,?,?)")) {
      connection.setAutoCommit(false);
      long now = System.currentTimeMillis();
      List<IndexDocument> batch = new ArrayList<>();
      for (int index = 0; index < QUEUED_JOBS; index++) {
        Path path = root.resolve("roots/alpha/scale").resolve(Integer.toString(index / 1000))
            .resolve(index + ".txt");
        if (index % 1000 == 0) Files.createDirectories(path.getParent());
        Files.write(path, source);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified));
        String normalized = io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(path);
        String hash = Release030Fixture.sha256(normalized.getBytes(StandardCharsets.UTF_8));
        job.setString(1, normalized);
        job.setLong(2, now);
        job.setLong(3, source.length);
        job.addBatch();
        resolution.setString(1, hash);
        resolution.setString(2, normalized);
        resolution.setLong(3, now);
        resolution.addBatch();
        if (index < INDEXED_PARENTS) {
          String uid = UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString();
          Map<String, Object> fields = new HashMap<>(template);
          fields.put("path", normalized);
          fields.put("doc_id", normalized);
          fields.put("doc_uid", uid);
          fields.put("filename", path.getFileName().toString());
          fields.put("title", path.getFileName().toString());
          batch.add(new IndexDocument(fields));
          identity.setString(1, hash);
          identity.setString(2, uid);
          identity.setLong(3, now);
          identity.setLong(4, now);
          identity.addBatch();
        }
        if ((index + 1) % 1000 == 0) {
          if (!batch.isEmpty()) runtime.indexingCoordinator().indexBatch(batch);
          batch.clear();
          job.executeBatch();
          identity.executeBatch();
          resolution.executeBatch();
        }
      }
      runtime.commitOps().commitAndTrack(CommitReason.DRAIN);
      connection.commit();
    }
    // Scaling is synthetic preparation, while crash markers still describe the captured Engine.
    Files.deleteIfExists(io.justsearch.adapters.lucene.runtime.CleanShutdownMarker.pathFor(generation));
    long parents;
    try (var directory = FSDirectory.open(generation); var reader = DirectoryReader.open(directory)) {
      parents = reader.numDocs();
      String prefix = io.justsearch.indexerworker.util.PathNormalizer.normalizePathPrefix(
          root.resolve("roots/alpha/scale").toString());
      int scaleParents = new org.apache.lucene.search.IndexSearcher(reader).count(
          new org.apache.lucene.search.PrefixQuery(new org.apache.lucene.index.Term("path", prefix)));
      if (scaleParents != INDEXED_PARENTS) throw new AssertionError("Wrong indexed parent count: " + scaleParents);
      if (!metadata.get("index_fingerprint").equals(reader.getIndexCommit().getUserData().get("index_fingerprint"))) {
        throw new AssertionError("Scale expansion changed the released commit identity");
      }
    }
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.createStatement()) {
      try (var rows = query.executeQuery("PRAGMA integrity_check")) {
        if (!rows.next() || !"ok".equals(rows.getString(1))) throw new AssertionError("Invalid scale database");
      }
      try (var rows = query.executeQuery("SELECT count(*) FROM jobs WHERE state='PENDING'")) {
        if (!rows.next() || rows.getLong(1) < QUEUED_JOBS) throw new AssertionError("Missing queued rows");
      }
      try (var rows = query.executeQuery("PRAGMA user_version")) {
        if (!rows.next() || rows.getInt(1) != 13) throw new AssertionError("Scale schema is not v13");
      }
    }
    var result = Map.of("additionalIndexedParents", INDEXED_PARENTS, "additionalPendingJobs", QUEUED_JOBS,
        "luceneDocumentsIncludingChunks", parents, "seconds", (System.nanoTime() - started) / 1_000_000_000.0);
    Files.writeString(root.resolve("scale.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    System.out.println("Generated #739 scale fixture: " + result + " at " + output);
  }
}
