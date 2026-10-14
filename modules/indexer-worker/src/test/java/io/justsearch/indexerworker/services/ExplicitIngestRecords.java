/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.io.IOException;
import java.nio.file.Path;

/**
 * What an explicit file ingest records besides its queue item (task t-20261013-ef3c41, round 8b,
 * decision 1): the volume of the file's folder, in the Worker's volume record under {@code
 * indexBase}. For tests that put such a queue item in place before the Worker boots, as a request
 * made before a restart left it.
 */
public final class ExplicitIngestRecords {
  private ExplicitIngestRecords() {}

  /** Records the volume {@code folder} is on now, as an explicit ingest of a file in it does. */
  public static void recordFileFolder(Path indexBase, Path folder) throws IOException {
    Path key = folder.toAbsolutePath().normalize();
    String volume = RootIdentity.capture(key).volume();
    if (volume != null) {
      RootVolumeLedger.under(indexBase).recordFileFolder(key, volume);
    }
  }
}
