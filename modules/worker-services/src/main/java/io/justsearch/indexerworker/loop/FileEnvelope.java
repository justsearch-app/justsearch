/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/** Trusted source witness carried unchanged from admission through publication. */
record FileEnvelope(FileFreshnessSnapshot snapshot) {
  static FileEnvelope fromSnapshot(FileFreshnessSnapshot snapshot) {
    return new FileEnvelope(snapshot);
  }

  String normalizedPath() { return snapshot.normalizedPath(); }
  String pathHash() { return snapshot.pathHash(); }
  long sizeBytes() { return snapshot.sizeBytes(); }
  long modifiedAtMs() { return snapshot.modifiedAtMs(); }
  boolean regularFile() { return snapshot.regularFile(); }
  long observedAtMs() { return snapshot.observedAtMs(); }
}
