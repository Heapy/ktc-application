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
    val optsVariables = listOf("JAVA_OPTS", optsEnvironmentVariable(applicationName)).distinct()
    Files.writeString(unixLauncher, unixLauncher(mainClass, optsVariables))
    if (Files.getFileStore(bin).supportsFileAttributeView("posix")) {
        Files.setPosixFilePermissions(unixLauncher, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
        ))
    }
    Files.writeString(bin.resolve("$applicationName.bat"), windowsLauncher(mainClass, optsVariables).replace("\n", "\r\n"))
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

internal fun optsEnvironmentVariable(applicationName: String): String {
    val name = applicationName
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .replace(Regex("[^A-Za-z0-9]+"), "_")
        .trimEnd('_')
        .uppercase(Locale.ROOT)
    return (if (name.first().isDigit()) "_$name" else name) + "_OPTS"
}

private fun unixLauncher(mainClass: String, optsVariables: List<String>): String = $$"""
    #!/bin/sh
    set -eu
    APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
    if [ -n "${JAVA_HOME:-}" ]; then
        JAVACMD="$JAVA_HOME/bin/java"
    else
        JAVACMD=java
    fi
    set -- -classpath "$APP_HOME/lib/*" '$$mainClass' "$@"
    app_arg_count=$#
    # Tokenize options without evaluating shell substitutions or expanding wildcards.
    for options in $${optsVariables.joinToString(" ") { "\"\${$it:-}\"" }}; do
        quote= escaped=false started=false arg=
        while [ -n "$options" ]; do
            rest=${options#?}
            char=${options%"$rest"}
            options=$rest
            if [ "$escaped" = true ]; then
                if [ "$quote" = '"' ]; then
                    case "$char" in
                        '"'|'\'|'$'|'`') ;;
                        *) arg=$arg'\' ;;
                    esac
                fi
                arg=$arg$char
                escaped=false
            elif [ "$quote" = "'" ]; then
                if [ "$char" = "'" ]; then quote=; else arg=$arg$char; fi
            else
                case "$char" in
                    '\') escaped=true; started=true ;;
                    '"'|"'")
                        if [ -z "$quote" ]; then
                            quote=$char; started=true
                        elif [ "$quote" = "$char" ]; then
                            quote=
                        else
                            arg=$arg$char
                        fi ;;
                    [[:space:]])
                        if [ -n "$quote" ]; then
                            arg=$arg$char
                        elif [ "$started" = true ]; then
                            set -- "$@" "$arg"
                            arg= started=false
                        fi ;;
                    *) arg=$arg$char; started=true ;;
                esac
            fi
        done
        if [ -n "$quote" ] || [ "$escaped" = true ]; then
            printf '%s\n' 'Unmatched quote or trailing backslash in $${optsVariables.joinToString(" or ")}.' >&2
            exit 1
        fi
        if [ "$started" = true ]; then set -- "$@" "$arg"; fi
    done
    # Move the original classpath, main class and application arguments after the JVM options.
    while [ "$app_arg_count" -gt 0 ]; do
        arg=$1
        shift
        set -- "$@" "$arg"
        app_arg_count=$((app_arg_count - 1))
    done
    exec "$JAVACMD" "@$APP_HOME/conf/jvm.options" "$@"
    """.trimIndent() + "\n"

private fun windowsLauncher(mainClass: String, optsVariables: List<String>): String = """
    @echo off
    setlocal DisableDelayedExpansion
    set "APP_HOME=%~dp0.."
    if defined JAVA_HOME (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVACMD=java.exe"
    )
    "%JAVACMD%" "@%APP_HOME%\conf\jvm.options" ${optsVariables.joinToString(" ") { "%$it%" }} -classpath "%APP_HOME%\lib\*" "$mainClass" %*
    exit /b %ERRORLEVEL%
    """.trimIndent() + "\n"

internal fun removeTree(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
