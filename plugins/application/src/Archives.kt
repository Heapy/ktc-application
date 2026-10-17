package io.heapy.ktc.plugins.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

@TaskAction
fun distTar(@Input distributionDir: Path, @Output outputDir: Path) {
    println("Application archive: ${archiveDistribution(distributionDir, outputDir, zip = false)}")
}

@TaskAction
fun distZip(@Input distributionDir: Path, @Output outputDir: Path) {
    println("Application archive: ${archiveDistribution(distributionDir, outputDir, zip = true)}")
}

internal fun archiveDistribution(distributionDir: Path, outputDir: Path, zip: Boolean): Path {
    val root = Files.list(distributionDir).use { entries -> entries.toList().single() }
    require(Files.isDirectory(root)) { "Expected one installed application directory in $distributionDir" }
    val files = Files.walk(root).use { entries -> entries.sorted().toList() }
    removeTree(outputDir)
    Files.createDirectories(outputDir)
    val archive = outputDir.resolve("${root.fileName}.${if (zip) "zip" else "tar"}")
    if (zip) {
        ZipArchiveOutputStream(archive).use { stream ->
            files.forEach { file ->
                val name = entryName(distributionDir, file)
                val entry = ZipArchiveEntry(name)
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                entry.unixMode = mode(root, file) or if (Files.isDirectory(file)) 0x4000 else 0x8000
                stream.putArchiveEntry(entry)
                if (Files.isRegularFile(file)) Files.copy(file, stream)
                stream.closeArchiveEntry()
            }
        }
    } else {
        TarArchiveOutputStream(Files.newOutputStream(archive)).use { stream ->
            stream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            stream.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            files.forEach { file ->
                val entry = TarArchiveEntry(entryName(distributionDir, file))
                entry.mode = mode(root, file)
                entry.setModTime(FileTime.fromMillis(0))
                entry.setIds(0, 0)
                entry.setNames("", "")
                if (Files.isRegularFile(file)) entry.size = Files.size(file)
                stream.putArchiveEntry(entry)
                if (Files.isRegularFile(file)) Files.copy(file, stream)
                stream.closeArchiveEntry()
            }
        }
    }
    return archive
}

private fun entryName(parent: Path, path: Path): String =
    parent.relativize(path).joinToString("/") + if (Files.isDirectory(path)) "/" else ""

private fun mode(root: Path, path: Path): Int =
    if (Files.isDirectory(path) || (path.parent == root.resolve("bin") && !path.toString().endsWith(".bat"))) 493 else 420
