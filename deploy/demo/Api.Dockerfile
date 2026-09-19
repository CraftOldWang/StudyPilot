FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
ENV MAVEN_OPTS=-Xmx768m
COPY pom.xml .
COPY src/main src/main
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app
RUN useradd --uid 10001 --create-home study && mkdir /data && chown study:study /data
COPY --from=build --chown=study:study /build/target/study-agent-0.0.1-SNAPSHOT.jar /app/app.jar
USER study
ENV SPRING_PROFILES_ACTIVE=demo
ENTRYPOINT ["java", "-Xms256m", "-Xmx1024m", "-jar", "/app/app.jar"]
