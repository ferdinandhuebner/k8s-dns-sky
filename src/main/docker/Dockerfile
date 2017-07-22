FROM anapsix/alpine-java:8_jdk_unlimited

RUN adduser -D -H dns-controller

ENV JVM_MIN_HEAP "64m"
ENV JVM_MAX_HEAP "128m"
ENV JVM_MAX_METASPACE "64m"
ENV JVM_INITIAL_CODE_CACHE "16m"
ENV JVM_MAX_CODE_CACHE "32m"
ENV JVM_COMPRESSED_CLASS_SPACE "32m"

COPY run.sh /run.sh
COPY dns-controller.jar /dns-controller.jar

RUN chown dns-controller: /run.sh && chown dns-controller: /dns-controller.jar && chmod +x /run.sh

USER dns-controller

ENTRYPOINT ["/run.sh"]