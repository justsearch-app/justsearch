/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

/** Reads adapter memory from NVML, the fallback when PDH fails. NVML has no LUID and no per-process memory. */
interface NvmlSource extends AutoCloseable {

  /** The NVIDIA device count and the memory of device 0. Throws on any failure. */
  Reading read() throws Exception;

  @Override
  void close();

  record Reading(int deviceCount, long totalBytes, long usedBytes) {}
}
