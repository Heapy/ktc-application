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
        val command = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd.exe", "/d", "/c", "\"${root.resolve("bin/hello.bat")}\" \"two words\" last")
        } else {
            listOf(root.resolve("bin/hello").toString(), "two words", "last")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            environment().remove("JAVA_TOOL_OPTIONS")
            environment().remove("JDK_JAVA_OPTIONS")
        }.start()
        val output = process.inputStream.bufferedReader().readText().replace("\r\n", "\n")
        assertEquals(7, process.waitFor(), output)
        assertEquals("hello \"quoted\" \\path $ % &\ntwo words\nlast\n", output)
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

    private fun applicationJar(): Path {
        val source = Files.createDirectories(temp.resolve("source/fixture")).resolve("Main.java")
        Files.writeString(source, """
            package fixture;
            public class Main {
                public static void main(String[] args) {
                    System.out.println(System.getProperty("message"));
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
