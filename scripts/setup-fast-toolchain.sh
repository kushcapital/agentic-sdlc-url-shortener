#!/usr/bin/env bash
# Optional: prepare the Maven-free "javac" toolchain so scenario runs verify sandboxes in seconds
# instead of minutes. Requires one prior `mvn verify` (populates ~/.m2). Writes .orchestrator-env;
# source it before running scenarios:   source .orchestrator-env
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -q -pl url-shortener dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=test
CONSOLE_VERSION=${JUNIT_CONSOLE_VERSION:-1.11.4}
mvn -q dependency:get -Dartifact=org.junit.platform:junit-platform-console-standalone:${CONSOLE_VERSION}
CONSOLE="$HOME/.m2/repository/org/junit/platform/junit-platform-console-standalone/${CONSOLE_VERSION}/junit-platform-console-standalone-${CONSOLE_VERSION}.jar"
cat > .orchestrator-env <<ENV
export ORCHESTRATOR_TOOLCHAIN=javac
export ORCHESTRATOR_CLASSPATH="$(cat url-shortener/target/classpath.txt)"
export ORCHESTRATOR_JUNIT_CONSOLE="$CONSOLE"
ENV
echo "wrote .orchestrator-env — run: source .orchestrator-env"
