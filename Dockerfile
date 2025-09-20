FROM eclipse-temurin:17-jdk-alpine as build
WORKDIR /app
COPY . /app
RUN ./mvnw -DskipTests package -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
