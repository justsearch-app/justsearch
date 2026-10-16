/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.gpu;

/**
 * The device facts of this process (Spec E section 5.3): the CUDA probe's devices and the pinned
 * device. The production source probes once and keeps the answer for the life of the process, so
 * the configuration (ORT ordinals), the GPU memory reader, admission and llama-server all see the
 * same device.
 */
public interface DeviceFactsSource {

  /** The facts; never throws. */
  DeviceFacts facts();

  /** The process's production source: the CUDA driver probe and DXGI, probed on first use. */
  static DeviceFactsSource process() {
    return ProbedDeviceFactsSource.PROCESS;
  }
}
