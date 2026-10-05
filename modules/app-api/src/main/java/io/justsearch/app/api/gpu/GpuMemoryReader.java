/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

/**
 * Reads graphics memory for the adapter the Engine computes on: adapter used and total, and
 * dedicated and shared bytes per process. There is one implementation, shared by every caller; it is
 * one reader, not one cached value.
 *
 * <p>{@link #sampleNow()} is serialized internally, so concurrent callers never interleave native
 * reads. It never throws: any failure is an {@link GpuMemorySample#unknown UNKNOWN} reading.
 */
public interface GpuMemoryReader extends AutoCloseable {

  /** Takes one reading now. Never throws; UNKNOWN on any failure. */
  GpuMemorySample sampleNow();

  /** Releases native resources. Later samples are UNKNOWN ({@code CLOSED}). */
  @Override
  void close();
}
