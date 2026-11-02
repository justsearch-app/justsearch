/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.IndexGcRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class IndexGcFailureResponseTest {
  @TempDir Path temp;

  @Test
  void enumerationFailureIsRefused() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    Path indices = temp.resolve("indices");
    Files.move(indices, temp.resolve("preserved-indices"));
    Files.writeString(indices, "not enumerable");
    var response = new MigrationControlOps(manager).runIndexGc(IndexGcRequest.getDefaultInstance());
    assertFalse(response.getAccepted(), "unavailable enumeration must not report accepted zero");
    assertFalse(response.getError().isBlank());
    assertTrue(Files.isDirectory(temp.resolve("preserved-indices")));
  }

  @Test
  void cachedStateDoesNotAuthorizeGcAfterStateReadFailure() throws Exception {
    var manager = new IndexGenerationManager(temp);
    manager.initializeOrLoad();
    assertNotNull(manager.readStateBestEffort());
    Files.move(temp.resolve("state.json"), temp.resolve("preserved-state.json"));
    Files.createDirectory(temp.resolve("state.json"));
    var response = new MigrationControlOps(manager).runIndexGc(IndexGcRequest.getDefaultInstance());
    assertFalse(response.getAccepted(), "a cached pointer cannot authorize destructive GC");
    assertFalse(response.getError().isBlank());
    assertTrue(Files.exists(temp.resolve("preserved-state.json")));
  }
}
