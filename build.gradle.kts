import conventions.CopyLauncherDist
import java.util.Locale
import org.gradle.kotlin.dsl.register
plugins {
  id("base")
  jacoco
  pmd
  alias(libs.plugins.spotless)
  alias(libs.plugins.licenseReport)
  alias(libs.plugins.dependencyAnalysis)
  alias(libs.plugins.versions)
  id("conventions.archiving-reproducible") apply false
  id("conventions.coverage") apply false
  id("conventions.locking")
  id("conventions.build-attribution")
  id("org.openrewrite.rewrite") version "7.39.0"
}

// Note: PMD toolVersion is configured in JvmBaseConventionsPlugin (currently 7.16.0).
// The root `pmd` block above is applied because root build files were once
// PMD-scanned, but root has no Java sources and the root extension has no
// effect — version drift here was tempdoc 516 Slice 0 cleanup.

rewrite {
  activeRecipe(
    "org.openrewrite.java.jackson.UpgradeJackson_2_3",
    "org.openrewrite.java.logging.slf4j.CompleteExceptionLogging",
  )
}


dependencies {
  rewrite("org.openrewrite.recipe:rewrite-jackson:1.19.0")
  rewrite("org.openrewrite.recipe:rewrite-logging-frameworks:3.26.0")
}

jacoco {
  toolVersion = "0.8.14"
}

// Filter out unstable versions (RCs, alphas, betas) from dependency update reports
tasks.withType<com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask> {
  rejectVersionIf {
    val dominated = listOf("alpha", "beta", "rc", "cr", "m", "preview", "eap", "dev")
        .any { candidate.version.lowercase().contains(it) }
    val currentUnstable = listOf("alpha", "beta", "rc", "cr", "m", "preview", "eap", "dev")
        .any { currentVersion.lowercase().contains(it) }
    dominated && !currentUnstable
  }
}

// Apply DAGP to all Java subprojects for dependency analysis
subprojects {
  pluginManager.withPlugin("java") {
    apply(plugin = "com.autonomousapps.dependency-analysis")
  }
}

// DAGP configuration - detect unused dependencies via bytecode analysis
dependencyAnalysis {
  issues {
    all {
      onUnusedDependencies {
        severity("fail")
        // JUnit 5 aggregator is correct to declare; individual modules pulled transitively
        exclude("org.junit.jupiter:junit-jupiter")
        exclude("org.junit.jupiter:junit-jupiter-api")
        // ArchUnit aggregator includes junit5 extension
        exclude("com.tngtech.archunit:archunit-junit5")
        // worker-core uses io.grpc.Context/Metadata/ServerInterceptor from grpc-api (transitive of grpc-stub)
        exclude("io.grpc:grpc-stub")
        // RecordBuilder annotation processor generates *Builder classes from @RecordBuilder
        // annotations on app-api records. The plugin's bytecode analysis cannot detect
        // annotation-processor usage (the processor generates code at compile time but
        // leaves no bytecode reference in the original class). False positive.
        exclude("io.soabase.record-builder:record-builder-processor")
      }
      onUsedTransitiveDependencies {
        severity("fail")
        // gRPC-api comes through grpc-stub intentionally
        exclude("io.grpc:grpc-api")
        // Jackson annotations come through jackson-databind
        exclude("com.fasterxml.jackson.core:jackson-annotations")
        exclude("com.fasterxml.jackson.core:jackson-core")
        // OpenTelemetry context comes through opentelemetry-api
        exclude("io.opentelemetry:opentelemetry-context")
        // ArchUnit internals come through archunit-junit5
        exclude("com.tngtech.archunit:archunit-junit5-api")
        exclude("com.tngtech.archunit:archunit")
        // Protobuf comes through grpc-protobuf
        exclude("com.google.protobuf:protobuf-java")
      }
      onIncorrectConfiguration {
        severity("fail")
        // gRPC netty uses NettyServerBuilder/NettyChannelBuilder at compile time
        exclude("io.grpc:grpc-netty-shaded")
      }
      onUnusedAnnotationProcessors {
        severity("fail")
        // RecordBuilder annotation processor: SOURCE-retention; the processor
        // generates *Builder classes from @RecordBuilder annotations, but
        // bytecode analysis cannot see that the processor was used because
        // the annotations don't survive to the .class files. False positive.
        // (Same exclude already exists for onUnusedDependencies above; this
        // rule is separate and needs its own entry.)
        exclude("io.soabase.record-builder:record-builder-processor")
      }
    }
  }
}

