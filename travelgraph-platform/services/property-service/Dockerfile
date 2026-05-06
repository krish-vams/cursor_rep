# syntax=docker/dockerfile:1.6

# ---- Stage 1: build ---------------------------------------------------------
FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace

# Copy build configuration first to leverage Docker layer cache for dependencies.
COPY settings.gradle.kts gradle.properties build.gradle.kts ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true

COPY src ./src
RUN gradle --no-daemon clean bootJar -x test

# Extract the layered jar so we can copy efficient layers into the runtime image.
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/build/libs/property-service-0.1.0.jar extract

# ---- Stage 2: runtime -------------------------------------------------------
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app

# Copy each Spring Boot layer separately so frequently changing layers (application)
# do not bust caches for slowly changing layers (dependencies).
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8081
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
