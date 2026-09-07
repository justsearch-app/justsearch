import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.api.tasks.Exec
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
  application
}

dependencies {
  implementation(project(":modules:core"))
  implementation(project(":modules:configuration"))
  implementation(project(":modules:ort-common"))  // 347: GpuAutoDetection for startup probe
  implementation(project(":modules:app-api"))
  implementation(project(":modules:app-inference"))
  implementation(project(":modules:app-services"))
  // Lane F stage A item A6: HeadlessApp composes the Engine through EngineRoot, the only module
  // allowed to bind both halves. `implementation`, so worker internals stay off this classpath.
  implementation(project(":modules:app-engine"))
  implementation(project(":modules:app-agent-api"))
  // Validation finding (2026-04-26): HeadlessApp's LocalTelemetry must register
  // catalog DEFINITIONS for every emit path HeadAssembly touches. Adding
  // app-agent compile-time so the catalog DEFINITIONS imports resolve.
  implementation(project(":modules:app-agent"))
  implementation(project(":modules:app-config"))
  implementation(project(":modules:app-util"))
  runtimeOnly(project(":modules:ai-backend"))
  implementation(project(":modules:gpu-bridge"))
  implementation(project(":modules:adapters-lucene"))
  implementation(project(":modules:telemetry"))
  implementation(project(":modules:ipc-common"))
  // Runtime-scope: HeadlessApp.main calls BootContractRunner.validateAll() (tempdoc 402 P3),
  // so core-contracts classes must be on the installed-distribution runtime classpath.
  implementation(project(":modules:core-contracts"))
  implementation("io.grpc:grpc-api:1.79.0")  // Provides io.grpc.Status / StatusRuntimeException APIs
  implementation("org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2")
  implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
  implementation(libs.jackson.databind)
  implementation(libs.jackson.core)
  implementation(libs.slf4j.api)
  implementation(libs.jul.to.slf4j)
  implementation(libs.logback.classic)
  // Required by src/main/resources/logback.xml (the head process logback config,
  // alpha.23 follow-up): LogstashEncoder for JSON output, OpenTelemetryAppender
  // for MDC bridge.
  runtimeOnly(libs.logstash.logback.encoder)
  runtimeOnly(libs.opentelemetry.logback.mdc)
  implementation(libs.javalin)
  // DAP: transitive dependencies declared directly.
  implementation(libs.opentelemetry.api)
  implementation(project(":modules:api-contract-projection-java"))
  implementation(project(":modules:app-observability"))
}

// DAP: transitive test/integrationTest dependencies declared directly.
// Must live in a top-level dependencies block (these configurations do not
// exist inside a JvmTestSuite dependencies {} block).
dependencies {
  testImplementation(libs.grpc.stub)
}

configurations.configureEach {
  resolutionStrategy.eachDependency {
    if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
      useVersion("2.0.17")
      because("Lock convergence for UI classpaths")
    }
  }
}

testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(testFixtures(project(":modules:configuration")))
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        implementation("org.junit.jupiter:junit-jupiter-params:5.14.3")
        implementation(libs.archunit.junit5)
        implementation(libs.mockito.core)
        implementation(libs.assertj.core)
        // Slice 445: integration test for the indexing-jobs SSE substrate uses
        // an in-process gRPC server (no live worker dep). Scoped to test only.
        implementation(libs.grpc.inprocess)
        // Tempdoc 911 (885 UL.9): the failed-jobs wire-contract test validates the ACTUAL endpoint
        // body against SSOT/schemas/failed-indexing-jobs-response.v1.json. The record→schema link is
        // pinned in app-api; this pins the other end — that the handler emits what the schema says.
        implementation(libs.json.schema.validator)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
      }
      targets {
        all {
          testTask.configure {
            jvmArgs(
              // Enable ByteBuddy experimental mode for JDK 25 support (until ByteBuddy 1.17.5+)
              "-Dnet.bytebuddy.experimental=true"
            )
          }
        }
      }
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        // Integration tests seed on-disk Lucene indices and stamp commit metadata.
        // Keep these deps scoped to integrationTest so we don't grow UI production compileClasspath.
        runtimeOnly(libs.lucene.core)
        runtimeOnly(libs.lucene.analysis.common)
        implementation(platform(libs.junit.bom))
        implementation(libs.junit.jupiter.api)
        runtimeOnly(libs.junit.jupiter.engine)
        runtimeOnly(libs.junit.platform.launcher)
        // DAP: integrationTest transitive dep declared directly.
        implementation(project(":modules:indexing"))
      }
      targets { all { testTask.configure { shouldRunAfter(tasks.named("test")) } } }
    }
  }
}

sourceSets.named("integrationTest").configure {
  compileClasspath += sourceSets["test"].output
  runtimeClasspath += sourceSets["test"].output
}

tasks.withType<Test>().configureEach {
  systemProperty("prism.allowhidpi", "false")
  jvmArgs("--enable-native-access=ALL-UNNAMED")
  // Tempdoc 771 item (b): forward the entity-carriage metric's corpus input to the test JVM. Unset
  // is the normal case — McpEntityCarriageMetricTest's real-corpus arm then skips and only its
  // synthetic strata run, which is what CI does.
  providers.systemProperty("justsearch.entityCarriage.casesTsv").orNull?.let {
    systemProperty("justsearch.entityCarriage.casesTsv", it)
  }
}

tasks.register<JavaExec>("generateRuntimeClientOpenApi") {
  group = "code generation"
  description = "Regenerate the self-contained @justsearch/runtime-client OpenAPI snapshot."
  dependsOn(tasks.named("testClasses"))
  mainClass.set("io.justsearch.ui.api.SdkOpenApiSnapshotMain")
  classpath = sourceSets["test"].runtimeClasspath
  args(rootProject.file("packages/runtime-client/openapi/runtime-client.openapi.json").absolutePath)
  jvmArgs("-Dnet.bytebuddy.experimental=true")
}

// Integration tests must be hermetic and must not read/write real machine policy locations.
// The production code resolves machine policy from %PROGRAMDATA%\\JustSearch\\policy.v1.json on Windows.
// We sandbox PROGRAMDATA for the integrationTest JVM process so tests can create/delete policy files safely.
tasks.named<Test>("integrationTest").configure {
  // Avoid global-state flakiness; these tests touch process-level sysprops and a shared PROGRAMDATA sandbox.
  maxParallelForks = 1

  // Force LocalApiServer to bind to an ephemeral port even if a developer has JUSTSEARCH_API_PORT set.
  // resolveConfiguredPort() treats <= 0 as unset.
  systemProperty("justsearch.api.port", "0")

  environment("PROGRAMDATA", layout.buildDirectory.dir("it-programdata").get().asFile.absolutePath)

  // Tempdoc 415 N10 wired a :modules:indexer-worker:installDist dependency here so UI
  // integration tests that boot a real Knowledge Server would not fail with "Worker lib directory
  // not found" on a fresh worktree. Lane F stage A item A13 deleted that distribution: these tests
  // boot the index half in-process off the test runtimeClasspath, so there is nothing to install.
}

val lintStyles by tasks.registering(conventions.StylelintTask::class) {
  cssFiles.from(fileTree("src/main/resources/css") { include("**/*.css") })
  configFile.set(rootProject.layout.projectDirectory.file("stylelint.config.cjs"))
  reportFile.set(layout.buildDirectory.file("reports/stylelint/stylelint.json"))
  workingDirectory.set(layout.projectDirectory)
}

tasks.named("check") {
  dependsOn(tasks.named("integrationTest"))
  dependsOn(lintStyles)
}

val uiWebDir = rootProject.layout.projectDirectory.dir("modules/ui-web")
val uiWebDistDir = uiWebDir.dir("dist")
val uiWebResourcesDir = project.layout.projectDirectory.dir("src/main/resources/web")

// Windows dev environments can run out of commit/paging-file memory when building the web bundle (Vite).
// Allow skipping the web build via: -PskipWebBuild=true
val skipWebBuild =
  providers.gradleProperty("skipWebBuild").orNull?.trim()?.lowercase(Locale.ROOT) == "true"

val installWebDependencies by tasks.registering(conventions.NpmInstallTask::class) {
  description = "Install npm dependencies for modules/ui-web"
  enabled = !skipWebBuild
  workingDir.set(uiWebDir)
  packageFiles.from(uiWebDir.file("package.json"), uiWebDir.file("package-lock.json"))
  nodeModulesDir.set(uiWebDir.dir("node_modules"))
}

val buildWeb by tasks.registering(conventions.NpmBuildTask::class) {
  description = "Build the React app in modules/ui-web"
  enabled = !skipWebBuild
  dependsOn(installWebDependencies)
  workingDir.set(uiWebDir)
  scriptName.set("build")
  sourceFiles.from(
    uiWebDir.dir("src"),
    uiWebDir.file("package.json"),
    uiWebDir.file("package-lock.json"),
    uiWebDir.file("vite.config.js")
  )
  outputDir.set(uiWebDistDir)
}

val copyWebResources by tasks.registering(Sync::class) {
  group = "build"
  description = "Copy built web assets into UI resources"
  enabled = !skipWebBuild
  dependsOn(buildWeb)
  from(uiWebDistDir)
  into(uiWebResourcesDir)
}

tasks.named<ProcessResources>("processResources") {
  if (!skipWebBuild) {
  dependsOn(copyWebResources)
  } else {
    logger.lifecycle("Skipping ui-web build/copy (skipWebBuild=true).")
  }
  dependsOn(tasks.named("syncSsotSchemas"))
  from(rootProject.file("governance/store-recoverability.v1.json")) {
    into("governance")
  }
}

// Slice 3a.1.9 §A.6a: SchemaController serves classpath copies of SSOT/schemas/*.v1.json.
// Mirror the repo-root SSOT/schemas/ into modules/ui/src/main/resources/SSOT/schemas/ at
// build time so the schemas are on the head's classpath. Same convention as
// adapters-lucene's syncSsotCatalogs (catalogs dual-copy, tempdoc 393 §3.6).
val syncSsotSchemas by tasks.registering(Sync::class) {
  group = "build"
  description = "Mirror SSOT/schemas/*.v1.json from the repo root into ui resources."
  from(rootProject.file("SSOT/schemas")) {
    include("*.v1.json")
  }
  into(layout.projectDirectory.dir("src/main/resources/SSOT/schemas"))
}

// Spotless scans src/main/resources which syncSsotSchemas may rewrite.
// Mirror adapters-lucene's syncSsotCatalogs ordering (tempdoc 393 §3.6).
tasks.matching { it.name.startsWith("spotless") }.configureEach {
  mustRunAfter(syncSsotSchemas)
}

// Spotless scans src/main/resources which copyWebResources modifies.
// Declare explicit ordering to satisfy Gradle's task dependency validation.
tasks.matching { it.name.startsWith("spotless") }.configureEach {
  if (!skipWebBuild) {
    mustRunAfter(copyWebResources)
  }
}

configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.named("testRuntimeOnly").get())
}

// Visual and A11y audit helpers (optional CI wiring)
tasks.register("catalogVisual", Exec::class) {
  description = "Run catalog visual snapshots and diff report"
  group = "verification"
  isIgnoreExitValue = true
  commandLine("bash", "scripts/catalog-visual.sh", "--url", "http://127.0.0.1:7878/catalog/index.html")
}

tasks.register("a11yAudit", Exec::class) {
  description = "Run axe-core a11y audit and contrast audit"
  group = "verification"
  isIgnoreExitValue = true
  commandLine(
    "bash",
    "-lc",
    "scripts/contrast-audit.sh --url http://127.0.0.1:7878/catalog/index.html && npx --yes @axe-core/cli 'http://127.0.0.1:7878/catalog/index.html?theme=light' --save reports/phase8/ui/a11y/axe-report-light.json --tags wcag2a,wcag2aa && npx --yes @axe-core/cli 'http://127.0.0.1:7878/catalog/index.html?theme=dark' --save reports/phase8/ui/a11y/axe-report-dark.json --tags wcag2a,wcag2aa"
  )
}

val headlessJar by tasks.registering(Jar::class) {
  archiveClassifier.set("headless")
  manifest {
    attributes["Main-Class"] = "io.justsearch.ui.HeadlessApp"
  }
  from(sourceSets.main.get().output)
}

val headlessDistDir = layout.buildDirectory.dir("headless-dist")
// G19/G20 / Tempdoc 374 alpha.19 Bug K: headlessDist is `Copy::class` (not
// `Sync::class`) for configuration-cache compatibility — naive Sync wipes
// neighboring outputs like `normalizeHeadlessJar`'s stable-name jar at the
// same root. (Until lane F stage A item A13 it also broke the configuration
// cache, because `generateWorkerAotCache` held a task-typed `Sync` reference
// and Sync subtypes don't serialize; that task is deleted, but the
// wipes-neighbouring-outputs reason stands on its own.) To get Sync-like
// purge semantics for the lib/ subdir without the side effects, alpha.19
// adds a `doFirst` that deletes only `lib/*.jar` before the Copy populates
// them with the current version's set.
//
// Before alpha.19, the destination accumulated stale module jars across
// version bumps (alpha.16 + alpha.17 + alpha.18 in lib/ at the same time).
// JVM `-cp lib/*` ordering is filesystem-dependent and the alphabetically
// older version often won — alpha.18's binary-incompatible
// VariantSelector.select signature change surfaced this as
// `NoSuchMethodError` on `/api/status` (round-9 sandbox finding).
val headlessDist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Assemble the headless application distribution"
    doFirst {
        // Tempdoc 374 alpha.19 Bug K: purge stale module jars from previous
        // version bumps. Only lib/*.jar — `normalizeHeadlessJar`'s stable-name
        // output at the destination root is preserved.
        delete(fileTree(headlessDistDir.get().asFile.resolve("lib")) {
            include("**/*.jar")
        })
    }
    from(headlessJar)
    from(configurations.runtimeClasspath) {
        into("lib")
    }
    into(layout.buildDirectory.dir("headless-dist"))
}

