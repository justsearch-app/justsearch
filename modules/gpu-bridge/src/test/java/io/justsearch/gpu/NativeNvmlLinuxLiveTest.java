package io.justsearch.gpu;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "JUSTSEARCH_LINUX_GPU_CHECK", matches = "true")
class NativeNvmlLinuxLiveTest {
  @Test void nvmlReportsDeviceCountAndVramWithoutSubprocesses() {
    var result = new NvmlService().probe();
    assertTrue(result.available(), result.toString());
    assertEquals("libnvidia-ml.so.1", result.loadedPath());
    assertNotNull(result.deviceCount());
    assertTrue(result.deviceCount() > 0);
    assertNotNull(result.totalVramBytes());
    assertTrue(result.totalVramBytes() > 0);
    assertNotNull(result.freeVramBytes());
    assertTrue(result.freeVramBytes() >= 0);
    assertNotNull(result.usedVramBytes());
    assertTrue(result.usedVramBytes() >= 0);
    System.out.println("NVML=" + result);
  }
}
