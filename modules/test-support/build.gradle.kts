import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByType
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject

plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
}

dependencies {
  implementation(libs.jackson.databind)
  runtimeOnly(libs.jackson.core)
  implementation(libs.jackson.annotations)
  runtimeOnly(libs.jackson.dataformat.yaml)
  runtimeOnly(libs.lucene.core)
  runtimeOnly(libs.lucene.analysis.common)
  runtimeOnly(libs.lucene.analysis.icu)
  runtimeOnly(libs.slf4j.nop)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for test-support classpaths")
    }
  }
}

abstract class GoldenSmokeTask : DefaultTask() {
  @get:Input
  abstract val mode: Property<String>

  @get:Input
  abstract val budgetProfile: Property<String>

  @get:Input
  abstract val reportsDir: Property<String>

  @get:Input
  abstract val sampleSet: Property<String>

  @get:Input
  abstract val goldenMode: Property<String>

  @get:Classpath
  abstract val launcherClasspath: ConfigurableFileCollection

  @get:InputDirectory
  abstract val repoRoot: DirectoryProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  init {
    mode.convention("inproc")
    budgetProfile.convention("search.desktop-default")
    reportsDir.convention("reports/phase10/goldens")
    sampleSet.convention("catalog-smoke")
    goldenMode.convention("lock")
  }

