# syntax=docker/dockerfile:1.7
# ---- build: compile and extract Spring Boot layers ---------------------------------
FROM eclipse-temurin:21-jdk AS build
# Optional, only behind a TLS-intercepting corporate proxy (all no-ops otherwise):
#   --secret id=extra_ca,src=corp-ca.pem      trust an extra CA in the BUILD stage only
#   --build-arg HTTPS_PROXY=... --build-arg MAVEN_OPTS="-Dhttps.proxyHost=... -Dhttps.proxyPort=..."
ARG MAVEN_OPTS=""
ENV MAVEN_OPTS=${MAVEN_OPTS}
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -f /run/secrets/extra_ca ]; then \
      cp /run/secrets/extra_ca /usr/local/share/ca-certificates/extra-ca.crt && update-ca-certificates >/dev/null; \
      awk '/BEGIN CERTIFICATE/{n++} {print > ("/tmp/extra-ca-" n ".pem")}' /run/secrets/extra_ca; \
      for f in /tmp/extra-ca-*.pem; do \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$f" .pem)" -file "$f" >/dev/null; \
      done; rm -f /tmp/extra-ca-*.pem; \
    fi
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies change rarely: resolve them in their own layer so code edits rebuild fast.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/backend-*.jar extract --layers --launcher --destination /layers

# ---- runtime: JRE only, non-root, layered for small pushes -------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app app
WORKDIR /app
# Least-changing layers first: a code-only change re-pushes just the small "application" layer.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001
EXPOSE 8080
# Size the heap from the container memory limit, not the host's RAM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