// Create a stable jar name for tooling (e.g., Tauri sidecar) to avoid version bumps breaking paths
val normalizeHeadlessJar by tasks.registering(Copy::class) {
  group = "distribution"
  description = "Copy headless jar to a stable name"
  dependsOn(headlessJar)
  from(headlessJar)
  into(layout.buildDirectory.dir("headless-dist"))
  rename { "ui-headless.jar" }
}

headlessDist {
  dependsOn(normalizeHeadlessJar)
}

val headlessRuntimeImageDir = layout.buildDirectory.dir("headless-runtime")
val tauriHeadlessResourcesDir =
    project.layout.projectDirectory.dir("../shell/src-tauri/resources/headless")

// ============================================================================
// v1 Simple Mode: bundle llama-server.exe (CPU-only) into the sidecar payload
// ============================================================================
val isWindowsHost = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
val llamaStageDir = layout.buildDirectory.dir("llama-server/stage")
val stagedLlamaServerExe = llamaStageDir.map { it.file(if (isWindowsHost) "llama-server.exe" else "llama-server") }

// Runtime source: the pinned upstream llama.cpp Windows build is downloaded at build time. The vendored
// third_party/llama.cpp source tree and its `-PllamaRuntime=source` developer override were removed
// (tempdoc 632 — go-public repo-size + license/provenance cleanup; clone upstream on demand if a
// source build is ever needed).
val usePrebuiltLlamaRuntime = isWindowsHost

val llamaPrebuiltVersion = "b8571"
val llamaPrebuiltAsset = "llama-$llamaPrebuiltVersion-bin-win-cpu-x64.zip"
val llamaPrebuiltUrl =
  "https://github.com/ggml-org/llama.cpp/releases/download/$llamaPrebuiltVersion/$llamaPrebuiltAsset"
// Pinned SHA-256 for supply-chain safety (downloaded from the URL above).
val llamaPrebuiltSha256 = "40D8C4B97676E8BE4C69A4B5BCDC25F31213137CAEB35B1563E3867D133EA31C"
val llamaPrebuiltZip = layout.buildDirectory.file("llama-server/prebuilt/$llamaPrebuiltAsset")

// Signed-mirror override (tempdoc 760/772 §K sign-once). A release cut from a signed, re-hosted
// mirror of the llama.cpp CPU prebuilt overrides BOTH the download URL and its pinned SHA-256
// together, mirroring the `includeCuda` gradleProperty idiom below. The pair is deliberately
// all-or-nothing: overriding the URL while keeping the default hash (or the hash while keeping the
// default URL) would silently defeat the supply-chain pin this block exists to enforce, so
// providing exactly one FAILS the build. With neither property set the effective values below are
// byte-identical to the defaults above — the hardcoded pins remain the floor.
val llamaPrebuiltUrlOverride = providers.gradleProperty("llamaPrebuiltUrlOverride").orNull
val llamaPrebuiltSha256Override = providers.gradleProperty("llamaPrebuiltSha256Override").orNull
if ((llamaPrebuiltUrlOverride == null) != (llamaPrebuiltSha256Override == null)) {
  throw GradleException(
    "llamaPrebuiltUrlOverride and llamaPrebuiltSha256Override must be provided together. " +
      "Overriding only one silently defeats the supply-chain SHA-256 pin on the llama-server CPU " +
      "prebuilt: a mirror URL paired with the default hash can never verify, and the default URL " +
      "paired with a mirror hash would reject the genuine pinned artifact. Provide BOTH (signed-mirror " +
      "release) or NEITHER (default pinned download)."
  )
}
val effectiveLlamaPrebuiltUrl = llamaPrebuiltUrlOverride ?: llamaPrebuiltUrl
val effectiveLlamaPrebuiltSha256 = llamaPrebuiltSha256Override ?: llamaPrebuiltSha256

// CUDA variant for GPU acceleration (requires NVIDIA GPU + CUDA 12.4+ drivers)
val llamaCudaAsset = "llama-$llamaPrebuiltVersion-bin-win-cuda-12.4-x64.zip"
val llamaCudaUrl =
  "https://github.com/ggml-org/llama.cpp/releases/download/$llamaPrebuiltVersion/$llamaCudaAsset"
val llamaCudaZip = layout.buildDirectory.file("llama-server/prebuilt/$llamaCudaAsset")
val includeCudaVariant = providers.gradleProperty("includeCuda").orNull?.toBoolean() ?: true

// CUDA redistributable DLLs (cudart, cublas) - required for GPU acceleration without CUDA Toolkit
val llamaCudartAsset = "cudart-llama-bin-win-cuda-12.4-x64.zip"
val llamaCudartUrl =
  "https://github.com/ggml-org/llama.cpp/releases/download/$llamaPrebuiltVersion/$llamaCudartAsset"
val llamaCudartZip = layout.buildDirectory.file("llama-server/prebuilt/$llamaCudartAsset")


// ============================================================================
// ONNX/SPLADE models: bundled for search (embedding, reranking, NER, SPLADE,
// citation scoring). Staged from local models/ directory (Git LFS).
// Validated production model set: tempdoc 343 Phase D (2026-03-28).
// ============================================================================
val includeOnnxModels = !(providers.gradleProperty("skipOnnxModels").orNull?.toBoolean() ?: false)

val onnxStageDir = layout.buildDirectory.dir("onnx-models/stage")
val repoModelsDir = rootProject.layout.projectDirectory.dir("models")
val onnxNoticeFile = layout.buildDirectory.file("onnx-models/NOTICE-MODELS.txt")

// Tempdoc 632 (attribution-as-projection): the model notice is no longer a hardcoded heredoc that
// forks the registry (it silently omitted the Qwen chat model). It is now PROJECTED from
// ai/model-registry.v2.json's `license` field by scripts/codegen/gen-notices.mjs into the committed
// packaging/runtime/NOTICE-MODELS.txt; this task simply stages that file into the bundle. The
// regen-all --check CI gate fails the build if the committed file drifts from the registry.
val generateOnnxNotice by tasks.registering {
  group = "distribution"
  description = "Stage the registry-projected model attribution notice (gen-notices.mjs; tempdoc 632)"
  enabled = includeOnnxModels
  val noticeSource = rootProject.layout.projectDirectory.file("packaging/runtime/NOTICE-MODELS.txt")
  // Resolve to plain Files at configuration time so the doLast closure captures no script-level
  // providers (config-cache safe).
  val srcFile = noticeSource.asFile
  val outFile = onnxNoticeFile.get().asFile
  inputs.file(noticeSource)
  outputs.file(onnxNoticeFile)
  doLast {
    outFile.parentFile.mkdirs()
    outFile.writeText(srcFile.readText())
  }
}

val stageOnnxModels by tasks.registering(Copy::class) {
  group = "distribution"
  description = "Stage ONNX/SPLADE models from local repo for installer bundling"
  dependsOn(generateOnnxNotice)
  enabled = includeOnnxModels

  // Global exclusions: runtime caches and build provenance
  exclude("*.optimized", "*.opt-meta", "*.sha256", "build.json")

  // Embedding: FP16 only (model_manifest.json specifies FP16 for both CPU and GPU)
  from(repoModelsDir.dir("onnx/gte-multilingual-base")) {
    include("model_fp16.onnx", "tokenizer.json", "model_manifest.json",
            "pooling_config.json", "prefix_config.json",
            "special_tokens_map.json", "tokenizer_config.json")
    into("models/onnx/gte-multilingual-base")
  }

  // Reranker: FP32 (CPU) + FP16 (GPU)
  from(repoModelsDir.dir("onnx/reranker")) {
    include("model.onnx", "model_fp16.onnx", "tokenizer.json", "config.json",
            "special_tokens_map.json", "tokenizer_config.json")
    into("models/onnx/reranker")
  }

  // Citation scorer: INT8 only (no FP16 variant)
  from(repoModelsDir.dir("onnx/citation-scorer")) {
    include("model.onnx", "tokenizer.json", "config.json")
    into("models/onnx/citation-scorer")
  }

  // NER: INT8 (CPU) + FP16 (GPU)
  from(repoModelsDir.dir("onnx/ner")) {
    include("model.onnx", "model_fp16.onnx", "tokenizer.json", "config.json",
            "special_tokens_map.json", "tokenizer_config.json")
    into("models/onnx/ner")
  }

  // SPLADE: FP32 (CPU) + FP16 (GPU), custom PRESPARSE build
  from(repoModelsDir.dir("splade/naver-splade-v3")) {
    include("model.onnx", "model_fp16.onnx", "tokenizer.json", "config.json",
            "model_manifest.json", "vocab.txt", "idf.json",
            "special_tokens_map.json", "tokenizer_config.json")
    into("models/splade/naver-splade-v3")
  }

  // Attribution notice
  from(onnxNoticeFile) {
    into("models")
  }

  into(onnxStageDir)
}

val downloadLlamaServerPrebuilt by tasks.registering {
  group = "distribution"
  description = "Download pinned upstream llama.cpp Windows CPU prebuilt (llama-server.exe + DLLs)"
  enabled = usePrebuiltLlamaRuntime
  val url = effectiveLlamaPrebuiltUrl
  val expectedSha256 = effectiveLlamaPrebuiltSha256
  val outFile = llamaPrebuiltZip.get().asFile
  // Declare url + expected hash as inputs so a signed-mirror override busts the up-to-date check
  // and forces a re-download/re-verify; without this an incremental build would keep the cached
  // default zip and silently ignore the override.
  inputs.property("url", url)
  inputs.property("expectedSha256", expectedSha256)
  outputs.file(outFile)
  doLast {
    fun sha256Hex(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(1024 * 1024)
        while (true) {
          val read = input.read(buf)
          if (read <= 0) break
          digest.update(buf, 0, read)
        }
      }
      return HexFormat.of().formatHex(digest.digest()).uppercase(Locale.ROOT)
    }

    outFile.parentFile.mkdirs()

    if (outFile.isFile) {
      val got = sha256Hex(outFile)
      if (got.equals(expectedSha256, ignoreCase = true)) {
        logger.lifecycle("llama-server prebuilt already present: ${outFile.absolutePath}")
        return@doLast
      }
      logger.warn(
        "llama-server prebuilt hash mismatch; re-downloading. expected=$expectedSha256 got=$got file=${outFile.absolutePath}"
      )
      outFile.delete()
    }

    val tmp = File(outFile.parentFile, outFile.name + ".partial")
    tmp.delete()

    val client =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    val req =
      HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofMinutes(10))
        .GET()
        .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
    if (resp.statusCode() != 200) {
      throw GradleException("Failed to download llama-server prebuilt ($url): HTTP ${resp.statusCode()}")
    }
    resp.body().use { input ->
      tmp.outputStream().use { output ->
        input.copyTo(output)
      }
    }

    val got = sha256Hex(tmp)
    if (!got.equals(expectedSha256, ignoreCase = true)) {
      tmp.delete()
      throw GradleException(
        "Downloaded llama-server prebuilt hash mismatch. expected=$expectedSha256 got=$got url=$url"
      )
    }

    Files.move(
      tmp.toPath(),
      outFile.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
    logger.lifecycle("Downloaded llama-server prebuilt: ${outFile.absolutePath}")
  }
}

val downloadLlamaCudaPrebuilt by tasks.registering {
  group = "distribution"
  description = "Download CUDA-enabled llama.cpp Windows prebuilt for GPU acceleration"
  enabled = usePrebuiltLlamaRuntime && includeCudaVariant
  val url = llamaCudaUrl
  val outFile = llamaCudaZip.get().asFile
  inputs.property("url", url)
  outputs.file(outFile)
  doLast {
    fun sha256Hex(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(1024 * 1024)
        while (true) {
          val read = input.read(buf)
          if (read <= 0) break
          digest.update(buf, 0, read)
        }
      }
      return HexFormat.of().formatHex(digest.digest()).uppercase(Locale.ROOT)
    }

    outFile.parentFile.mkdirs()

    if (outFile.isFile) {
      // CUDA variant already present; skip download (hash check disabled for large file)
      logger.lifecycle("llama-server CUDA prebuilt already present: ${outFile.absolutePath}")
      return@doLast
    }

    val tmp = File(outFile.parentFile, outFile.name + ".partial")
    tmp.delete()

    logger.lifecycle("Downloading llama-server CUDA variant from $url ...")
    val client =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    val req =
      HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofMinutes(15))  // CUDA build is larger
        .GET()
        .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
    if (resp.statusCode() != 200) {
      logger.warn("Failed to download CUDA variant ($url): HTTP ${resp.statusCode()} - GPU acceleration will not be available")
      return@doLast
    }
    resp.body().use { input ->
      tmp.outputStream().use { output ->
        input.copyTo(output)
      }
    }

    Files.move(
      tmp.toPath(),
      outFile.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
    logger.lifecycle("Downloaded llama-server CUDA prebuilt: ${outFile.absolutePath}")
  }
}

