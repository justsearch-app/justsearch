/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared metadata-only Files-on-Demand detection, before hashing or extraction. */
public final class CloudPlaceholderDetector {
  private static final boolean HAS_DOS_ATTRIBUTES =
      java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("dos");
  private static final int RECALL_ON_DATA_ACCESS = 0x00400000;

  private CloudPlaceholderDetector() {}

  public static boolean isCloudPlaceholder(Path file) {

    if (!HAS_DOS_ATTRIBUTES) return false;
    try {
      return ((int) Files.getAttribute(file, "dos:attributes") & RECALL_ON_DATA_ACCESS) != 0;
    } catch (IOException | UnsupportedOperationException | IllegalArgumentException unavailable) {
      return false;
    }
  }
}
