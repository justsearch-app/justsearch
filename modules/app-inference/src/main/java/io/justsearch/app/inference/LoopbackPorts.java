/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Fresh loopback ports for managed llama-server launches (ADR-0051 decision 2).
 *
 * <p>The port is released before llama-server binds it, so another process can take it in
 * between. That race is closed by the caller: a launch whose log shows a failed bind is retried
 * once on a new port (see {@link LlamaServerBindFailure}).
 */
final class LoopbackPorts {
  private LoopbackPorts() {}

  /** A loopback port that was free at the moment of the call. */
  static int findFree() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
