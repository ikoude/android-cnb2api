#!/bin/sh
# Gradle wrapper launcher (POSIX). JDK 17+ required for AGP 8.6.
set -e
APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=$(command -v java || true)
fi
if [ -z "$JAVACMD" ] || [ ! -x "$JAVACMD" ]; then
    echo "ERROR: JAVA_HOME is not set and no java found in PATH." >&2
    exit 1
fi

exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
