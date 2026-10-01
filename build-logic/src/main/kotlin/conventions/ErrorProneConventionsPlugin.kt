package conventions

import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

class ErrorProneConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    // Apply the Error Prone Gradle plugin when Java is present
    project.plugins.withType(JavaPlugin::class.java) {
      project.plugins.apply("net.ltgt.errorprone")

      // Add Error Prone dependency to the dedicated configuration using the version catalog if available.
      val coordinateOrProvider: Any = run {
        // Attempt to pull from the project's 'libs' version catalog
        val catalogs = project.extensions.findByType(VersionCatalogsExtension::class.java)
        val provider: Provider<MinimalExternalModuleDependency>? =
            catalogs?.named("libs")?.findLibrary("errorprone-core")?.orElse(null)
        if (provider != null) {
          provider
        } else {
          // Fallback: read from root libs.versions.toml; final fallback to the policy-pinned version
          "com.google.errorprone:error_prone_core:${resolveErrorProneVersionFromCatalog(project) ?: "2.35.1"}"
        }
      }
      project.dependencies.add("errorprone", coordinateOrProvider)

      // Optional policy file to centrally disable specific checks
      val suppressionsFile =
          project.rootProject.layout.projectDirectory.file("config/errorprone/suppressions.txt")
      val suppressedChecks =
          project.providers
              .fileContents(suppressionsFile)
              .asText
              .map { text ->
                text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
              }

      // Skip Error Prone on test sources in local builds by default. Override with:
      //   -PskipErrorProneTests=false   (force enable locally)
      //   CI=true env var                (auto-enabled in CI)
      val skipErrorProneTests = project.providers.gradleProperty("skipErrorProneTests")
          .map { it.equals("true", ignoreCase = true) }
          .orElse(
              project.providers.environmentVariable("CI")
                  .map { it.isBlank() || it.equals("false", ignoreCase = true) }
                  .orElse(true)
          )

      // Configure Java compile tasks
      project.tasks.withType<JavaCompile>().configureEach {
        val taskName = name
        // Align with new Error Prone requirement for javac should-stop policy
        options.compilerArgs.add("--should-stop=ifError=FLOW")
        options.errorprone {
          // Disable Error Prone on test compilation tasks locally to speed up builds.
          // Test source quality is less critical than production code.
          if (skipErrorProneTests.get() && taskName != "compileJava") {
            isEnabled.set(false)
          }
          disableWarningsInGeneratedCode.set(true)
          // Protobuf generated sources often miss @Generated annotations.
          // Exclude build/generated trees to keep warning output focused on handwritten code.
          excludedPaths.set(".*[\\\\/]build[\\\\/]generated[\\\\/].*")
          // Promote specific javadoc-quality checks from warning to error so stale method refs
          // fail the build instead of accumulating silently. suppressions.txt wins if the same
          // check is listed there (disable runs after and overrides).
          error("InvalidLink")
          val list = suppressedChecks.orNull
          if (list != null && list.isNotEmpty()) disable(*list.toTypedArray())
        }
      }
    }
  }
}

private fun resolveErrorProneVersionFromCatalog(project: Project): String? {
  return try {
    val file = project.rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile
    if (!file.exists()) return null
    val regex = Regex("^\\s*errorprone\\s*=\\s*\\\"([^\\\"]+)\\\"\\s*$")
    file.useLines { lines ->
      lines.map { it.trim() }
          .dropWhile { it != "[versions]" }
          .mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
          .firstOrNull()
    }
  } catch (_: Exception) {
    null
  }
}
