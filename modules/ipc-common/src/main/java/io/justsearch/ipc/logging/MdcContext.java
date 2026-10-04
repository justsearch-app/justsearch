/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.logging;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

public final class MdcContext implements AutoCloseable {
  private final Map<String, String> previous;

  private MdcContext(Map<String, String> previous) {
    this.previous = previous;
  }

  public static MdcContext request(String traceId, String requestId) {
    Map<String, String> prev = MDC.getCopyOfContextMap();
    if (traceId != null && !traceId.isBlank()) {
      MDC.put("trace_id", traceId);
    }
    if (requestId != null && !requestId.isBlank()) {
      MDC.put("request_id", requestId);
    }
    return new MdcContext(prev);
  }

  // pipeline_hash + budget_profile were retired by tempdoc 400 LR2-d: the values tracked
  // PipelineDefinition identity, which ADR 0014 (2026-03-16) removed. pipeline_name stays —
  // it is still populated from PipelineConfig activation flags on the Head side.
  public static MdcContext pipeline(String pipelineName) {
    Map<String, String> prev = MDC.getCopyOfContextMap();
    if (pipelineName != null && !pipelineName.isBlank()) {
      MDC.put("pipeline_name", pipelineName);
    }
    return new MdcContext(prev);
  }

  public static MdcContext stage(String stageId) {
    Map<String, String> prev = MDC.getCopyOfContextMap();
    if (stageId != null && !stageId.isBlank()) {
      MDC.put("stage_id", stageId);
    }
    return new MdcContext(prev);
  }

  /**
   * Pushes a {@code scan_id} for the duration of a Worker-owned ScanRoot RPC (tempdoc 419 / T2).
   * Set at the in-process port entry point so every log line emitted under the scan correlates to the same
   * scanId. The same value is also stamped on every emitted {@code ScanRootProgress.scan_id}.
   */
  public static MdcContext scan(String scanId) {
    Map<String, String> prev = MDC.getCopyOfContextMap();
    if (scanId != null && !scanId.isBlank()) {
      MDC.put("scan_id", scanId);
    }
    return new MdcContext(prev);
  }

  @Override
  public void close() {
    Map<String, String> map = this.previous;
    if (map == null || map.isEmpty()) {
      MDC.clear();
    } else {
      MDC.setContextMap(new HashMap<>(map));
    }
  }
}
