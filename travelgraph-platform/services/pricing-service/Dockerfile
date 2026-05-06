# syntax=docker/dockerfile:1.6

FROM gradle:8.7-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts gradle.properties build.gradle.kts ./
RUN gradle --no-daemon dependencies > /dev/null 2>&1 || true
COPY src ./src
RUN gradle --no-daemon clean bootJar -x test
WORKDIR /workspace/extracted
RUN java -Djarmode=layertools -jar /workspace/build/libs/pricing-service-0.1.0.jar extract

FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

EXPOSE 8082
USER nonroot
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
