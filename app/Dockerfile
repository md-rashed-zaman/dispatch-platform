# The app image, built from the jar Maven already made: ./mvnw package -DskipTests, then docker compose build.
# Layered: dependencies change rarely, so a rebuild after a code change only replaces the small top layer.
FROM eclipse-temurin:21.0.12.1_1-jre AS layers
WORKDIR /build
COPY target/dispatch-0.0.1-SNAPSHOT.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21.0.12.1_1-jre
RUN useradd --system --uid 10001 dispatch
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER dispatch
# Heap sized from the container's memory limit, not the machine's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "org.springframework.boot.loader.launch.JarLauncher"]
