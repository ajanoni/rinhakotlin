FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Cache dependency layer
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q 2>&1 | tail -1 || true

# Build fat JAR
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
  "-Xms64m", "-Xmx96m", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=5", \
  "-XX:G1HeapRegionSize=4m", \
  "-XX:MaxMetaspaceSize=28m", \
  "-XX:ReservedCodeCacheSize=16m", \
  "-Dio.netty.maxDirectMemory=4194304", \
  "-jar", "/app.jar"]
