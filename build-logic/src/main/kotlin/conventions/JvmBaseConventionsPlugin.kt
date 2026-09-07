package conventions

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

class JvmBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Base JVM plugins and repo-wide conventions
    project.plugins.apply("java-library")
    project.plugins.apply("pmd")
    project.plugins.apply("com.diffplug.spotless")

    // Conventions from included build
    project.plugins.apply("conventions.archiving-reproducible")
    project.plugins.apply("conventions.coverage")
    project.plugins.apply("conventions.errorprone")
    project.plugins.apply("conventions.spotbugs")

    // Serialize test tasks across parallel project builds to avoid memory pressure.
    // Default: 3 concurrent test JVMs locally (32GB RAM, 12-core 12700K), 1 in CI (conservative).
    // Bumped 2 -> 3 on 2026-04-19 after E-J-N4 measurement (tempdoc 390): test wall
    // 96s -> 75.9s = -20.1%, non-overlapping IQRs. Thermally safe (Gradle build measured
    // 86 C max vs 100 C TjMax, CPU Package Power p95 148W of 190W PL2).
    // CI value stays 1 — this was EMPIRICALLY VALIDATED, not conservatism. Tempdoc 668 hypothesised
    // that raising it to 2 would parallelise the critical-path unit lane; the hosted A/B refuted it:
    // app-ui wall-clock was 597s at 2 vs ~598s at 1 (zero gain — the 4-vCPU runner is already
    // CPU-saturated by one test task, so a second just time-slices), AND the contention blew a
    // timing-sensitive test's timebox (worker-services ProcessExtractionSandboxTest, now
    // PersistentExtractionSandboxTest ->
    // ExtractionTimeoutException -> lane FAILED). So on a 4-vCPU runner, cross-module test
    // parallelism buys nothing and breaks timing-sensitive tests. Do not raise it without moving to
    // a runner with more vCPUs. Local stays 3 (measurement-backed on a 12-core box, tempdoc 390).
    // Override: -PtestParallelism=N
    val testParallelism = project.providers.gradleProperty("testParallelism")
        .map { it.toInt() }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { if (it.isNotBlank() && !it.equals("false", ignoreCase = true)) 1 else 3 }
                .orElse(3)
        )

    // registerIfAbsent is idempotent — safe to call from each project's plugin application.
    val testGate = project.gradle.sharedServices.registerIfAbsent(
        "testGate", TestGateService::class.java
    ) { maxParallelUsages.set(testParallelism) }

    // Jackson 3.x requires jackson-annotations 2.21+. Force across all modules to prevent
    // transitive deps (e.g., Javalin) pulling in older 2.18.x annotations.
    project.configurations.configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
          useVersion("2.21")
          because("Jackson 3.1.0 requires jackson-annotations 2.21+")
        }
      }
    }

    // Toolchain: Java 25 LTS and JUnit Platform across all tests
    project.extensions.findByType(JavaPluginExtension::class.java)?.let { javaExt ->
      javaExt.toolchain.languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25))

      val includeExperiment = project.findProperty("includeExperiment")?.toString()?.toBoolean() ?: false
      val includeStress = project.findProperty("includeStress")?.toString()?.toBoolean() ?: false
      // Tempdoc 668 Windows→Linux migration (option B): tests tagged "windows" exercise
      // genuinely Windows-specific product behaviour (single-instance lock, job objects,
      // power status). They are excluded on NON-Windows hosts (the Linux CI lanes) so the bulk
      // of CI runs on the ~2.8x faster Linux runners; the dedicated windows-native lane runs
      // ONLY the tagged tests via -PwindowsOnly=true (includeTags "windows"). On a Windows host
      // (local dev, or that lane's runner) they run normally in the default flow — they can only
      // be tested on Windows, so excluding them there would silently drop their local coverage
      // (and make `--tests "*WindowsFooTest*"` fail with "no tests found").
      val windowsOnly = project.findProperty("windowsOnly")?.toString()?.toBoolean() ?: false
      val isWindowsHost =
          System.getProperty("os.name").lowercase(java.util.Locale.ROOT).contains("windows")
      project.tasks.withType(Test::class.java).configureEach {
        usesService(testGate)
        useJUnitPlatform {
          if (windowsOnly) {
            includeTags("windows")
          } else {
            val excluded = mutableListOf<String>()
            // Skip the Windows-specific tests only where they cannot run (non-Windows CI).
            if (!isWindowsHost) {
              excluded.add("windows")
            }
            // Tempdoc 710 Move 6 (obs:spladeindexcontentcrashharnesstest): "evidence"/"experiment"
            // -tagged tests (sweep benchmarks, crash forensics harnesses) are excluded from every
            // module's default `test` task — including a bare `--tests ClassName` selection, which
            // silently reports "No tests found for given includes" with no hint why. Opt in with
            // -PincludeExperiment=true (unlocks BOTH tags despite the flag's singular name).
            if (!includeExperiment) {
              excluded.add("evidence")
              excluded.add("experiment")
            }
            // Tempdoc 397 §14.21 R3: stress tests (e.g.,
            // OrtSessionManagerConcurrentStressTest) are designed for nightly / merge-gate
            // runs, not fast inner loops. Opt in with -PincludeStress=true to run them.
            if (!includeStress) {
              excluded.add("stress")
            }
            if (excluded.isNotEmpty()) {
              excludeTags(*excluded.toTypedArray())
            }
          }
        }
        maxHeapSize = "384m"
        jvmArgs("--sun-misc-unsafe-memory-access=warn", "--enable-native-access=ALL-UNNAMED",
            "-XX:+UseCompactObjectHeaders")
        systemProperty("junit.jupiter.execution.timeout.default", "30s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        // Retry flaky tests in CI; surface them with failOnPassedAfterRetry.
        // The retry extension is registered by the Develocity plugin (settings.gradle.kts).
        // Accessed via reflection because the type is shaded inside the Develocity plugin.
        // Develocity 4.x: develocity.testRetry on the Test task; 3.x: retry (deprecated).
        val retryExt = run {
          extensions.findByName("develocity")?.let { devExt ->
            try { devExt.javaClass.getMethod("getTestRetry").invoke(devExt) } catch (_: Exception) { null }
          } ?: extensions.findByName("retry")
        }
        retryExt?.let { ext ->
          try {
            val isCi = project.providers.environmentVariable("CI")
                .map { it.isNotBlank() }.orElse(false).get()
            val maxRetriesProp = ext.javaClass.getMethod("getMaxRetries").invoke(ext)
            val failOnPassedProp = ext.javaClass.getMethod("getFailOnPassedAfterRetry").invoke(ext)
            @Suppress("UNCHECKED_CAST")
            (maxRetriesProp as org.gradle.api.provider.Property<Int>).set(if (isCi) 2 else 0)
            @Suppress("UNCHECKED_CAST")
            (failOnPassedProp as org.gradle.api.provider.Property<Boolean>).set(true)
          } catch (e: ReflectiveOperationException) {
            project.logger.warn("Could not configure test retry via reflection: ${e.message}")
          } catch (e: ClassCastException) {
            project.logger.warn("Test retry extension has unexpected type: ${e.message}")
          }
        }
        // Generate JaCoCo report after tests
        finalizedBy(project.tasks.matching { it.name == "jacocoTestReport" })
      }

      project.tasks.withType(JavaExec::class.java).configureEach {
        jvmArgs("--sun-misc-unsafe-memory-access=warn", "--enable-native-access=ALL-UNNAMED")
      }
    }

    // Spotless: whitespace/newline hygiene for Java sources. JustSearch deliberately does NOT
    // auto-format Java — google-java-format was removed, not deferred (tempdoc 729, owner
    // decision 2026-07-15). Do not re-add a formatter without reading that doc: the benefits
    // (ending style debates, onboarding, reviewer load) are ~zero in an agent-authored,
    // single-reviewer repo, and enabling one costs a reformat of every Java file.
    //
    // This block is the ONLY thing enforcing trailing-whitespace/final-newline hygiene — the
    // repo has no .editorconfig, and agents writing through file tools never pass through an
    // editor. It is the cheap slice of the one benefit that mattered (diff noise).
    //
    // The task name `spotlessJavaSources` is load-bearing: build.gradle.kts wires it into a
    // mustRunAfter set. Renaming this format block silently breaks that ordering.
    project.extensions.configure(SpotlessExtension::class.java) {
      format("javaSources") {
        target("src/**/*.java")
        trimTrailingWhitespace()
        endWithNewline()
      }
    }

    // PMD configuration (light ruleset)
    project.extensions.configure(PmdExtension::class.java) {
      toolVersion = "7.16.0"
      isConsoleOutput = true
      isIgnoreFailures = false
      ruleSets = emptyList()
      ruleSetFiles = project.objects.fileCollection()
          .from(project.rootProject.layout.projectDirectory.file("config/pmd/ruleset.xml"))
    }

    // `pmdMain` runs in `check`/`build` everywhere (tempdoc 930 §22.2 follow-up 2). It used to
    // default to skipped off-CI, and no workflow ran `check`, so the ruleset — including the
    // `CommentContent` parked-marker ban that succeeded the retired `todo-fixme` kernel gate —
    // bit only when someone passed `-PskipPmd=false` by hand. Measured cost of the flip: ~23s
    // wall for all 30 `pmdMain` tasks with classes already compiled (`--max-workers=1`, cache
    // off, 2026-09-05), so there is nothing left to buy by defaulting it off.
    //   -PskipPmd=true    escape hatch for a fast local iteration loop; CI never sets it.
    val skipPmd = project.providers.gradleProperty("skipPmd")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

    // Java TEST sources are covered too (930 §22.2 follow-up 10). `pmd.includeTests` is gone:
    // every source set PMD sees — `test`, `integrationTest`, `systemTest`, `determinismTest`,
    // `testFixtures` — runs in `check`/`build`, under a ruleset of its own. (`soakTest` was in
    // this list until lane F stage A item A12 removed the only source set that had one.)
    // `ruleset-tests.xml` is `ruleset.xml` minus two rules whose premise does not hold in a test
    // (`SystemPrintln`, `NonThreadSafeSingleton`); the file states why. 569 pre-existing
    // violations were cleared in the same change. Measured cost: see the CI step comment.
    val testRuleSetFiles = project.objects.fileCollection()
        .from(project.rootProject.layout.projectDirectory.file("config/pmd/ruleset-tests.xml"))

    // Gradle's PMD plugin already wires every source set's task into `check`; the explicit
    // `dependsOn` below survives from when this plugin narrowed that to `pmdMain`.
    project.tasks.withType(Pmd::class.java).configureEach {
      onlyIf { !skipPmd.get() }
      if (name != "pmdMain") {
        ruleSetFiles = testRuleSetFiles
      }
    }
    project.tasks.named("check") {
      dependsOn(project.tasks.matching { it.name == "spotlessCheck" })
      dependsOn(project.tasks.withType(Pmd::class.java))
    }

    // Skip distribution archives in local builds. Override with:
    //   -PskipDist=false   (force enable locally)
    //   CI=true env var     (auto-enabled in CI)
    // distTar is unconditionally disabled (Windows project, never used).
    // distZip is skipped locally (~68s across 4 modules) but enabled in CI.
    // Explicit tasks like assembleDesktopDist still work via -PskipDist=false.
    val skipDist = project.providers.gradleProperty("skipDist")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(
            project.providers.environmentVariable("CI")
                .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                .orElse(true)
        )

    project.pluginManager.withPlugin("application") {
      project.tasks.named("distTar") {
        enabled = false
      }
      project.tasks.named("distZip") {
        onlyIf { !skipDist.get() }
      }
    }

    // Spotless 8.1.0+ has full configuration cache support (issue #987 resolved)
    project.tasks.withType(SpotlessTask::class.java).configureEach {
      // Spotless tasks can write into source trees; never restore outputs from the Gradle build cache.
      outputs.cacheIf { false }
    }
  }
}
