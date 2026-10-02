/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.feedback;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.configuration.persistence.UnsupportedStoreVersionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/**
 * Tempdoc 580 §17 (Track C) — a generic append-only NDJSON record store (one JSON object per line,
 * synchronized append), backing the feedback/outcome-tier streams: per-query {@link FeatureSnapshot}s
 * (P1) and {@link ResultDisposition}s (P2). One persistence primitive, many record types — the AHA
 * answer to "two near-identical stores" (and what keeps the disposition store from being a clone of
 * the snapshot store).
 *
 * <p>Mirrors {@link io.justsearch.app.services.gpl.GplTrainingTripleStore}'s NDJSON pattern. Capture
 * is an <strong>observer</strong>, never a dependency of the query path: {@link #append} swallows
 * and logs all failures so feedback can never break search.
 *
 * <p>Tempdoc 778 — the {@code feedback} store is classified {@code AUTHORED} in
 * {@link io.justsearch.agent.api.encryption.StoreCatalog}, so each line is sealed line-by-line with
 * the shared {@link StoreCipher} when at-rest encryption is enabled (passthrough when disabled, so
 * default/eval behaviour is byte-identical). All writers and readers of a given file MUST share the
 * same cipher (data key) or the round-trip breaks — the {@code LabelProjection} join relies on it.
 *
 * @param <T> the record type persisted, one per line
 */
public final class NdjsonAppendStore<T> {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  private static final Logger log = LoggerFactory.getLogger(NdjsonAppendStore.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Path storeFile;
  private final Class<T> type;
  private final StoreCipher cipher;
  private final FeatureSnapshotLookup lookup;
  private final boolean initializeOnAppend;

  /**
   * @param storeFile the NDJSON file (its parent directory is created if absent)
   * @param type the record type, used to deserialize on {@link #readAll}
   */
  public NdjsonAppendStore(Path storeFile, Class<T> type) {
    this(storeFile, type, StoreCipher.disabled());
  }

  /**
   * Tempdoc 778 — {@code cipher} seals/opens each line for the {@code AUTHORED} feedback store.
   * {@link StoreCipher#disabled()} is passthrough (the DERIVED/legacy/test path).
   */
  public NdjsonAppendStore(Path storeFile, Class<T> type, StoreCipher cipher) {
    this(storeFile, type, cipher, true);
  }

  /** Production capture borrows the generation maintained by the process backfill owner. */
  public static NdjsonAppendStore<FeatureSnapshot> observedFeatureSnapshots(
      Path file, StoreCipher cipher) {
    return new NdjsonAppendStore<>(file, FeatureSnapshot.class, cipher, false);
  }

  private NdjsonAppendStore(
      Path storeFile, Class<T> type, StoreCipher cipher, boolean initializeOnAppend) {
    this.storeFile = storeFile;
    this.type = type;
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.lookup = type == FeatureSnapshot.class ? new FeatureSnapshotLookup(storeFile, cipher) : null;
    this.initializeOnAppend = initializeOnAppend;
    try {
      Files.createDirectories(storeFile.getParent());
    } catch (IOException e) {
      log.warn("Failed to create store dir {}: {}", storeFile.getParent(), e.getMessage());
      log.debug("Failed to create store dir (stack trace)", e);
    }
  }

  /** Appends one record as an NDJSON line. Best-effort — never throws. */
  public synchronized void append(T record) {
    try {
      if (initializeOnAppend) initializeLookup();
      String line =
          cipher.seal(
                  MAPPER.writeValueAsString(
                      new PersistedRecord<>(CURRENT_SCHEMA_VERSION, record)))
              + "\n";
      if (lookup != null && record instanceof FeatureSnapshot snapshot) {
        // Publish conservative identity evidence first: an archive failure can omit a label,
        // but must never leave a conflicting archival row resolving through a stale UID.
        lookup.append(snapshot);
      }
      Files.writeString(
          storeFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception e) {
      log.warn("Failed to persist {} record: {}", type.getSimpleName(), e.getMessage());
      log.debug("Record persist failure (stack trace)", e);
    }
  }

  /** Streaming legacy backfill, retried by the background owner until a generation is complete. */
  public synchronized void initializeLookup() throws IOException {
    if (lookup == null) return;
    lookup.initialize(writer -> {
      if (!Files.exists(storeFile)) return;
      try (var reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("Feedback backfill interrupted");
          if (!line.isBlank()) writer.append((FeatureSnapshot) parseRecord(line));
        }
      }
    });
  }

  boolean lookupInitialized() throws IOException {
    return lookup == null || lookup.initialized();
  }

  /** Resolves one captured identity without reading or locking the archival stream. */
  public java.util.Optional<String> resolveStableDocId(String interactionId, String sourceDocId)
      throws IOException {
    if (lookup == null) throw new IllegalStateException("Identity lookup requires feature snapshots");
    return lookup.resolve(interactionId, sourceDocId);
  }

  /** Reads archival records for offline label projection, backup and diagnostics. */
  public synchronized List<T> readAll() throws IOException {
    List<T> out = new ArrayList<>();
    if (!Files.exists(storeFile)) {
      return out;
    }
    if (cipher.enabled() && cipher.locked()) {
      return out; // sealed + locked: empty until unlock (mirrors RunEventStore)
    }
    try (var reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        out.add(parseRecord(line));
      }
    }
    return out;
  }

  private T parseRecord(String line) throws IOException {
    JsonNode root = MAPPER.readTree(cipher.open(line));
    JsonNode version = root.get("schemaVersion");
    if (version == null) {
      return MAPPER.treeToValue(root, type);
    }
    if (!version.isInt()) {
      throw new IOException("feedback record schemaVersion must be an integer");
    }
    int observed = version.intValue();
    if (observed > CURRENT_SCHEMA_VERSION) {
      throw new UnsupportedStoreVersionException(
          "feedback-records", observed, CURRENT_SCHEMA_VERSION);
    }
    JsonNode record = root.get("record");
    if (observed != CURRENT_SCHEMA_VERSION || record == null) {
      throw new IOException("unsupported feedback record envelope");
    }
    return MAPPER.treeToValue(record, type);
  }

  /** The backing NDJSON file path (package-private for tests / diagnostics). */
  Path storeFile() {
    return storeFile;
  }

  private record PersistedRecord<T>(int schemaVersion, T record) {}
}