allprojects {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      if (requested.group == "com.google.code.gson" && requested.name == "gson") {
        useVersion("2.13.2")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
        useVersion("3.20.0")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "com.google.errorprone" && requested.name == "error_prone_annotations") {
        useVersion("2.47.0")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.checkerframework" && requested.name == "checker-qual") {
        useVersion("3.49.5")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "commons-io" && requested.name == "commons-io") {
        useVersion("2.21.0")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "commons-logging" && requested.name == "commons-logging") {
        useVersion("1.3.5")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "com.google.guava" && requested.name == "failureaccess") {
        useVersion("1.0.3")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "com.google.j2objc" && requested.name == "j2objc-annotations") {
        useVersion("3.1")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.apache.commons" && requested.name == "commons-compress") {
        useVersion("1.28.0")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (
          requested.group == "org.apache.pdfbox" &&
              requested.name in setOf("pdfbox", "pdfbox-io", "fontbox")) {
        useVersion("3.0.6")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "commons-codec" && requested.name == "commons-codec") {
        useVersion("1.21.0")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.pcollections" && requested.name == "pcollections") {
        useVersion("4.0.2")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.slf4j") {
        useVersion("2.0.17")
        because("Lock convergence: group releases in lockstep")
      }
      if (requested.group == "net.java.dev.jna" && requested.name == "jna") {
        useVersion("5.18.1")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "com.google.guava" && requested.name == "guava") {
        useVersion("33.5.0-jre")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "com.google.protobuf" && requested.name == "protobuf-java") {
        useVersion("4.33.5")
        because("Lock convergence for duplicate-coordinate reduction")
      }
      if (requested.group == "org.ow2.asm") {
        useVersion("9.9.1")
        because("Lock convergence: group releases in lockstep")
      }
      if (requested.group == "org.apache.logging.log4j") {
        useVersion("2.24.3")
        because("Lock convergence: group releases in lockstep")
      }
    }
  }
}

// Dependency locking configured via convention; lock mode defaults apply


// SSOT tasks — Java-based validation and generation (no Node.js required)
val ssotRootDir = layout.projectDirectory.dir("SSOT")
val ssotToolsCp: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
  attributes {
    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
  }
}
dependencies { ssotToolsCp(project(":modules:ssot-tools")) }

fun ssotJavaExec(taskName: String, mainClassName: String, rootArg: File = ssotRootDir.asFile): TaskProvider<JavaExec> =
    tasks.register<JavaExec>(taskName) {
      classpath = ssotToolsCp
      mainClass.set(mainClassName)
      args(rootArg.absolutePath)
    }

val ssotValidateExec = ssotJavaExec("ssotValidateExec", "io.justsearch.ssot.tools.SsotValidator").apply {
  configure {
    group = "verification"
    description = "Validate SSOT manifests and prompt inputs."
  }
}

val ssotGenerateGbnf = ssotJavaExec("ssotGenerateGbnf", "io.justsearch.ssot.tools.GbnfGenerator").apply {
  configure { description = "Generate grammar files from SSOT prompts." }
}

// SSOT synonym compilation removed in tempdoc 581 §13 / ADR-0043 (native multilingual, no
// per-language levers) — per-language synonym lists are no longer part of the analysis pipeline.

tasks.register("ssotGenerate") { dependsOn(ssotGenerateGbnf) }

tasks.register("ssotValidate") { dependsOn(ssotValidateExec) }

val ssotChecks = tasks.register("ssotChecks") {
  group = "verification"
  description = "Run SSOT repository validations."
  dependsOn(ssotValidateExec)
}

// Make root :check depend on each subproject's :check (CC-compatible lazy wiring)
subprojects {
  pluginManager.withPlugin("base") {
    rootProject.tasks.named("check") {
      dependsOn(tasks.named("check"))
    }
  }
}

tasks.register<conventions.CheckNoDirectSysPropTask>("checkNoDirectJustsearchSysProp") {
    sourceFiles.from(fileTree("modules") {
        include("**/src/main/**/*.java")
        exclude("**/telemetry/**", "**/build/**")
    })
    reportFile.set(layout.buildDirectory.file("reports/sysprop-check.txt"))

    // The fileTree covers subdirectories that overlap with subproject outputs.
    // Declare mustRunAfter so validation passes without creating hard dependencies.
    val overlapping = setOf("processResources", "spotlessJavaSources")
    rootProject.allprojects.forEach { sub ->
        mustRunAfter(sub.tasks.matching { it.name in overlapping })
    }
}

