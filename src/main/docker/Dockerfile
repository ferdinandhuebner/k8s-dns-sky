FROM anapsix/alpine-java:8_jdk_unlimited

RUN useradd -d / -U -m -s /bin/false app

USER app

ENV JAVA_TOOL_OPTIONS "-Xms64m -Xmx128m -XX:MaxMetaspaceSize=64m"

COPY dns-controller.jar /dns-controller.jar

ENTRYPOINT ["java", "-server", "-jar", "/dns-controller.jar"]