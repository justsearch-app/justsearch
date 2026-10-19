package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class DownloadLinuxCommandTest {
  @Test void usesNativeCurl() {
    assertEquals("curl", DownloadExecutor.curlCommand("http://127.0.0.1/model", Path.of("partial"), false).getFirst());
  }
}
