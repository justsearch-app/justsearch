/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;

/** Checks the loopback HTTP/1 connection after its request body has been consumed. */
final class ProxyClientConnection {
  private final org.eclipse.jetty.io.EndPoint endpoint;
  private final HttpConnection connection;
  private final ByteBuffer probe = ByteBuffer.allocate(1);

  ProxyClientConnection(Context ctx) {
    Request request = ctx.req() == null ? null : Request.getBaseRequest(ctx.req());
    endpoint = request == null ? null : request.getHttpChannel().getEndPoint();
    connection = endpoint != null && endpoint.getConnection() instanceof HttpConnection http
        ? http : null;
  }

  boolean closed() {
    if (endpoint == null) return false; // Non-Jetty test contexts have no physical connection.
    if (!endpoint.isOpen() || endpoint.isInputShutdown()) return true;
    // Jetty 11 does not read the socket while a synchronous handler waits. isOpen alone cannot
    // see FIN/RST. The consumed-body handler owns the read until it returns. Preserve any
    // pipelined byte in Jetty's request buffer using its public buffer handover API; never
    // consume another request or race a pending Jetty read. Buffered pipeline input can hide
    // a subsequent FIN until Jetty processes it; the response-body check is the next chance.
    if (connection == null || endpoint.isFillInterested() || !connection.isRequestBufferEmpty()) {
      return false;
    }
    try {
      probe.clear().limit(0); // EndPoint.fill uses Jetty flush-mode buffers.
      int read = endpoint.fill(probe);
      if (read < 0) return true;
      if (read > 0) {
        connection.onUpgradeTo(probe);
      }
      return false;
    } catch (IOException departed) {
      return true;
    }
  }
}
