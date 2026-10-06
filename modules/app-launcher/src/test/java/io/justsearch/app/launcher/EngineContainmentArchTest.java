package io.justsearch.app.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Spec F §1.1: the Engine joins its own job before it can start any process, and the job is created
 * in one place and never terminated from Java.
 *
 * <ul>
 *   <li>The first call {@code HeadlessApp.main} makes is {@code EngineContainment.installAtBoot}
 *       (bytecode, ArchUnit). Anything earlier could start a process outside the job: {@code
 *       resolveConfig} can run {@code nvidia-smi}.
 *   <li>Only {@code WindowsEngineJob} and {@code WindowsParserContainment} create a job object
 *       (source: FFM binds Windows functions by name, which bytecode analysis cannot see).
 *   <li>No Java code binds {@code TerminateJobObject}: on the Engine's own job it would end the
 *       Engine. In the shell only {@code containment.rs} may call it.
 * </ul>
 */
class EngineContainmentArchTest {
  private static final String INSTALL_AT_BOOT =
      "io.justsearch.app.api.runtime.containment.EngineContainment.installAtBoot";
  private static final Set<String> JOB_CREATORS =
      Set.of("WindowsEngineJob.java", "WindowsParserContainment.java");

  @Test
  void engineMainJoinsItsJobBeforeAnyOtherCall() throws ClassNotFoundException {
    // ui is on this module's runtime classpath only; load without initialising HeadlessApp.
    Class<?> headless = Class.forName("io.justsearch.ui.HeadlessApp", false,
        EngineContainmentArchTest.class.getClassLoader());
    JavaClass imported = new ClassFileImporter().importClass(headless);
    JavaMethod main = imported.getMethod("main", String[].class);
    List<JavaCall<?>> calls = new ArrayList<>(main.getCallsFromSelf());
    calls.sort(Comparator.comparingInt(JavaCall::getLineNumber));
    assertFalse(calls.isEmpty(), "HeadlessApp.main makes calls");
    JavaCall<?> first = calls.get(0);
    assertEquals(INSTALL_AT_BOOT, first.getTargetOwner().getFullName() + "." + first.getName(),
        "the first call of HeadlessApp.main, at " + first.getSourceCodeLocation());
  }

  @Test
  void onlyTheTwoContainmentClassesCreateJobsAndJavaNeverTerminatesOne() throws IOException {
    Path root = repoRoot();
    List<String> creators = new ArrayList<>();
    List<String> terminators = new ArrayList<>();
    try (Stream<Path> modules = Files.list(root.resolve("modules"))) {
      for (Path module : modules.toList()) {
        Path main = module.resolve("src/main/java");
        if (!Files.isDirectory(main)) continue;
        try (Stream<Path> files = Files.walk(main)) {
          for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
            String text = Files.readString(file);
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (text.contains("\"CreateJobObjectW\"")
                && !JOB_CREATORS.contains(file.getFileName().toString())) {
              creators.add(rel);
            }
            if (text.contains("\"TerminateJobObject\"")) terminators.add(rel);
          }
        }
      }
    }
    Path shell = root.resolve("modules/shell/src-tauri/src");
    if (Files.isDirectory(shell)) {
      try (Stream<Path> files = Files.walk(shell)) {
        for (Path file : files.filter(p -> p.toString().endsWith(".rs")).toList()) {
          if (file.getFileName().toString().equals("containment.rs")) continue;
          for (String line : Files.readAllLines(file)) {
            String code = line.strip();
            if (!code.startsWith("//") && code.contains("TerminateJobObject(")) {
              terminators.add(root.relativize(file).toString().replace('\\', '/'));
              break;
            }
          }
        }
      }
    }
    assertEquals(List.of(), creators, "job objects are created only by the Engine and parser jobs");
    assertEquals(List.of(), terminators,
        "TerminateJobObject is bound by no Java code and called only in containment.rs");
  }

  private static Path repoRoot() {
    Path root = Path.of("").toAbsolutePath();
    while (!Files.isDirectory(root.resolve("governance"))) root = root.getParent();
    return root;
  }
}
