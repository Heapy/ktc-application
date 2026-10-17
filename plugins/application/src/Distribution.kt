package io.heapy.ktc.plugins.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

@TaskAction
fun installDist(
    moduleName: String,
    mainClass: String?,
    @Input jar: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    settings: ApplicationSettings,
    @Output outputDir: Path,
) {
    val distribution = assembleDistribution(
        applicationName = settings.applicationName ?: moduleName,
        distributionName = settings.distributionName ?: moduleName,
        mainClass = mainClass,
        classpath = listOf(jar.artifact) + runtimeClasspath.resolvedFiles,
        defaultJvmArgs = settings.defaultJvmArgs,
        outputDir = outputDir,
    )
    println("Application distribution: $distribution")
}

internal fun assembleDistribution(
    applicationName: String,
    distributionName: String,
    mainClass: String?,
    classpath: List<Path>,
    defaultJvmArgs: List<String>,
    outputDir: Path,
): Path {
    validateName(applicationName)
    validateName(distributionName)
    require(mainClass != null && mainClass.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))) {
        "Set settings.jvm.mainClass explicitly to the application's fully qualified JVM entry point."
    }
    require(defaultJvmArgs.none { '\n' in it || '\r' in it || '\u0000' in it }) {
        "defaultJvmArgs must not contain line breaks or NUL characters."
    }
    val jars = classpath.map { it.toAbsolutePath().normalize() }.distinct()
    require(jars.isNotEmpty()) { "The application runtime classpath is empty." }
    jars.forEach {
        require(Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            "Application distributions require a JAR runtime classpath. Set settings.jvm.runtimeClasspathMode: jars. Invalid entry: $it"
        }
        require(it.fileName.toString().none { c -> c == '\\' || c == ':' || c.code < 32 }) {
            "Non-portable JAR filename: ${it.fileName}"
        }
    }
    // A portable distribution must not silently replace different dependencies with the same filename.
    val collisions = jars.groupBy { it.fileName.toString().lowercase(Locale.ROOT) }.filterValues { it.size > 1 }
    require(collisions.isEmpty()) { "Runtime JAR filename collision: ${collisions.values.flatten()}" }

    removeTree(outputDir)
    val root = outputDir.resolve(distributionName)
    val bin = Files.createDirectories(root.resolve("bin"))
    val lib = Files.createDirectories(root.resolve("lib"))
    val conf = Files.createDirectories(root.resolve("conf"))
    jars.forEach { Files.copy(it, lib.resolve(it.fileName), StandardCopyOption.COPY_ATTRIBUTES) }
    Files.writeString(conf.resolve("jvm.options"), defaultJvmArgs.joinToString("\n", postfix = "\n") { javaArgument(it) })
    val unixLauncher = bin.resolve(applicationName)
    Files.writeString(unixLauncher, unixLauncher(mainClass))
    if (Files.getFileStore(bin).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(unixLauncher, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
        ))
    }
    Files.writeString(bin.resolve("$applicationName.bat"), windowsLauncher(mainClass).replace("\n", "\r\n"))
    return root
}

private fun validateName(name: String) {
    require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) && !name.endsWith('.')) {
        "Application and distribution names must be portable filenames: $name"
    }
    require(name.substringBefore('.').uppercase(Locale.ROOT) !in
        setOf("CON", "PRN", "AUX", "NUL", *(1..9).map { "COM$it" }.toTypedArray(), *(1..9).map { "LPT$it" }.toTypedArray())) {
        "Reserved Windows filename: $name"
    }
}

private fun javaArgument(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun unixLauncher(mainClass: String): String = $$"""
    #!/bin/sh
    set -eu
    APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
    if [ -n "${JAVA_HOME:-}" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        JAVACMD=java
    fi
    exec "$JAVACMD" "@$APP_HOME/conf/jvm.options" -classpath "$APP_HOME/lib/*" '$$mainClass' "$@"
    """.trimIndent() + "\n"

private fun windowsLauncher(mainClass: String): String = """
    @echo off
    setlocal DisableDelayedExpansion
    set "APP_HOME=%~dp0.."
    if defined JAVA_HOME (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVACMD=java.exe"
    )
    "%JAVACMD%" "@%APP_HOME%\conf\jvm.options" -classpath "%APP_HOME%\lib\*" "$mainClass" %*
    exit /b %ERRORLEVEL%
    """.trimIndent() + "\n"

internal fun removeTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
