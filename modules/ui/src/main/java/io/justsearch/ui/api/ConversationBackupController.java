/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.encryption.DataKeyState;
import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.encryption.StoreDescriptor;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.encryption.DataKeyManager;
import io.justsearch.app.services.encryption.EncryptionEnvelope;
import io.justsearch.app.services.encryption.KeystoreRecord;
import io.justsearch.app.services.encryption.WrongPassphraseException;
import io.justsearch.configuration.persistence.StoreIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tempdoc 629 (#7 export / #E import) — encrypted, portable backup of ALL AUTHORED user data.
 *
 * <p>The §Reach payoff (629 #1): the recoverability class is the authority and backup is a <i>projection</i>
 * — both directions iterate {@code HeadAssembly.authoredStores()} (the ONE store list) with NO per-store
 * knowledge. The export reads each descriptor's {@code source}; the import writes each descriptor's
 * {@code sink}. Adding/removing an AUTHORED store changes only the catalog + its registration.
 *
 * <p>Container (downloaded via {@code Content-Disposition: attachment}):
 *
 * <pre>{@code
 * { "format": "justsearch-backup/v1",
 *   "keystore": <KeystoreRecord — KDF params + passphrase/recovery-wrapped DEK>,
 *   "data": "JSEv1:<base64 — { <store.dirName>: [...entries] } sealed with the backup's data key> }
 * }</pre>
 *
 * Reuses the exact LAYER envelope (no new crypto). The bundled keystore makes the container
 * offline-decryptable with the passphrase/recovery key. Both ops require the LOCAL store UNLOCKED.
 */
final class ConversationBackupController {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String FORMAT = "justsearch-backup/v1";

  private final HeadAssembly headAssembly;
  private final DataKeyManager keys;

  ConversationBackupController(HeadAssembly headAssembly, DataKeyManager keys) {
    this.headAssembly = headAssembly;
    this.keys = keys;
  }

  /** POST /api/conversations/encryption/export — stream an encrypted backup of authored data. */
  void handleExport(Context ctx) {
    if (keys.state() != DataKeyManager.State.UNLOCKED) {
      ctx.status(409).json(Map.of("error", "encryption must be set up and unlocked to export"));
      return;
    }
    // 629 (#1): read the ONE authoritative list — every AUTHORED store's read-side, no per-store code here.
    Map<String, Object> bundle = new LinkedHashMap<>();
    for (StoreDescriptor d : headAssembly.authoredStores()) {
      bundle.put(d.store().dirName(), d.source().read());
    }
    String bundleJson = MAPPER.writeValueAsString(bundle);
    String sealed = new StoreCipher(keys).seal(bundleJson);

    Map<String, Object> container = new LinkedHashMap<>();
    container.put("format", FORMAT);
    container.put("keystore", keys.keystoreRecord());
    container.put("data", sealed);

    ctx.header("Content-Disposition", "attachment; filename=\"justsearch-backup.json\"");
    ctx.json(container);
  }

  /**
   * POST /api/conversations/encryption/import — restore an encrypted backup. Body: {@code {container,
   * passphrase | recoveryKey}}. Decrypts the backup with ITS OWN keystore (derive KEK → unwrap the
   * backup's DEK → open the bundle), then writes each section back through the descriptor sinks, which
   * re-seal under the LOCAL data key. Collision policy: skip-existing.
   */
  void handleImport(Context ctx) {
    if (keys.state() != DataKeyManager.State.UNLOCKED) {
      ctx.status(409).json(Map.of("error", "local encryption must be set up and unlocked to import"));
      return;
    }
    JsonNode body = MAPPER.readTree(ctx.body());
    JsonNode container = body.path("container");
    if (!FORMAT.equals(container.path("format").asString(""))) {
      ctx.status(400).json(Map.of("error", "unrecognized backup format"));
      return;
    }

    KeystoreRecord backupKeystore = MAPPER.treeToValue(container.get("keystore"), KeystoreRecord.class);
    String passphrase = body.path("passphrase").asString("");
    String recoveryKey = body.path("recoveryKey").asString("");

    byte[] backupDek;
    try {
      if (!recoveryKey.isBlank()) {
        backupDek = EncryptionEnvelope.recover(backupKeystore, recoveryKey);
      } else {
        char[] pass = passphrase.toCharArray();
        try {
          backupDek = EncryptionEnvelope.unlock(backupKeystore, pass);
        } finally {
          Arrays.fill(pass, '\0');
        }
      }
    } catch (WrongPassphraseException e) {
      ctx.status(401).json(Map.of("error", "wrong passphrase or recovery key for this backup"));
      return;
    }

    Map<String, Object> bundle;
    try {
      String bundleJson = new StoreCipher(adhocState(backupDek)).open(container.path("data").asString(""));
      bundle =
          MAPPER.readValue(
              bundleJson, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
    } finally {
      Arrays.fill(backupDek, (byte) 0);
    }

    // 629 (#1/#E): restore via the ONE store list — each descriptor's sink, no per-store code here.
    Map<String, Object> summary = new LinkedHashMap<>();
    int skipped = 0;
    for (StoreDescriptor d : headAssembly.authoredStores()) {
      List<Map<String, Object>> entries = sectionEntries(bundle.get(d.store().dirName()));
      // Old backups can contain unaddressable session/run names. Do not replay those ids into
      // the sinks' write APIs, or one stale entry would prevent every later valid restore.
      int before = entries.size();
      entries.removeIf(entry -> entry.containsKey("sessionId")
          && (!(entry.get("sessionId") instanceof String id) || !StoreIds.isValid(id)));
      skipped += before - entries.size();
      summary.put(d.store().dirName(), d.sink().restore(entries));
    }
    ctx.json(skipped == 0 ? Map.of("restored", summary) : Map.of("restored", summary, "skipped", skipped));
  }

  /** A transient key-state wrapping the backup's own DEK, to open its sealed bundle. */
  private static DataKeyState adhocState(byte[] dek) {
    return new DataKeyState() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public boolean locked() {
        return false;
      }

      @Override
      public byte[] dek() {
        return dek.clone();
      }
    };
  }

  /** Coerce a bundle section (a JSON array of objects) into typed entries; null/non-array → empty. */
  private static List<Map<String, Object>> sectionEntries(Object section) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (section instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> raw) {
          Map<String, Object> typed = new LinkedHashMap<>();
          for (Map.Entry<?, ?> e : raw.entrySet()) {
            typed.put(String.valueOf(e.getKey()), e.getValue());
          }
          out.add(typed);
        }
      }
    }
    return out;
  }
}
