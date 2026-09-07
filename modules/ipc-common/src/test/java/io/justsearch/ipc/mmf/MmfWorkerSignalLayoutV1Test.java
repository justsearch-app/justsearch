/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc.mmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 882 item 26: pins the MMF v1 signal-bus byte layout so the field ranges never overlap
 * again.
 *
 * <p>It also used to tie {@link MmfWorkerSignalLayoutV1#OFFSET_RELOAD_SIGNAL} to the dev MCP
 * server's write site. That pin was RETARGETED, not dropped: lane F stage A review S2 moved the
 * hot-reload trigger off the memory-mapped byte onto a request file, so the two halves that can
 * now drift are the dev tool's path literal and
 * {@code InProcessWorkerSignalBus.RELOAD_REQUEST_FILENAME}. That is asserted in
 * {@code InProcessWorkerSignalBusReloadTest}, which is the module that can see both.
 *
 * <p>The layout itself outlives its two Java implementations (item A10 deleted both) because the
 * system-test harness still reads it; item A12 owns that.
 */
class MmfWorkerSignalLayoutV1Test {

  private record FieldRange(String name, int start, int end) {
    FieldRange {
      if (end <= start) {
        throw new IllegalArgumentException(name + ": end must be after start");
      }
    }
  }

  private static List<FieldRange> declaredRanges() {
    List<FieldRange> ranges = new ArrayList<>();
    ranges.add(new FieldRange("activity_epoch_ms",
        MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS,
        MmfWorkerSignalLayoutV1.OFFSET_ACTIVITY_EPOCH_MS + 8));
    ranges.add(new FieldRange("heartbeat_epoch_ms",
        MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS,
        MmfWorkerSignalLayoutV1.OFFSET_HEARTBEAT_EPOCH_MS + 8));
    ranges.add(new FieldRange("shutdown_signal",
        MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL,
        MmfWorkerSignalLayoutV1.OFFSET_SHUTDOWN_SIGNAL + 1));
    ranges.add(new FieldRange("energy_reduced",
        MmfWorkerSignalLayoutV1.OFFSET_ENERGY_REDUCED,
        MmfWorkerSignalLayoutV1.OFFSET_ENERGY_REDUCED + 1));
    ranges.add(new FieldRange("reserved0",
        MmfWorkerSignalLayoutV1.OFFSET_RESERVED0_START,
        MmfWorkerSignalLayoutV1.OFFSET_RESERVED0_START
            + MmfWorkerSignalLayoutV1.RESERVED0_LENGTH_BYTES));
    ranges.add(new FieldRange("worker_grpc_port",
        MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT,
        MmfWorkerSignalLayoutV1.OFFSET_WORKER_GRPC_PORT + 4));
    ranges.add(new FieldRange("main_gpu_active",
        MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE,
        MmfWorkerSignalLayoutV1.OFFSET_MAIN_GPU_ACTIVE + 1));
    // Header (magic + version + flags): MmfWorkerSignalHeaderV1.OFFSET_FLAGS is its last byte.
    ranges.add(new FieldRange("header",
        MmfWorkerSignalHeaderV1.OFFSET_MAGIC,
        MmfWorkerSignalHeaderV1.OFFSET_FLAGS + 1));
    ranges.add(new FieldRange("reload_signal",
        MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL,
        MmfWorkerSignalLayoutV1.OFFSET_RELOAD_SIGNAL + 1));
    ranges.add(new FieldRange("reserved1",
        MmfWorkerSignalLayoutV1.OFFSET_RESERVED1_START,
        MmfWorkerSignalLayoutV1.OFFSET_RESERVED1_START
            + MmfWorkerSignalLayoutV1.RESERVED1_LENGTH_BYTES));
    return ranges;
  }

  @Test
  void namedFieldRangesArePairwiseDisjoint() {
    List<FieldRange> ranges = declaredRanges();
    for (int i = 0; i < ranges.size(); i++) {
      for (int j = i + 1; j < ranges.size(); j++) {
        FieldRange a = ranges.get(i);
        FieldRange b = ranges.get(j);
        boolean overlaps = a.start() < b.end() && b.start() < a.end();
        assertFalse(overlaps,
            () -> "Overlap between " + a.name() + " [" + a.start() + "," + a.end()
                + ") and " + b.name() + " [" + b.start() + "," + b.end() + ")");
      }
    }
  }

  @Test
  void reserved1EndsAtMmfSize() {
    assertEquals(MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES,
        MmfWorkerSignalLayoutV1.OFFSET_RESERVED1_START
            + MmfWorkerSignalLayoutV1.RESERVED1_LENGTH_BYTES);
    assertEquals(64, MmfWorkerSignalLayoutV1.MMF_SIZE_BYTES);
  }
}
