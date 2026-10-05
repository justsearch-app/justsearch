/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import java.util.Map;

/** Collects the Windows GPU memory performance counters. The native implementation is PDH. */
interface CounterSource extends AutoCloseable {

  /** Collects once and returns every instance of the three counters. Throws on any failure. */
  Counters collect() throws Exception;

  @Override
  void close();

  /**
   * One collection, keyed by PDH instance name exactly as PDH returns it. Instances whose counter
   * status was not valid are left out.
   *
   * @param processDedicated {@code \GPU Process Memory(*)\Dedicated Usage}, instances {@code
   *     pid_<pid>_luid_0x<high>_0x<low>_phys_<n>}
   * @param processShared {@code \GPU Process Memory(*)\Shared Usage}, the same instances
   * @param adapterDedicated {@code \GPU Adapter Memory(*)\Dedicated Usage}, instances {@code
   *     luid_0x<high>_0x<low>_phys_<n>}
   */
  record Counters(
      Map<String, Long> processDedicated,
      Map<String, Long> processShared,
      Map<String, Long> adapterDedicated) {
    public Counters {
      processDedicated = Map.copyOf(processDedicated);
      processShared = Map.copyOf(processShared);
      adapterDedicated = Map.copyOf(adapterDedicated);
    }
  }
}
