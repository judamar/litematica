#!/usr/bin/env bash
# Builds the Litematica 0.31.x line with Java 8 without touching any tracked file of the repo.
# usage: build-env/build.sh [gradle args...]     default args: build
# Env: JAVA_HOME (default /usr/lib/jvm/java-8-openjdk), CLEAN=1 (rm -rf build first, `gradle clean` fails on NTFS mounts),
#      MC_JAR / ASM_JAR (override the Minecraft jar made by ForgeGradle / the ASM 9 jar used by the fixer).
set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$ENV_DIR/.." && pwd)"
WORK="$ENV_DIR/.work"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"

JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk}"
GRADLE_ARGS=("${@:-build}")

# The Minecraft jar made by ForgeGradle (MCP names). Gradle creates it during the first build.
MC_JAR="${MC_JAR:-$GRADLE_HOME_DIR/caches/minecraft/net/minecraft/minecraft/1.12.2/stable/39/minecraftBin-1.12.2.jar}"

export JAVA_HOME
export LITEMATICA_ENV_WORK="$WORK"

# ./gradlew can have CRLF line endings, so the wrapper jar is run directly
gradle() {
	(cd "$REPO_DIR" && "$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar \
		org.gradle.wrapper.GradleWrapperMain -I "$ENV_DIR/init-0.31.x.gradle" "$@")
}

# Any ASM 9 jar from the Gradle cache, otherwise ASM 9.7.1 from Maven Central
find_asm() {
	ls "$GRADLE_HOME_DIR"/caches/modules-2/files-2.1/org.ow2.asm/asm/9.*/*/asm-9.*.jar 2>/dev/null | grep -v -- '-sources' | head -1 || true
}

mkdir -p "$WORK"

if [ -z "${ASM_JAR:-}" ]; then
	ASM_JAR="$(find_asm)"
fi

if [ -z "$ASM_JAR" ]; then
	ASM_JAR="$WORK/asm-9.7.1.jar"
	if [ ! -f "$ASM_JAR" ]; then
		echo "No ASM 9 jar in the Gradle cache, downloading asm-9.7.1 from Maven Central"
		curl -sS -m 300 -fL -o "$ASM_JAR" https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar
		echo "f0ed132a49244b042cd0e15702ab9f2ce3cc8436  $ASM_JAR" | sha1sum -c -
	fi
fi

"$ENV_DIR/make-localrepo.sh"

if [ "${CLEAN:-0}" = 1 ]; then
	rm -rf "$REPO_DIR/build"
fi

# First run on a new machine: the build fails in compileJava, but it creates the Minecraft jar
if [ ! -f "$MC_JAR" ]; then
	echo "No $MC_JAR yet, running a first build to create it (a failure here is expected)"
	gradle compileJava || true
fi

mkdir -p "$WORK/fixer-classes"
"$JAVA_HOME/bin/javac" -nowarn -source 8 -target 8 -cp "$ASM_JAR" -d "$WORK/fixer-classes" "$ENV_DIR/ParamAnnotationFixer.java" 2>&1 | grep -v -E 'bootstrap class path|^1 warning|^warning' || true
rm -f "$WORK/fixed-mc.jar"
"$JAVA_HOME/bin/java" -cp "$WORK/fixer-classes:$ASM_JAR" ParamAnnotationFixer "$MC_JAR" "$WORK/fixed-mc.jar"

gradle "${GRADLE_ARGS[@]}"
