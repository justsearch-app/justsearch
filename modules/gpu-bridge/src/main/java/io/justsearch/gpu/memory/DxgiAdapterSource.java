/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.gpu.memory;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.justsearch.app.api.gpu.GpuDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists adapters through DXGI ({@code CreateDXGIFactory1}, {@code IDXGIFactory1::EnumAdapters1},
 * {@code IDXGIAdapter1::GetDesc1}). It creates no device and loads no driver runtime; spike S1
 * measured it matching the PDH instance LUIDs exactly.
 */
final class DxgiAdapterSource implements AdapterSource {

  /** IID_IDXGIFactory1 {770aae78-f26f-4dba-a829-253c83d1b387}. */
  private static final byte[] IID_TAIL = {
    (byte) 0xa8, 0x29, 0x25, 0x3c, (byte) 0x83, (byte) 0xd1, (byte) 0xb3, (byte) 0x87
  };

  private static final int DXGI_ERROR_NOT_FOUND = 0x887A0002;
  private static final int DXGI_ADAPTER_FLAG_SOFTWARE = 2;
  private static final int RELEASE = 2;
  private static final int ADAPTER_GET_DESC1 = 10;
  private static final int FACTORY_ENUM_ADAPTERS1 = 12;

  // DXGI_ADAPTER_DESC1: WCHAR Description[128]; UINT VendorId, DeviceId, SubSysId, Revision;
  // SIZE_T DedicatedVideoMemory, DedicatedSystemMemory, SharedSystemMemory; LUID {LowPart, HighPart};
  // UINT Flags.
  private static final long DESC_SIZE = 312;
  private static final long DESCRIPTION_BYTES = 256;
  private static final long VENDOR_ID = 256;
  private static final long DEDICATED_VIDEO_MEMORY = 272;
  private static final long LUID_LOW = 296;
  private static final long LUID_HIGH = 300;
  private static final long FLAGS = 304;

  private final Arena library = Arena.ofShared();
  private MethodHandle createFactory;

  @Override
  public synchronized List<Adapter> adapters() {
    try (Arena arena = Arena.ofConfined()) {
      if (createFactory == null) {
        createFactory =
            NativeCalls.downcall(
                NativeCalls.systemLibrary("dxgi.dll", library),
                "CreateDXGIFactory1",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
      }
      MemorySegment factoryOut = arena.allocate(ADDRESS);
      int hr = (int) createFactory.invokeExact(iidFactory1(arena), factoryOut);
      if (hr != 0) throw NativeCalls.status("CreateDXGIFactory1", hr);
      MemorySegment factory = factoryOut.get(ADDRESS, 0);
      try {
        return enumerate(arena, factory);
      } finally {
        release(factory);
      }
    } catch (Throwable t) {
      throw NativeCalls.failure("DXGI adapter enumeration", t);
    }
  }

  private static List<Adapter> enumerate(Arena arena, MemorySegment factory) throws Throwable {
    MethodHandle enumAdapters =
        method(factory, FACTORY_ENUM_ADAPTERS1, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    List<Adapter> adapters = new ArrayList<>();
    for (int index = 0; ; index++) {
      MemorySegment adapterOut = arena.allocate(ADDRESS);
      int hr = (int) enumAdapters.invokeExact(factory, index, adapterOut);
      if (hr == DXGI_ERROR_NOT_FOUND) return adapters;
      if (hr != 0) throw NativeCalls.status("EnumAdapters1", hr);
      MemorySegment adapter = adapterOut.get(ADDRESS, 0);
      try {
        MemorySegment desc = arena.allocate(DESC_SIZE, 8);
        int descHr =
            (int)
                method(adapter, ADAPTER_GET_DESC1, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
                    .invokeExact(adapter, desc);
        if (descHr != 0) throw NativeCalls.status("GetDesc1", descHr);
        adapters.add(
            new Adapter(
                GpuDevice.luidOf(desc.get(JAVA_INT, LUID_HIGH), desc.get(JAVA_INT, LUID_LOW)),
                desc.get(JAVA_INT, VENDOR_ID),
                description(desc),
                desc.get(JAVA_LONG, DEDICATED_VIDEO_MEMORY),
                (desc.get(JAVA_INT, FLAGS) & DXGI_ADAPTER_FLAG_SOFTWARE) != 0));
      } finally {
        release(adapter);
      }
    }
  }

  private static String description(MemorySegment desc) {
    StringBuilder out = new StringBuilder();
    for (long offset = 0; offset < DESCRIPTION_BYTES; offset += 2) {
      char c = (char) desc.get(JAVA_SHORT, offset);
      if (c == 0) break;
      out.append(c);
    }
    return out.toString();
  }

  private static MemorySegment iidFactory1(Arena arena) {
    MemorySegment iid = arena.allocate(16, 4);
    iid.set(JAVA_INT, 0, 0x770aae78);
    iid.set(JAVA_SHORT, 4, (short) 0xf26f);
    iid.set(JAVA_SHORT, 6, (short) 0x4dba);
    for (int i = 0; i < IID_TAIL.length; i++) iid.set(JAVA_BYTE, 8 + i, IID_TAIL[i]);
    return iid;
  }

  /** A COM method by vtable slot. */
  private static MethodHandle method(MemorySegment object, int slot, FunctionDescriptor descriptor) {
    MemorySegment vtable =
        object.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (slot + 1L));
    return NativeCalls.LINKER.downcallHandle(vtable.getAtIndex(ADDRESS, slot), descriptor);
  }

  private static void release(MemorySegment object) throws Throwable {
    if (object.address() == 0) return;
    int unusedRefCount = (int) method(object, RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(object);
  }

  @Override
  public synchronized void close() {
    createFactory = null;
    if (library.scope().isAlive()) library.close();
  }
}
