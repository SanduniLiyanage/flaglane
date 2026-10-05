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
    // Provides the DataSource that Flyway migrates and the application queries. Becomes a
    // transitive dependency of spring-boot-starter-data-jpa in slice 1.7; drop this line then.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Not in the Spring Boot BOM. The 2.x line targets Spring Boot 3; 3.x targets Spring Boot 4.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
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