val downloadLlamaCudartPrebuilt by tasks.registering {
  group = "distribution"
  description = "Download CUDA redistributable DLLs (cudart, cublas) for GPU acceleration"
  enabled = usePrebuiltLlamaRuntime && includeCudaVariant
  val url = llamaCudartUrl
  val outFile = llamaCudartZip.get().asFile
  inputs.property("url", url)
  outputs.file(outFile)
  doLast {
    outFile.parentFile.mkdirs()

    if (outFile.isFile) {
      logger.lifecycle("CUDA redistributables already present: ${outFile.absolutePath}")
      return@doLast
    }

    val tmp = File(outFile.parentFile, outFile.name + ".partial")
    tmp.delete()

    logger.lifecycle("Downloading CUDA redistributables from $url ...")
    val client =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    val req =
      HttpRequest.newBuilder(URI(url))
        .timeout(Duration.ofMinutes(15))
        .GET()
        .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
    if (resp.statusCode() != 200) {
      logger.warn("Failed to download CUDA redistributables ($url): HTTP ${resp.statusCode()} - GPU acceleration will require CUDA Toolkit")
      return@doLast
    }
    resp.body().use { input ->
      tmp.outputStream().use { output ->
        input.copyTo(output)
      }
    }

    Files.move(
      tmp.toPath(),
      outFile.toPath(),
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
    logger.lifecycle("Downloaded CUDA redistributables: ${outFile.absolutePath}")
  }
}

val stageLlamaCudaVariant by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Stage CUDA llama-server variant for GPU acceleration"
  dependsOn(downloadLlamaCudaPrebuilt, downloadLlamaCudartPrebuilt)
  enabled = usePrebuiltLlamaRuntime && includeCudaVariant
  val cudaZipFile = llamaCudaZip.get().asFile
  val cudartZipFile = llamaCudartZip.get().asFile
  // Tempdoc 682 Item 2: the staged build's ASSERTED version pin (single authority:
  // llamaPrebuiltVersion above). Written as the machine-readable runtime-version.txt marker
  // next to the staged exe — same convention as stageLlamaServerFromPrebuilt's CPU stamp —
  // so the inference lifecycle can compare it against the running server's /props build_info.
  val cudaRuntimeStamp = "llama.cpp $llamaPrebuiltVersion win-cuda-12.4-x64\n"
  val variantDir = llamaStageDir.get().asFile.resolve("variants").resolve("cuda12")
  outputs.dir(variantDir)
  outputs.upToDateWhen { false }
  includeEmptyDirs = false
  into(variantDir)

  // Runtime files (llama-server.exe + ggml-cuda.dll + backends)
  from(zipTree(cudaZipFile)) {
    include("**/llama-server.exe")
    include("**/*.dll")
    include("**/LICENSE*")
    // See stageLlamaServerFromPrebuilt: ggml-rpc.dll is the unused, unsigned distributed-inference
    // backend that trips Windows Defender — exclude it from the GPU variant too.
    exclude("**/ggml-rpc.dll")
  }

  // CUDA redistributable DLLs (cudart, cublas) - enables GPU without CUDA Toolkit install
  from(zipTree(cudartZipFile)) {
    include("**/*.dll")
  }

  // Flatten zip paths into a single directory
  eachFile {
    relativePath = org.gradle.api.file.RelativePath(true, name)
  }
  onlyIf { cudaZipFile.exists() && cudartZipFile.exists() }
  doLast {
    val serverExe = variantDir.resolve("llama-server.exe")
    if (!serverExe.exists()) {
      logger.warn("CUDA variant staging failed - llama-server.exe not found")
      return@doLast
    }

    // Tempdoc 682 Item 2: stamp the pinned build version next to the staged exe (written as
    // soon as the exe is verified present — the exe's build is what it is regardless of
    // whether the cudart DLL check below passes).
    variantDir.resolve("runtime-version.txt").writeText(cudaRuntimeStamp, Charsets.UTF_8)

    // Verify CUDA redistributable DLLs were extracted before writing NOTICE
    val requiredCudaDlls = listOf("cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll")
    val missingDlls = requiredCudaDlls.filter { !variantDir.resolve(it).exists() }
    if (missingDlls.isNotEmpty()) {
      logger.warn("CUDA variant incomplete - missing DLLs: $missingDlls")
      logger.warn("GPU acceleration may not work without CUDA Toolkit installed")
      return@doLast
    }

    // Write NVIDIA notice file for legal compliance (EULA Attachment A redistribution)
    // Only written after verification that all DLLs are present
    val notice = variantDir.resolve("NOTICE-NVIDIA-CUDA.txt")
    notice.writeText(
      """
      |NVIDIA CUDA Runtime Redistributables
      |=====================================
      |
      |This directory contains NVIDIA CUDA runtime libraries redistributed
      |under the NVIDIA CUDA Toolkit EULA.
      |
      |EULA: https://docs.nvidia.com/cuda/eula/index.html
      |Attachment A (Redistributables): https://docs.nvidia.com/cuda/eula/index.html#attachment-a
      |
      |Redistributed DLLs:
      |- cudart64_12.dll (CUDA Runtime)
      |- cublas64_12.dll (cuBLAS)
      |- cublasLt64_12.dll (cuBLAS Lt)
      |
      |Variant: cuda-12.4
      |Minimum Windows driver: 551.61
      |
      |These DLLs are redistributed as "distributable portions" of the CUDA Toolkit
      |for use with JustSearch's GPU-accelerated inference functionality.
      """.trimMargin()
    )
    logger.lifecycle("CUDA variant staged at: ${variantDir.absolutePath}")
  }
}

// ============================================================================
// ORT CUDA variant: bundle ONNX Runtime CUDA provider + dependencies for GPU-
// accelerated embedding, SPLADE, NER, and cross-encoder reranking. The DLLs are
// sourced from the developer's local ORT variant directory (conventional path).
// ============================================================================
val ortCudaConventionalDir = rootProject.layout.projectDirectory.dir("tmp/ort-variant-test/cuda-12.4-v1.24.3")
val ortCudaStageDir = layout.buildDirectory.dir("ort-cuda/stage")
val includeOrtCuda = isWindowsHost && includeCudaVariant

val stageOrtCudaVariant by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Stage ORT CUDA DLLs for GPU-accelerated ONNX inference"
  enabled = includeOrtCuda
  val srcDir = ortCudaConventionalDir.asFile
  into(ortCudaStageDir)
  from(srcDir) {
    include("*.dll")
  }
  onlyIf { srcDir.exists() && srcDir.resolve("onnxruntime_providers_cuda.dll").exists() }
  doLast {
    val stageRoot = ortCudaStageDir.get().asFile
    val requiredDlls = listOf(
      "onnxruntime_providers_cuda.dll", "onnxruntime_providers_shared.dll",
      "cudart64_12.dll", "cublas64_12.dll", "cublasLt64_12.dll"
    )
    val missing = requiredDlls.filter { !stageRoot.resolve(it).exists() }
    if (missing.isNotEmpty()) {
      logger.warn("ORT CUDA variant incomplete — missing: $missing")
      return@doLast
    }
    val notice = stageRoot.resolve("NOTICE-NVIDIA-CUDA.txt")
    if (!notice.exists()) {
      notice.writeText(
        """
        |NVIDIA CUDA Runtime Redistributables (ONNX Runtime)
        |====================================================
        |
        |This directory contains NVIDIA CUDA runtime libraries redistributed
        |under the NVIDIA CUDA Toolkit EULA for GPU-accelerated ONNX inference.
        |
        |EULA: https://docs.nvidia.com/cuda/eula/index.html
        |
        |Variant: cuda-12.4 / ORT 1.24.3
        |Minimum Windows driver: 551.61
        """.trimMargin()
      )
    }
    logger.lifecycle("ORT CUDA variant staged at: ${stageRoot.absolutePath} (${requiredDlls.size} required DLLs present)")
  }
}

val stageLlamaServerFromPrebuilt by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Stage llama-server payload from pinned upstream prebuilt (exe + DLLs)"
  dependsOn(downloadLlamaServerPrebuilt)
  enabled = usePrebuiltLlamaRuntime
  val zipFile = llamaPrebuiltZip.get().asFile
  val stageRoot = llamaStageDir.get().asFile
  val runtimeStamp = "llama.cpp $llamaPrebuiltVersion prebuilt\n"
  outputs.dir(stageRoot)
  // This task is cheap and must be correct. Don't allow stale staged payloads to persist.
  outputs.upToDateWhen { false }
  includeEmptyDirs = false
  into(stageRoot)
  // Preserve GPU variant subdirectories (staged by stageLlamaCudaVariant) - Sync would delete them otherwise
  preserve {
    include("variants/**")
  }
  from(zipTree(zipFile)) {
    include("**/llama-server.exe")
    include("**/*.dll")
    include("**/LICENSE*")
    // ggml-rpc.dll is llama.cpp's distributed/multi-machine inference backend. JustSearch only ever
    // runs a single local llama-server (no `--rpc` flag is ever constructed — see LlamaServerOps), so
    // the RPC backend is dead weight. The upstream prebuilt ships it UNSIGNED, so Windows Defender
    // blocks its load at backend-registration time ("can't confirm who published ggml-rpc.dll"),
    // which both warns the user and is a non-deterministic startup dependency. Drop it.
    exclude("**/ggml-rpc.dll")
  }
  // Flatten zip paths into a single directory (llama-server expects adjacent DLLs).
  eachFile {
    relativePath = org.gradle.api.file.RelativePath(true, name)
  }
  doLast {
    val serverExe = stageRoot.resolve("llama-server.exe")
    if (!serverExe.exists()) {
      throw GradleException("Staged llama-server.exe not found after prebuilt extract: ${serverExe.absolutePath}")
    }
    val cpuBackends =
      stageRoot.listFiles()
        ?.filter { it.isFile && it.name.lowercase(Locale.ROOT).startsWith("ggml-cpu") && it.name.lowercase(Locale.ROOT).endsWith(".dll") }
        ?: emptyList()
    if (cpuBackends.isEmpty()) {
      throw GradleException("Staged prebuilt llama-server is missing ggml-cpu*.dll backends in ${stageRoot.absolutePath}")
    }

    // Sandbox compatibility: upstream binaries require MSVCP140_CODECVT_IDS.dll which is often missing
    // in clean images. Bundle it next to llama-server.exe so the Windows loader can satisfy the import.
    val codecvt = stageRoot.resolve("msvcp140_codecvt_ids.dll")
    if (!codecvt.exists()) {
      fun findMsvcpCodecvtIdsDll(): File? {
        val winDir = System.getenv("WINDIR") ?: System.getenv("SystemRoot") ?: "C:\\Windows"
        val candidates = listOf(
          File(winDir).resolve("System32").resolve("msvcp140_codecvt_ids.dll"),
          File(winDir).resolve("SysWOW64").resolve("msvcp140_codecvt_ids.dll"),
        )
        return candidates.firstOrNull { it.isFile }
      }
      val src = findMsvcpCodecvtIdsDll()
      if (src == null) {
        throw GradleException(
          "msvcp140_codecvt_ids.dll not found on this build machine. Install the VC++ 2015-2022 x64 runtime (or VS Build Tools) and retry."
        )
      }
      Files.copy(src.toPath(), codecvt.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    // Stamp runtime version for upgrade/repair logic.
    stageRoot.resolve("runtime-version.txt")
      .writeText(runtimeStamp, Charsets.UTF_8)
  }
}

val stageLlamaServer by tasks.registering {
  group = "distribution"
  description = "Stage llama-server payload (downloaded pinned upstream prebuilt)"
  enabled = isWindowsHost
  outputs.dir(llamaStageDir)
  outputs.upToDateWhen { false }
  dependsOn(stageLlamaServerFromPrebuilt)
  if (usePrebuiltLlamaRuntime && includeCudaVariant) {
    dependsOn(stageLlamaCudaVariant)
  }
}

// Tempdoc 618 §3: NO gradle task mirrors the build stage into native-bin. native-bin/llama-server
// is the RUNTIME's install directory — "Install AI" extracts the cuda12 GPU variant there and it
// persists across sessions — so a pruning `Sync` (or any wholesale copy) would clobber a
// user-installed variant. The dev-runner stages a CPU-baseline-ONLY fallback into an EMPTY
// native-bin and bails the moment any runtime is present (scripts/dev/dev-runner.cjs
// ensureLlamaStagedInNativeBin); GPU cuda12 stays exclusively "Install AI"'s domain.

val createHeadlessRuntime by tasks.registering(Exec::class) {
  group = "distribution"
  description = "Create a stripped runtime image for the headless sidecar"
  dependsOn(headlessDist)
  val javaLauncher =
      javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get()
  val javaHome = javaLauncher.metadata.installationPath.asFile
  val jlinkExecutable =
      javaHome.resolve("bin")
          .resolve(
              if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows"))
                  "jlink.exe"
              else "jlink")
  val outDir = headlessRuntimeImageDir.get().asFile
  inputs.dir(headlessDistDir)
  outputs.dir(outDir)
  doFirst {
    outDir.deleteRecursively()
    outDir.parentFile.mkdirs()
  }
  commandLine(
      jlinkExecutable.absolutePath,
      "--no-header-files",
      "--no-man-pages",
      "--strip-debug",
      "--compress=zip-6",
      "--add-modules",
      // Module rationale:
      // - java.base: Core runtime (required)
      // - java.logging: SLF4J/Logback logging subsystem
      // - java.naming: JNDI (used by some JDBC drivers, gRPC service discovery)
      // - java.net.http: HttpClient for AI pack downloads, health checks
      // - java.sql: SQLite job queue in Worker, JDBC drivers
      // - java.management: JMX ManagementFactory for heap/thread metrics
      // - jdk.management: com.sun.management.OperatingSystemMXBean for process metrics (JvmRuntimeGauges)
      // - java.security.sasl: SASL authentication (gRPC, security providers)
      // - java.xml: XML parsing (config files, Tika metadata)
      // - java.desktop: AWT/Swing (image handling, clipboard in UI scenarios)
      // - jdk.httpserver: Lightweight HTTP server for local API
      listOf(
              "java.base",
              "java.logging",
              "java.naming",
              "java.net.http",
              "java.sql",
              "java.management",
              "jdk.management",
              "java.security.sasl",
              "java.xml",
              "java.desktop",
              "jdk.httpserver")
          .joinToString(","),
      "--output",
      outDir.absolutePath)
}

// Copy javaw.exe to the jlink runtime on Windows.
// jlink only includes the java launcher by default; javaw.exe is needed by the Tauri shell
// to spawn the backend without a console window.
// This is a separate task with proper input/output tracking so Gradle handles UP-TO-DATE correctly.
val copyJavawToRuntime by tasks.registering {
  group = "distribution"
  description = "Copy javaw.exe to the jlink runtime (Windows only)"
  val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
  onlyIf { isWindows }
  dependsOn(createHeadlessRuntime)

  if (isWindows) {
    val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }.get()
    val javaHome = javaLauncher.metadata.installationPath.asFile
    val srcJavaw = javaHome.resolve("bin").resolve("javaw.exe")
    val dstJavaw = headlessRuntimeImageDir.get().asFile.resolve("bin").resolve("javaw.exe")

    inputs.file(srcJavaw).optional()
    outputs.file(dstJavaw)

    doLast {
      if (!srcJavaw.exists()) {
        throw GradleException("javaw.exe not found in JDK: ${srcJavaw.absolutePath}")
      }
      srcJavaw.copyTo(dstJavaw, overwrite = true)
      logger.lifecycle("Copied javaw.exe to bundled runtime: ${dstJavaw.absolutePath}")
    }
  }
}