// 347 D5: EnvRegistry direct-read enforcement moved to ArchUnit rule in
// modules/app-launcher/src/test/.../EnvRegistryDirectReadTest.java.
// The string-based CheckEnvRegistryDirectReadsTask was deleted.

tasks.named("check") { dependsOn("checkNoDirectJustsearchSysProp") }

// Tempdoc 634 (go-public): the size/count ratchet gates (class-size, clone, ui-bundle,
// exception-count) were removed end-to-end. The discipline-gate kernel still runs the
// correctness/architectural gates via ci.yml; no gradle-wired class-size task remains.

// Aggregate lifecycle: quality depends on root :check (which depends on all subprojects' :check)
val quality = tasks.register("quality") {
  group = "verification"
  description = "Aggregate quality gate across all modules via :check."
  // Include root-level checks (which already depend on subprojects' :check)
  dependsOn("check")
}

// Verify aggregates quality + SSOT + packaging checks
tasks.register("verify") {
  dependsOn(quality)
  dependsOn("ssotValidate")
  dependsOn("verifyPackaging")
  // Docs: prevent drift back to removed legacy endpoints (cheap contract hygiene).
  dependsOn("docsApiDriftCheck")
  // Docs (noncanonical): warn-only scan for removed legacy endpoints (does not fail the build).
  dependsOn("docsNoncanonicalRemovedEndpointsWarn")
  // Docs (tempdocs): warn-only scan for invalid frontmatter status values.
  dependsOn("docsTempdocStatusWarn")
  dependsOn("promptTemplateLint")
}

// SSOT drift check: regenerate artifacts, fail if git detects changes
tasks.register<Exec>("ssotDriftCheck") {
  group = "verification"
  description = "Validate SSOT and fail if artifacts/manifests drift."
  dependsOn("ssotGenerate", "ssotValidateExec")
  commandLine("git", "diff", "--quiet", "--exit-code", "--", "SSOT/artifacts", "SSOT/manifests/repro")
  isIgnoreExitValue = false
  onlyIf { System.getenv("CI") != null }
}

tasks.named("verify") {
  dependsOn("ssotGenerate")
  dependsOn("ssotDriftCheck")
}

// === Wire-Category contract codegen (slice 3a-1-8 Phase 2) ===
//
// Java emission is part of `:modules:api-contract-projection-java:generateProto`
// (driven by the com.google.protobuf Gradle plugin); `:wireGenerate` wraps it.
// FE types are NOT generated from proto: they come from the record→JSON-Schema
// →{TS,Zod} pipeline (scripts/codegen/gen-wire-schema-types.mjs). The npm
// workspace at scripts/wire-contract/ now pins only the buf CLI, used by the
// `wire` governance gate for `buf breaking` (scripts/governance/gates/wire/).

val wireContractDir = layout.projectDirectory.dir("scripts/wire-contract")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val npmCmd = if (isWindows) "npm.cmd" else "npm"

val wireNpmInstall = tasks.register<Exec>("wireNpmInstall") {
  group = "build"
  description = "Install the pinned buf CLI (used by the wire governance gate) into scripts/wire-contract/."
  workingDir = wireContractDir.asFile
  commandLine(npmCmd, "install", "--no-fund", "--no-audit")
  inputs.file(wireContractDir.file("package.json").asFile)
  inputs.file(wireContractDir.file("package-lock.json").asFile).optional(true)
  outputs.dir(wireContractDir.dir("node_modules").asFile)
}

tasks.register("wireGenerate") {
  group = "build"
  description = "Regenerate the Java projection of the wire-Category contract."
  dependsOn(":modules:api-contract-projection-java:generateProto")
}

