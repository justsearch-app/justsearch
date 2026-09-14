/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class SettingsWitnessReadTest {
  @TempDir Path directory;

  @Test
  void getProjectsSettingsAndWitnessFromOneInspection() {
    var settings = new UiSettings();
    settings.setTheme("dark");
    var witness = new SettingsWitness(7, OperationKeys.generate(Clock.systemUTC()));
    var store = mock(UiSettingsStore.class);
    when(store.mode()).thenReturn(UiSettingsStore.PersistenceMode.READ_WRITE);
    when(store.inspect()).thenReturn(new UiSettingsStore.Snapshot(settings, witness));
    var later = new UiSettings();
    later.setTheme("light");
    when(store.load()).thenReturn(later);
    var context = context();
    new SettingsController(store, directory, null).handleGetSettingsV2(context);
    var response = ArgumentCaptor.forClass(SettingsV2.class);
    verify(context).json(response.capture());
    assertEquals("dark", response.getValue().ui().theme());
    assertEquals(witness, response.getValue().witness());
    assertEquals(java.util.List.of(directory.toString()), response.getValue().indexPaths());
    verify(store, times(1)).inspect();
    verify(store, never()).load();
    verify(store, never()).save(any());
    assertNull(response.getValue().operationKey(), "a read cannot mint an attempt identity");
  }

  @Test
  void corruptReadCannotQuarantineOrInventAnInitialWitness() throws Exception {
    var file = directory.resolve("settings.json");
    Files.writeString(file, "{broken");
    byte[] before = Files.readAllBytes(file);
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, file);
    var context = context();
    new SettingsController(store, directory, null).handleGetSettingsV2(context);
    var response = ArgumentCaptor.forClass(Object.class);
    verify(context).status(503);
    verify(context).json(response.capture());
    var body = (Map<?, ?>) response.getValue();
    assertEquals("SETTINGS_RECOVERY_REQUIRED", body.get("errorCode"));
    assertFalse(body.containsKey("witness"));
    assertArrayEquals(before, Files.readAllBytes(file));
    try (var files = Files.list(directory)) { assertEquals(1L, files.count()); }
  }

  @Test
  void absentWritableSettingsHaveTheActualInitialWitnessWithoutPersistingDefaultPath() {
    var store = new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, directory.resolve("settings.json"));
    var context = context();
    new SettingsController(store, directory, null).handleGetSettingsV2(context);
    var response = ArgumentCaptor.forClass(SettingsV2.class);
    verify(context).json(response.capture());
    assertEquals(new SettingsWitness(0, null), response.getValue().witness());
    assertFalse(Files.exists(store.settingsPath()));
  }

  private static Context context() {
    var context = mock(Context.class);
    when(context.json(any())).thenReturn(context);
    when(context.status(anyInt())).thenReturn(context);
    return context;
  }
}