// ============================================================================
// AOT Cache Generation (JEP 514/515 — JDK 25 Project Leyden)
// ============================================================================
// Two-step workflow per process:
//   1. Training run: -XX:AOTMode=record writes a .aotconf file
//   2. Assembly:     -XX:AOTMode=create reads .aotconf, writes .aot cache
// Using manual two-step to avoid the 2x memory cost of -XX:AOTCacheOutput=.
// Training classes (AotTraining) load representative libraries and exit cleanly.
// See tempdoc 269 §D4a for the full investigation.

val aotCacheDir = layout.buildDirectory.dir("aot-cache")

val generateHeadAotCache by tasks.registering {
  group = "distribution"
  description = "Generate JDK 25 AOT cache for the Head process"
  notCompatibleWithConfigurationCache("Spawns external JVM processes for AOT training")
  dependsOn(headlessDist, createHeadlessRuntime, copyJavawToRuntime)
  val runtimeDir = headlessRuntimeImageDir.map { it.asFile }
  val headDistDirProp = headlessDistDir.map { it.asFile }
  val aotDir = aotCacheDir.map { it.dir("head").asFile }
  outputs.dir(aotDir)

  doLast {
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
    val java = runtimeDir.get().resolve("bin").resolve(if (isWindows) "java.exe" else "java").absolutePath
    val headDist = headDistDirProp.get()
    val aot = aotDir.get()
    aot.mkdirs()
    val confFile = aot.resolve("head.aotconf")
    val cacheFile = aot.resolve("head.aot")

    // Build explicit classpath (AOT requires JAR paths, no wildcards)
    val sep = System.getProperty("path.separator")
    val headJar = headDist.resolve("ui-headless.jar")
    val libJars = headDist.resolve("lib").listFiles()?.filter { it.extension == "jar" }?.sorted() ?: emptyList()
    val cp = (listOf(headJar) + libJars).joinToString(sep) { it.absolutePath }

    // Step 1: Training run — record class loading into .aotconf
    logger.lifecycle("AOT training (Head): recording class loading...")
    val r1 = ProcessBuilder(java,
        "-XX:AOTMode=record", "-XX:AOTConfiguration=${confFile.absolutePath}",
        "--sun-misc-unsafe-memory-access=warn", "-cp", cp,
        "io.justsearch.ui.AotTraining")
        .inheritIO().start().waitFor()
    if (r1 != 0) throw GradleException("AOT training (Head) failed with exit code $r1")

    // Step 2: Assembly — create .aot cache from .aotconf
    logger.lifecycle("AOT assembly (Head): creating cache...")
    val r2 = ProcessBuilder(java,
        "-XX:AOTMode=create", "-XX:AOTConfiguration=${confFile.absolutePath}",
        "-XX:AOTCache=${cacheFile.absolutePath}",
        "--sun-misc-unsafe-memory-access=warn", "-cp", cp,
        "io.justsearch.ui.AotTraining")
        .inheritIO().start().waitFor()
    if (r2 != 0) throw GradleException("AOT assembly (Head) failed with exit code $r2")

    logger.lifecycle("AOT cache (Head): ${cacheFile.absolutePath} (${cacheFile.length() / 1024}KB)")
  }
}

// Lane F stage A item A13 deleted `generateWorkerAotCache` and the `worker.aot` it produced.
// One JVM gets one cache: the index half runs inside the Engine since item A6, so the touches
// that used to warm the Worker cache folded into io.justsearch.ui.AotTraining (Lucene, Tika,
// SQLite, ORT, KnowledgeServer) and `generateHeadAotCache` above trains them on the Engine
// classpath. Training them anywhere else would warm classes no process loads.

// Dev-mode AOT Cache Generation (S1 from tempdoc 275)
// ============================================================================
// Same two-step workflow as production but uses the system JDK instead of the
// bundled JLink runtime. The cache is stored under build/aot-dev/head and used by
// dev-runner's direct-launch path (scripts/dev/dev-runner.cjs). There is one cache because
// lane F stage A item A13 left one JVM: item A11 deleted the WorkerSpawner that consumed the
// dev worker cache, and A13 deleted the cache itself.
// The cache is UP-TO-DATE as long as the installDist JARs haven't changed.

val devAotCacheDir = layout.buildDirectory.dir("aot-dev")

val generateDevHeadAotCache by tasks.registering {
  group = "development"
  description = "Generate dev-mode AOT cache for the Head process (system JDK)"
  notCompatibleWithConfigurationCache("Spawns external JVM processes for AOT training")
  dependsOn(tasks.named("installDist"))
  val installDir = layout.buildDirectory.dir("install/ui")
  inputs.dir(installDir.map { it.dir("lib") })
  val aotDir = devAotCacheDir.map { it.asFile }
  outputs.dir(aotDir.map { it.resolve("head") })

  doLast {
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
    val java = org.gradle.internal.jvm.Jvm.current().javaHome
        .resolve("bin").resolve(if (isWindows) "java.exe" else "java").absolutePath
    val dist = installDir.get().asFile
    val aot = aotDir.get().resolve("head")
    aot.mkdirs()
    val confFile = aot.resolve("head.aotconf")
    val cacheFile = aot.resolve("head.aot")

    val sep = System.getProperty("path.separator")
    val libJars = dist.resolve("lib").listFiles()?.filter { it.extension == "jar" }?.sorted() ?: emptyList()
    val cp = libJars.joinToString(sep) { it.absolutePath }

    logger.lifecycle("Dev AOT training (Head): recording class loading...")
    val r1 = ProcessBuilder(java,
        "-XX:AOTMode=record", "-XX:AOTConfiguration=${confFile.absolutePath}",
        "--sun-misc-unsafe-memory-access=warn", "-cp", cp,
        "io.justsearch.ui.AotTraining")
        .inheritIO().start().waitFor()
    if (r1 != 0) throw GradleException("Dev AOT training (Head) failed with exit code $r1")

    logger.lifecycle("Dev AOT assembly (Head): creating cache...")
    val r2 = ProcessBuilder(java,
        "-XX:AOTMode=create", "-XX:AOTConfiguration=${confFile.absolutePath}",
        "-XX:AOTCache=${cacheFile.absolutePath}",
        "--sun-misc-unsafe-memory-access=warn", "-cp", cp,
        "io.justsearch.ui.AotTraining")
        .inheritIO().start().waitFor()
    if (r2 != 0) throw GradleException("Dev AOT assembly (Head) failed with exit code $r2")

    logger.lifecycle("Dev AOT cache (Head): ${cacheFile.absolutePath} (${cacheFile.length() / 1024}KB)")
  }
}

// Item A13: `generateDevWorkerAotCache` went with its production twin — one dist, one dev cache.

// 371 / ADR-0021: content-hash build stamp for stale-JVM detection. The stamp changes iff the
// distribution contents change; external tools (jseval, the MCP reload tool) read this file to
// decide whether a running JVM matches the on-disk distribution.
//
// Lane F stage A item A13 re-homed this task from `modules/indexer-worker` (where it stamped
// `build/install/indexer-worker/build-stamp.txt`) onto the one surviving distribution. The
// mechanism is unchanged — same SHA-256 over the lib jars, same 16-hex-char prefix, same
// SNAPSHOT-content / third-party-name+size split — only the tree it describes is now the Engine's.
val generateBuildStamp by tasks.registering {
  dependsOn(tasks.named("installDist"))
  val libDir = layout.buildDirectory.dir("install/ui/lib")
  inputs.dir(libDir)
  val stampFile = layout.buildDirectory.file("install/ui/build-stamp.txt")
  outputs.file(stampFile)

  doLast {
    val dir = libDir.get().asFile
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(8192)
    dir.listFiles()
        ?.filter { it.isFile }
        ?.sortedBy { it.name }
        ?.forEach { f ->
          md.update(f.name.toByteArray(Charsets.UTF_8))
          md.update("\n".toByteArray())
          // Content-hash project JARs (SNAPSHOT) for exact change detection.
          // Third-party JARs are immutable — name+size is sufficient and avoids
          // reading 371MB onnxruntime_gpu on every stamp.
          if (f.name.contains("SNAPSHOT")) {
            f.inputStream().use { stream ->
              var n: Int
              while (stream.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
              }
            }
          } else {
            md.update(f.length().toString().toByteArray(Charsets.UTF_8))
          }
        }
    val hash = md.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }.take(16)
    stampFile.get().asFile.writeText(hash + "\n")
  }
}

// Ensure the stamp is generated whenever installDist runs — covers runHeadless, the dev-runner's
// launch path, and any other task that triggers installDist directly.
tasks.named("installDist") {
  finalizedBy(generateBuildStamp)
}

val tesseractRuntimeManifestFile =
    rootProject.layout.projectDirectory.file("packaging/runtime/tesseract-windows.v1.json")
val tesseractRuntimeStageDir = layout.buildDirectory.dir("runtime/tesseract-windows")

// Signed-mirror override for the Tesseract runtime archive (tempdoc 760/772 §K sign-once), same
// all-or-nothing contract as the llama-server pair above: a re-hosted/signed mirror of the
// self-extracting Tesseract installer overrides BOTH the manifest `sourceUrl` and `sourceSha256`.
// Providing exactly one FAILS the build (defeating the archive pin). SCOPE LIMIT: this covers only
// the archive-level pin. The manifest ALSO pins per-file SHAs (`files[]`, verified by
// verifyTesseractRuntime) which signed inner PEs invalidate; those are regenerated from the signed
// archive by `scripts/release/regen-tesseract-manifest.mjs`, so verifyTesseractRuntime is made to
// fail with an explicit regeneration instruction when the override is active and per-file hashes
// mismatch, rather than silently skipping per-file verification.
val tesseractSourceUrlOverride = providers.gradleProperty("tesseractSourceUrlOverride").orNull
val tesseractSourceSha256Override = providers.gradleProperty("tesseractSourceSha256Override").orNull
if ((tesseractSourceUrlOverride == null) != (tesseractSourceSha256Override == null)) {
  throw GradleException(
    "tesseractSourceUrlOverride and tesseractSourceSha256Override must be provided together. " +
      "Overriding only one silently defeats the supply-chain SHA-256 pin on the Tesseract runtime " +
      "archive. Provide BOTH (signed-mirror release) or NEITHER (default pinned download)."
  )
}
val tesseractOverrideActive = tesseractSourceUrlOverride != null

val downloadTesseractRuntimeArtifacts by tasks.registering {
  group = "distribution"
  description = "Download pinned Windows Tesseract OCR runtime artifacts"
  enabled = isWindowsHost
  val manifestFile =
      rootProject.layout.projectDirectory.file("packaging/runtime/tesseract-windows.v1.json").asFile
  val sourceFile =
      layout.buildDirectory.file("downloads/tesseract/tesseract-ocr-w64-setup-5.5.0.20241111.exe")
          .get()
          .asFile
  val engFile = layout.buildDirectory.file("downloads/tesseract/eng.traineddata").get().asFile
  val sourceUrlOverride = tesseractSourceUrlOverride
  val sourceSha256Override = tesseractSourceSha256Override
  inputs.file(manifestFile)
  // Overrides participate in up-to-date so a signed-mirror swap forces a re-download/re-verify.
  inputs.property("sourceUrlOverride", sourceUrlOverride ?: "")
  inputs.property("sourceSha256Override", sourceSha256Override ?: "")
  outputs.file(sourceFile)
  outputs.file(engFile)
  doLast {
    fun sha256Hex(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      return HexFormat.of().formatHex(digest.digest())
    }

    fun download(url: String, expectedSha: String, target: File) {
      target.parentFile.mkdirs()
      if (target.isFile && sha256Hex(target).equals(expectedSha, ignoreCase = true)) {
        logger.lifecycle("Tesseract artifact already present: ${target.absolutePath}")
        return
      }
      if (target.exists()) {
        logger.warn("Tesseract artifact hash mismatch; re-downloading: ${target.absolutePath}")
        target.delete()
      }
      val tmp = File(target.parentFile, target.name + ".partial")
      tmp.delete()
      val client =
          HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NORMAL)
              .connectTimeout(Duration.ofSeconds(20))
              .build()
      val request =
          HttpRequest.newBuilder(URI(url))
              .timeout(Duration.ofMinutes(10))
              .GET()
              .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
      if (response.statusCode() != 200) {
        throw GradleException("Failed to download Tesseract artifact ($url): HTTP ${response.statusCode()}")
      }
      response.body().use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
      }
      val actualSha = sha256Hex(tmp)
      if (!actualSha.equals(expectedSha, ignoreCase = true)) {
        tmp.delete()
        throw GradleException("Downloaded Tesseract artifact hash mismatch. expected=$expectedSha got=$actualSha url=$url")
      }
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    val manifest =
        ObjectMapper().readValue(manifestFile, Map::class.java) as Map<*, *>
    val sourceUrl = (sourceUrlOverride ?: (manifest["sourceUrl"] as String)).substringBefore("#")
    val sourceSha = sourceSha256Override ?: (manifest["sourceSha256"] as String)
    @Suppress("UNCHECKED_CAST")
    val files = manifest["files"] as List<Map<String, Any>>
    val engEntry =
        files.firstOrNull { it["path"] == "tessdata/eng.traineddata" }
            ?: throw GradleException("Tesseract manifest is missing tessdata/eng.traineddata")
    val engUrl = engEntry["sourceUrl"] as String
    val engSha = engEntry["sha256"] as String

    download(sourceUrl, sourceSha, sourceFile)
    download(engUrl, engSha, engFile)
  }
}

