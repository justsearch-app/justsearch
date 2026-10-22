/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.indexerworker.loop.SourcePresence;
import io.justsearch.indexerworker.services.WorkerIngestService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.mockito.Mockito;

/**
 * The live Worker's root authority, as the Engine's refusal cleanup is given it: a folder vouches
 * while it is a present, listable directory.
 */
public final class VouchingRootAuthority {
  private VouchingRootAuthority() {}

  /** A publisher whose root check vouches for present, listable folders. */
  public static WorkerIngestService publisher() {
    WorkerIngestService publisher = Mockito.mock(WorkerIngestService.class);
    Mockito.when(publisher.rootCheck()).thenReturn(new SourcePresence.RootCheck() {
      @Override
      public boolean vouches(Path folder) {
        return Files.isDirectory(folder) && Files.isReadable(folder);
      }

      @Override
      public boolean admits(Path folder) {
        return vouches(folder);
      }
    });
    return publisher;
  }
}
