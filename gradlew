#!/bin/sh
# GLASS WAVE — минимал placeholder.
# Нағыз gradlew скрипті CI-де `gradle wrapper` командасымен автоматты жасалады
# (.github/workflows/build-debug.yml ішінде). Локалды қолдану үшін сол жерде
# Gradle 8.9 орнатып, `gradle wrapper --gradle-version 8.9` орындау жеткілікті.

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
