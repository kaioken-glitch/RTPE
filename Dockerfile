FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
RUN mvn -f backend/pom.xml --batch-mode package -DskipTests

FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /workspace/backend/target/rtpe-backend.jar /app/rtpe-backend.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/rtpe-backend.jar"]
