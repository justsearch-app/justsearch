/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import io.justsearch.app.api.chat.ChatDeviceLoss;
import io.justsearch.app.api.chat.DeviceLostEvidence;
import io.justsearch.ort.DeviceHealth;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec E §6.2 item 6: tells the chat server controller, once per Engine incarnation, that an
 * encoder found its CUDA device lost, as {@code reportDeviceLost(new DeviceLostEvidence(ORT_CUDA,
 * code))}. The controller applies the device-lost limit and restarts the Engine within it; this
 * reporter only reports. A second device lost in the same incarnation is not reported again: the
 * restart the first report asks for re-probes every device.
 */
public final class EncoderDeviceLossReporter implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(EncoderDeviceLossReporter.class);

  private final Consumer<DeviceLostEvidence> controller;
  private final AtomicBoolean reported = new AtomicBoolean();
  private final AutoCloseable subscription;

  private EncoderDeviceLossReporter(Consumer<DeviceLostEvidence> controller) {
    this.controller = controller;
    this.subscription = DeviceHealth.addLossListener(this::onDeviceLost);
  }

  /**
   * Starts reporting encoder device loss to {@code controller}. A device already lost before this
   * call is reported at once.
   */
  public static EncoderDeviceLossReporter start(ChatDeviceLoss controller) {
    Objects.requireNonNull(controller, "controller");
    return new EncoderDeviceLossReporter(controller::reportDeviceLost);
  }

  private void onDeviceLost(int cudaDevice, int code) {
    if (!reported.compareAndSet(false, true)) {
      log.warn(
          "CUDA device {} lost (CUDA error {}); device loss was already reported in this"
              + " incarnation",
          cudaDevice,
          code);
      return;
    }
    controller.accept(
        new DeviceLostEvidence(DeviceLostEvidence.Source.ORT_CUDA, Integer.toString(code)));
  }

  @Override
  public void close() {
    try {
      subscription.close();
    } catch (Exception e) {
      log.warn("device-loss subscription close failed", e);
    }
  }
}
