FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Cache dependency layer
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>&1 | tail -1 || true

# Build fat JAR
# index.bin.gz must exist at src/main/resources/data/index.bin.gz before building
# Obtain it by running: cargo run --release --bin build_index (jairoblatt/rinha-2026-rust)
COPY src ./src
RUN test -f src/main/resources/data/index.bin.gz || \
    (echo "ERROR: src/main/resources/data/index.bin.gz missing. See README." && exit 1)
RUN ./gradlew shadowJar --no-daemon -q

FROM --platform=linux/amd64 eclipse-temurin:21-jre-jammy
COPY --from=build /app/build/libs/rinhaKotlin-1.0-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", \
  "--add-modules", "jdk.incubator.vector", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "-Xms48m", "-Xmx72m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=5", \
  "-XX:G1HeapRegionSize=2m", \
  "-XX:MaxMetaspaceSize=32m", \
  "-XX:ReservedCodeCacheSize=20m", \
  "-Dio.netty.maxDirectMemory=8388608", \
  "-jar", "/app.jar"]