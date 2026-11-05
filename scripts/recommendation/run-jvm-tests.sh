#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${KOTLIN_COMPILER_LIB:?Set KOTLIN_COMPILER_LIB to a Kotlin compiler/Gradle lib directory}"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
LIB="$KOTLIN_COMPILER_LIB"
CP=$(printf '%s:' "$LIB"/kotlin-stdlib-*.jar "$LIB"/annotations-*.jar "$LIB"/junit-4*.jar "$LIB"/hamcrest-core-*.jar)
java -cp "$LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 -no-stdlib -no-reflect -classpath "$CP" -d "$OUT" \
  app/src/main/java/dev/cinematv/data/{Models,MetadataCatalog,MetadataPolicy,LibraryStatus,MediaIdentity,MediaDomainMapper,SearchAggregator,SearchIndex,RecommendationEngine}.kt \
  app/src/main/java/dev/cinematv/domain/*.kt app/src/main/java/dev/cinematv/data/recommendation/*.kt \
  app/src/test/java/dev/cinematv/data/recommendation/*.kt
java -cp "$OUT:$CP" org.junit.runner.JUnitCore \
  dev.cinematv.data.recommendation.ContentRecommendationCoreTest \
  dev.cinematv.data.recommendation.LocalRecommendationPipelineTest
