#!/bin/bash

if [ -z "${JAVA_TOOL_OPTIONS}" ]; then
  JAVA_TOOL_OPTIONS="-Xms${JVM_MIN_HEAP} -Xmx${JVM_MAX_HEAP} -XX:MaxMetaspaceSize=${JVM_MAX_METASPACE}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -XX:InitialCodeCacheSize=${JVM_INITIAL_CODE_CACHE}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -XX:ReservedCodeCacheSize=${JVM_MAX_CODE_CACHE}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -XX:CompressedClassSpaceSize=${JVM_COMPRESSED_CLASS_SPACE}"
  export JAVA_TOOL_OPTIONS
fi

exec java -server -jar /dns-controller.jar $*