val stageTesseractRuntime by tasks.registering {
  group = "distribution"
  description = "Stage the pinned Windows Tesseract OCR runtime from the declared manifest"
  enabled = isWindowsHost
  dependsOn(downloadTesseractRuntimeArtifacts)
  val manifestFile =
      rootProject.layout.projectDirectory.file("packaging/runtime/tesseract-windows.v1.json").asFile
  val sourceFile =
      layout.buildDirectory.file("downloads/tesseract/tesseract-ocr-w64-setup-5.5.0.20241111.exe")
          .get()
          .asFile
  val engFile = layout.buildDirectory.file("downloads/tesseract/eng.traineddata").get().asFile
  val stageRoot = layout.buildDirectory.dir("runtime/tesseract-windows").get().asFile
  inputs.file(manifestFile)
  inputs.file(sourceFile)
  inputs.file(engFile)
  outputs.dir(stageRoot)
  outputs.upToDateWhen { false }
  doLast {
    stageRoot.deleteRecursively()
    stageRoot.mkdirs()

    val sevenZip = listOf("7z", "7za", "7zr").firstOrNull { command ->
      try {
        ProcessBuilder(command).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS)
        true
      } catch (_: Exception) {
        false
      }
    } ?: throw GradleException("7z is required to extract the pinned Tesseract Windows runtime")

    val extractLog = File(temporaryDir, "tesseract-extract.log")
    val extract =
        ProcessBuilder(
            sevenZip,
            "x",
            "-y",
            "-o${stageRoot.absolutePath}",
            sourceFile.absolutePath)
            .redirectErrorStream(true)
            .redirectOutput(extractLog)
            .start()
    if (!extract.waitFor(2, TimeUnit.MINUTES)) {
      extract.destroyForcibly()
      throw GradleException("Tesseract runtime extraction timed out")
    }
    if (extract.exitValue() != 0) {
      val output = if (extractLog.isFile) extractLog.readText() else ""
      throw GradleException("Tesseract runtime extraction failed: $output")
    }

    stageRoot.resolve("$" + "PLUGINSDIR").deleteRecursively()
    val tessdata = stageRoot.resolve("tessdata")
    tessdata.mkdirs()
    Files.copy(
        engFile.toPath(),
        tessdata.resolve("eng.traineddata").toPath(),
        StandardCopyOption.REPLACE_EXISTING)
  }
}

val verifyTesseractRuntime by tasks.registering {
  group = "verification"
  description = "Validate the staged Windows Tesseract OCR runtime against the pinned manifest"
  dependsOn(stageTesseractRuntime)
  val manifestFile =
      rootProject.layout.projectDirectory.file("packaging/runtime/tesseract-windows.v1.json").asFile
  val runtimeDir = layout.buildDirectory.dir("runtime/tesseract-windows").get().asFile
  val isWindowsForTask = isWindowsHost
  val overrideActive = tesseractOverrideActive
  inputs.file(manifestFile)
  inputs.property("overrideActive", overrideActive)
  inputs.dir(runtimeDir)
  onlyIf("Windows Tesseract runtime is supported") { isWindowsForTask }
  doLast {
    fun sha256Hex(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      return HexFormat.of().formatHex(digest.digest())
    }
    // When a signed mirror is active, a per-file hash/size mismatch is EXPECTED (signing rewrites the
    // inner PEs) and the manifest's files[] pins must be regenerated before the release can be cut.
    // Point the operator at that step instead of failing with a bare pin mismatch.
    fun mismatchHint(detail: String): String =
        if (overrideActive) {
          "$detail\n" +
              "A tesseractSourceUrl/Sha256 signed-mirror override is ACTIVE, so this per-file mismatch " +
              "is expected: signing rewrites the inner PE bytes and invalidates the files[] pins in " +
              "packaging/runtime/tesseract-windows.v1.json. Regenerate them from the signed archive " +
              "with `node scripts/release/regen-tesseract-manifest.mjs <tesseract-...-signed.zip>` " +
              "before cutting the release, then re-run this task. Per-file verification is " +
              "NOT skipped for signed mirrors."
        } else {
          detail
        }
    val manifest =
        ObjectMapper().readValue(manifestFile, Map::class.java) as Map<*, *>
    @Suppress("UNCHECKED_CAST")
    val files = manifest["files"] as List<Map<String, Any>>
    for (entry in files) {
      val path = entry["path"] as String
      val expectedSha = (entry["sha256"] as String).lowercase(Locale.ROOT)
      val expectedSize = (entry["sizeBytes"] as Number).toLong()
      val file = runtimeDir.resolve(path.replace('/', File.separatorChar))
      if (!file.isFile) {
        throw GradleException("Missing Tesseract runtime file declared by manifest: ${file.absolutePath}")
      }
      if (file.length() != expectedSize) {
        throw GradleException(
            mismatchHint(
                "Tesseract runtime file size mismatch for $path: expected $expectedSize, got ${file.length()}"))
      }
      val actualSha = sha256Hex(file).lowercase(Locale.ROOT)
      if (actualSha != expectedSha) {
        throw GradleException(
            mismatchHint("Tesseract runtime SHA-256 mismatch for $path: expected $expectedSha, got $actualSha"))
      }
    }

    val tesseract = runtimeDir.resolve("tesseract.exe")
    val tessdata = runtimeDir.resolve("tessdata")
    val process =
        ProcessBuilder(tesseract.absolutePath, "--list-langs")
            .redirectErrorStream(true)
            .apply { environment()["TESSDATA_PREFIX"] = tessdata.absolutePath }
            .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw GradleException("Tesseract runtime validation timed out")
    }
    val output = process.inputStream.bufferedReader().readText()
    if (process.exitValue() != 0 || !output.lineSequence().any { it.trim() == "eng" }) {
      throw GradleException("Tesseract runtime validation failed; expected --list-langs to include eng. Output: $output")
    }
  }
}

// Tempdoc 772 §G + §J item 2 — Trim two classes of dead/relocatable native from the
// onnxruntime_gpu jar before it is staged into the shipped Windows installer. The upstream Maven
// artifact `com.microsoft.onnxruntime:onnxruntime_gpu` is published as a single fat jar that bundles
// native libraries for EVERY platform (win-x64 AND linux-x64) — no per-OS classifier exists.
//
// Two exclusions:
//   1. §G — `ai/onnxruntime/native/linux-x64/**` (~201 MB, incl. a ~316 MB-uncompressed
//      `libonnxruntime_providers_cuda.so`). ONNX Runtime's Java loader
//      (`OnnxRuntime.initOsArch()` / `extractFromResources()`) only ever reads
//      `/ai/onnxruntime/native/<current-JVM-OS_ARCH>/…`, so on this Windows-only product the
//      Linux natives are provably dead weight.
//   2. §J item 2 — `ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll` (the ~164 MB
//      Windows CUDA execution-provider DLL). This DLL is strictly useless without the CUDA
//      dependency DLLs, which only ever arrive via the consent-gated `cuda-runtime` Install-AI
//      pack. Rather than ship it to every user (36% of the download) it is relocated INTO that
//      pack: the `cuda-runtime` package's new `ort-native-cuda12-v1.24.3.zip` supporting file
//      carries the complete ORT native set (this EP DLL + the core trio) into the pack's cuda12
//      dir, and the Engine points ORT at that dir via the `onnxruntime.native.path` system
//      property when the set is present (see `OrtCudaHelper.applyOrtNativePackProperty` +
//      `HeadlessApp.main`). Probe-validated (tempdoc 772 §J): with this DLL absent from the jar
//      and no property set, `addCUDA` fails with the legible `ORT_EP_FAIL "Failed to find CUDA
//      shared provider"` and CPU inference still works from the retained core natives; with the
//      property pointed at a complete external set, real CUDA sessions succeed.
//
// The core trio (`onnxruntime.dll`, `onnxruntime_providers_shared.dll`) plus the JNI shim
// (`onnxruntime4j_jni.dll`) are KEPT in the jar — they serve the property-unset / CPU path (ORT
// extracts them from the jar to %TEMP% as before). Only the CUDA EP DLL is removed.
//
// Same shape as stageLlamaCudaVariant's `exclude("**/ggml-rpc.dll")`: repackage a jar with
// `exclude`s during staging. Windows-only packaging path — the jar on the module classpath (used
// by the ubuntu-latest CI test lanes) is untouched.
//
// Lane F stage A item A13 moved the SOURCE of this jar. It used to be read out of the Worker's
// own `installDist` output, because the shipped bundle carried a whole second copy of the index
// half under `lib/worker/`. There is one distribution now: `onnxruntime_gpu` reaches the Engine's
// `headlessDist` lib/ through `ui -> app-engine -> indexer-worker` (verify with
// `./gradlew :modules:ui:dependencies --configuration runtimeClasspath`), so the trim reads and
// replaces THAT copy. Before A13 the untrimmed 371 MB jar was in `lib/` as well as `lib/worker/`,
// and only the `lib/worker/` copy was trimmed.
val trimmedOnnxRuntimeGpuDir = layout.buildDirectory.dir("onnxruntime-gpu-trimmed")
val stageTrimmedOnnxRuntimeGpu by tasks.registering(Jar::class) {
  group = "distribution"
  description = "Repackage the onnxruntime_gpu jar without its Linux natives or win-x64 CUDA EP DLL (Windows-only installer). Tempdoc 772 §G + §J item 2."
  dependsOn(headlessDist)
  // headlessDist copies the runtimeClasspath jars into lib/ under their original Maven names, so
  // the untrimmed jar lands at a path we can derive statically from the single source of truth for
  // the version — the version catalog (libs.versions.onnxruntime), NOT a hardcoded string — so a
  // future onnxruntime bump needs no change here. A static file path (rather than a glob resolved
  // at configuration time) is also what keeps the enclosing `zipTree(...)` off the
  // config-cache-hostile path, mirroring stageLlamaCudaVariant's `zipTree(cudaZipFile)`.
  val onnxGpuJarName = "onnxruntime_gpu-${libs.versions.onnxruntime.get()}.jar"
  val originalJar =
      headlessDistDir.get().asFile.resolve("lib").resolve(onnxGpuJarName)
  from(zipTree(originalJar)) {
    exclude("ai/onnxruntime/native/linux-x64/**")
    // §J item 2: the win-x64 CUDA EP DLL moves to the cuda-runtime pack (see comment above).
    exclude("ai/onnxruntime/native/win-x64/onnxruntime_providers_cuda.dll")
  }
  destinationDirectory.set(trimmedOnnxRuntimeGpuDir)
  // Fixed output name (not the version-stamped original): the Engine loads it via `-cp lib/*`, so
  // the exact jar filename is immaterial.
  archiveFileName.set("onnxruntime_gpu-trimmed.jar")
}

val bundleSidecarResources by tasks.registering(Sync::class) {
  group = "distribution"
  description = "Stage headless jar, libs, and custom runtime into the Tauri shell resources"
  dependsOn(headlessDist, createHeadlessRuntime, copyJavawToRuntime,
      generateHeadAotCache, stageLlamaServer, stageOnnxModels,
      stageOrtCudaVariant, verifyTesseractRuntime, stageTrimmedOnnxRuntimeGpu)
  includeEmptyDirs = false
  into(tauriHeadlessResourcesDir)
  // Lane F stage A item A13: ONE classpath. The bundle used to carry the Engine's `lib/` plus a
  // second, near-identical copy of the index half under `lib/worker/` — the Worker distribution
  // the deleted WorkerSpawner launched. Since item A6 the index half is composed in this JVM and
  // `ui`'s own runtimeClasspath already contains indexer-worker, Lucene, Tika, SQLite and
  // onnxruntime_gpu, so `lib/worker/` was a duplicate the shipped installer paid for twice.
  // The untrimmed onnxruntime_gpu jar is excluded here and replaced (below) by the
  // Linux-natives-stripped variant from stageTrimmedOnnxRuntimeGpu — Tempdoc 772 §G.
  from(headlessDistDir) {
    include("ui-headless.jar")
    include("lib/**")
    exclude("lib/onnxruntime_gpu-*.jar")
  }
  // Tempdoc 657 — the Headless Runtime launcher: runs the co-located ui-headless.jar as a local,
  // loopback-only service (no desktop shell) in a chosen mode. See docs/how-to/headless-runtime.md.
  from(rootProject.layout.projectDirectory.dir("packaging/headless")) {
    include("justsearch-headless.cmd", "justsearch-headless.ps1")
  }
  // Tempdoc 772 §G — the trimmed onnxruntime_gpu jar (Linux natives + CUDA EP DLL removed) lands
  // in lib/ where the untrimmed original was excluded above.
  from(stageTrimmedOnnxRuntimeGpu.map { it.archiveFile }) {
    into("lib")
  }
  // AOT cache file for the Engine (JEP 514). Item A13: one JVM, one cache — `worker/worker.aot`
  // was deleted with the task that produced it.
  from(aotCacheDir) {
    include("head/head.aot")
    eachFile { relativePath = org.gradle.api.file.RelativePath(true, name) }
    into("aot")
  }
  // Bundle SSOT so repo-layout discovery works in a packaged app.
  from(rootProject.layout.projectDirectory.dir("SSOT")) {
    into("SSOT")
  }
  // Bundle a production-safe config for the desktop sidecar.
  from(layout.projectDirectory.file("src/main/resources/headless-config/application.yaml")) {
    into("config")
    rename { "application.yaml" }
  }
  from(headlessRuntimeImageDir) {
    into("runtime")
  }

  // Bundle the llama-server runtime (CPU-only) into the sidecar payload.
  if (isWindowsHost) {
    from(llamaStageDir) {
      into("native-bin/llama-server")
    }
  }

  // Bundle the verified Tesseract OCR runtime payload. The directory is app-owned and restored
  // into AI Home by the shell, just like llama-server.
  if (isWindowsHost) {
    from(tesseractRuntimeStageDir) {
      into("native-bin/tesseract")
    }
    from(tesseractRuntimeManifestFile) {
      into("native-bin/tesseract")
      rename { "manifest.justsearch.json" }
    }
    from(rootProject.layout.projectDirectory.file("packaging/runtime/NOTICE-TESSERACT.txt")) {
      into("native-bin/tesseract")
    }
    // GPL-2.0 compliance for the bundled libjbig-0.dll codec (tempdoc 632, libjbig "keep" decision):
    // GPL-2.0 §1 requires shipping the license text and §3 a corresponding-source / written offer
    // alongside the binary. Both land next to the DLL in native-bin/tesseract/.
    from(rootProject.layout.projectDirectory.file("packaging/runtime/NOTICE-JBIGKIT.txt")) {
      into("native-bin/tesseract")
    }
    from(rootProject.layout.projectDirectory.file("packaging/runtime/LICENSE-GPL-2.0.txt")) {
      into("native-bin/tesseract")
    }
  }

  // Bundle ORT CUDA variant for GPU-accelerated ONNX inference (embedding, SPLADE, NER, reranker).
  if (includeOrtCuda) {
    from(ortCudaStageDir) {
      into("native-bin/onnxruntime/cuda12")
    }
  }

  // Bundle ONNX cross-encoder models for search reranking and citation scoring.
  if (includeOnnxModels) {
    from(onnxStageDir)
  }
}

