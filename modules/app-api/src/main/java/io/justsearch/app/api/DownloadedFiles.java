/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced settings - Downloaded files (design D4): one row per optional part with its measured
 * size on disk, whether "Remove files" is offered and, if not, why. Served at {@code GET
 * /api/ai/install/downloaded-files}. Sizes are measured on disk; a size that cannot be read stays
 * {@code null} (unknown), never estimated.
 *
 * <p>Mutable plain DTO, serialized to JSON (the FE reads it untyped, like {@link InstallPlanPreview}).
 */
public final class DownloadedFiles {

  /** One row per listed part, in registry order. */
  public final List<Part> parts = new ArrayList<>();

  /** The latest removal: running, finished, refused or interrupted; {@code null} when none. */
  public Removal removal;

  /** One part as the Downloaded files section shows it. */
  public static final class Part {
    /** Registry package id. */
    public String id = "";
    /** Plain name. */
    public String label = "";
    /**
     * {@code downloaded} when any of its files is on disk, else {@code not-downloaded};
     * {@code not-measured} for runtime libraries, which live outside the models folder.
     */
    public String state = "not-downloaded";
    /** Measured bytes on disk of the files found for this part; {@code null} when unreadable. */
    public Long sizeOnDiskBytes;
    /** Whether the user has chosen not to download this part. */
    public boolean declined;
    /** Whether "Remove files" is offered now. */
    public boolean removable;
    /** Why "Remove files" is not offered; {@code null} when it is. */
    public String reason;
    /** Identity of {@link #delete} the confirmation shows; the removal refuses if it changed. */
    public String previewDigest;
    /** What becomes unavailable, in plain words. */
    public String unavailableAfter = "";
    /** Files that would be deleted (each is verified again immediately before deletion). */
    public final List<FileEntry> delete = new ArrayList<>();
    /** Files that stay, with the reason. */
    public final List<FileEntry> kept = new ArrayList<>();
  }

  /** One file, relative to the models folder. */
  public static final class FileEntry {
    public String path = "";
    /** Measured size; {@code null} when unknown. */
    public Long sizeBytes;
    /** Why the file is kept; {@code null} for a deleted or to-be-deleted file. */
    public String reason;

    public FileEntry() {}

    public FileEntry(String path, Long sizeBytes, String reason) {
      this.path = path;
      this.sizeBytes = sizeBytes;
      this.reason = reason;
    }
  }

  /** The outcome of one removal, reported from what is actually on disk. */
  public static final class Removal {
    public String packageId = "";
    public String label = "";
    /** {@code running} | {@code removed} | {@code partly-removed} | {@code not-removed} | {@code refused} | {@code interrupted}. */
    public String state = "";
    /** Plain summary. */
    public String message = "";
    public final List<FileEntry> deleted = new ArrayList<>();
    /** Files that were not deleted, with the reason; for an interruption, the files left. */
    public final List<FileEntry> kept = new ArrayList<>();
    public long startedAtEpochMs;
    public long finishedAtEpochMs;
  }
}
