package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.NativeTargets;
import java.lang.foreign.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "JUSTSEARCH_LINUX_GPU_CHECK", matches = "true")
class NativeGpuLinuxLiveTest {
  @Test void driverReportsDeviceIdentityAndMemoryWithoutSubprocesses() throws Throwable {
    var result = GpuDriverApiProbe.probe();
    assertTrue(result.available(), result.toString());
    assertTrue(result.deviceCount() > 0);
    var device = result.devices().getFirst();
    assertTrue(device.totalBytes() > 0, device.toString());
    assertTrue(device.uuid().isPresent());
    assertTrue(device.pciBusId().isPresent());
    // Diagnostic name read through the same CUDA driver SONAME, without expanding production APIs.
    try (var arena = Arena.ofConfined()) {
      var lookup = SymbolLookup.libraryLookup(NativeTargets.current().libraryName("cuda"), arena);
      var name = Linker.nativeLinker().downcallHandle(lookup.find("cuDeviceGetName").orElseThrow(),
          FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
      var buffer = arena.allocate(256);
      assertEquals(0, (int) name.invokeExact(buffer, 256, device.ordinal()));
      String description = buffer.getString(0);
      assertFalse(description.isBlank());
      System.out.println("CUDA device=" + description + " result=" + result);
    }
    assertEquals("true", GpuAutoDetection.probe(null).get("justsearch.gpu.enabled"));
  }
}
