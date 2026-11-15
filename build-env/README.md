# Build environment for the 0.31.x line (Java 8)

Scripts to compile the old Litematica line (`bugfix/liteloader/1.12.2-0.31.x` and branches off it: package
`fi.dy.masa.litematica`, malilib 0.53.0, Minecraft 1.12.2, ForgeGradle 2.3.4, Gradle 2.14.1) with the
Mixin annotation processor enabled, so the `.litemod` contains `mixins.litematica.refmap.json`.
No tracked file of the repo is changed: everything is passed to Gradle with an init script (`-I`).
Everything generated goes into `build-env/.work/` (gitignored).

This does not apply to the new line (`liteloader_1.12.2`, malilib 0.55.0).

## Why it is needed

A plain `gradle build` of this line fails on a current machine for two reasons.

1. **javac rejects two vanilla classes** (`bad RuntimeInvisibleParameterAnnotations attribute`) when the Mixin
   annotation processor completes them: `RenderGlobal$ContainerLocalRenderInformation` and
   `SPacketPlayerListItem$AddPlayerData`. Their constructors have one parameter annotation entry per descriptor
   parameter (including the outer instance), javac expects one less. Other JDKs (8u352 to 8u512, 17, 21) behave
   the same. `ParamAnnotationFixer` (ASM 9) scans ForgeGradle's `minecraftBin-1.12.2.jar` and writes
   `.work/fixed-mc.jar` with only the affected classes, with those attributes dropped.
   `init-0.31.x.gradle` puts that jar in front of the **compile** classpath. The runtime classpath and the output
   jar are not changed.
2. **ForgeGradle 2.3.4 cannot read the official malilib 0.53.0 deobf jar** from the masa maven
   (`ZipException: invalid entry compressed size (expected 67 but got 69 bytes)` in `:deobfCompileDeobfDepTask0`).
   `make-localrepo.sh` downloads it, checks its sha1, repacks it with `jar cf` (same classes, only the zip container
   differs) and serves it from a file maven repo in `.work/localrepo/`, which `init-0.31.x.gradle` puts in front
   of the masa maven.

The `GStringImpl cannot be cast to java.lang.String` problem of the new line does not happen here (this
`build.gradle` has plain Strings in `mixinConfigs`), so there is no workaround for it.

## Requirements

- Java 8 JDK (`JAVA_HOME`, default `/usr/lib/jvm/java-8-openjdk`). The Gradle 2.14.1 distribution, ForgeGradle and the
  dependencies are downloaded by Gradle the first time (network needed).
- `bash`, `curl`, `unzip`, `sha1sum`.
- An ASM 9 jar: it is looked up in `~/.gradle/caches/modules-2` (`$GRADLE_USER_HOME` is honored); if there is none,
  asm-9.7.1 is downloaded from Maven Central into `.work/` and its sha1 is checked.

## Usage

```
build-env/build.sh                    # gradle build
build-env/build.sh compileJava        # any other Gradle arguments are passed through
CLEAN=1 build-env/build.sh            # rm -rf build first
JAVA_HOME=/path/to/jdk8 build-env/build.sh
```

The result is `build/libs/litematica-liteloader-1.12.2-0.31.4.litemod` (name and version come from `build.properties`).

- The wrapper jar `gradle/wrapper/gradle-wrapper.jar` is run directly, because `./gradlew` can have CRLF line endings.
- Do not use the `clean` task on an NTFS mount: Gradle 2.14.1 fails to delete `build/tmp/litemod`. Use `CLEAN=1`.
- On a new machine there is no `minecraftBin-1.12.2.jar` yet. `build.sh` then runs a `compileJava` first (it fails,
  but ForgeGradle creates the jar) and continues. Set `MC_JAR` if the jar is somewhere else.
- The Gradle 2.14.1 HTTP client prints many `Invalid cookie header ... Invalid 'expires' attribute` lines
  (Cloudflare cookies from jcenter/maven.org). They are harmless: `build-env/build.sh | grep -v 'Invalid cookie header'`.

## What it does NOT do

- It does not change `build.gradle`, `build.properties` or any other tracked file, and it does not touch `~/.gradle`
  beyond what Gradle itself writes.
- It does not commit binaries: `fixed-mc.jar`, the compiled fixer, the malilib download and `localrepo/` are
  regenerated into `.work/` (delete that directory to start from scratch).
- It does not build malilib; the jar comes from `https://masa.dy.fi/maven` (0.53.0, pinned by sha1).
- It does not support the new line, other Minecraft versions or JDKs other than 8.
- It does not test the result: no compatibility checks against malilib 0.54.0 at runtime are included.
