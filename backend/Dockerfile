FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir out && javac -d out src/main/java/rtpe/*.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/out ./out
CMD ["sh", "-c", "RTPE_PORT=${PORT:-8080} java -cp out rtpe.RtpeServer"]