tasks.named("headlessDist") {
  finalizedBy(bundleSidecarResources)
}

tasks.register("bundleSidecar") {
  group = "distribution"
  description = "Build the headless sidecar artifacts and copy them into the Tauri shell bundle"
  dependsOn(bundleSidecarResources)
}

tasks.register("smokeSidecarBundle") {
  group = "verification"
  description = "Smoke test the staged Tauri headless bundle (spawn HeadlessApp + hit /api/status)."
  notCompatibleWithConfigurationCache("Spawns external java process and performs HTTP calls")
  dependsOn(bundleSidecarResources)

  doLast {
    val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
    val headlessDir = project.layout.projectDirectory.dir("../shell/src-tauri/resources/headless").asFile
    val javaBin = headlessDir.resolve("runtime").resolve("bin").resolve(if (isWindows) "java.exe" else "java")
    val uiJar = headlessDir.resolve("ui-headless.jar")
    val libGlob = headlessDir.resolve("lib").resolve("*").absolutePath
    val cpSep = if (isWindows) ";" else ":"
    val cp = uiJar.absolutePath + cpSep + libGlob

    if (!javaBin.exists()) throw GradleException("Missing bundled java runtime: ${javaBin.absolutePath}")
    if (!uiJar.exists()) throw GradleException("Missing ui-headless.jar: ${uiJar.absolutePath}")
    if (isWindows) {
      // Tauri shell uses javaw.exe to spawn backend without console window
      val javawBin = headlessDir.resolve("runtime").resolve("bin").resolve("javaw.exe")
      if (!javawBin.exists()) throw GradleException("Missing javaw.exe in bundled runtime: ${javawBin.absolutePath}")
      val serverExe = headlessDir.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe")
      if (!serverExe.exists()) {
        throw GradleException("Missing bundled llama-server.exe: ${serverExe.absolutePath}")
      }
      val serverDir = headlessDir.resolve("native-bin").resolve("llama-server")
      val requiredFiles =
        listOf(
          "llama.dll",
          "ggml.dll",
          "ggml-base.dll",
          "mtmd.dll",
          "libcurl-x64.dll",
          "libomp140.x86_64.dll",
          "msvcp140_codecvt_ids.dll",
          "runtime-version.txt"
        )
      val missing = requiredFiles.filter { !serverDir.resolve(it).exists() }
      val cpuBackends =
        serverDir.listFiles()
          ?.filter { it.isFile && it.name.lowercase(Locale.ROOT).startsWith("ggml-cpu") && it.name.lowercase(Locale.ROOT).endsWith(".dll") }
          ?: emptyList()
      if (missing.isNotEmpty() || cpuBackends.isEmpty()) {
        val present = serverDir.listFiles()?.map { it.name }?.sorted()?.joinToString(", ") ?: "(unreadable)"
        val cpuMsg = if (cpuBackends.isEmpty()) " Missing ggml-cpu*.dll backends." else ""
        throw GradleException(
            "Bundled llama-server payload incomplete.$cpuMsg Missing: ${missing.joinToString(", ")}. Present in ${serverDir.absolutePath}: $present"
        )
      }
    }
    // Lane F stage A item A13 replaced the "lib/worker/ exists" check. There is no second
    // distribution to look for; the property that matters now is that the index half and its
    // trimmed ORT jar are on the ONE classpath the Engine launches with (`-cp ui-headless.jar;lib/*`).
    // Checking for the directory's absence would assert nothing; checking for these two jars
    // catches the real failure mode — a `lib/` that boots the API and then cannot open an index.
    run {
      val libDir = headlessDir.resolve("lib")
      val libJars = libDir.listFiles()?.filter { it.isFile && it.extension == "jar" } ?: emptyList()
      val indexHalf = libJars.filter { it.name.startsWith("indexer-worker-") || it.name.startsWith("worker-services-") }
      if (indexHalf.size < 2) {
        throw GradleException(
            "Bundled lib/ is missing the index half of the Engine (expected indexer-worker-*.jar AND "
              + "worker-services-*.jar in ${libDir.absolutePath}; found: "
              + "${indexHalf.joinToString(", ") { it.name }.ifEmpty { "none" }})")
      }
      val onnx = libJars.filter { it.name.startsWith("onnxruntime_gpu") }
      if (onnx.size != 1 || onnx[0].name != "onnxruntime_gpu-trimmed.jar") {
        throw GradleException(
            "Bundled lib/ must carry exactly the trimmed onnxruntime_gpu jar (tempdoc 772 §G/§J); found: "
              + "${onnx.joinToString(", ") { it.name }.ifEmpty { "none" }} in ${libDir.absolutePath}")
      }
    }
    if (!headlessDir.resolve("SSOT").isDirectory) {
      throw GradleException("Missing SSOT/ in bundle: ${headlessDir.resolve("SSOT").absolutePath}")
    }
    if (!headlessDir.resolve("config").resolve("application.yaml").exists()) {
      throw GradleException("Missing config/application.yaml in bundle: ${headlessDir.resolve("config/application.yaml").absolutePath}")
    }
    if (includeOnnxModels) {
      val onnxDir = headlessDir.resolve("models").resolve("onnx")
      val requiredOnnx = listOf(
        "gte-multilingual-base/model_fp16.onnx",
        "gte-multilingual-base/tokenizer.json",
        "gte-multilingual-base/model_manifest.json",
        "reranker/model.onnx",
        "reranker/model_fp16.onnx",
        "reranker/tokenizer.json",
        "citation-scorer/model.onnx",
        "citation-scorer/tokenizer.json",
        "ner/model.onnx",
        "ner/model_fp16.onnx",
        "ner/tokenizer.json",
      )
      val missingOnnx = requiredOnnx.filter { !onnxDir.resolve(it).exists() }
      if (missingOnnx.isNotEmpty()) {
        throw GradleException("Missing ONNX model files in bundle: ${missingOnnx.joinToString(", ")}")
      }
      // SPLADE models live under models/splade/ (not models/onnx/)
      val spladeDir = headlessDir.resolve("models").resolve("splade").resolve("naver-splade-v3")
      val requiredSplade = listOf("model.onnx", "model_fp16.onnx", "tokenizer.json", "vocab.txt", "idf.json")
      val missingSplade = requiredSplade.filter { !spladeDir.resolve(it).exists() }
      if (missingSplade.isNotEmpty()) {
        throw GradleException("Missing SPLADE model files in bundle: ${missingSplade.joinToString(", ")}")
      }
      // Size sanity: models should be >10 MB (catches truncated or empty files)
      listOf("reranker/model.onnx", "citation-scorer/model.onnx",
             "ner/model.onnx", "gte-multilingual-base/model_fp16.onnx").forEach { rel ->
        val f = onnxDir.resolve(rel)
        if (f.length() < 10 * 1024 * 1024) {
          throw GradleException("ONNX model $rel is suspiciously small (${f.length()} bytes) — possible truncated file")
        }
      }
      if (spladeDir.resolve("model.onnx").length() < 10 * 1024 * 1024) {
        throw GradleException("SPLADE model.onnx is suspiciously small — possible truncated file")
      }
      val noticeFile = headlessDir.resolve("models").resolve("NOTICE-MODELS.txt")
      if (!noticeFile.exists()) {
        throw GradleException("Missing models/NOTICE-MODELS.txt in bundle")
      }
    }

    // Use a unique per-run data directory to avoid stale locks/state between smoke runs.
    val dataDir = layout.buildDirectory.get().asFile
        .resolve("smoke")
        .resolve("sidecar-data-" + System.currentTimeMillis())
    dataDir.mkdirs()
    // Tempdoc 501 Phase 18: read the canonical runtime manifest instead of the
    // deprecated api-port.txt mirror.
    val manifestFile = dataDir.toPath().resolve("runtime").resolve("manifest.json")

    val configPath = headlessDir.resolve("config").resolve("application.yaml").absolutePath
    val ssotPath = headlessDir.resolve("SSOT").absolutePath
    val pluginsManifest =
        headlessDir.resolve("SSOT").resolve("manifests").resolve("plugins").resolve("pipeline-stage-plugins.v1.json").absolutePath

    val indexBasePath = dataDir.toPath().resolve("index").resolve("default").toAbsolutePath().toString()

    val command = listOf(
        javaBin.absolutePath,
        "-Djustsearch.prod=true",
        "-Djustsearch.data.dir=${dataDir.absolutePath}",
        "-Djustsearch.ui.settings.mode=IN_MEMORY",
        "-Djustsearch.index.base_path=$indexBasePath",
        "-Djustsearch.config=$configPath",
        "-Djustsearch.repo.root=${headlessDir.absolutePath}",
        "-Djustsearch.ssot.path=$ssotPath",
        "-Djustsearch.plugins.manifest=$pluginsManifest",
        "-cp",
        cp,
        "io.justsearch.ui.HeadlessApp"
    )

    val portRef = AtomicInteger(0)
    val latch = CountDownLatch(1)
    val outputTail = ArrayDeque<String>()
    val outputLock = Any()
    val pb = ProcessBuilder(command)
        .directory(headlessDir)
        .redirectErrorStream(true)
    val proc = pb.start()

    val readerThread = Thread {
      proc.inputStream.bufferedReader().useLines { lines ->
        for (line in lines) {
          synchronized(outputLock) {
            if (outputTail.size >= 200) {
              outputTail.removeFirst()
            }
            outputTail.addLast(line)
          }
          if (line.startsWith("JUSTSEARCH_API_PORT=")) {
            val value = line.substringAfter("JUSTSEARCH_API_PORT=").trim()
            val parsed = value.toIntOrNull()
            if (parsed != null && parsed > 0 && portRef.compareAndSet(0, parsed)) {
              latch.countDown()
            }
          }
        }
      }
    }
    readerThread.isDaemon = true
    readerThread.start()

    // Tempdoc 501 Phase 18: fallback discovery via the canonical runtime manifest
    // (the HeadlessApp writes dataDir/runtime/manifest.json with head.apiPort).
    val portFileWatcher = Thread {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
      val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()
      while (System.nanoTime() < deadline && portRef.get() <= 0) {
        try {
          if (Files.exists(manifestFile)) {
            val tree = jsonMapper.readTree(Files.readString(manifestFile))
            val parsed = tree?.path("head")?.path("apiPort")?.asInt(0) ?: 0
            if (parsed > 0 && portRef.compareAndSet(0, parsed)) {
              latch.countDown()
              return@Thread
            }
          }
        } catch (_: Exception) {
          // ignore and retry — partial write
        }
        try {
          Thread.sleep(100)
        } catch (_: InterruptedException) {
          return@Thread
        }
      }
    }
    portFileWatcher.isDaemon = true
    portFileWatcher.start()

    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    var gotPort = false
    while (System.nanoTime() < deadline) {
      if (portRef.get() > 0) {
        gotPort = true
        break
      }
      if (!proc.isAlive) {
        break
      }
      if (latch.await(200, TimeUnit.MILLISECONDS)) {
        gotPort = true
        break
      }
    }
    val port = portRef.get()
    if (!gotPort || port <= 0) {
      val exitCode = runCatching { proc.exitValue() }.getOrNull()
      val tail = synchronized(outputLock) { outputTail.joinToString("\n") }
      // Best-effort teardown
      if (isWindows) {
        try {
          ProcessBuilder("taskkill", "/PID", proc.pid().toString(), "/T", "/F").start().waitFor()
        } catch (_: Exception) {}
      }
      proc.destroyForcibly()
      val portFileMsg =
          if (Files.exists(manifestFile)) " manifest=$manifestFile contents='${runCatching { Files.readString(manifestFile).trim() }.getOrNull()}'"
          else " manifest=$manifestFile (missing)"
      throw GradleException(
        "Sidecar smoke failed: did not observe JUSTSEARCH_API_PORT=... or runtime manifest within 60s." +
            " exitCode=$exitCode alive=${proc.isAlive}.$portFileMsg\n" +
            "---- headless stdout/stderr tail ----\n" +
            (if (tail.isBlank()) "<no output captured>" else tail) + "\n" +
            "---- end tail ----"
      )
    }

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    val mapper = ObjectMapper()

    fun teardownAndFail(message: String): Nothing {
      // Best-effort teardown
      if (isWindows) {
        try {
          ProcessBuilder("taskkill", "/PID", proc.pid().toString(), "/T", "/F").start().waitFor()
        } catch (_: Exception) {}
      }
      proc.destroyForcibly()
      throw GradleException(message)
    }

    // Hit /api/status and assert the Worker is reachable (deterministic "READY" signal for the bundle).
    val statusUri = URI("http://127.0.0.1:$port/api/status")
    val statusReq = HttpRequest.newBuilder(statusUri)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build()
    val statusResp = client.send(statusReq, HttpResponse.BodyHandlers.ofString())
    if (statusResp.statusCode() != 200) {
      teardownAndFail("Sidecar smoke failed: /api/status returned ${statusResp.statusCode()} body=${statusResp.body()}")
    }
    val statusJson = try {
      mapper.readTree(statusResp.body())
    } catch (e: Exception) {
      teardownAndFail("Sidecar smoke failed: /api/status was not valid JSON: ${e.message} body=${statusResp.body()}")
    }
    val indexAvailable = statusJson.path("indexAvailable").asBoolean(false)
    val indexState = statusJson.path("indexState").asText("")
    val ksError = statusJson.path("knowledgeServerStartError").asText("")
    if (!indexAvailable) {
      teardownAndFail("Sidecar smoke failed: Worker not reachable (indexAvailable=false, indexState=$indexState, knowledgeServerStartError=$ksError)")
    }

    // Hit /api/health and assert worker READY (do not require overall READY; inference may be DEGRADED in bundle runs).
    val healthUri = URI("http://127.0.0.1:$port/api/health")
    val healthReq = HttpRequest.newBuilder(healthUri)
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build()
    val healthResp = client.send(healthReq, HttpResponse.BodyHandlers.ofString())
    if (healthResp.statusCode() != 200) {
      teardownAndFail("Sidecar smoke failed: /api/health returned ${healthResp.statusCode()} body=${healthResp.body()}")
    }
    val healthJson = try {
      mapper.readTree(healthResp.body())
    } catch (e: Exception) {
      teardownAndFail("Sidecar smoke failed: /api/health was not valid JSON: ${e.message} body=${healthResp.body()}")
    }
    val workerState = healthJson.path("components").path("worker").path("state").asText("")
    if (workerState != "READY") {
      teardownAndFail("Sidecar smoke failed: /api/health components.worker.state=$workerState body=${healthResp.body()}")
    }

    // Teardown: kill the process tree on Windows; best-effort elsewhere.
    if (isWindows) {
      try {
        ProcessBuilder("taskkill", "/PID", proc.pid().toString(), "/T", "/F").start().waitFor()
      } catch (_: Exception) {}
    }
    proc.destroy()
    proc.waitFor(5, TimeUnit.SECONDS)
    if (proc.isAlive) {
      proc.destroyForcibly()
    }
  }
}

