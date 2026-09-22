FROM maven:3.9.12-eclipse-temurin-21-alpine@sha256:8b2f036477a5bc9fbeb16cfb7301c484d7fff727b1c4907301ac665526bd7a8e AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

COPY backend/src src
RUN ./mvnw --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21.0.9_10-jre-alpine@sha256:08eecc477dbe3f2e33daac27f36e41daf7f4ec51d2f3396006e54fa41832c74c

RUN apk add --no-cache curl \
    && addgroup -S agenvas \
    && adduser -S -G agenvas -h /opt/agenvas agenvas \
    && mkdir -p /opt/agenvas/data /opt/agenvas/tmp \
    && chown -R agenvas:agenvas /opt/agenvas

WORKDIR /opt/agenvas
COPY --from=build --chown=agenvas:agenvas /workspace/backend/target/agenvas-server-*.jar app.jar

USER agenvas
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.io.tmpdir=/opt/agenvas/tmp", "-jar", "/opt/agenvas/app.jar"]
