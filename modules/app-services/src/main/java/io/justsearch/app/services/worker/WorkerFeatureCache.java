/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import java.util.List;

/**
 * Provides cached ONNX model discovery status from the index component.
 *
 * <p>Implementations return the last-known-good state: if the Worker is unreachable, the most
 * recent successful response is returned. If no successful response has been received, returns an
 * empty list.
 */
@FunctionalInterface
public interface WorkerFeatureCache {
  List<OnnxModelStatus> getOnnxModels();
}
