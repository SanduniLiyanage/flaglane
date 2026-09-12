# Builds the API from source with the same wrapper and JDK the build uses everywhere else.
# Build context is the repository root: docker build -f backend/Dockerfile .

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY backend/build.gradle.kts backend/
COPY backend/src/ backend/src/
# The cache mount keeps the Gradle distribution and dependencies between builds.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :backend:bootJar
# Split the jar into layers that change at different rates, so a code change does not
# re-ship the dependencies layer.
RUN mv backend/build/libs/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre AS runtime
RUN groupadd --system flaglane \
    && useradd --system --gid flaglane --no-create-home --shell /usr/sbin/nologin flaglane
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER flaglane
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
