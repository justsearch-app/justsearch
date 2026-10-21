/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

/**
 * A non-model file required by a model package (tokenizer, config, pooling config, vocab, etc.).
 *
 * <p>Supporting files are always downloaded regardless of hardware profile.
 *
 * @param filename the file name (e.g., "tokenizer.json"). For archives ({@code extract == true}),
 *     this is the archive filename — its extracted contents land in the same {@code targetDir}.
 * @param sha256 uppercase hex SHA-256 hash
 * @param sizeBytes file size in bytes
 * @param downloadUrl HTTPS URL to download the file
 * @param extract when true, the file is treated as a zip archive and its entries are extracted
 *     into the package's {@code targetDir} after download + SHA verification. The archive itself
 *     is kept for extraction-only repairs. Installation requires the archive plus a matching
 *     extraction receipt and complete output sizes (legacy installs without receipts use ZIP
 *     entry sizes). Install runs hash a kept archive before repairing its outputs; status does not.
 * @param required whether the capability this package delivers needs this file to work (tempdoc
 *     824 §3.3a). Defaults to TRUE and every absent-in-JSON entry is required — the axis exists to
 *     stop an 872-byte metadata file producing the same terminal red as a missing 500 MB model
 *     (round 16: {@code splade/config.json} absent while SPLADE ran 1 660 CUDA inferences), and a
 *     misclassification in the other direction hides a real gap, so unclassifiable is required.
 */
public record SupportingFile(
    String filename,
    String sha256,
    long sizeBytes,
    String downloadUrl,
    boolean extract,
    boolean required) {

  /** Backwards-compat constructor — non-extracted, required file (existing behavior). */
  public SupportingFile(String filename, String sha256, long sizeBytes, String downloadUrl) {
    this(filename, sha256, sizeBytes, downloadUrl, false, true);
  }

  /** Backwards-compat constructor — required file with an explicit extract flag. */
  public SupportingFile(
      String filename, String sha256, long sizeBytes, String downloadUrl, boolean extract) {
    this(filename, sha256, sizeBytes, downloadUrl, extract, true);
  }
}
