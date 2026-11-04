/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Path;

/**
 * What an explicit ingest records besides its queue items (task t-20261013-ef3c41, round 8b,
 * decision 1): the volume of the root or an explicit file's folder, in the Worker's volume record
 * under {@code indexBase}. For tests that put such queue items in place before the Worker boots,
 * as a request made before a restart left them.
 */
public final class ExplicitIngestRecords {
  private ExplicitIngestRecords() {}

  /** Records the volume {@code root} is on now, as an explicit ingest of that root does. */
  public static void recordRoot(Path indexBase, Path root) throws IOException {
    Path key = root.toAbsolutePath().normalize();
    String volume = RootIdentity.capture(key).volume();
    if (volume != null) {
      RootVolumeLedger.under(indexBase).record(key, volume);
    }
  }

  /** Records the volume {@code folder} is on now, as an explicit ingest of a file in it does. */
  public static void recordFileFolder(Path indexBase, Path folder) throws IOException {
    Path key = folder.toAbsolutePath().normalize();
    String volume = RootIdentity.capture(key).volume();
    if (volume != null) {
      RootVolumeLedger.under(indexBase).recordFileFolder(key, volume);
    }
  }
}
