plugins {
    java
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
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
