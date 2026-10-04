/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigChangedEvent;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C2-6: physical settings and config publication have one runtime owner. */
@AnalyzeClasses(packages = "io.justsearch", importOptions = {
    ImportOption.DoNotIncludeTests.class, SettingsWriterArchitectureTest.NoTestFixtures.class})
class SettingsWriterArchitectureTest {
  private static final String OWNER = "io.justsearch.app.services.settings.SettingsCommitCoordinator";
  private static final String BOOT = "io.justsearch.ui.HeadlessApp";

  @ArchTest
  static final ArchRule SETTINGS_PUBLICATION = classes().should(new ArchCondition<JavaClass>(
      "publish settings only through their accepted owner, with the exact pre-inference boot refresh") {
    @Override public void check(JavaClass item, ConditionEvents events) {
      for (var call : item.getCodeUnitAccessesFromSelf()) {
        String origin = item.getFullName();
        String method = call.getOrigin().getName();
        boolean owner = origin.equals(OWNER) || origin.startsWith(OWNER + "$");
        boolean physical = call.getTargetOwner().isAssignableTo(UiSettingsStore.class)
            && Set.of("prepare", "prepareExact", "replacePrepared", "notifyRecoveryCleared", "save")
                .contains(call.getName());
        boolean config = call.getTargetOwner().isAssignableTo(ConfigStore.class)
            && Set.of("update", "swap", "notifyListeners").contains(call.getName());
        boolean rebuild = call.getTargetOwner().isAssignableTo(ConfigStoreRebuilder.class)
            && call.getName().equals("rebuild");
        boolean bootEntry = call.getTargetOwner().getFullName().equals(BOOT)
            && call.getName().equals("rebuildAfterPostBuildWrites");
        boolean configPrimitive = origin.equals(ConfigStore.class.getName()) && method.equals("update");
        boolean bootRebuilder = origin.equals(ConfigStoreRebuilder.class.getName()) && method.equals("rebuild");
        boolean bootRefresh = origin.equals(BOOT) && method.equals("rebuildAfterPostBuildWrites");
        boolean bootAssembly = origin.equals(BOOT) && method.equals("resolveConfig");
        if ((physical && !owner) || (config && !owner && !configPrimitive && !bootRebuilder)
            || (rebuild && !bootRefresh) || (bootEntry && !bootAssembly)) {
          events.add(SimpleConditionEvent.violated(item, origin + " bypasses settings publication via "
              + call.getTarget().getFullName() + " at " + call.getSourceCodeLocation()));
        }
      }
    }
  });

  @Test
  void unrecordedSaveApiIsAbsent() {
    assertThrows(NoSuchMethodException.class, () -> UiSettingsStore.class.getDeclaredMethod("save", UiSettings.class));
  }

  @Test
  void guardRejectsEnteringTheBootWrapperOutsideConfigurationAssembly() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedSettingsBootRefresh.class);
    var result = SETTINGS_PUBLICATION.evaluate(imported);
    assertTrue(result.getFailureReport().getDetails().stream().anyMatch(line ->
        line.contains("UnauthorizedSettingsBootRefresh") && line.contains("rebuildAfterPostBuildWrites(")));
  }

  @Test
  void guardRejectsEveryPhysicalAndPublicationBypassIncludingMethodReferences() {
    var imported = new ClassFileImporter().importClasses(UnauthorizedWriter.class, UiSettingsStore.class,
        ConfigStore.class, ConfigStoreRebuilder.class);
    var result = SETTINGS_PUBLICATION.evaluate(imported);
    assertTrue(result.hasViolation());
    var details = result.getFailureReport().getDetails();
    for (String target : new String[] {"prepare(", "prepareExact(", "replacePrepared(", "notifyRecoveryCleared(",
        "update(", "swap(", "notifyListeners(", "rebuild("}) {
      assertTrue(details.stream().anyMatch(line -> line.contains("UnauthorizedWriter") && line.contains(target)),
          () -> "Missing negative control for " + target + ": " + details);
    }
  }

  public static final class NoTestFixtures implements ImportOption {
    @Override public boolean includes(com.tngtech.archunit.core.importer.Location location) {
      String uri = location.asURI().toString();
      return !uri.contains("/testFixtures/") && !uri.contains("-test-fixtures.jar");
    }
  }

  static final class UnauthorizedSettingsBootRefresh {
    void refresh(ConfigStore store, UiSettings settings) {
      HeadlessApp.rebuildAfterPostBuildWrites(store, settings);
    }
  }

  static final class UnauthorizedWriter {
    void prepare(UiSettingsStore store, UiSettings settings) {
      store.prepare(settings, new SettingsWitness(0, null));
    }
    void prepareExact(UiSettingsStore store, UiSettings settings) {
      store.prepareExact(settings, new SettingsWitness(0, null));
    }
    void replace(UiSettingsStore store, UiSettingsStore.PreparedSettings prepared) throws IOException {
      store.replacePrepared(prepared);
    }
    Runnable notification(UiSettingsStore store) { return store::notifyRecoveryCleared; }
    void update(ConfigStore store, ResolvedConfig config) { store.update(config); }
    void swap(ConfigStore store, ResolvedConfig config) { store.swap(config); }
    void notify(ConfigStore store, ConfigChangedEvent event) { store.notifyListeners(event); }
    void rebuildAfterPostBuildWrites(ConfigStore store, UiSettings settings) {
      ConfigStoreRebuilder.rebuild(store, settings);
    }
  }
}
