/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

/** Trusted source witness carried unchanged from admission through publication. */
record FileEnvelope(FileFreshnessSnapshot snapshot, String originId) {
  static FileEnvelope fromSnapshot(FileFreshnessSnapshot snapshot) {
    return new FileEnvelope(snapshot, null);
  }

  FileEnvelope withOrigin(String id) { return new FileEnvelope(snapshot, id); }

  String normalizedPath() { return snapshot.normalizedPath(); }
  String pathHash() { return snapshot.pathHash(); }
  long sizeBytes() { return snapshot.sizeBytes(); }
  long modifiedAtMs() { return snapshot.modifiedAtMs(); }
  boolean regularFile() { return snapshot.regularFile(); }
  long observedAtMs() { return snapshot.observedAtMs(); }
}