application {
  mainClass.set("io.justsearch.ui.HeadlessApp")
  applicationDefaultJvmArgs = listOf(
    "--sun-misc-unsafe-memory-access=warn",
    "--enable-native-access=ALL-UNNAMED",
  )
}

// Reduce Windows command length by collapsing the classpath to a single wildcard entry.
// Without this, the generated ui.bat exceeds the ~8191 char command line limit (100+ JARs).
// Pattern copied from modules/app-launcher/build.gradle.kts:114-129.
tasks.withType<CreateStartScripts>().configureEach {
  doLast {
    val os = OperatingSystem.current()
    if (os.isWindows) {
      val windowsText = windowsScript.readText()
      val collapsed =
          windowsText.replace(
              Regex("(?m)^set CLASSPATH=.*$", RegexOption.MULTILINE),
              "set CLASSPATH=%APP_HOME%\\\\lib\\\\*")
      windowsScript.writeText(collapsed)
    } else {
      val unixText = unixScript.readText()
      // Lambda form: the string-replacement overload treats '$' as a group reference, so
      // "$APP_HOME" -> "$A" throws "Illegal group reference" when building on Linux (tempdoc 668).
      val collapsed = Regex("(?m)^CLASSPATH=.*$").replace(unixText) { "CLASSPATH=\"\$APP_HOME/lib/*\"" }
      unixScript.writeText(collapsed)
    }
  }
}

/**
 * Detects ORT CUDA DLLs in the standard variant test directory (tempdoc 329: shared infra).
 *
 * Checks [projectRoot]/tmp/ort-variant-test/cuda-12.4-v1.24.3/ for onnxruntime_providers_cuda.dll.
 * If the project root is a git worktree, also checks the main repo root. Returns the absolute
 * path to the detected directory, or null if not found.
 */
fun detectOrtCudaPath(projectRoot: File): String? {
  val cudaDirName = "tmp/ort-variant-test/cuda-12.4-v1.24.3"
  val cudaDllName = "onnxruntime_providers_cuda.dll"
  val candidates = mutableListOf(projectRoot)
  // In a git worktree, .git is a file (not a directory) containing "gitdir: <path>".
  // Resolve the main repo root from it so we can find shared tmp/ resources.
  val dotGit = File(projectRoot, ".git")
  if (dotGit.isFile) {
    val gitdirLine = dotGit.readText().trim()
    if (gitdirLine.startsWith("gitdir:")) {
      val mainGitDir = File(gitdirLine.substringAfter("gitdir:").trim())
      // .git/worktrees/<name> → .git → repo root
      val mainRepoRoot = mainGitDir.parentFile?.parentFile?.parentFile
      if (mainRepoRoot != null && mainRepoRoot.exists()) {
        candidates.add(mainRepoRoot)
      }
    }
  }
  return candidates
    .map { File(it, cudaDirName) }
    .firstOrNull { it.exists() && it.resolve(cudaDllName).exists() }
    ?.absolutePath
}

/** Env vars forwarded from operator shell to Head JVM for dev/profiling runs (tempdoc 329). */
val HEADLESS_AI_ENV_VARS = listOf(
    "JUSTSEARCH_SERVER_EXE",
    "JUSTSEARCH_MODELS_DIR",
    "JUSTSEARCH_SERVER_PORT",
    "JUSTSEARCH_AI_DISABLED",
    "JUSTSEARCH_HOME",
    "JUSTSEARCH_EMBED_BACKEND",
    "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
    "JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH",
    "JUSTSEARCH_NATIVE_PATH", // deprecated — prefer JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH
    "JUSTSEARCH_GPU_ENABLED",
    "JUSTSEARCH_SPLADE_GPU_ENABLED",
    "JUSTSEARCH_DATA_DIR",
    "JUSTSEARCH_INDEX_TRACING_LEVEL",
    "JUSTSEARCH_LLM_ENABLED",       // 363: needed for QU eval
    "JUSTSEARCH_LLM_MODEL_PATH",    // 363: needed for QU eval
    "JUSTSEARCH_AI_AUTOSTART_ENABLED",  // 369: eval LLM autostart
    "JUSTSEARCH_AI_AUTOSTART_DISABLED", // 369: eval LLM autostart
    "JUSTSEARCH_CONTEXT_SIZE",          // 366: LLM context window for eval
    "JUSTSEARCH_CHAT_PROFILE",          // 842: chat model profile (standard|compact)
    // Tempdoc 410 §13 / Slice B (operator-configurable IngestionSkipPolicy).
    // Production env-inheritance works naturally; eval mode needs explicit
    // forwarding because applyHeadlessEvalContract whitelist-filters env vars.
    "JUSTSEARCH_INGESTION_SKIP_PATTERNS",
    "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS",
    "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES",
    // Tempdoc 789 Phase 2: MCP delivery framings must be arm-selectable in eval
    // mode; without explicit forwarding the whitelist silently drops them (the
    // probe's framing-presence smoke caught this before any spend, 2026-07-28).
    "JUSTSEARCH_SEARCH_MCP_FRAMING_CONTINUATION_ENABLED",
    "JUSTSEARCH_SEARCH_MCP_FRAMING_EVIDENCE_NOT_ANSWER_ENABLED",
    "JUSTSEARCH_SEARCH_MCP_FRAMING_CALIBRATED_ABSENCE_ENABLED",
    "JUSTSEARCH_SEARCH_MCP_FRAMING_THIN_RESULT_FLOOR_BYTES",
    // Tempdoc 771 item (b): entity carriage must be arm-selectable in eval mode for the same
    // reason the 789 framings are — applyHeadlessEvalContract whitelist-filters env vars, so an
    // unlisted knob is silently dropped and the arm measures the default.
    "JUSTSEARCH_SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_ENABLED",
    "JUSTSEARCH_SEARCH_MCP_DELIVERY_ENTITY_CARRIAGE_MAX_CHARS",
    "JUSTSEARCH_SEARCH_MCP_FRAMING_WEAK_SCORE_FLOOR",
    // Tempdoc 885 item 19 + item 14: the cadence and extraction-routing arms are selected per
    // eval run, and an unlisted knob is silently dropped here — the arm then measures the
    // default and the comparison table reads "no difference" for the wrong reason.
    // HeadlessEvalEnvAllowlistTest pins this block so a new cadence knob cannot be added to
    // EnvRegistry and forgotten here.
    "JUSTSEARCH_INDEX_NRT_MODE",
    "JUSTSEARCH_INDEX_NRT_BACKGROUND_REOPEN_MS",
    "JUSTSEARCH_INDEX_NRT_ON_DEMAND_MAX_STALE_MS",
    "JUSTSEARCH_BACKFILL_COMMIT_INTERVAL_MS",
    "JUSTSEARCH_BACKFILL_MAX_DOCS_BEFORE_COMMIT",
    // The safety-net commit timer (885's tracked item, made configurable in #605). It is the
    // CEILING on the other commit knobs above, so a commit-cadence arm that cannot select it
    // measures the same nothing the rejected A3 arm did.
    "JUSTSEARCH_INDEX_COMMIT_TIMER_INTERVAL_MS",
    "JUSTSEARCH_EXTRACTION_SANDBOX_MODE",
)

/** GPU/model env vars forwarded from operator shell for eval runs (tempdoc 329). */
val HEADLESS_GPU_ENV_VARS = listOf(
    "JUSTSEARCH_GPU_ENABLED",
    "JUSTSEARCH_GPU_LAYERS",
    "JUSTSEARCH_EMBED_GPU_ENABLED",
    "JUSTSEARCH_EMBED_GPU_MEM_MB",
    "JUSTSEARCH_EMBED_BACKEND",
    "JUSTSEARCH_SPLADE_GPU_ENABLED",
    "JUSTSEARCH_SPLADE_GPU_DEVICE_ID",
    "JUSTSEARCH_SPLADE_GPU_MEM_MB",
    "JUSTSEARCH_RERANK_GPU_ENABLED",
    "JUSTSEARCH_RERANK_GPU_MEM_MB",
    "JUSTSEARCH_NER_GPU_ENABLED",
    "JUSTSEARCH_NER_GPU_DEVICE_ID",
    "JUSTSEARCH_NER_GPU_MEM_MB",
    "JUSTSEARCH_EMBED_ONNX_MODEL_PATH",
    "JUSTSEARCH_INDEX_SCHEMA_MISMATCH_POLICY"
)

