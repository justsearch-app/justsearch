/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A child's DLL search path: {@link DefaultChildControl} puts a spec's path prefix in front of the
 * PATH the child inherits, in the one variable the parent has, whatever its spelling.
 */
final class ChildPathPrefixTest {

  @Test
  void theInheritedSpellingIsKeptSoTheChildHasOnePathVariable() {
    Map<String, String> environment = new HashMap<>();
    environment.put("Path", "C:/inherited");

    DefaultChildControl.prependPath(environment, List.of(Path.of("C:/dll"), Path.of("C:/bin")));

    assertEquals(Map.of("Path", Path.of("C:/dll") + File.pathSeparator + Path.of("C:/bin")
        + File.pathSeparator + "C:/inherited"), environment);
  }

  @Test
  void withoutAnInheritedPathThePrefixIsThePath() {
    Map<String, String> environment = new HashMap<>();

    DefaultChildControl.prependPath(environment, List.of(Path.of("C:/dll")));

    assertEquals(Map.of("PATH", Path.of("C:/dll").toString()), environment);
  }
}
