/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * One inference launch's address and optional private credential, held only in Engine memory.
 * External endpoints have no managed key. Never publish this value in status or diagnostics.
 */
public record LlamaServerEndpoint(int port, String apiKey) {
  @Override
  public String toString() {
    return "LlamaServerEndpoint[port=" + port + "]";
  }
}
