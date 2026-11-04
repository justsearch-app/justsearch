/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class SourceEnvelopeUnknownOriginTest {
  @Test
  void versionedEnvelopeWithoutOriginIsUnknownOriginRatherThanLegacy() {
    var fields = Map.<String, Object>of(SchemaFields.ENVELOPE_VERSION, "1",
        SchemaFields.STORAGE_STATUS, "known", SchemaFields.STORAGE_MOUNT, "/media/disk",
        SchemaFields.STORAGE_KEY, "aaaa-1111:ext4:/subtree");
    var envelope = SourceEnvelope.decode(fields);
    assertTrue(envelope.isPresent(), "versioned storage evidence is distinct from legacy absence");
    assertNull(envelope.orElseThrow().originId());
    assertEquals(SourceEnvelope.StorageStatus.KNOWN, envelope.orElseThrow().storageStatus());
    var corrupt = new HashMap<>(fields);
    corrupt.put(SchemaFields.ORIGIN_ID, "not-a-uuid");
    assertTrue(SourceEnvelope.decode(corrupt).isEmpty());
    corrupt.remove(SchemaFields.ORIGIN_ID);
    corrupt.put(SchemaFields.STORAGE_KEY, "malformed");
    assertEquals(SourceEnvelope.StorageStatus.UNKNOWN, SourceEnvelope.decode(corrupt).orElseThrow().storageStatus());
    assertTrue(SourceEnvelope.decode(Map.of()).isEmpty());
  }
}
