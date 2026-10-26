/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.runtime.IndexMetadataParityGuard;
import io.justsearch.adapters.lucene.runtime.CleanShutdownMarker;
import io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.Release030RecoveryState;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.queue.SwitchBufferDelete;
import io.justsearch.indexerworker.queue.SwitchBufferUpsert;
import io.justsearch.adapters.lucene.runtime.PathPrefixScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.store.FSDirectory;

/** The release build is redundant only after every differing source and retained receipt settles. */
final class Release030MigrationRetirement {
  private Release030MigrationRetirement() {}

  static IndexGenerationManager.State prepare(IndexGenerationManager manager,
      IndexGenerationManager.State state, Release030RecoveryState recovery,
      Map<String, Object> expected, List<String> unresolved, MigrationTransitionBarrier.Hook hook)
      throws IOException, InterruptedException {
    if (recovery.retiringBuild()) {
      Path active = manager.resolveGenerationPathStrict(recovery.retiringActive());
      manager.validateReleaseRetirementOwner(recovery.retiringActive());
      if (!IndexMetadataParityGuard.certifiesCommittedCompatibility(active, expected, unresolved))
        throw new IOException("Checkpointed release source no longer has a compatible committed index");
      return manager.cancelCompatibleReleaseBuild(recovery.retiringActive(), recovery.retiringBuilding());
    }
    if (!("MIGRATING".equals(state.migration_state()) || "SWITCHING".equals(state.migration_state()))
        || state.building_generation() == null) return state;
    Path active = manager.resolveGenerationPathStrict(state.active_generation());
    Path building = manager.resolveGenerationPathStrict(state.building_generation());
    manager.validateReleaseRetirementOwner(state.active_generation());
    manager.validateReleaseRetirementOwner(state.building_generation());
    if (!IndexMetadataParityGuard.certifiesCommittedCompatibility(active, expected, unresolved)) return state;
    Map<String, FileVersion> blue = files(active);
    Map<String, FileVersion> green = files(building);
    Map<Path, String> differences = new LinkedHashMap<>();
    for (var entry : blue.entrySet()) {
      FileVersion other = green.remove(entry.getKey());
      if (!entry.getValue().equals(other) || !entry.getValue().hasIdentity()) differences.put(Path.of(entry.getKey()),
          other == null ? entry.getValue().collection() : other.collection());
    }
    for (var entry : green.entrySet()) differences.put(Path.of(entry.getKey()), entry.getValue().collection());
    recovery.beginRetirement(state.active_generation(), state.building_generation(), differences);
    hook.await(new MigrationTransitionBarrier.Transition("release030-checkpointed",
        state.active_generation(), state.building_generation()));
    var cancelled = manager.cancelCompatibleReleaseBuild(state.active_generation(), state.building_generation());
    hook.await(new MigrationTransitionBarrier.Transition("release030-cancelled",
        state.active_generation(), state.building_generation()));
    return cancelled;
  }

  static Path boundary(Path file, List<Path> roots) {
    return roots.stream().filter(file::startsWith)
        .max(java.util.Comparator.comparingInt(Path::getNameCount)).orElse(file);
  }

  /** New admissions share B's retained ordered journal until the last recovery obligation ends. */
  static void retainAuthority(SqliteJobQueue queue, Release030RecoveryState recovery, List<Path> roots)
      throws IOException {
    var exact = new java.util.HashSet<Path>();
    for (Path file : recovery.retirementPaths().keySet()) {
      if (boundary(file, roots).equals(file)) exact.add(file);
    }
    for (var op : queue.retirementReplayOps()) {
      if (op.generation() != null && !op.generation().isBlank()
          && !recovery.retiringBuilding().equals(op.generation())) continue;
      Path file = null;
      if ("UPSERT".equals(op.op())) file = Path.of(SwitchBufferUpsert.decode(op.payload()).path());
      if ("DELETE".equals(op.op())) file = Path.of(SwitchBufferDelete.decode(op.payload()).path());
      if (file != null && boundary(file, roots).equals(file)) exact.add(file);
    }
    queue.retainRelease030Retirement(recovery.retiringBuilding(), exact, recovery.retirementPaths());
  }

