/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the GPU memory counters through PDH. The query is opened on the first collect and kept,
 * because the first open costs 0.2 to 0.8 s (spike S1); after that a collect costs under 1 ms. The
 * counters are raw, so one collect per reading is enough. A failure closes the query and the next
 * collect opens a new one.
 */
final class PdhCounterSource implements CounterSource {

  static final String PROCESS_DEDICATED = "\\GPU Process Memory(*)\\Dedicated Usage";
  static final String PROCESS_SHARED = "\\GPU Process Memory(*)\\Shared Usage";
  static final String ADAPTER_DEDICATED = "\\GPU Adapter Memory(*)\\Dedicated Usage";

  private static final int PDH_FMT_LARGE = 0x400;
  private static final int PDH_MORE_DATA = 0x800007D2;
  private static final int PDH_CSTATUS_VALID_DATA = 0;
  private static final int PDH_CSTATUS_NEW_DATA = 1;
  // PDH_FMT_COUNTERVALUE_ITEM_W: LPWSTR szName; PDH_FMT_COUNTERVALUE {DWORD CStatus; LONGLONG}.
  private static final long ITEM_BYTES = 24;
  private static final long ITEM_STATUS = 8;
  private static final long ITEM_VALUE = 16;
  private static final long MAX_NAME_BYTES = 1024;

  private final Arena library = Arena.ofShared();
  private MethodHandle openQuery;
  private MethodHandle addCounter;
  private MethodHandle collectQuery;
  private MethodHandle counterArray;
  private MethodHandle closeQuery;

  private MemorySegment query;
  private MemorySegment processDedicated;
  private MemorySegment processShared;
  private MemorySegment adapterDedicated;

  @Override
  public synchronized Counters collect() {
    try {
      if (query == null) open();
      int status = (int) collectQuery.invokeExact(query);
      if (status != 0) throw NativeCalls.status("PdhCollectQueryData", status);
      return new Counters(read(processDedicated), read(processShared), read(adapterDedicated));
    } catch (Throwable t) {
      closeQuery();
      throw NativeCalls.failure("PDH collect", t);
    }
  }

  private void open() throws Throwable {
    if (openQuery == null) {
      SymbolLookup pdh = NativeCalls.systemLibrary("pdh.dll", library);
      openQuery =
          NativeCalls.downcall(pdh, "PdhOpenQueryW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
      addCounter =
          NativeCalls.downcall(
              pdh, "PdhAddEnglishCounterW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
      collectQuery = NativeCalls.downcall(pdh, "PdhCollectQueryData", FunctionDescriptor.of(JAVA_INT, ADDRESS));
      counterArray =
          NativeCalls.downcall(
              pdh,
              "PdhGetFormattedCounterArrayW",
              FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
      closeQuery = NativeCalls.downcall(pdh, "PdhCloseQuery", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    }
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment queryOut = arena.allocate(ADDRESS);
      int status = (int) openQuery.invokeExact(MemorySegment.NULL, 0L, queryOut);
      if (status != 0) throw NativeCalls.status("PdhOpenQueryW", status);
      query = queryOut.get(ADDRESS, 0);
      processDedicated = add(arena, PROCESS_DEDICATED);
      processShared = add(arena, PROCESS_SHARED);
      adapterDedicated = add(arena, ADAPTER_DEDICATED);
    }
  }

  private MemorySegment add(Arena arena, String path) throws Throwable {
    MemorySegment counterOut = arena.allocate(ADDRESS);
    int status =
        (int)
            addCounter.invokeExact(
                query, arena.allocateFrom(path, StandardCharsets.UTF_16LE), 0L, counterOut);
    if (status != 0) throw NativeCalls.status("PdhAddEnglishCounterW " + path, status);
    return counterOut.get(ADDRESS, 0);
  }

  private Map<String, Long> read(MemorySegment counter) throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      MemorySegment size = arena.allocate(JAVA_INT);
      MemorySegment count = arena.allocate(JAVA_INT);
      int status = (int) counterArray.invokeExact(counter, PDH_FMT_LARGE, size, count, MemorySegment.NULL);
      if (status != PDH_MORE_DATA && status != 0) {
        throw NativeCalls.status("PdhGetFormattedCounterArrayW size", status);
      }
      long bufferBytes = Math.max(size.get(JAVA_INT, 0), 8);
      MemorySegment buffer = arena.allocate(bufferBytes, 8);
      status = (int) counterArray.invokeExact(counter, PDH_FMT_LARGE, size, count, buffer);
      if (status != 0) throw NativeCalls.status("PdhGetFormattedCounterArrayW", status);
      int items = count.get(JAVA_INT, 0);
      if ((long) items * ITEM_BYTES > bufferBytes) {
        throw new IllegalStateException("PDH returned " + items + " items for " + bufferBytes + " bytes");
      }
      Map<String, Long> values = new LinkedHashMap<>();
      for (int i = 0; i < items; i++) {
        MemorySegment item = buffer.asSlice(i * ITEM_BYTES, ITEM_BYTES);
        int itemStatus = item.get(JAVA_INT, ITEM_STATUS);
        if (itemStatus != PDH_CSTATUS_VALID_DATA && itemStatus != PDH_CSTATUS_NEW_DATA) continue;
        values.merge(name(buffer, item.get(ADDRESS, 0)), item.get(JAVA_LONG, ITEM_VALUE), Long::sum);
      }
      return values;
    }
  }

  /** The instance name, which PDH stores inside the same buffer after the items. */
  private static String name(MemorySegment buffer, MemorySegment pointer) {
    long offset = pointer.address() - buffer.address();
    MemorySegment chars =
        offset >= 0 && offset < buffer.byteSize()
            ? buffer.asSlice(offset)
            : pointer.reinterpret(MAX_NAME_BYTES);
    StringBuilder out = new StringBuilder();
    for (long at = 0; at + 1 < chars.byteSize(); at += 2) {
      char c = (char) chars.get(JAVA_CHAR_UNALIGNED, at);
      if (c == 0) return out.toString();
      out.append(c);
    }
    throw new IllegalStateException("unterminated PDH instance name");
  }

  private void closeQuery() {
    MemorySegment open = query;
    query = null;
    if (open == null || closeQuery == null) return;
    try {
      int ignored = (int) closeQuery.invokeExact(open);
    } catch (Throwable t) {
      if (t instanceof VirtualMachineError error) throw error;
    }
  }

  @Override
  public synchronized void close() {
    closeQuery();
    openQuery = null;
    if (library.scope().isAlive()) library.close();
  }
}