fun JavaExec.applyHeadlessEvalContract() {
  workingDir = rootProject.layout.projectDirectory.asFile
  val repoRootProvider =
    providers.environmentVariable("JUSTSEARCH_REPO_ROOT")
      .orElse(rootProject.layout.projectDirectory.asFile.absolutePath)
  val dataDirProvider =
    providers.environmentVariable("JUSTSEARCH_DATA_DIR")
      .orElse(rootProject.layout.projectDirectory.dir("tmp/headless-eval-data").asFile.absolutePath)
  val indexBasePathProvider =
    providers.environmentVariable("JUSTSEARCH_INDEX_BASE_PATH")
      .orElse(dataDirProvider.map { dataDir ->
        File(dataDir).toPath().resolve("index").resolve("default").toString()
      })
  val ssotPathProvider =
    providers.environmentVariable("JUSTSEARCH_SSOT_PATH")
      .orElse(repoRootProvider.map { repoRoot -> File(repoRoot, "SSOT").absolutePath })
  val pluginsManifestProvider =
    providers.environmentVariable("JUSTSEARCH_STAGE_PLUGIN_MANIFEST")
      .orElse(
        repoRootProvider.map { repoRoot ->
          File(repoRoot, "SSOT/manifests/plugins/pipeline-stage-plugins.v1.json").absolutePath
        }
      )
  val configPathProvider =
    providers.environmentVariable("JUSTSEARCH_CONFIG")
      .orElse(
        project.layout.projectDirectory.file("src/main/resources/headless-config/application.yaml")
          .asFile.absolutePath
      )
  val modelsDirProvider =
    providers.environmentVariable("JUSTSEARCH_MODELS_DIR")
      .orElse(repoRootProvider.map { repoRoot -> File(repoRoot, "models").absolutePath })
  val serverExeProvider = providers.environmentVariable("JUSTSEARCH_SERVER_EXE")
  val ortNativePathProvider = providers.environmentVariable("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH")
  val apiPortProvider = providers.environmentVariable("JUSTSEARCH_API_PORT").orElse("33221")

  val repoRoot = repoRootProvider.get()
  val dataDir = dataDirProvider.get()
  val indexBasePath = indexBasePathProvider.get()
  val ssotPath = ssotPathProvider.get()
  val pluginsManifest = pluginsManifestProvider.get()
  val configPath = configPathProvider.get()
  val modelsDir = modelsDirProvider.get()
  val serverExe = serverExeProvider.orNull
  var ortNativePath = ortNativePathProvider.orNull
  // Auto-detect ORT CUDA DLLs when not explicitly set (ORT 1.24.3 + CUDA 12.4).
  // Without this, all ONNX GPU sessions fall back to CPU silently.
  if (ortNativePath.isNullOrBlank()) {
    ortNativePath = detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)
  }
  val apiPort = apiPortProvider.get()

  val envValues =
    linkedMapOf(
      "JUSTSEARCH_API_PORT" to apiPort,
      "JUSTSEARCH_DATA_DIR" to dataDir,
      "JUSTSEARCH_HOME" to dataDir,
      "JUSTSEARCH_UI_SETTINGS_MODE" to "IN_MEMORY",
      "JUSTSEARCH_INDEX_BASE_PATH" to indexBasePath,
      "JUSTSEARCH_REPO_ROOT" to repoRoot,
      "JUSTSEARCH_SSOT_PATH" to ssotPath,
      "JUSTSEARCH_STAGE_PLUGIN_MANIFEST" to pluginsManifest,
      "JUSTSEARCH_CONFIG" to configPath,
      "JUSTSEARCH_MODELS_DIR" to modelsDir,
    )
  if (!serverExe.isNullOrBlank()) {
    envValues["JUSTSEARCH_SERVER_EXE"] = serverExe
  }
  if (!ortNativePath.isNullOrBlank()) {
    envValues["JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH"] = ortNativePath
  }
  // GPU eval profile: forward GPU-related env vars when set by the caller.
  for (gpuVar in HEADLESS_GPU_ENV_VARS) {
    val value = providers.environmentVariable(gpuVar).orNull
    if (!value.isNullOrBlank()) {
      envValues[gpuVar] = value
    }
  }
  // AI env vars declared in HEADLESS_AI_ENV_VARS but not hardcoded above.
  // Mirrors the runHeadless task (line 1828) so keys like
  // JUSTSEARCH_INDEX_TRACING_LEVEL (400 Layer 2) and JUSTSEARCH_LLM_* reach
  // the Worker JVM in eval mode. Hardcoded keys in envValues take precedence
  // via LinkedHashMap insertion semantics (first write wins only when using
  // putIfAbsent — Map.set overwrites, so only forward missing keys).
  for (aiVar in HEADLESS_AI_ENV_VARS) {
    if (envValues.containsKey(aiVar)) continue
    val value = providers.environmentVariable(aiVar).orNull
    if (!value.isNullOrBlank()) {
      envValues[aiVar] = value
    }
  }
  // Auto-enable GPU inference via master switch when CUDA DLLs are detected (tempdoc 337).
  // Per-model overrides (EMBED_GPU_ENABLED, SPLADE_GPU_ENABLED, etc.) take precedence when set.
  if (!ortNativePath.isNullOrBlank()) {
    if (!envValues.containsKey("JUSTSEARCH_GPU_ENABLED")) {
      envValues["JUSTSEARCH_GPU_ENABLED"] = "true"
    }
  }
  envValues.forEach { (key, value) -> environment(key, value) }

  // Logback config is shipped on the ui-headless.jar classpath via
  // src/main/resources/logback.xml; no -Dlogback.configurationFile needed.
  // Tempdoc 374 alpha.23 follow-up — closes Jetty DEBUG noise + dev/prod drift.

  systemProperty("justsearch.api.port", apiPort)
  systemProperty("justsearch.data.dir", dataDir)
  systemProperty("justsearch.home", dataDir)
  systemProperty("justsearch.ui.settings.mode", "IN_MEMORY")
  systemProperty("justsearch.index.base_path", indexBasePath)
  systemProperty("justsearch.config", configPath)
  systemProperty("justsearch.repo.root", repoRoot)
  systemProperty("justsearch.ssot.path", ssotPath)
  systemProperty("justsearch.plugins.manifest", pluginsManifest)
  systemProperty("justsearch.models.dir", modelsDir)
  // 355: Enable eval-only endpoints (e.g. POST /api/debug/reset-index)
  systemProperty("justsearch.eval.mode", "true")
  if (!serverExe.isNullOrBlank()) {
    systemProperty("justsearch.server.exe", serverExe)
  }
  if (!ortNativePath.isNullOrBlank()) {
    systemProperty("justsearch.onnxruntime.native_path", ortNativePath)
  }
  // 363: Forward LLM config from env to system properties for QU eval
  val llmEnabled = providers.environmentVariable("JUSTSEARCH_LLM_ENABLED").orNull
  if (!llmEnabled.isNullOrBlank()) {
    systemProperty("justsearch.llm.enabled", llmEnabled)
  }
  val llmModelPath = providers.environmentVariable("JUSTSEARCH_LLM_MODEL_PATH").orNull
  if (!llmModelPath.isNullOrBlank()) {
    systemProperty("justsearch.llm.model_path", llmModelPath)
  }
  // 334 Phase 11: Profiling support. Enable via env vars:
  // JUSTSEARCH_JFR_ENABLED=true — starts Java Flight Recorder (writes to tmp/eval-profile.jfr)
  // JUSTSEARCH_ORT_PROFILING=true — enables ORT per-node profiling (writes Chrome trace to tmp/)
  val jfrEnabled = providers.environmentVariable("JUSTSEARCH_JFR_ENABLED").orNull
  if (jfrEnabled == "true") {
    val jfrPath = rootProject.layout.projectDirectory.dir("tmp").file("eval-profile.jfr").asFile.absolutePath
    jvmArgs("-XX:+FlightRecorder", "-XX:StartFlightRecording=filename=$jfrPath,duration=300s,settings=profile")
  }
  val ortProfiling = providers.environmentVariable("JUSTSEARCH_ORT_PROFILING").orNull
  if (ortProfiling == "true") {
    val profileDir = rootProject.layout.projectDirectory.dir("tmp").asFile.absolutePath
    environment("JUSTSEARCH_ORT_PROFILING_DIR", profileDir)
  }
  // 347: Removed env→sysprop bridges for EMBED_ONNX_MODEL_PATH and INDEX_SCHEMA_MISMATCH_POLICY.
  // These are forwarded as env vars via HEADLESS_GPU_ENV_VARS, and EnvRegistry.get() + contributeEnvRegistry()
  // both read env vars, so sysprop bridges are redundant.

  // 369: -Pllm=true enables LLM autostart with a generous health check timeout.
  // Usage: ./gradlew.bat :modules:ui:runHeadlessEval -Pllm=true
  // Server exe auto-detection is handled by InferenceConfig.findServerExecutable()
  // which searches dev-layout paths when justsearch.repo.root is set.
  val llmFlag = project.findProperty("llm")?.toString()?.lowercase() == "true"
  if (llmFlag) {
    environment("JUSTSEARCH_AI_AUTOSTART_ENABLED", "true")
    systemProperty("justsearch.ai.autostart.enabled", "true")
    // 3 minutes — sufficient for 9B models on modest hardware.
    systemProperty("justsearch.inference.health_check_timeout_ms", "180000")
  }
}

tasks.register<JavaExec>("runHeadless") {
  group = "application"
  description = "Run the headless UI backend (LocalApiServer) for dev"
  // Item A13: no Worker distribution to keep up to date — the headless app IS the Engine and runs
  // the index half in-process off this task's own runtimeClasspath.
  mainClass.set("io.justsearch.ui.HeadlessApp")
  classpath = sourceSets["main"].runtimeClasspath
  // 347: Use providers.environmentVariable() for config-cache compatibility.
  // System.getenv() captures daemon-start-time values; providers track inputs properly.
  val dataDirProvider = providers.environmentVariable("JUSTSEARCH_DATA_DIR").orElse("build/applauncher-data")
  val apiPortProvider = providers.environmentVariable("JUSTSEARCH_API_PORT").orElse("33221")
  jvmArgs("-Djustsearch.api.port=${apiPortProvider.get()}")
  jvmArgs("-Djustsearch.data.dir=${dataDirProvider.get()}")

  // Tempdoc 657 — install/runtime mode passthrough for dev: `-Pmode=mcp-lite` (or `headless`)
  // sets -Djustsearch.mode so the runtime manifest reports mode.intent and the planner would skip
  // the LLM tier. Unset ⇒ full-desktop (unchanged).
  val modeProp = project.findProperty("mode")?.toString()
  if (!modeProp.isNullOrBlank()) {
    jvmArgs("-Djustsearch.mode=$modeProp")
  }

  // Logback config is shipped on the ui-headless.jar classpath via
  // src/main/resources/logback.xml; no -Dlogback.configurationFile needed.
  // Tempdoc 374 alpha.23 follow-up — closes Jetty DEBUG noise + dev/prod drift.

  // 347: Forward both AI and GPU env vars (previously only AI was forwarded,
  // causing EMBED_GPU_ENABLED, NER_GPU_ENABLED etc. to be silently dropped).
  (HEADLESS_AI_ENV_VARS + HEADLESS_GPU_ENV_VARS).toSet().forEach { key ->
    val value = providers.environmentVariable(key).orNull
    if (!value.isNullOrBlank()) {
      environment(key, value)
    }
  }

  // Auto-detect ORT CUDA DLLs when not explicitly set (312 infra).
  // Without this, all ONNX GPU sessions fall back to CPU silently.
  var cudaDetected = false
  val ortEnv = providers.environmentVariable("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH").orNull
  if (ortEnv == null) {
    val detected = detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)
    if (detected != null) {
      environment("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", detected)
      cudaDetected = true
    }
  } else {
    cudaDetected = true  // Already have CUDA path, treat as detected
  }
  // 347: Auto-enable GPU master switch when CUDA DLLs are detected (like runHeadlessEval).
  // This cascades to all encoders via resolveMasterGpuEnabled() fallback.
  // Per-model overrides (JUSTSEARCH_EMBED_GPU_ENABLED=false) still take precedence.
  if (cudaDetected && providers.environmentVariable("JUSTSEARCH_GPU_ENABLED").orNull == null) {
    environment("JUSTSEARCH_GPU_ENABLED", "true")
  }
}

tasks.register<JavaExec>("runHeadlessEval") {
  group = "application"
  description = "Run the headless UI backend with isolated worktree/eval settings"
  mainClass.set("io.justsearch.ui.HeadlessApp")
  classpath = sourceSets["main"].runtimeClasspath
  applyHeadlessEvalContract()
}

// Profiling variant: runs headless with JFR enabled for performance analysis
// Usage: ./gradlew :modules:ui:runHeadlessWithProfiling -PjfrDuration=300 -PjfrOutput=tmp/profile.jfr
tasks.register<JavaExec>("runHeadlessWithProfiling") {
  group = "application"
  description = "Run headless with JFR profiling enabled (see docs/tempdocs/59-profiling-guide.md)"
  mainClass.set("io.justsearch.ui.HeadlessApp")
  classpath = sourceSets["main"].runtimeClasspath

  val jfrDuration = project.findProperty("jfrDuration")?.toString() ?: "300"
  val jfrOutput = project.findProperty("jfrOutput")?.toString()
    ?: rootProject.layout.projectDirectory.file("tmp/profile.jfr").asFile.absolutePath

  // Logback config is shipped on the ui-headless.jar classpath via
  // src/main/resources/logback.xml; no -Dlogback.configurationFile needed.
  // Tempdoc 374 alpha.23 follow-up — closes Jetty DEBUG noise + dev/prod drift.
  jvmArgs(
    "-Djustsearch.api.port=${System.getenv("JUSTSEARCH_API_PORT") ?: "33221"}",
    "-XX:+FlightRecorder",
    "-XX:StartFlightRecording=filename=$jfrOutput,duration=${jfrDuration}s,settings=profile"
  )

  // Pass through AI-related environment variables
  HEADLESS_AI_ENV_VARS.forEach { key ->
    System.getenv(key)?.let { value -> environment(key, value) }
  }

  // Auto-detect ORT CUDA DLLs when not explicitly set
  var cudaDetected = false
  if (System.getenv("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH") == null) {
    val detected = detectOrtCudaPath(rootProject.layout.projectDirectory.asFile)
    if (detected != null) {
      environment("JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH", detected)
      cudaDetected = true
    }
  }
  if (System.getenv("JUSTSEARCH_SPLADE_GPU_ENABLED") == null && cudaDetected) {
    environment("JUSTSEARCH_SPLADE_GPU_ENABLED", "true")
  }

  doFirst {
    logger.lifecycle("JFR profiling enabled: output=$jfrOutput, duration=${jfrDuration}s")
    logger.lifecycle("See docs/tempdocs/59-profiling-guide.md for analysis instructions")
  }
}

// Tempdoc 893 §D.2 — offline projection from the committed live-route capture. This proves only
// snapshot-to-derivative coherence; the live capture command owns router fidelity.
tasks.register<JavaExec>("generateReferenceClientOpenApiSnapshot") {
  group = "build"
  description = "Generate/check the classified reference-client OpenAPI snapshot"
  dependsOn(tasks.named("classes"))
  mainClass.set("io.justsearch.ui.api.OpenApiSnapshotExporter")
  classpath = sourceSets["main"].runtimeClasspath
  args(
    rootProject.layout.projectDirectory.file(
      "modules/ui-web/src/api/generated/route-manifest.snapshot.json"
    ).asFile.absolutePath,
    rootProject.layout.projectDirectory.file(
      "modules/ui-web/src/api/generated/reference-client-openapi.snapshot.json"
    ).asFile.absolutePath
  )
  if (providers.gradleProperty("referenceClientOpenApiCheck").orNull == "true") {
    args("--check")
  }
}
