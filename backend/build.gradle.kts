plugins {
    java
    jacoco
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

group = "io.github.sanduniliyanage"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // JWT verification and issuance through Spring Security's own Nimbus integration (ADR-021).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Not in the Spring Boot BOM. The 2.x line targets Spring Boot 3; 3.x targets Spring Boot 4.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // The Testcontainers fixture provisions the database roles with the same script Compose
    // uses, so a change to one cannot silently leave the other behind.
    systemProperty(
        "flaglane.postgres.initScript",
        rootDir.resolve("docker/postgres/init-roles.sh").absolutePath,
    )
    // The application reads its JWT signing secret from the environment with no default, so the
    // test JVM is given one the same way a deployment is. It signs nothing outside a test run.
    environment("FLAGLANE_JWT_SECRET", "test-only-signing-secret-never-used-outside-tests")
    // Every test request comes from one address, and the suite signs up more users a minute than
    // the default sign-in limit allows. AuthRateLimitTest sets its own limit and tests it.
    environment("FLAGLANE_AUTH_RATE_LIMIT_PER_MINUTE", "60000")
    // OpenApiSnapshotTest compares the served document with the dashboard's snapshot, so the
    // snapshot is an input: editing it by hand must re-run the test rather than find it up to date.
    // A file collection rather than a file, because the snapshot may not exist yet.
    inputs
        .files(rootDir.resolve("dashboard/openapi.json"))
        .withPropertyName("openApiSnapshot")
    // -PupdateOpenApiSnapshot rewrites the snapshot from the served document instead (ADR-031).
    if (providers.gradleProperty("updateOpenApiSnapshot").isPresent) {
        systemProperty("flaglane.openapi.update", "true")
        outputs.upToDateWhen { false }
    }
}

jacoco {
    // Pinned rather than left to Gradle's default: reading Java 25 class files needs 0.8.14 or
    // later, and an older agent fails the test task rather than the coverage check.
    toolVersion = "0.8.15"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// docs/TESTING.md gate 4 (NFR-MNT-001): evaluation/ keeps 90% line coverage. Only that package is
// gated. It is the product, and it is pure, so every line in it is reachable from a plain test.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            element = "PACKAGE"
            includes = listOf("io.github.sanduniliyanage.flaglane.evaluation")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.90".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport, tasks.jacocoTestCoverageVerification)
}

spotless {
    java {
        importOrder()
        removeUnusedImports()
        googleJavaFormat()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

spotbugs {
    ignoreFailures = false
    showStackTraces = false
    excludeFilter = file("config/spotbugs/exclude.xml")
}

// io.spring.dependency-management applies the Spring Boot BOM to every configuration,
// including SpotBugs's own tool classpath, which downgrades commons-lang3 below the
// version SpotBugs itself requires and breaks the analysis with NoClassDefFoundError.
configurations.named("spotbugs") {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
            useVersion("3.20.0")
            because("SpotBugs requires a newer commons-lang3 than the Spring Boot BOM provides")
        }
    }
}

// Findings go to build/reports/spotbugs/<sourceSet>.txt, which CI uploads on failure. Without a
// report the task fails with an exit code and nothing that says what it found.
tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("text") { required = true }
}

// NFR-PER-001 for the server's engine (docs/BENCHMARKS.md). Not part of check: a latency figure
// taken on a shared CI runner measures the neighbours as much as the code. Run on an idle machine.
tasks.register<JavaExec>("evaluationBenchmark") {
    group = "verification"
    description = "Times single evaluations with 1,000 flags and 50 rules (NFR-PER-001)."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.sanduniliyanage.flaglane.benchmark.EvaluationBenchmark"
    // A fixed heap, so the collector does not resize it between rounds.
    jvmArgs("-Xms1g", "-Xmx1g")
}
