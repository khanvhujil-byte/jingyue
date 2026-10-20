FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /source
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /source/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/uploads && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
