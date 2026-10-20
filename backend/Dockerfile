FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B dependency:go-offline
COPY backend/src ./src
RUN mvn -B package -DskipTests
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/jingyue-ticket-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xms256m -Xmx768m -Djava.awt.headless=true"
HEALTHCHECK --interval=10s --start-period=60s --retries=6 CMD curl -fsS http://127.0.0.1:8089/api/v1/health || exit 1
ENTRYPOINT ["java","-jar","app.jar"]
