#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${KOTLIN_COMPILER_LIB:?Set KOTLIN_COMPILER_LIB to a Kotlin compiler/Gradle lib directory}"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
LIB="$KOTLIN_COMPILER_LIB"
CP=$(printf '%s:' "$LIB"/kotlin-stdlib-*.jar "$LIB"/annotations-*.jar "$LIB"/junit-4*.jar "$LIB"/hamcrest-core-*.jar)
EXTRA_SOURCES=()
TEST_CLASSES=(dev.cinematv.data.recommendation.ContentRecommendationCoreTest
  dev.cinematv.data.recommendation.LocalRecommendationPipelineTest
  dev.cinematv.data.recommendation.RecommendationSourceArchiveTest)
if [ -n "${RANKSYS_JAR_DIR:-}" ]; then
  for JAR in RankSys-core-0.4.3.jar RankSys-novdiv-0.4.3.jar RankSys-diversity-0.4.3.jar fastutil-7.0.6.jar jool-0.9.10.jar; do
    test -f "$RANKSYS_JAR_DIR/$JAR"
    CP="$CP$RANKSYS_JAR_DIR/$JAR:"
  done
  # A full JDK is required for this optional actual-published-library path.
  java com.sun.tools.javac.Main -proc:none -cp "$CP" -d "$OUT" \
    app/src/main/java/dev/cinematv/data/recommendation/ranksys/GuardedRankSys.java
  EXTRA_SOURCES=(app/src/main/java/dev/cinematv/data/recommendation/ranksys/*.kt
    app/src/test/java/dev/cinematv/data/recommendation/ranksys/*.kt)
  TEST_CLASSES+=(dev.cinematv.data.recommendation.ranksys.RankSysContentBackendTest)
fi
java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath "$CP$OUT" -d "$OUT" \
  app/src/main/java/dev/cinematv/data/{Models,MetadataCatalog,MetadataPolicy,LibraryStatus,MediaIdentity,MediaDomainMapper,SearchAggregator,SearchIndex,RecommendationEngine}.kt \
  app/src/main/java/dev/cinematv/domain/*.kt app/src/main/java/dev/cinematv/data/recommendation/*.kt \
  app/src/test/java/dev/cinematv/data/recommendation/*.kt "${EXTRA_SOURCES[@]}"
java -cp "$OUT:$CP" org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