  /** A/B differences precede every retained admission; that admission owns its overlapping scope. */
  static boolean covered(Path file, String collection, List<SwitchBufferCapableQueue.SwitchBufferOp> ops) {
    for (var op : ops) {
      switch (op.op()) {
        case "UPSERT" -> {
          if (Path.of(SwitchBufferUpsert.decode(op.payload()).path()).equals(file)) return true;
        }
        case "DELETE" -> {
          if (Path.of(SwitchBufferDelete.decode(op.payload()).path()).equals(file)) return true;
        }
        case "DELETE_PREFIX", "PRUNE_PREFIX" -> {
          if (PathPrefixScope.decode(op.payload()).contains(
              io.justsearch.indexerworker.util.PathNormalizer.normalizeKey(file))) return true;
        }
        case "DELETE_COLLECTION" -> {
          if (op.payload().equals(collection)) return true;
        }
        default -> { }
      }
    }
    return false;
  }

  static void finish(IndexGenerationManager manager, Release030RecoveryState recovery, Path base)
      throws IOException {
    Path building = manager.resolveGenerationPathStrict(recovery.retiringBuilding());
    Files.deleteIfExists(CleanShutdownMarker.pathFor(building));
    Files.deleteIfExists(IndexRecoveryMarker.pathFor(building));
    Files.deleteIfExists(base.resolve("migration_progress.json"));
    Files.deleteIfExists(base.resolve("migration_progress.json.tmp"));
    manager.normalizeRetiredReleaseBuild(recovery.retiringActive(), recovery.retiringBuilding());
    recovery.finishRetirement();
  }

  private record FileVersion(String hash, String content, String modified, String collection) {
    boolean hasIdentity() {
      return io.justsearch.adapters.lucene.commit.IndexFingerprint.isWellFormedDigest(hash)
          || io.justsearch.adapters.lucene.commit.IndexFingerprint.isWellFormedDigest(content) && modified != null;
    }
  }

  /** Read committed parents only; an uncommitted empty Green has no completed effects to recover. */
  private static Map<String, FileVersion> files(Path generation) throws IOException {
    Map<String, FileVersion> files = new HashMap<>();
    try (var directory = FSDirectory.open(generation)) {
      if (!DirectoryReader.indexExists(directory)) return files;
      try (var reader = DirectoryReader.open(directory)) {
        for (var leaf : reader.leaves()) {
          var segment = leaf.reader();
          var stored = segment.storedFields();
          for (int doc = 0; doc < segment.maxDoc(); doc++) {
            if (segment.getLiveDocs() != null && !segment.getLiveDocs().get(doc)) continue;
            var fields = stored.document(doc, java.util.Set.of("path", "doc_id", "is_chunk",
                "source_sha256", "content_sha256", "modified_at", "collection"));
            String chunk = value(segment, fields, doc, "is_chunk");
            if ("true".equals(chunk) || "1".equals(chunk)) continue;
            String path = value(segment, fields, doc, "path");
            if (path == null || !Path.of(path).isAbsolute()
                || !path.equals(value(segment, fields, doc, "doc_id"))) {
              throw new IOException("Release index contains an unidentifiable file parent");
            }
            var version = new FileVersion(value(segment, fields, doc, "source_sha256"),
                value(segment, fields, doc, "content_sha256"), value(segment, fields, doc, "modified_at"),
                Objects.toString(value(segment, fields, doc, "collection"), ""));
            if (files.put(path, version) != null) throw new IOException("Release index has duplicate file parents");
          }
        }
      }
    }
    return files;
  }

  private static String value(LeafReader reader, org.apache.lucene.document.Document stored,
      int doc, String field) throws IOException {
    var saved = stored.getField(field);
    if (saved != null) return saved.numericValue() == null
        ? saved.stringValue() : saved.numericValue().toString();
    var info = reader.getFieldInfos().fieldInfo(field);
    if (info == null) return null;
    if (info.getDocValuesType() == org.apache.lucene.index.DocValuesType.SORTED) {
      var values = DocValues.getSorted(reader, field);
      return values.advanceExact(doc) ? values.lookupOrd(values.ordValue()).utf8ToString() : null;
    }
    if (info.getDocValuesType() == org.apache.lucene.index.DocValuesType.NUMERIC) {
      var values = DocValues.getNumeric(reader, field);
      return values.advanceExact(doc) ? Long.toString(values.longValue()) : null;
    }
    return null;
  }
}
