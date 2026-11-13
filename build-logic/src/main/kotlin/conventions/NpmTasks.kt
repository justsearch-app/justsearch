package conventions

import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

/**
 * Configuration-cache-compatible task to install npm dependencies.
 *
 * Uses `npm ci` (clean install): installs exactly what `package-lock.json` pins and, unlike
 * `npm install`, never rewrites the lockfile. This keeps builds reproducible and stops the
 * recurring `package-lock.json` peer/optional metadata churn that `npm install` produces and
 * that pollutes every worktree merge (tempdoc 618 §2). `npm ci` fails fast if `package.json`
 * and `package-lock.json` are out of sync — regenerate the lock via the explicit lockfile
 * workflow (`/lockfile`) rather than letting the build silently self-heal.
 */
abstract class NpmInstallTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** Package files (package.json, package-lock.json) that trigger reinstall. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageFiles: ConfigurableFileCollection

    /** Output node_modules directory. */
    @get:OutputDirectory
    abstract val nodeModulesDir: DirectoryProperty

    /** Working directory for npm execution. */
    @get:Internal
    abstract val workingDir: DirectoryProperty

    init {
        group = "build"
        description = "Install npm dependencies (npm ci — clean, lockfile-pinned, never rewrites the lock)"
    }

    @TaskAction
    fun run() {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
        val npm = if (isWindows) "npm.cmd" else "npm"

        execOperations.exec {
            workingDir = this@NpmInstallTask.workingDir.get().asFile
            commandLine(npm, "ci")
        }
    }
}

/**
 * Configuration-cache-compatible task to run npm build scripts.
 *
 * Runs a named npm script (default: "build") with proper input/output tracking.
 */
abstract class NpmBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    /** Web sources and assets (including public/ and index.html) that trigger rebuild. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** Build output directory. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Working directory for npm execution. */
    @get:Internal
    abstract val workingDir: DirectoryProperty

    /** npm script name to run (default: "build"). */
    @get:Input
    abstract val scriptName: Property<String>

    init {
        group = "build"
        description = "Run npm build script"
        scriptName.convention("build")
    }

    @TaskAction
    fun run() {
        val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
        val npm = if (isWindows) "npm.cmd" else "npm"

        execOperations.exec {
            workingDir = this@NpmBuildTask.workingDir.get().asFile
            commandLine(npm, "run", scriptName.get())
        }
    }
}
