package org.junit.rules
import java.io.File
import java.nio.file.Files
class TemporaryFolder {
    val root: File = Files.createTempDirectory("ld-test").toFile()
    fun newFolder(name: String): File = File(root, name).apply { mkdirs() }
    fun newFolder(): File = Files.createTempDirectory(root.toPath(), "f").toFile()
    fun newFile(name: String): File = File(root, name).apply { parentFile?.mkdirs(); createNewFile() }
    fun delete() { root.deleteRecursively() }
}