// Docs linting tasks — Java-based, no Node.js required
val repoRootDir = layout.projectDirectory.asFile
val docsApiDriftCheck = ssotJavaExec("docsApiDriftCheck", "io.justsearch.ssot.tools.DocsApiDriftCheck", repoRootDir).apply {
  configure {
    group = "verification"
    description = "Fail if docs reference removed legacy Local API endpoints."
  }
}
val docsNoncanonicalRemovedEndpointsWarn = ssotJavaExec("docsNoncanonicalRemovedEndpointsWarn", "io.justsearch.ssot.tools.DocsNoncanonicalEndpointsWarn", repoRootDir).apply {
  configure {
    group = "verification"
    description = "Warn-only scan: flag removed endpoints in noncanonical docs unless marked HISTORICAL:/IDEA:."
  }
}
val docsTempdocStatusWarn = ssotJavaExec("docsTempdocStatusWarn", "io.justsearch.ssot.tools.DocsTempdocStatusCheck", repoRootDir).apply {
  configure {
    group = "verification"
    description = "Scan tempdoc frontmatter statuses (warn locally; fail in CI on invalid values)."
  }
}

// Governance: prevent inline or dynamic plugin versions in module builds
abstract class EnforcePluginVersionsGovernance : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val buildFiles: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:Optional
  abstract val versionCatalog: RegularFileProperty

  /** Root directory for relative path computation (injected at config time for CC compatibility). */
  @get:Internal
  abstract val rootDir: DirectoryProperty

  @TaskAction
  fun check() {
    val offenders = mutableListOf<String>()
    val inlineVersionRegex = Regex("(?s)plugins\\s*\\{[^}]*\\bversion\\s*\\\"")
    val dynamicRegex = Regex("(?i)version\\s*=\\s*\\\"[^\\\"]*(\\+|\\-SNAPSHOT)\\\"")
    val root = rootDir.get().asFile

    buildFiles.forEach { f ->
      val text = f.readText()
      if (inlineVersionRegex.containsMatchIn(text) || dynamicRegex.containsMatchIn(text)) {
        offenders.add(f.relativeTo(root).path)
      }
    }

    val catalogOffenses = mutableListOf<String>()
    if (versionCatalog.isPresent && versionCatalog.get().asFile.exists()) {
      val catalogFile = versionCatalog.get().asFile
      val lines = catalogFile.readText()
      val pluginLineRegex = Regex("(?m)^\\s*\\w+\\s*=\\s*\\{[^}]*(?:version|version\\.ref)\\s*=\\s*\\\"([^\\\"]+)\\\"")
      pluginLineRegex.findAll(lines).forEach { m ->
        val v = m.groupValues[1]
        if (v.contains('+') || v.contains("SNAPSHOT", ignoreCase = true)) {
          catalogOffenses.add("gradle/libs.versions.toml")
        }
      }
    }

    if (offenders.isNotEmpty() || catalogOffenses.isNotEmpty()) {
      throw GradleException(buildString {
        appendLine("Plugin versions governance violation:")
        if (offenders.isNotEmpty()) {
          appendLine("- Inline/dynamic plugin versions detected in:")
          offenders.forEach { appendLine("  - $it") }
        }
        if (catalogOffenses.isNotEmpty()) {
          appendLine("- Dynamic plugin versions in catalog:")
          catalogOffenses.forEach { appendLine("  - $it") }
        }
        appendLine("Use alias(libs.plugins.<id>) in builds and pin versions in gradle/libs.versions.toml.")
      })
    }
  }
}

tasks.register<EnforcePluginVersionsGovernance>("enforcePluginVersionsGovernance") {
  group = "verification"
  description = "Fail if inline plugin versions or dynamic plugin versions are used in modules."
  rootDir.set(layout.projectDirectory)
  buildFiles.from(layout.projectDirectory.asFileTree.matching {
    include("**/*.gradle.kts")
    exclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
    // Allow versions in settings pluginManagement only
    exclude("settings.gradle.kts")
  })
  versionCatalog.set(layout.projectDirectory.file("gradle/libs.versions.toml"))
}

tasks.named("verify") { dependsOn("enforcePluginVersionsGovernance") }

val assembleDesktopDist = tasks.register<CopyLauncherDist>("assembleDesktopDist") {
  group = "distribution"
  description = "Copy launcher distribution ZIP into dist/ for packaging verification."
  dependsOn(":modules:app-launcher:distZip")
  projectDir.set(layout.projectDirectory)
  outputDir.set(layout.projectDirectory.dir("dist"))
  archiveName.set("JustSearch-${project.version}.zip")
  sourceZip.set(
      layout.projectDirectory.file(
          "modules/app-launcher/build/distributions/app-launcher-${project.version}.zip"))
}

tasks.register("verifyPackaging") {
  group = "verification"
  description = "Alias that assembles the distribution for packaging verification."
  dependsOn(assembleDesktopDist)
}

