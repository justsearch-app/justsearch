plugins {
  `java-library`
  id("jvm-test-suite")
  id("conventions.jvm-base")
  `maven-publish`
}

dependencies {
  // Lane F C1: EngineContext is an explicit parameter of the search and indexing contracts.
  api(project(":modules:core"))
  api(project(":modules:app-agent-api"))
  // configuration provides ModelRegistry, returned by AiInstallService.getManifest().
  // Added as part of tempdoc 519 §9 Block B2. configuration is a leaf module, no cycle.
  api(project(":modules:configuration"))
  // 548 §4.1 (tier-1 collapse): the generated proto LifecycleState enum is the SINGLE
  // authority for the lifecycle vocabulary. The hand-written enum is deleted; LifecycleSnapshotV1
  // records carry the proto enum directly, so it is part of app-api's public surface → `api`.
  api(project(":modules:api-contract-projection-java"))
  api(libs.jackson.databind)
  compileOnly(libs.record.builder.core)
  annotationProcessor(libs.record.builder.processor)
}

configurations
    .matching { cfg -> cfg.name.startsWith("test") || cfg.name.startsWith("integrationTest") }
    .configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
          useVersion("2.0.17")
          because("Lock convergence for app-api test classpaths")
        }
      }
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
        implementation(libs.jsonschema.generator)
        implementation(libs.jsonschema.module.jackson)
        implementation(libs.json.schema.validator)
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

tasks.withType<Test>().configureEach {
  // Tempdoc 696 follow-up: check the actual -P properties, NOT project.hasProperty("updateSchemas"),
  // which is ALWAYS true here because the `updateSchemas` task (registered below) shadows the property
  // name — that silently forced every test run into regenerate mode instead of compare, defeating the
  // schema-drift guard. startParameter.projectProperties is the -P map only, immune to task-name shadowing.
  if (gradle.startParameter.projectProperties.containsKey("updateSchemas")) {
    systemProperty("updateSchemas", "true")
  }
}

tasks.register<Test>("updateSchemas") {
  description = "Regenerate all schema baselines and contract fixtures"
  group = "verification"
  val testSS = sourceSets["test"]
  testClassesDirs = testSS.output.classesDirs
  classpath = testSS.runtimeClasspath
  useJUnitPlatform()
  filter {
    includeTestsMatching("*StatusRecordSchemaTest*")
    includeTestsMatching("*ErrorCatalogJsonArtifactTest*")
    includeTestsMatching("*SubstrateSchemaGenTest*")
    includeTestsMatching("*KnowledgeSearchResponseSchemaTest*")
    includeTestsMatching("*WireRecordSchemaGenTest*")
  }
  systemProperty("updateSchemas", "true")
}

// Ensure integrationTest inherits unit test dependencies
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.named("testImplementation").get())
}
configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.named("testRuntimeOnly").get())
}

dependencies {
  testImplementation("com.fasterxml:classmate:1.7.2")
}

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      from(components["java"])
      groupId = project.group.toString()
      artifactId = project.name
      version = project.version.toString()
    }
  }
  repositories {
    maven {
      name = "GitHubPackages"
      val ghRepo = System.getenv("GITHUB_REPOSITORY")
      if (ghRepo != null && ghRepo.contains('/')) {
        setUrl("https://maven.pkg.github.com/$ghRepo")
      } else {
        // Placeholder; CI will set GITHUB_REPOSITORY
        setUrl("https://maven.pkg.github.com/owner/repo")
      }
      credentials {
        username = System.getenv("GITHUB_ACTOR") ?: ""
        password = System.getenv("GITHUB_TOKEN") ?: ""
      }
    }
  }
}
