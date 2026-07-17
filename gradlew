#!/bin/sh

# Minimal POSIX launcher for the standard Gradle Wrapper JAR.
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "${JAVA_HOME:-}" ]; then
	JAVACMD="$JAVA_HOME/bin/java"
else
	JAVACMD=java
fi

if [ ! -x "$JAVACMD" ] && [ "$JAVACMD" != "java" ]; then
	echo "ERROR: JAVA_HOME points to a Java installation without an executable bin/java." >&2
	exit 1
fi

exec "$JAVACMD" ${JAVA_OPTS:-} ${GRADLE_OPTS:-} \
	-Dorg.gradle.appname=gradlew \
	-classpath "$CLASSPATH" \
	org.gradle.wrapper.GradleWrapperMain "$@"
