/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Verifies that a managed runtime actually enforces its environment credential. */
public final class LlamaServerAuthentication {
  private static final String PATH_PROPS = "/props";

  private LlamaServerAuthentication() {}

  /** A healthy runtime must reject this credential-free request before it can serve the Engine. */
  public static void requireEnforcement(HttpClient client, int port, Duration timeout)
      throws IOException, InterruptedException {
    var endpoint = new LlamaServerEndpoint(port, null);
    var request = InferenceHttpHelpers.buildGetRequest(endpoint, PATH_PROPS, timeout);
    HttpResponse<InputStream> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException failure) {
      throw new IOException("Cannot verify llama-server API-key protection; repair the AI runtime"
          + " and ensure its launcher preserves LLAMA_API_KEY ("
          + failure.getClass().getSimpleName() + ").", failure);
    }
    // Do not read an untrusted or indefinitely streaming response body.
    response.body().close();
    if (response.statusCode() != 401) {
      throw new IOException("this llama-server ignores its API key; repair the AI runtime or"
          + " install a current llama-server and ensure its launcher preserves LLAMA_API_KEY"
          + " (unauthenticated /props returned HTTP " + response.statusCode() + "; required 401).");
    }
  }
}
