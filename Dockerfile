#
# Builds the whole application from source: Vue frontend -> Spring Boot jar -> JRE image.
#   docker compose up -d --build
#
# The base images can be swapped for a mirror without editing this file, either through
# .env (NODE_IMAGE / JDK_IMAGE / RUNTIME_IMAGE) or with --build-arg:
#   NODE_IMAGE=docker.m.daocloud.io/library/node:22-alpine
#   JDK_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:25-jdk
#   RUNTIME_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:25-jre
#
# No API key is ever baked into the image; everything is injected at run time.
#
# Intentionally no `# syntax=` line: it makes BuildKit fetch a frontend image from Docker
# Hub before anything else, which fails on networks where Docker Hub is unreachable.

ARG NODE_IMAGE=node:22-alpine
ARG JDK_IMAGE=eclipse-temurin:25-jdk
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre

# ---------- 1. frontend ----------
FROM ${NODE_IMAGE} AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund --loglevel=error
COPY web/ ./
# vite.config.js writes to ../src/main/resources/static
RUN npm run build

# ---------- 2. backend ----------
FROM ${JDK_IMAGE} AS build
WORKDIR /src
# unzip is needed by the Maven wrapper (distributionType=only-script)
RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q -DskipTests dependency:go-offline || true
COPY src/ src/
# generated assets come from the frontend stage, never from the working tree
RUN rm -rf src/main/resources/static
COPY --from=web /src/src/main/resources/static/ src/main/resources/static/
RUN ./mvnw -B -DskipTests package

# ---------- 3. runtime ----------
FROM ${RUNTIME_IMAGE}
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
WORKDIR /app
COPY --from=build /src/target/spring-ai-rag-demo-3.0.1.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]