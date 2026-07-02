FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
COPY target/*.jar app.jar
RUN mkdir -p /data && chown app:app /data
USER app
EXPOSE 8085 9090
ENV JAVA_OPTS="-Xmx3g"
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
