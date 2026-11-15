# Support Visit server. Built from the output of "./gradlew -p server installDist"
# (see deploy/deploy.sh), so the server itself doesn't need Gradle or a JDK.
FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 app && mkdir -p /data && chown app /data
COPY build/install/supportvisit-server /app
USER app
ENV DATA_DIR=/data PORT=8090
VOLUME /data
EXPOSE 8090
HEALTHCHECK --interval=60s --timeout=5s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/8090 && printf 'GET /health HTTP/1.0\r\n\r\n' >&3 && grep -q ok <&3"]
CMD ["/app/bin/supportvisit-server"]
