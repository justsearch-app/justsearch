/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("SYSTEM_PROPERTIES")
class OrtUnsupportedTargetTest {
  @TempDir Path temp;

  @Test void unsupportedTargetsCanLoadCpuHelpersWithoutResolvingGpuNames() throws Exception {
    String os = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");
    try {
      for (String[] target : new String[][] {{"Mac OS X", "aarch64"}, {"Linux", "aarch64"}}) {
        System.setProperty("os.name", target[0]);
        System.setProperty("os.arch", target[1]);
        // A fresh definition makes this a first-load check even after another ORT test ran.
        try (var loader = new URLClassLoader(new java.net.URL[] {
            OrtCudaHelper.class.getProtectionDomain().getCodeSource().getLocation()
        }, OrtCudaHelper.class.getClassLoader()) {
          @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("io.justsearch.ort.OrtCudaHelper")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
              Class<?> found = findLoadedClass(name);
              if (found == null) found = findClass(name);
              if (resolve) resolveClass(found);
              return found;
            }
          }
        }) {
          Class<?> helper = Class.forName("io.justsearch.ort.OrtCudaHelper", true, loader);
          assertEquals(java.util.List.of(), helper.getMethod("checkMissingCudaRuntimeDlls", Path.class).invoke(null, temp));
          Object decision = helper.getMethod("evaluateOrtNativePack", Path.class, String.class).invoke(null, temp, null);
          assertEquals("DIR_ABSENT", decision.getClass().getMethod("status").invoke(decision).toString());
          assertTrue(decision.getClass().getMethod("detail").invoke(decision).toString().contains("GPU unavailable"));
        }
      }
    } finally {
      System.setProperty("os.name", os);
      System.setProperty("os.arch", arch);
    }
  }
}
