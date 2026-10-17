#!/usr/bin/env kotlinr

/**
 * Extracts and runs the actual plugin-produced archives on each CI host.
 *
 * Usage: kotlinr scripts/smoke.main.kts
 * First build both example archives with the distTar and distZip commands.
 */

@file:DependsOn("org.apache.commons:commons-compress:1.28.0")

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Locale
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile

fun extractEntry(destination: Path, name: String, directory: Boolean, mode: Int, input: InputStream) {
    val target = destination.resolve(name).normalize()
    require(target.startsWith(destination) && target != destination) { "Invalid archive path: $name" }
    if (directory) {
        Files.createDirectories(target)
    } else {
        Files.createDirectories(target.parent)
        Files.copy(input, target)
    }
    if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
        // The enum lists owner/group/other read, write and execute in Unix mode-bit order.
        val permissions = PosixFilePermission.values().filterIndexed { index, _ ->
            mode and (1 shl (8 - index)) != 0
        }.toSet()
        Files.setPosixFilePermissions(target, permissions)
    }
}

fun extractTar(archive: Path, destination: Path) {
    TarArchiveInputStream(Files.newInputStream(archive)).use { contents ->
        while (true) {
            val entry = contents.nextEntry ?: break
            require(entry.isDirectory || entry.isFile) { "Unsupported tar entry: ${entry.name}" }
            extractEntry(destination, entry.name, entry.isDirectory, entry.mode, contents)
        }
    }
}

fun extractZip(archive: Path, destination: Path) {
    ZipFile.builder().setPath(archive).get().use { contents ->
        for (entry in contents.entries.asSequence()) {
            require(!entry.isUnixSymlink) { "Unsupported zip entry: ${entry.name}" }
            contents.getInputStream(entry).use { input ->
                extractEntry(destination, entry.name, entry.isDirectory, entry.unixMode, input)
            }
        }
    }
}

val project = __FILE__.canonicalFile.parentFile.parentFile.toPath()
val windows = System.getProperty("os.name").startsWith("Windows")
val ignoredEnvironment = setOf("JAVA_OPTS", "HELLO_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
val cases = listOf(
    emptyMap<String, String>() to "Hello from the distribution",
    mapOf("JAVA_OPTS" to "-Dexample.message=\"shared options\"") to "Shared options",
    mapOf(
        "JAVA_OPTS" to "-Dexample.message=\"shared options\"",
        "HELLO_OPTS" to "-Dexample.message=\"application options\"",
    ) to "Application options",
)

for (extension in listOf("tar", "zip")) {
    val task = if (extension == "tar") "distTar" else "distZip"
    val archive = project.resolve("build/tasks/_example_$task@application/example.$extension")
    val destination = Files.createTempDirectory("ktc application smoke ")
    try {
        if (extension == "tar") extractTar(archive, destination) else extractZip(archive, destination)
        val root = destination.resolve("example")
        // Match the cmd.exe invocation used by the plugin's launcher tests.
        val command = if (windows) {
            listOf("cmd.exe", "/d", "/c", "\"${root.resolve("bin/hello.bat")}\" \"two words\" last")
        } else {
            listOf(root.resolve("bin/hello").toString(), "two words", "last")
        }
        for ((overrides, expected) in cases) {
            // Keep stderr separate from stdout without risking a full error pipe blocking the child.
            val stderr = destination.resolve("stderr.txt")
            val process = ProcessBuilder(command).redirectError(stderr.toFile()).apply {
                environment().keys.removeIf { it.uppercase(Locale.ROOT) in ignoredEnvironment }
                environment().putAll(overrides)
            }.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val errorOutput = Files.readString(stderr)
            check(exitCode == 0) { "$extension: exit code $exitCode\nstdout: $stdout\nstderr: $errorOutput" }
            check(stdout.reader().buffered().readLines() == listOf(expected, "two words", "last")) {
                "$extension: unexpected stdout: $stdout\nstderr: $errorOutput"
            }
        }
        println("$extension: extracted application and dependency ran successfully")
    } finally {
        Files.walk(destination).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