// NOTE: The legacy JavaFX UI automation lane has been removed. Standardized evidence capture is now handled via EvidenceBundle v1
// (Playwright + API snapshots).

val redactionLintTask = tasks.register<Copy>("redactionLint") {
  group = "verification"
  description = "Run redaction golden tests and copy the HTML summary to build/reports/analysis/redaction-lint.html."
  val moduleReport = layout.projectDirectory.file("modules/app-services/build/reports/tests/redactionLint/index.html")
  val aggregateDir = layout.buildDirectory.dir("reports/analysis")
  dependsOn(":modules:app-services:redactionLintTest")
  from(moduleReport.asFile)
  rename { "redaction-lint.html" }
  into(aggregateDir)
  doFirst {
    check(moduleReport.asFile.exists()) {
      "Expected module redaction report at ${moduleReport.asFile.absolutePath}; ensure :modules:app-services:redactionLintTest succeeded."
    }
  }
}

tasks.named("verify") {
  dependsOn(redactionLintTask)
}

// Enforce dependency hygiene (no dynamic/SNAPSHOT versions); root only
plugins.apply("conventions.deps-hygiene")
// Note: enforceDependencyVersions now uses CC-compatible EnforceDependencyVersionsTask class

// Apply build-logic conventions
// Root-level cross-project configuration moved to the precompiled
// convention plugin `conventions.jvm-base`, applied per-module.

// Generate thresholds doc from build configuration
val coverageLineMin = providers.gradleProperty("coverage.line.min").orElse("0.80")
val coverageBranchMin = providers.gradleProperty("coverage.branch.min").orElse("0.70")
val coverageClassLineMin = providers.gradleProperty("coverage.class.line.min").orElse("0.60")

abstract class GenerateCoverageThresholdsDoc : DefaultTask() {
  @get:Input
  abstract val coverageLineMin: Property<String>

  @get:Input
  abstract val coverageBranchMin: Property<String>

  @get:Input
  abstract val coverageClassLineMin: Property<String>

  @get:OutputFile
  abstract val outputFile: RegularFileProperty

  @TaskAction
  fun generate() {
    val content = buildString {
      appendLine("# Coverage Thresholds (Generated)")
      appendLine()
      appendLine("Source of truth: build configuration (gradle.properties or plugin defaults).")
      appendLine()
      appendLine("| Metric | Minimum | Source |")
      appendLine("| ------ | ------- | ------ |")
      appendLine("| Line (bundle) | ${coverageLineMin.get()} | gradle.properties or default |")
      appendLine("| Branch (bundle) | ${coverageBranchMin.get()} | gradle.properties or default |")
      appendLine("| Line per class | ${coverageClassLineMin.get()} | gradle.properties or default |")
    }
    outputFile.get().asFile.writeText(content)
  }
}

tasks.register<GenerateCoverageThresholdsDoc>("generateCoverageThresholdsDoc") {
  group = "documentation"
  description = "Generate docs/_generated/coverage-thresholds.md from build thresholds."
  coverageLineMin.set(providers.gradleProperty("coverage.line.min").orElse("0.80"))
  coverageBranchMin.set(providers.gradleProperty("coverage.branch.min").orElse("0.70"))
  coverageClassLineMin.set(providers.gradleProperty("coverage.class.line.min").orElse("0.60"))
  outputFile.set(layout.projectDirectory.file("docs/_generated/coverage-thresholds.md"))
}

tasks.named("verify") { dependsOn("generateCoverageThresholdsDoc") }

// (reverted) resolveAndLockAll helper removed

licenseReport {
  outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.absolutePath
  configurations = arrayOf("runtimeClasspath")
  projects = (listOf(project) + subprojects).toTypedArray()
  renderers = arrayOf<com.github.jk1.license.render.ReportRenderer>(
      com.github.jk1.license.render.JsonReportRenderer("third-party-licenses.json"),
      com.github.jk1.license.render.SimpleHtmlReportRenderer("third-party-licenses.html"))
  filters = arrayOf<com.github.jk1.license.filter.DependencyFilter>(
      com.github.jk1.license.filter.LicenseBundleNormalizer())
  // Tempdoc 632: enforce the allowlist — `checkLicense` fails the build on a non-allowlisted
  // (e.g. future copyleft) dependency. Permissive licenses are allowed by name; the dual-licensed
  // deps + junrar/jcip are allowed by moduleName (see config/allowed-licenses.json).
  allowedLicensesFile = rootProject.layout.projectDirectory.file("config/allowed-licenses.json").asFile
}

