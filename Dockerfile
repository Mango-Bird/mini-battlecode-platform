FROM maven:3.10.0-eclipse-temurin-21-noble AS build
WORKDIR /build
# Explicit inputs: no frontend, local env, credentials, caches or generated artifacts.
COPY pom.xml ./
COPY .mvn/maven.config .mvn/maven.config
COPY rules-api/ rules-api/
COPY protocol/ protocol/
COPY engine/ engine/
COPY competition/ competition/
COPY network/ network/
COPY rules-impl/ rules-impl/
COPY client/ client/
COPY server/ server/
RUN mvn -B -pl server -am package

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --gid 10001 battlecode && useradd --uid 10001 --gid 10001 --no-create-home battlecode
COPY --from=build --chown=10001:10001 /build/server/target/server-1.0.0-SNAPSHOT.jar /app/server.jar
COPY --from=build --chown=10001:10001 /build/server/target/dependency/ /app/lib/
ENV BATTLECODE_MODE=WEB_ONLY
ENV JAVA_TOOL_OPTIONS="-Xms32m -Xmx192m -XX:MaxMetaspaceSize=96m -XX:MaxDirectMemorySize=64m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
# PORT and exact BATTLECODE_ORIGINS are injected by Render, not baked into the image.
# Exec form delivers Render SIGTERM directly to the JVM shutdown hook.
ENTRYPOINT ["java", "-cp", "/app/server.jar:/app/lib/*", "dev.battlecode.server.ServerMain"]
