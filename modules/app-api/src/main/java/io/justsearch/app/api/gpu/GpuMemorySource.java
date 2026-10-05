/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

/** Which counters a GPU memory reading came from. */
public enum GpuMemorySource {
  /** Windows performance counters: adapter and per-process memory. */
  PDH,
  /** NVIDIA management library: adapter totals only, no per-process memory under WDDM. */
  NVML,
  /** No reading. */
  NONE
}
