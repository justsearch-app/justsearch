package io.justsearch.configuration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("cross-platform")
class PlatformPathsTest {

    @Test
    void resolveDataDir_returnsNonNullPath() {
        Path dataDir = PlatformPaths.resolveDataDir();
        assertNotNull(dataDir, "Data directory should never be null");
        assertFalse(dataDir.toString().isBlank(), "Data directory should not be blank");
    }

    @Test
    void resolveDataDir_containsJustSearchComponent() {
        Path dataDir = PlatformPaths.resolveDataDir();
        String pathStr = dataDir.toString().toLowerCase(Locale.ROOT);
        assertTrue(
            pathStr.contains("justsearch") || pathStr.contains(".justsearch"),
            "Data directory should contain 'justsearch' or '.justsearch': " + dataDir
        );
    }

    @Test
    void getPlatformDefault_returnsNonNullPath() {
        Path defaultPath = PlatformPaths.getPlatformDefault();
        assertNotNull(defaultPath, "Platform default should never be null");
    }

    @Test
    void getPlatformDefault_isPlatformAppropriate() {
        Path defaultPath = PlatformPaths.getPlatformDefault();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String pathStr = defaultPath.toString();

        if (os.contains("win")) {
            // Windows: should be in AppData\Local or similar
            assertTrue(
                pathStr.contains("AppData") || pathStr.contains("Local"),
                "Windows default should be in AppData: " + pathStr
            );
        } else if (os.contains("mac")) {
            // macOS: should be in Library/Application Support
            assertTrue(
                pathStr.contains("Library") && pathStr.contains("Application Support"),
                "macOS default should be in Library/Application Support: " + pathStr
            );
        } else {
            // Linux/other: should be .justsearch in home
            assertTrue(
                pathStr.contains(".justsearch"),
                "Linux default should be .justsearch: " + pathStr
            );
        }
    }

    @Test
    void resolveIndexPath_appendsCollectionName() {
        Path indexPath = PlatformPaths.resolveIndexPath("testCollection");
        assertNotNull(indexPath);
        assertTrue(indexPath.toString().endsWith("testCollection"));
        assertTrue(indexPath.toString().contains("index"));
    }

    @Test
    void resolveIndexPath_defaultCollection() {
        Path indexPath = PlatformPaths.resolveIndexPath("default");
        Path dataDir = PlatformPaths.resolveDataDir();
        assertEquals(dataDir.resolve("index").resolve("default"), indexPath);
    }

    @Test
    void resolveDataDir_respectsSystemProperty() {
        String original = System.getProperty("justsearch.data.dir");
        try {
            System.setProperty("justsearch.data.dir", "/custom/test/path");
            Path dataDir = PlatformPaths.resolveDataDir();
            assertEquals(Path.of("/custom/test/path"), dataDir);
        } finally {
            if (original != null) {
                System.setProperty("justsearch.data.dir", original);
            } else {
                System.clearProperty("justsearch.data.dir");
            }
        }
    }
}
