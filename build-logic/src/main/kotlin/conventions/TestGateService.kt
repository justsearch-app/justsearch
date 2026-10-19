package conventions

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * A shared build service that gates test task parallelism.
 * JvmBaseConventionsPlugin defaults maxParallelUsages to 3 locally and 1 in CI,
 * limiting concurrent Test tasks even when org.gradle.parallel=true to prevent
 * heavyweight test JVM memory contention. Override with -PtestParallelism=N.
 */
abstract class TestGateService : BuildService<BuildServiceParameters.None>
