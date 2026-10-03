/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DataVersionMarkerTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  @TempDir Path dir;

  private DataVersionMarker.Marker read() throws Exception {
    return JSON.readValue(Files.readAllBytes(dir.resolve("data-version.json")), DataVersionMarker.Marker.class);
  }

  private void seed(String app, Map<String, Integer> stores) throws Exception {
    Files.write(dir.resolve("data-version.json"), JSON.writeValueAsBytes(new DataVersionMarker.Marker(app, stores)));
  }

  @Test
  void markerRecordsEveryCodeBoundVersionAndAppBeforeStoresOpen() throws Exception {
    Map<String, Integer> expected = new TreeMap<>();
    Path root = repositoryRoot();
    for (var row : JSON.readTree(root.resolve("governance/store-recoverability.v1.json").toFile()).path("durableStores")) {
      if (row.has("versionSource")) expected.put(row.path("id").asText(), row.path("currentVersion").asInt());
    }
    assertEquals(expected, SupportedDataVersions.current());
    assertTrue(DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current()).isEmpty());
    assertEquals("0.3.0", read().appVersion());
    assertEquals(expected, read().storeVersions());
    // The real boot call must be inside the instance lock and ahead of the first migrations.
    String boot = Files.readString(root.resolve("modules/ui/src/main/java/io/justsearch/ui/HeadlessApp.java"));
    int marker = boot.indexOf("DataVersionMarker.recordBoot(");
    int lock = boot.indexOf("appInstanceLock.acquire()");
    // Q10 (1c63db01c) opens the operations store through its startup cleanup-owner helper.
    int operations = boot.indexOf("operations = openOperationsForStartup(");
    assertTrue(lock >= 0);
    assertTrue(marker > lock);
    assertTrue(marker < operations);
    assertTrue(marker < boot.indexOf("ConfigPhaseResult configPhase = resolveConfig(operations)"));
    assertTrue(boot.indexOf("NewerDataNotice.publish(newerDataNotice", marker) > marker);
  }

  @Test
  void newerMarkerWarnsThroughHealthAndAnOlderBootRaisesOnlyLowerFields() throws Exception {
    seed("1.0.0", Map.of("ui-settings", 99, "jobs-db", 1, "future-store", 7));
    var message = assertDoesNotThrow(() -> DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current()));
    assertTrue(message.isPresent());
    assertTrue(message.orElseThrow().contains("app 1.0.0"));
    assertTrue(message.orElseThrow().contains("ui-settings v99"));
    assertTrue(message.orElseThrow().contains("future-store v7"));
    assertTrue(message.orElseThrow().contains("settings.v<old>.bak.json"));
    assertTrue(message.orElseThrow().contains(".corrupt-*"));
    assertEquals("1.0.0", read().appVersion());
    assertEquals(99, read().storeVersions().get("ui-settings"));
    assertEquals(7, read().storeVersions().get("future-store"));
    assertEquals(SupportedDataVersions.current().get("jobs-db"), read().storeVersions().get("jobs-db"));
    var health = new ConditionStore();
    assertDoesNotThrow(() -> NewerDataNotice.publish(message, health,
        new HealthEventChangeRegistry(), new Source("head", "test", Optional.empty()), Clock.systemUTC()));
    var event = health.find(LifecycleReasonCode.DATA_NEWER_BUILD.code(), "data").orElseThrow();
    assertEquals(message.orElseThrow(), ((AssertedCondition) event.body()).message().orElseThrow());
    // Another boot must retain the warning: the high-water mark was not replaced by this build.
    assertTrue(DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current()).isPresent());
  }

  @Test
  void futureMetadataDoesNotDiscardReadableHighWaterFields() throws Exception {
    Files.writeString(dir.resolve("data-version.json"),
        "{\"appVersion\":\"1.0.0\",\"storeVersions\":{\"jobs-db\":99},\"futureMetadata\":{}}");
    assertTrue(DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current()).isPresent());
    assertEquals("1.0.0", read().appVersion());
    assertEquals(99, read().storeVersions().get("jobs-db"));
  }

  @Test
  void storeOnlyNewerVersionWarnsEvenWhenAppVersionIsOlder() throws Exception {
    seed("0.2.0", Map.of("jobs-db", 99));
    var notice = DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current()).orElseThrow();
    assertTrue(notice.contains("jobs-db v99"));
    assertEquals("0.3.0", read().appVersion());
    assertEquals(99, read().storeVersions().get("jobs-db"));
  }

  @Test
  void equalAndOlderMarkersProduceNoCondition() throws Exception {
    Map<String, Integer> olderStores = Map.of("ui-settings", 2, "jobs-db", 12);
    for (var previous : java.util.List.of(
        new DataVersionMarker.Marker("0.2.0", olderStores),
        new DataVersionMarker.Marker("0.3.0", olderStores),
        new DataVersionMarker.Marker("0.3.0", SupportedDataVersions.current()))) {
      seed(previous.appVersion(), previous.storeVersions());
      var notice = DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current());
      assertTrue(notice.isEmpty());
      assertEquals(new DataVersionMarker.Marker("0.3.0", SupportedDataVersions.current()), read());
      var health = new ConditionStore();
      NewerDataNotice.publish(notice, health, new HealthEventChangeRegistry(),
          new Source("head", "test", Optional.empty()), Clock.systemUTC());
      assertTrue(health.find(LifecycleReasonCode.DATA_NEWER_BUILD.code(), "data").isEmpty());
    }
  }

  @Test
  void corruptMarkerIsRewrittenWithoutBlockingBootOrProducingNewerDataNotice() throws Exception {
    for (String broken : new String[] {"{", "null", "{}", "{\"appVersion\":\"garbage\",\"storeVersions\":{}}",
        "{\"appVersion\":\"1.0.0\",\"storeVersions\":{\"jobs-db\":-1}}",
        "{\"appVersion\":\"1.0.0\",\"storeVersions\":{\"jobs-db\":1.5}}"}) {
      Files.writeString(dir.resolve("data-version.json"), broken);
      assertTrue(assertDoesNotThrow(() -> DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current())).isEmpty());
      assertEquals(new DataVersionMarker.Marker("0.3.0", SupportedDataVersions.current()), read());
    }
  }

  @Test
  void unreadableAndUnwritableMarkerDoesNotBlockBoot() throws Exception {
    Files.createDirectory(dir.resolve("data-version.json"));
    assertTrue(assertDoesNotThrow(() -> DataVersionMarker.recordBoot(dir, "0.3.0", SupportedDataVersions.current())).isEmpty());
  }

  @Test
  void versionComparisonUsesNumericSemverAndPrereleasePrecedence() {
    assertTrue(DataVersionMarker.compareVersions("0.10.0", "0.9.0") > 0);
    assertTrue(DataVersionMarker.compareVersions("1.0.0", "1.0.0-rc.9") > 0);
    assertTrue(DataVersionMarker.compareVersions("1.0.0-rc.10", "1.0.0-rc.9") > 0);
    assertTrue(DataVersionMarker.compareVersions("1.0.0-1", "1.0.0-alpha") < 0);
    assertEquals(0, DataVersionMarker.compareVersions("1.0.0+new", "1.0.0+old"));
  }

  private static Path repositoryRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.exists(path.resolve("governance/store-recoverability.v1.json"))) path = path.getParent();
    return java.util.Objects.requireNonNull(path);
  }
}
