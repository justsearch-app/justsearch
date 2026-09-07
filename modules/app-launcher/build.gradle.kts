import org.gradle.api.GradleException
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.jvm.application.tasks.CreateStartScripts
import java.util.Locale

plugins {
  `java-library`
  application
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

application {
  // Bootstrap to ensure data-dir properties exist before SLF4J/logback initialization.
  mainClass.set("io.justsearch.applauncher.LauncherBootstrap")
}

distributions {
  main {
    contents {
      from(rootProject.layout.projectDirectory.dir("config")) {
        into("config")
      }
    }
  }
}

// Vector API (jdk.incubator.vector) intentionally omitted — see tempdoc 269 §D4a
val commonJvmArgs = listOf("--sun-misc-unsafe-memory-access=warn")

dependencies {
  implementation(libs.slf4j.api)  // Internal logging only
  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.logstash.logback.encoder)
  runtimeOnly(libs.opentelemetry.logback.mdc)
  implementation(project(":modules:configuration"))
  implementation(project(":modules:app-api"))
  runtimeOnly(project(":modules:indexer-worker"))
  implementation(project(":modules:telemetry"))
  implementation(project(":modules:app-services"))
  // Tempdoc 417 Phase 2d/2e: needed at compile-time for catalog DEFINITIONS lookup in
  // LauncherEnvironment's TelemetryFactory. app-services has implementation deps on these,
  // so the classes aren't on app-launcher's compile classpath transitively.
  implementation(project(":modules:app-agent"))
  implementation(project(":modules:app-config"))
  implementation(project(":modules:app-util"))
  runtimeOnly(project(":modules:ui"))
  // Lane F stage A item A1: the Engine composition root. `runtimeOnly` on purpose — it puts
  // app-engine's bytecode on the ArchUnit test classpath so `@AnalyzeClasses(packages =
  // "io.justsearch")` in LayeringEnforcementTest/BoundaryRulesTest sees it, WITHOUT giving the
  // launcher a compile path into it (BoundaryRulesTest#launcherMayOnlyDependOnAppApi forbids
  // exactly that).
  runtimeOnly(project(":modules:app-engine"))
  // JavaFX UI dependencies removed - using web UI instead
  implementation(libs.jackson.core)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  runtimeOnly(libs.lucene.analysis.common)
  runtimeOnly(libs.lucene.analysis.icu)
  runtimeOnly(libs.lucene.core)
}

// DAP: transitive test dependencies declared directly.
// Must live in a top-level dependencies block (testImplementation does not
// exist inside a JvmTestSuite dependencies {} block).
dependencies {
  testImplementation(libs.opentelemetry.api)
  testImplementation(project(":modules:gpu-bridge"))
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-core") {
      useVersion("3.1.0")
      because("Lock convergence for launcher classpaths")
    }
    if (requested.group == "tools.jackson.core" && requested.name == "jackson-databind") {
      useVersion("3.1.0")
      because("Lock convergence for launcher classpaths")
    }
    if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
      useVersion("2.21")
      because("Lock convergence for launcher classpaths (annotations shared between Jackson 2.x/3.x)")
    }
    if (requested.group == "tools.jackson.dataformat" && requested.name == "jackson-dataformat-yaml") {
      useVersion("3.1.0")
      because("Lock convergence for launcher classpaths")
    }
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for launcher classpaths")
    }
  }
}

tasks.withType<JavaExec>().configureEach {
  jvmArgs(commonJvmArgs)
}

tasks.withType<Test>().configureEach {
  jvmArgs(commonJvmArgs)
  // Enable ByteBuddy experimental mode for JDK 25 support (until ByteBuddy 1.17.5+)
  jvmArgs("-Dnet.bytebuddy.experimental=true")
}

tasks.withType<CreateStartScripts>().configureEach {
  val os = OperatingSystem.current()
  val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
  val nativeDir =
      when {
        os.isWindows -> "windows-x86_64/cpu"
        os.isMacOsX && arch.contains("aarch64") -> "macos-aarch64/cpu"
        os.isMacOsX -> "macos-x86_64/cpu"
        else -> "linux-x86_64/cpu"
      }
  val libName = when {
    os.isWindows -> "llama.dll"
    os.isMacOsX -> "libllama.dylib"
    else -> "libllama.so"
  }
  val pathHint =
      if (os.isWindows) "%APP_HOME%\\\\native\\\\$nativeDir\\\\$libName"
      else "\$APP_HOME/native/$nativeDir/$libName"
  defaultJvmOpts = (defaultJvmOpts ?: listOf()) + "-Dllama.lib.path=$pathHint"

  // Reduce Windows command length by collapsing the classpath to a single wildcard entry.
  doLast {
    if (os.isWindows) {
      val windowsText = windowsScript.readText()
      val collapsed =
          windowsText.replace(
              Regex("(?m)^set CLASSPATH=.*$", RegexOption.MULTILINE),
              "set CLASSPATH=%APP_HOME%\\\\lib\\\\*")
      windowsScript.writeText(collapsed)
    } else {
      val unixText = unixScript.readText()
      // Use the lambda form of Regex.replace: the string-replacement overload treats '$' as a
      // group reference, so "$APP_HOME" -> "$A" throws "Illegal group reference" (only hit when
      // building on Linux — the Windows branch has no '$'). The lambda returns the replacement
      // literally. Tempdoc 668 (Build-lane Linux migration).
      val collapsed = Regex("(?m)^CLASSPATH=.*$").replace(unixText) { "CLASSPATH=\"\$APP_HOME/lib/*\"" }
      unixScript.writeText(collapsed)
    }
  }
}

tasks.withType<JacocoCoverageVerification>().configureEach {
  dependsOn(tasks.named("startScripts"))
}

// Launcher CLI wiring is exercised by smoke/simulate/integration flows; skip per-class coverage
// enforcement on those glue classes so coverage signals focus on the services they call.
val launcherCoverageExcludes = listOf(
    "io/justsearch/applauncher/Launcher.class",
    "io/justsearch/applauncher/Launcher$*.class",
    "io/justsearch/applauncher/LauncherCommands.class",
    "io/justsearch/applauncher/LauncherCommands$*.class"
)
tasks.withType<JacocoCoverageVerification>().configureEach {
  classDirectories.setFrom(
      sourceSets["main"].output.asFileTree.matching {
        exclude(launcherCoverageExcludes)
      })
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation(libs.archunit.junit5)
        implementation(libs.lucene.core)
        implementation(libs.mockito.core)
        // OrtSessionApiGuardrailsTest was deleted with tempdoc 397 §14.22
        // Phase A — the ort-common testImplementation is no longer used.
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
  }
}

tasks.named("check") { dependsOn(tasks.named("integrationTest")) }

// `pmdIntegrationTest` fails to serialize its `Pmd.classpath` (UnionFileCollection) into
// Gradle's configuration cache at v9.1.0, blocking `./gradlew build -x test` from worktrees
// with a fresh cache. The task never executes (`pmd.includeTests` gates it off in
// JvmBaseConventionsPlugin) and no workflow names it, so this marker exists purely to keep
// local CC-enabled builds functional. Same precedent as the AOT-training tasks in
// `modules/ui/build.gradle.kts`.
tasks.matching { it.name == "pmdIntegrationTest" }.configureEach {
  notCompatibleWithConfigurationCache(
      "Pmd.classpath UnionFileCollection serialization fails at Gradle 9.1.0")
}