// Root Spotless — kotlinGradle format is intentionally NOT configured here.
// The root kotlinGradle tasks cause a hard config-cache serialization failure on Windows
// (lineEndingsPolicy DefaultProvider not serializable — diffplug/spotless#2025).
// This breaks config cache for ALL tasks in the build, not just Spotless.
// Tested: Spotless 8.1.0/Gradle 9.1.0 and 8.3.0/9.4.0 — still fails.
//
// .kts formatting is handled by:
//   - Per-module spotlessJavaSources (works fine with config cache)
//   - Manual `spotlessApply` when editing .kts files
//   - CI pre-commit hooks
//
// To format .kts files manually:
//   ./gradlew.bat --no-configuration-cache spotlessApply
spotless {
  // No kotlinGradle block — intentionally omitted to avoid config cache breakage.
  // Per-module Java formatting is configured in JvmBaseConventionsPlugin.
}

allprojects {
  // Spotless 8.1.0+ has full configuration cache support (issue #987 resolved)
  tasks.withType(com.diffplug.gradle.spotless.SpotlessTask::class.java).configureEach {
    // Spotless tasks can write into source trees; never restore outputs from the Gradle build cache.
    outputs.cacheIf { false }
  }
}

tasks.named("check") {
  dependsOn(ssotChecks)
}
// (reverted) buildscript locking removed

// Dependency locking is enabled and managed via the `conventions.locking` plugin.
// Strict enforcement is handled in CI via lockfile drift checks.

// Note: Source artifact verification is handled via trusted-artifacts in verification-metadata.xml

// Note: The Head process JLink runtime is built by modules/ui/build.gradle.kts
// (createHeadlessRuntime). The Worker shares that runtime at launch via
// System.getProperty("java.home"). Dead createWorkerRuntime, generateWorkerAppCDS,
// and buildWorkerDist tasks were removed in tempdoc 269 — replaced by JDK 25
// AOT Cache (JEP 514/515) which supersedes manual AppCDS.

// ============================================================================
// Test Preparation and End-to-End Test Alias
// ============================================================================

// Unified task to build all artifacts required for system/integration tests
// This ensures both shadow JAR and distribution are built before tests run
tasks.register("prepareTests") {
  group = "verification"
  description = "Builds all artifacts required for system/integration tests"
  dependsOn(":modules:indexer-worker:installDist")
}

// Alias for running all system tests (Process + Chaos tests)
// Automatically builds all required artifacts via prepareTests
tasks.register("e2eTest") {
  group = "verification"
  description = "Run end-to-end system tests (builds artifacts automatically)"
  dependsOn("prepareTests")
  dependsOn(":modules:system-tests:systemTest")
}

// ============================================================================
// Quick Build (tempdoc 275 I2)
// ============================================================================

// Fast compilation check for iterative agent development. Skips static analysis
// (SpotBugs, PMD, Spotless) which add ~9.5s to cold-cache builds. Agents use
// this during iteration; full `build -x test` for final verification.
tasks.register("quickBuild") {
  group = "build"
  description = "Compile all sources + test sources without static analysis (fast iteration)"
  dependsOn(subprojects.filter { it.plugins.hasPlugin("java") }.map { "${it.path}:classes" })
  dependsOn(subprojects.filter { it.plugins.hasPlugin("java") }.map { "${it.path}:testClasses" })
}

// ============================================================================
// PMD aggregate (tempdoc 930 §22.2 follow-up 10)
// ============================================================================

// Every PMD task in every module — `pmdMain` plus one per non-main source set (`test`,
// `integrationTest`, `systemTest`, `soakTest`, `determinismTest`, `testFixtures`). CI runs THIS,
// not a hand-written list of task names: a module that registers a new `JvmTestSuite` gets a new
// `pmd<Suite>` task, and a list would silently not cover it — the same dormancy shape follow-up 2
// found in `pmdMain` and this change found in `modules/system-tests`. `dependsOn(provider { … })`
// because the root project is evaluated before its children, so the task set does not exist yet
// when this block runs; the provider is resolved when the execution graph is built.
tasks.register("pmdAll") {
  group = "verification"
  description = "Run PMD over every Java source set in every module"
  dependsOn(provider { subprojects.flatMap { it.tasks.withType(org.gradle.api.plugins.quality.Pmd::class.java) } })
}
