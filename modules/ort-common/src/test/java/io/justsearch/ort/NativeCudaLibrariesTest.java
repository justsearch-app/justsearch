package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.NativeTargets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeCudaLibrariesTest {
  @TempDir Path root;

  @Test void checksActualHostNamesAndKeepsRuntimeSeparateFromProviders() throws Exception {
    var names = NativeTargets.current();
    for (String id : new String[] {"cudart", "cublas", "cublasLt"}) {
      Files.writeString(root.resolve(names.libraryName(id)), "library");
    }
    assertTrue(OrtCudaHelper.checkMissingCudaRuntimeDlls(root).isEmpty());
    assertTrue(OrtCudaHelper.checkMissingCudaDlls(root).contains(names.libraryName("ort-cuda")));
    for (String id : new String[] {"ort-cuda", "ort-shared"}) {
      Files.writeString(root.resolve(names.libraryName(id)), "library");
    }
    assertTrue(OrtCudaHelper.checkMissingCudaDlls(root).isEmpty());
    Files.delete(root.resolve(names.libraryName("cublas")));
    assertTrue(OrtCudaHelper.checkMissingCudaRuntimeDlls(root).contains(names.libraryName("cublas")));
  }
}