  @TaskAction
  fun runGoldenSmoke() {
    val allowedModes = setOf("inproc", "indexer-worker", "hybrid")
    val resolvedMode = mode.get()
    require(resolvedMode in allowedModes) {
      "Unsupported mode '$resolvedMode'. Expected one of $allowedModes."
    }
    val repoRootDir = repoRoot.get().asFile
    val reportsRoot = repoRootDir.resolve(reportsDir.get())
    reportsRoot.mkdirs()

    val profileMeta = resolveProfileMeta(budgetProfile.get(), repoRootDir)
    val scenario = sampleSet.get()
    val runKey = "$scenario/${resolvedMode}-${profileMeta.label}"
    val goldenDir = reportsRoot.resolve(runKey).apply { mkdirs() }
    val pagingFile =
        repoRootDir.resolve("reports/phase10/paging/$scenario").apply { mkdirs() }
            .resolve("${resolvedMode}-${profileMeta.label}.json")
    val simulatePath = goldenDir.resolve("simulate.json")
    val metadataFile = goldenDir.resolve("metadata.json")

    execOperations.javaexec {
      classpath = launcherClasspath
      mainClass.set("io.justsearch.testsupport.cli.GoldenSmokeRunner")
      workingDir = repoRootDir
      args(
          "--mode=$resolvedMode",
          "--budget-profile=${budgetProfile.get()}",
          "--sample-set=$scenario",
          "--simulate=${simulatePath.absolutePath}",
          "--paging=${pagingFile.absolutePath}",
          "--metadata=${metadataFile.absolutePath}",
          "--pages=5")
    }

    val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
    val simulateRel =
        repoRootDir.toPath().relativize(simulatePath.toPath())
    val pagingRel = repoRootDir.toPath().relativize(pagingFile.toPath())

    val currentJson = goldenDir.resolve("current.json")
    val current = mutableMapOf<String, Any?>()
    current["mode"] = resolvedMode
    current["budget_profile"] = metadata["budget_profile"]
    current["profile_artifact"] = metadata["sample_set"]
    current["ssot_manifest_hash"] = metadata["ssot_manifest_hash"]
    current["template_ver"] = metadata["template_ver"]
    current["simulate_log"] = simulateRel.toString().replace("\\", "/")
    current["paging_trace"] = pagingRel.toString().replace("\\", "/")
    current["status"] = "passed"
    current["generated_at"] = Instant.now().toString()
    currentJson.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(current)))

    val manifestPath = reportsRoot.resolve("manifest.json")
    updateManifest(manifestPath, profileMeta.canonicalProfile, profileMeta)

    val summaryPath = reportsRoot.resolve("pr-matrix-summary.json")
    val currentRel = repoRootDir.toPath().relativize(currentJson.toPath())
    updateMatrixSummary(
        summaryPath,
        resolvedMode,
        profileMeta.pipelineCoordinate,
        currentRel.toString().replace("\\", "/"))
  }

  private data class ProfileMeta(
      val canonicalProfile: String,
      val runtimeProfile: String,
      val label: String,
      val pipelineCoordinate: String,
      val ssotHash: String,
      val templateVer: Int)

  private fun resolveProfileMeta(input: String, repoRoot: java.io.File): ProfileMeta {
    var candidate = input.trim()
    require(candidate.isNotEmpty()) { "Budget profile must not be blank" }
    candidate = candidate.replace('\\', '/')
    if (candidate.endsWith(".json")) {
      candidate = candidate.substring(0, candidate.length - 5)
    }
    candidate = candidate.replace("@", ".v")
    if (!candidate.contains(".")) {
      candidate = "search.$candidate"
    }
    if (!candidate.contains(".v")) {
      candidate = "$candidate.v1"
    }
    val prefix = candidate.substringBefore('.')
    val rest = candidate.substringAfter('.')
    val versionIdx = rest.lastIndexOf(".v")
    require(versionIdx > 0 && versionIdx + 2 < rest.length) {
      "Unable to parse version from budget profile '$input'"
    }
    val name = rest.substring(0, versionIdx)
    val version = rest.substring(versionIdx + 2).toInt()
    val runtime = name.substringAfterLast('.')
    val label = if (runtime.contains("-")) runtime.substringAfterLast("-") else runtime
    val canonical = "$prefix.$name.v$version"
    val pipelineCoord = "$prefix.$name@$version"
    val ssotHash = computeManifestHash(repoRoot)
    val templateVer = readTemplateVersion(repoRoot)
    return ProfileMeta(canonical, runtime, label, pipelineCoord, ssotHash, templateVer)
  }

  private fun computeManifestHash(repoRoot: java.io.File): String {
    val manifest = repoRoot.resolve("SSOT/manifest.v1.json")
    val digest = MessageDigest.getInstance("SHA-256").digest(manifest.readBytes())
    return digest.joinToString("") { "%02x".format(it) }.substring(0, 8)
  }

  private fun readTemplateVersion(repoRoot: java.io.File): Int {
    val catalog = repoRoot.resolve("SSOT/versions/catalog.json")
    val parsed = JsonSlurper().parse(catalog)
    val intent = (parsed as Map<*, *>)["intent_v1"] as? Map<*, *> ?: return 0
    val template =
        (intent["template_ver"] as? List<*>)?.lastOrNull() as? Number ?: return 0
    return template.toInt()
  }

  private fun updateManifest(
      manifestPath: java.io.File,
      canonicalProfile: String,
      profileMeta: ProfileMeta) {
    val data = mutableMapOf<String, Any?>().apply {
      if (manifestPath.exists()) {
        val parsed = JsonSlurper().parse(manifestPath)
        if (parsed is Map<*, *>) {
          parsed.forEach { (key, value) ->
            val typedKey = key as? String ?: return@forEach
            this[typedKey] = value
          }
        }
      }
    }
    val profiles =
        ((data["budget_profiles"] as? Iterable<*>)?.mapNotNull { it as? String }?.toMutableList())
            ?: mutableListOf()
    if (!profiles.contains(canonicalProfile)) {
      profiles.add(canonicalProfile)
    }
    data["budget_profiles"] = profiles
    data["template_ver"] = profileMeta.templateVer
    data["ssot_manifest_hash"] = profileMeta.ssotHash
    data["generated_at"] = Instant.now().toString()
    manifestPath.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
  }

  private fun updateMatrixSummary(
      summaryPath: java.io.File,
      mode: String,
      budgetProfile: String,
      artifact: String) {
    val data = mutableMapOf<String, Any?>().apply {
      if (summaryPath.exists()) {
        val parsed = JsonSlurper().parse(summaryPath)
        if (parsed is Map<*, *>) {
          parsed.forEach { (key, value) ->
            val typedKey = key as? String ?: return@forEach
            this[typedKey] = value
          }
        }
      }
    }
    val runs = mutableListOf<MutableMap<String, Any?>>()
    val existingRuns = data["runs"]
    if (existingRuns is Iterable<*>) {
      existingRuns.forEach { entry ->
        if (entry is Map<*, *>) {
          val typed = linkedMapOf<String, Any?>()
          entry.forEach { (key, value) ->
            val typedKey = key as? String ?: return@forEach
            typed[typedKey] = value
          }
          runs += typed
        }
      }
    }
    runs.removeIf { it["mode"] == mode && it["budget_profile"] == budgetProfile }
    runs.add(
        mutableMapOf(
            "mode" to mode,
            "budget_profile" to budgetProfile,
            "status" to "passed",
            "artifact" to artifact))
    data["runs"] = runs
    summaryPath.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
  }
}

