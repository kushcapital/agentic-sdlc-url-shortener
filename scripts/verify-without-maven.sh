#!/usr/bin/env bash
# Maven-free verification of one module: javac + the JUnit Platform console launcher.
# This is how the submission was built and tested in an environment without Maven Central access;
# reviewers should simply use `mvn verify`. Usage:
#   LIB=<dir of jars> CONSOLE=<junit-platform-console-standalone.jar> scripts/verify-without-maven.sh url-shortener dev.rajeev.shortener
set -uo pipefail
MODULE=$1; PKG=$2; shift 2
LIB=${LIB:?directory containing the Spring Boot / Jackson / H2 / validation jars}
CP=$(ls $LIB/*.jar | tr '\n' ':')
CONSOLE=${CONSOLE:?path to junit-platform-console-standalone.jar}
OUT=build/$(basename $MODULE)
rm -rf $OUT && mkdir -p $OUT/classes $OUT/test-classes $OUT/reports
javac -parameters -Xlint:all -d $OUT/classes -cp "$CP" $(find $MODULE/src/main/java -name "*.java") 2>&1 | grep -vE "^Note|\[serial\]|^\s+\^|serialVersionUID"
[ -d $MODULE/src/main/resources ] && cp -r $MODULE/src/main/resources/* $OUT/classes/
javac -parameters -d $OUT/test-classes -cp "$OUT/classes:$CP:$CONSOLE" $(find $MODULE/src/test/java -name "*.java") 2>&1 | grep -v "^Note"
[ -d $MODULE/src/test/resources ] && cp -r $MODULE/src/test/resources/* $OUT/test-classes/
java "$@" -jar $CONSOLE execute -cp "$OUT/test-classes:$OUT/classes:$CP" --select-package "$PKG" --details=summary --disable-banner --reports-dir=$OUT/reports 2>&1 | grep -vE "^\s*$|JAVA_TOOL_OPTIONS"
