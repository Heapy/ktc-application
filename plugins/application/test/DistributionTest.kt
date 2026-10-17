package io.heapy.ktc.plugins.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class DistributionTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `launches from a directory with spaces and preserves arguments and JVM options`() {
        val jar = applicationJar()
        val root = assembleDistribution(
            "hello", "example", "fixture.Main", listOf(jar, jar),
            listOf("-Dmessage=hello \"quoted\" \\path $ % &"), temp.resolve("a space ' & $ directory"),
        )
        assertEquals(listOf("app.jar"), Files.list(root.resolve("lib")).use { it.map { p -> p.fileName.toString() }.toList() })
        val batch = Files.readString(root.resolve("bin/hello.bat"))
        assertTrue(batch.contains("\r\n"))
        assertFalse(batch.replace("\r\n", "").contains('\n'))
        val (exitCode, output) = launch(root, "hello")
        assertEquals(7, exitCode, output)
        assertEquals("hello \"quoted\" \\path $ % &\ntwo words\nlast\n", output)
    }

    @Test
    fun `runtime options are additive and application options override shared options and defaults`() {
        val root = assembleDistribution(
            "myApp", "different-dist", "fixture.Main", listOf(applicationJar()),
            listOf("-Dmessage=default"), temp.resolve("installed"),
        )
        val cases = listOf(
            emptyMap<String, String>() to "default",
            mapOf("JAVA_OPTS" to "", "MY_APP_OPTS" to "") to "default",
            mapOf("JAVA_OPTS" to "  \t  ", "MY_APP_OPTS" to " ") to "default",
            mapOf("JAVA_OPTS" to "-Dmessage=shared") to "shared",
            mapOf("MY_APP_OPTS" to "-Dmessage=application") to "application",
            mapOf("JAVA_OPTS" to "-Dmessage=shared -Dextra=retained", "MY_APP_OPTS" to "-Dmessage=application") to
                "application\nretained",
        )
        for ((options, expected) in cases) {
            val (exitCode, output) = launch(root, "myApp", options)
            assertEquals(7, exitCode, output)
            assertEquals("$expected\ntwo words\nlast\n", output)
        }
    }

    @Test
    fun `runtime options preserve double quoted spaces quotes and metacharacters`() {
        val root = assembleDistribution(
            "my-app", "dist", "fixture.Main", listOf(applicationJar()), emptyList(), temp.resolve("installed"),
        )
        for (variable in listOf("JAVA_OPTS", "MY_APP_OPTS")) {
            val (exitCode, output) = launch(root, "my-app", mapOf(
                variable to "-Dmessage=\"hello \\\"quoted\\\" \\path $ % & | < > * ? !\"",
            ))
            assertEquals(7, exitCode, output)
            assertEquals("hello \"quoted\" \\path $ % & | < > * ? !\ntwo words\nlast\n", output)
        }
    }

    @Test
    fun `Unix runtime options handle single quotes escapes and literal shell substitutions`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = assembleDistribution(
            "hello", "dist", "fixture.Main", listOf(applicationJar()), emptyList(), temp.resolve("installed"),
        )
        val cases = listOf(
            "-Dmessage='single quoted words'" to "single quoted words",
            "-Dmessage=escaped\\ words" to "escaped words",
            "-Dmessage=before\" middle \"after" to "before middle after",
            "-Dmessage=''" to "",
            "-Dmessage=\"\"" to "",
            "-Dmessage='\$(touch injected) `touch injected` \$APP_HOME *.jar ; & |'" to
                "\$(touch injected) `touch injected` \$APP_HOME *.jar ; & |",
            "-Dmessage=\"\$(touch injected)\"" to "\$(touch injected)",
        )
        for ((options, expected) in cases) {
            val (exitCode, output) = launch(root, "hello", mapOf("HELLO_OPTS" to options))
            assertEquals(7, exitCode, output)
            assertEquals("$expected\ntwo words\nlast\n", output)
            assertFalse(Files.exists(root.resolve("injected")))
        }
    }

    @Test
    fun `Unix rejects unmatched quotes and dangling escapes in either options variable`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val root = assembleDistribution(
            "hello", "dist", "fixture.Main", listOf(applicationJar()), emptyList(), temp.resolve("installed"),
        )
        for (variable in listOf("JAVA_OPTS", "HELLO_OPTS")) {
            for (options in listOf("-Dmessage='unfinished", "-Dmessage=\"unfinished", "-Dmessage=unfinished\\")) {
                val (exitCode, output) = launch(root, "hello", mapOf(variable to options))
                assertEquals(1, exitCode, output)
                assertTrue(output.contains("Unmatched quote or trailing backslash"), output)
                assertFalse(output.contains("two words"), output)
            }
        }
    }

    @Test
    fun `application names map to portable environment variables`() {
        for ((name, variable) in mapOf(
            "my-app" to "MY_APP_OPTS", "myApp" to "MY_APP_OPTS", "my.app" to "MY_APP_OPTS",
            "my_app" to "MY_APP_OPTS", "HTTPServer" to "HTTP_SERVER_OPTS", "7zip" to "_7ZIP_OPTS",
        )) {
            assertEquals(variable, optsEnvironmentVariable(name))
        }
        val root = assembleDistribution(
            "7zip", "dist", "fixture.Main", listOf(applicationJar()), emptyList(), temp.resolve("installed"),
        )
        val (exitCode, output) = launch(root, "7zip", mapOf("_7ZIP_OPTS" to "-Dmessage=numeric"))
        assertEquals(7, exitCode, output)
        assertEquals("numeric\ntwo words\nlast\n", output)
    }

    @Test
    fun `rejects colliding runtime JAR names before changing the installed output`() {
        val first = Files.writeString(temp.resolve("same.jar"), "first")
        val other = Files.createDirectories(temp.resolve("other")).resolve("SAME.jar")
        Files.writeString(other, "second")
        val output = Files.createDirectories(temp.resolve("output"))
        val marker = Files.writeString(output.resolve("keep"), "previous output")
        assertThrows<IllegalArgumentException> {
            assembleDistribution("app", "app", "Main", listOf(first, other), emptyList(), output)
        }
        assertEquals("previous output", Files.readString(marker))
    }

    @Test
    fun `rejects invalid names entry points classpaths and JVM argument line breaks`() {
        val jar = applicationJar()
        for (name in listOf("../escape", "a/b", "a\\b", "name.", "CON", "nul.zip", "x & echo")) {
            assertThrows<IllegalArgumentException>(name) {
                assembleDistribution(name, "dist", "Main", listOf(jar), emptyList(), temp.resolve("output"))
            }
        }
        for (main in listOf(null, "", "Main; echo injected", "-version")) {
            assertThrows<IllegalArgumentException> {
                assembleDistribution("app", "dist", main, listOf(jar), emptyList(), temp.resolve("output"))
            }
        }
        assertThrows<IllegalArgumentException> {
            assembleDistribution("app", "dist", "Main", listOf(temp), emptyList(), temp.resolve("output"))
        }
        assertThrows<IllegalArgumentException> {
            assembleDistribution("app", "dist", "Main", listOf(jar), listOf("-Da=one\ntwo"), temp.resolve("output"))
        }
    }

    @Test
    fun `rebuild removes old dependencies and archives preserve content and executable mode`() {
        val jar = applicationJar()
        val output = temp.resolve("installed")
        val root = assembleDistribution("hello", "example", "fixture.Main", listOf(jar), emptyList(), output)
        Files.writeString(root.resolve("lib/stale.jar"), "old")
        assembleDistribution("hello", "example", "fixture.Main", listOf(jar), emptyList(), output)
        assertFalse(Files.exists(root.resolve("lib/stale.jar")))
        val tar = archiveDistribution(output, temp.resolve("tar"), zip = false)
        val zip = archiveDistribution(output, temp.resolve("zip"), zip = true)
        val expected = mapOf("example/bin/hello" to 493, "example/bin/hello.bat" to 420, "example/lib/app.jar" to 420)
        val tarModes = mutableMapOf<String, Int>()
        TarArchiveInputStream(Files.newInputStream(tar)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                if (entry.name in expected) tarModes[entry.name] = entry.mode
                if (entry.name == "example/lib/app.jar") assertArrayEquals(Files.readAllBytes(jar), stream.readBytes())
                assertEquals(0L, entry.modTime.time)
            }
        }
        assertEquals(expected, tarModes)
        ZipFile.builder().setPath(zip).get().use { archive ->
            val zipModes = archive.entries.asSequence().filter { it.name in expected }.associate { it.name to (it.unixMode and 511) }
            assertEquals(expected, zipModes)
            assertArrayEquals(Files.readAllBytes(jar), archive.getInputStream(archive.getEntry("example/lib/app.jar")).readBytes())
        }
        val firstTar = Files.readAllBytes(tar)
        val firstZip = Files.readAllBytes(zip)
        Files.setLastModifiedTime(root.resolve("lib/app.jar"), java.nio.file.attribute.FileTime.fromMillis(123456789L))
        assertArrayEquals(firstTar, Files.readAllBytes(archiveDistribution(output, temp.resolve("tar"), zip = false)))
        assertArrayEquals(firstZip, Files.readAllBytes(archiveDistribution(output, temp.resolve("zip"), zip = true)))
    }

    private fun launch(root: Path, name: String, options: Map<String, String> = emptyMap()): Pair<Int, String> {
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/d", "/c", "\"${root.resolve("bin/$name.bat")}\" \"two words\" last")
        } else {
            listOf(root.resolve("bin/$name").toString(), "two words", "last")
        }
        val process = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).apply {
            environment().keys.removeIf {
                it.uppercase() in setOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS") ||
                    it.endsWith("_OPTS", ignoreCase = true)
            }
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment().putAll(options)
        }.start()
        val output = process.inputStream.bufferedReader().readText().replace("\r\n", "\n")
        return process.waitFor() to output
    }

    private fun applicationJar(): Path {
        val source = Files.createDirectories(temp.resolve("source/fixture")).resolve("Main.java")
        Files.writeString(source, """
            package fixture;
            public class Main {
                public static void main(String[] args) {
                    System.out.println(System.getProperty("message"));
                    if (System.getProperty("extra") != null) System.out.println(System.getProperty("extra"));
                    for (String arg : args) System.out.println(arg);
                    System.exit(7);
                }
            }
        """.trimIndent())
        val classes = Files.createDirectories(temp.resolve("classes"))
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()))
        val jar = temp.resolve("app.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { stream ->
            stream.putNextEntry(JarEntry("fixture/Main.class"))
            Files.copy(classes.resolve("fixture/Main.class"), stream)
            stream.closeEntry()
        }
        return jar
    }
}