val appLauncherSourceSets =
    project(":modules:app-launcher").extensions.getByType(SourceSetContainer::class.java)
val appLauncherRuntimeClasspath = appLauncherSourceSets.getByName("main").runtimeClasspath

val reportsDirProperty = providers.gradleProperty("reportsDir").orElse("reports/phase10/goldens")

tasks.register<GoldenSmokeTask>("goldenSmoke") {
  launcherClasspath.from(appLauncherRuntimeClasspath)
  dependsOn(project(":modules:app-launcher").tasks.named("classes"))
  mode.convention(providers.gradleProperty("mode").orElse("inproc"))
  budgetProfile.convention(providers.gradleProperty("budgetProfile").orElse("search.desktop-default"))
  reportsDir.convention(reportsDirProperty)
  sampleSet.convention(providers.gradleProperty("sampleSet").orElse("catalog-smoke"))
  goldenMode.convention(providers.gradleProperty("goldenMode").orElse("lock"))
  repoRoot.set(project.rootProject.layout.projectDirectory)
}

fun safeName(value: String) = value.replace("[^a-zA-Z0-9]".toRegex(), "_")

val sampleSets = listOf("catalog-smoke", "paging-stress", "budget-tight")
val matrixModes = listOf("inproc", "indexer-worker", "hybrid")
val budgetProfilesMatrix = listOf("search.desktop-default", "search.desktop-tight")
val matrixTasks = mutableListOf<TaskProvider<GoldenSmokeTask>>()
for (sample in sampleSets) {
  for (modeValue in matrixModes) {
    for (budget in budgetProfilesMatrix) {
      val taskName =
          "goldenSmoke_${safeName(sample)}_${safeName(modeValue)}_${safeName(budget)}"
      matrixTasks +=
          tasks.register<GoldenSmokeTask>(taskName) {
            launcherClasspath.from(appLauncherRuntimeClasspath)
            dependsOn(project(":modules:app-launcher").tasks.named("classes"))
            mode.set(modeValue)
            budgetProfile.set(budget)
            sampleSet.set(sample)
            goldenMode.set("lock")
            reportsDir.set(reportsDirProperty)
            repoRoot.set(project.rootProject.layout.projectDirectory)
          }
    }
  }
}

tasks.register("goldenMatrix") { dependsOn(matrixTasks) }

val updateTasks = mutableListOf<TaskProvider<GoldenSmokeTask>>()
for (sample in sampleSets) {
  for (modeValue in matrixModes) {
    for (budget in budgetProfilesMatrix) {
      val taskName =
          "goldenUpdate_${safeName(sample)}_${safeName(modeValue)}_${safeName(budget)}"
      updateTasks +=
          tasks.register<GoldenSmokeTask>(taskName) {
            launcherClasspath.from(appLauncherRuntimeClasspath)
            dependsOn(project(":modules:app-launcher").tasks.named("classes"))
            mode.set(modeValue)
            budgetProfile.set(budget)
            sampleSet.set(sample)
            goldenMode.set("unlock")
            reportsDir.set(reportsDirProperty)
            repoRoot.set(project.rootProject.layout.projectDirectory)
          }
    }
  }
}

tasks.register("goldenUpdate") {
  doFirst {
    if (System.getenv("SSOT_ALLOW_REGEN") != "1") {
      throw GradleException("goldenUpdate requires SSOT_ALLOW_REGEN=1")
    }
  }
  dependsOn(updateTasks)
}

abstract class GoldensValidateTask : DefaultTask() {
  @get:Inject abstract val execOperations: ExecOperations

  @TaskAction
  fun validate() {
    val statusOutput = ByteArrayOutputStream()
    execOperations.exec {
      commandLine("git", "status", "--porcelain")
      standardOutput = statusOutput
    }
    val lines = statusOutput.toString().lines().filter { it.isNotBlank() }
    val goldensChanged = lines.any { it.contains("reports/phase10/goldens") }
    if (goldensChanged) {
      val ssotChanged =
          lines.any {
            it.contains("SSOT/") ||
                it.contains("test-support/sample-docs") ||
                it.contains("modules/test-support/src/main/resources/test-support")
          }
      if (!ssotChanged) {
        throw GradleException(
            "Golden outputs changed without corresponding SSOT/template updates.")
      }
    }
  }
}

tasks.register<GoldensValidateTask>("testSupportGoldensValidate")

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
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

tasks.named("check") {
  dependsOn(tasks.named("integrationTest"))
  dependsOn(tasks.named("testSupportGoldensValidate"))
}
