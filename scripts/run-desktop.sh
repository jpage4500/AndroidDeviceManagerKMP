#!/bin/bash
#######################################################################################################################################
# Run the desktop app from source.
#
# Optional env vars or ~/.gradle/gradle.properties entries — the app runs without them, and only
# the log upload refuses:
#   LOGGING_URL, LOGGING_API
#
# Needs an adb server on 127.0.0.1:5037 to have anything to show. This does not start one:
#   adb start-server
#######################################################################################################################################

set -e

cd "$(dirname "$0")/.."

# JDK 21: Gradle 8.14 cannot run on Java 25, which is what JAVA_HOME points at on this machine
if [ -z "${JAVA_HOME:-}" ] && [ -d /Library/Java/JavaVirtualMachines/jdk-21.jdk ]; then
    export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
fi

# the gradle daemon outlives the app otherwise, and holds the build lock against the next run
trap './gradlew --stop >/dev/null 2>&1' EXIT INT TERM
exec ./gradlew :composeApp:run
