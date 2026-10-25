/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.memory.MemoriesUnavailableException;
import io.justsearch.agent.api.memory.MemoryRecord;
import io.justsearch.agent.api.memory.MemoryStore;
import io.justsearch.configuration.persistence.AtomicFileWrites;
import io.justsearch.configuration.persistence.CorruptDurableStoreException;
import io.justsearch.configuration.persistence.StoreFormatVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 561 P-E — the ONE durable authority for the agent's learned memory. A single JSON file of
 * {@link MemoryRecord}s keyed by id; {@link #whatItKnows()} and {@link #forget(String)} are read/edit
 * projections of it (single authority + user control). Modeled on the {@code FileConversationStore} /
 * {@code AgentRunStore} discipline: durable, id-keyed, no second store.
 */
public final class FileMemoryStore implements MemoryStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<Map<String, Object>>> LIST_REF = new TypeReference<>() {};
  public static final int CURRENT_SCHEMA_VERSION = 1;

  private final Path file;
  private final StoreCipher cipher;
  private Map<String, MemoryRecord> byId = new LinkedHashMap<>();
  // Validators are process-local and never serialized. Retain incarnations across key locks:
  // locking, unlocking and formatting changes do not create new memories.
  private final Map<String, Long> incarnations = new LinkedHashMap<>();
  private final String revisionEpoch = UUID.randomUUID().toString();
  private long nextIncarnation;
  private boolean loaded;
  private MemoriesUnavailableException unavailable;

  public FileMemoryStore(Path rootDir) {
    this(rootDir, StoreCipher.disabled());
  }

  /**
   * Tempdoc 629 (LAYER) — {@code memory.json} is an AUTHORED store, sealed whole-file by {@code cipher}
   * when at-rest encryption is enabled. Because this store eager-loads at construction, a launch in the
   * LOCKED state skips the read. Key listeners invalidate the cache on transitions; each unlocked
   * access also establishes readiness, including before the unlock listener has run.
   */
  public FileMemoryStore(Path rootDir, StoreCipher cipher) {
    this.file = rootDir.resolve("memory.json");
    this.cipher = java.util.Objects.requireNonNull(cipher, "cipher");
    load();
  }

  /** Tempdoc 629: re-read after unlock (the constructor load was skipped while locked). */
  public synchronized void onKeyUnlocked() {
    loaded = false;
    byId.clear();
    requireReady();
  }

  /** Tempdoc 629: drop plaintext memory from RAM when the key locks (reads return empty until unlock). */
  public synchronized void onKeyLocked() {
    loaded = false;
    byId.clear();
  }

  /**
   * Tempdoc 806 W1 — TRUE while this store is sealed and its key is locked. In that state the cache is
   * empty by construction ({@link #onKeyLocked()} / the skipped constructor load) and the file cannot be
   * decrypted, so {@link #whatItKnows()}'s empty list means "cannot read", NOT "nothing learned". Readers
   * MUST project the two differently — an unreadable state answered as an empty one is a false factual
   * claim about what the system knows (the R12-F3 defect).
   */
  @Override
  public synchronized boolean isLocked() {
    return cipher.enabled() && cipher.locked();
  }

  /**
   * Tempdoc 806 W1 — every mutation fails loud BEFORE touching the cache while locked. While locked the
   * cache is empty and the disk is unreadable, so the store cannot know what it holds: any outcome it
   * reported would be a guess. Concretely, without this gate a {@code forget} of an id the cache cannot
   * see returns silently (cache miss ⇒ no persist ⇒ no error) while the record survives on disk and
   * reappears on unlock — a privacy control that appears to work and does not.
   */
  private void requireUnlocked() {
    if (isLocked()) {
      loaded = false;
      byId.clear();
      throw new io.justsearch.agent.api.encryption.KeyLockedException();
    }
    requireReady();
  }

  /**
   * A published key does not imply that the unlock listener has loaded the store. Every access
   * establishes readiness itself, with one load attempt and no background retry. StoreCipher only
   * calls DataKeyManager's monitor-free enabled(), locked() and dek() methods; it never acquires
   * the manager monitor while this store monitor is held (listeners acquire them in the other order).
   */
  private void requireReady() {
    if (loaded) {
      return;
    }
    try {
      load();
    } catch (RuntimeException e) {
      // Keep one error identity per unavailable episode, including retries, for passive-log dedup.
      if (unavailable == null) {
        unavailable = new MemoriesUnavailableException(e);
      }
      throw unavailable;
    }
    if (!loaded) {
      // The key can lock between the access pre-check and load's own check. A skipped load is
      // never readiness, even if another unlock publishes its key before this access returns.
      throw new io.justsearch.agent.api.encryption.KeyLockedException();
    }
  }

  /**
   * Tempdoc 806 W1 — no observable write without a durable write. If {@link #persist()} fails, the cache
   * is restored to {@code snapshot} before the failure propagates, so a caller can never read back a
   * record that never reached disk (nor miss one that was never removed from it).
   */
  private void persistOrRollback(Map<String, MemoryRecord> snapshot) {
    try {
      persist();
    } catch (RuntimeException e) {
      byId.clear();
      byId.putAll(snapshot);
      throw e;
    }
  }

  @Override
  public synchronized void remember(MemoryRecord record) {
    if (record == null) {
      return;
    }
    requireUnlocked();
    Map<String, MemoryRecord> snapshot = new LinkedHashMap<>(byId);
    byId.put(record.id(), record);
    persistOrRollback(snapshot);
    incarnations.put(record.id(), ++nextIncarnation);
  }

  @Override
  public synchronized List<MemoryRecord> whatItKnows() {
    // Preserve the locked projection; an unlocked failed load must never look empty or partial.
    if (isLocked()) {
      loaded = false;
      byId.clear();
      return List.of();
    }
    requireReady();
    List<MemoryRecord> out = new ArrayList<>(byId.values());
    out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
    return List.copyOf(out);
  }

  @Override
  public synchronized List<VersionedMemory> versionedMemories() {
    return whatItKnows().stream()
        .map(record -> new VersionedMemory(record, revision(record)))
        .toList();
  }

  @Override
  public synchronized ForgetResult forgetIf(String id, String expectedRevision) {
    requireUnlocked();
    MemoryRecord current = byId.get(id);
    if (current == null) {
      return new ForgetResult(false, null);
    }
    if (!revision(current).equals(expectedRevision)) {
      return new ForgetResult(false, current);
    }
    forget(id);
    return new ForgetResult(true, null);
  }

  private String revision(MemoryRecord record) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      hashField(hash, revisionEpoch);
      hashField(hash, Long.toString(incarnations.get(record.id())));
      hashField(hash, record.id());
      hashField(hash, record.kind());
      hashField(hash, record.content());
      hashField(hash, record.sourceConversationId());
      hashField(hash, record.actor());
      hashField(hash, record.createdAt().toString());
      return "\"" + HexFormat.of().formatHex(hash.digest()) + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required for memory revisions", e);
    }
  }

  private static void hashField(MessageDigest hash, String value) {
    byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    hash.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : bytes.length).array());
    hash.update(bytes);
  }

  @Override
  public synchronized void forget(String id) {
    if (id == null) {
      return;
    }
    requireUnlocked();
    if (!byId.containsKey(id)) {
      return;
    }
    Map<String, MemoryRecord> snapshot = new LinkedHashMap<>(byId);
    byId.remove(id);
    persistOrRollback(snapshot);
    incarnations.remove(id);
  }

  @Override
  public synchronized void clear() {
    requireUnlocked();
    if (byId.isEmpty()) {
      return;
    }
    Map<String, MemoryRecord> snapshot = new LinkedHashMap<>(byId);
    byId.clear();
    persistOrRollback(snapshot);
    incarnations.clear();
  }

  private void load() {
    if (cipher.enabled() && cipher.locked()) {
      // Sealed + locked: leave the cache empty and reload on unlock. Do NOT read-and-swallow into a
      // false-empty (the §L4 "locked must not look deleted" invariant, applied to the eager cache).
      return;
    }
    try {
      Map<String, MemoryRecord> candidate = new LinkedHashMap<>();
      String plaintext = cipher.open(Files.readString(file, StandardCharsets.UTF_8));
      JsonNode root = MAPPER.readTree(plaintext);
      if (root == null || (!root.isArray() && !root.isObject())) {
        throw new CorruptDurableStoreException(
            "memories", "expected a legacy array or versioned object");
      }
      List<Map<String, Object>> rows;
      if (root.isArray()) {
        rows = MAPPER.readValue(root.toString(), LIST_REF);
      } else {
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || !versionNode.isIntegralNumber()) {
          throw new CorruptDurableStoreException("memories", "schemaVersion must be an integer");
        }
        StoreFormatVersions.requireReadable(
            "memories", versionNode.asInt(), CURRENT_SCHEMA_VERSION, 0, 0);
        MemoryEnvelope envelope = MAPPER.treeToValue(root, MemoryEnvelope.class);
        rows = envelope.memories();
      }
      if (rows == null) {
        throw new CorruptDurableStoreException("memories", "memories payload is missing");
      }
      for (Map<String, Object> r : rows) {
        MemoryRecord rec = fromMap(r);
        candidate.put(rec.id(), rec);
      }
      incarnations.keySet().retainAll(candidate.keySet());
      for (String id : candidate.keySet()) {
        if (!incarnations.containsKey(id)) {
          incarnations.put(id, ++nextIncarnation);
        }
      }
      byId = candidate;
      loaded = true;
      unavailable = null;
    } catch (NoSuchFileException e) {
      // Only a definite absence is a first run; exists() also returns false on access failures.
      byId = new LinkedHashMap<>();
      incarnations.clear();
      loaded = true;
      unavailable = null;
    } catch (CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException e) {
      throw e;
    } catch (Exception e) {
      throw new CorruptDurableStoreException("memories", "cannot parse " + file, e);
    }
  }

  private void persist() {
    if (cipher.enabled() && cipher.locked()) {
      // Refuse to write while locked — we can't seal without the key, and must never overwrite the
      // ciphertext with plaintext/empty. Propagates to the caller (agent ops are gated while locked).
      throw new io.justsearch.agent.api.encryption.KeyLockedException();
    }
    try {
      List<Map<String, Object>> rows = new ArrayList<>(byId.size());
      for (MemoryRecord r : byId.values()) {
        rows.add(toMap(r));
      }
      AtomicFileWrites.replaceUtf8(
          file,
          cipher.seal(
              MAPPER
                  .writerWithDefaultPrettyPrinter()
                  .writeValueAsString(new MemoryEnvelope(CURRENT_SCHEMA_VERSION, rows))));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to persist memories to " + file, e);
    }
  }

  private record MemoryEnvelope(int schemaVersion, List<Map<String, Object>> memories) {}

  private static Map<String, Object> toMap(MemoryRecord r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("kind", r.kind());
    m.put("content", r.content());
    m.put("sourceConversationId", r.sourceConversationId());
    m.put("actor", r.actor());
    m.put("createdAt", r.createdAt().toString());
    return m;
  }

  private static MemoryRecord fromMap(Map<String, Object> m) {
    return new MemoryRecord(
        str(m.get("id")),
        str(m.get("kind")),
        str(m.get("content")),
        str(m.get("sourceConversationId")),
        str(m.get("actor")),
        parseTs(m.get("createdAt")));
  }

  private static String str(Object o) {
    return o instanceof String s ? s : null;
  }

  private static Instant parseTs(Object raw) {
    if (raw instanceof String s && !s.isBlank()) {
      try {
        return Instant.parse(s);
      } catch (DateTimeParseException ignored) {
        // fall through
      }
    }
    return Instant.EPOCH;
  }
}
