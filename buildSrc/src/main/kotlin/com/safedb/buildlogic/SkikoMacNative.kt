package com.safedb.buildlogic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

internal const val SKIKO_MAC_ARM64_DYLIB = "libskiko-macos-arm64.dylib"

internal const val SKIKO_MAC_ARM64_DYLIB_SHA256 = "libskiko-macos-arm64.dylib.sha256"

private const val MACH_O_64_MAGIC = 0xfeedfacf.toInt()

private const val CPU_TYPE_ARM64 = 0x0100000c

// Skiko's macOS arm64 runtime jar ships the upstream dylib next to a sha256 sidecar.
// Conveyor later signs a loose copy of that file; the sidecar hash must still describe
// these bytes so a re-signed JAR member cannot be mistaken for the library we bundle.
fun skikoMacArm64RuntimeJar(files: Iterable<File>): File {
    val matches = files.filter { file ->
        file.name.startsWith("skiko-awt-runtime-macos-arm64-") && file.name.endsWith(".jar")
    }
    if (matches.size != 1) {
        val found = matches.joinToString { it.name }.ifEmpty { "none" }
        throw GradleException("expected one skiko-awt-runtime-macos-arm64 jar, found $found")
    }
    return matches.single()
}

fun extractSkikoMacArm64Dylib(jar: File, destination: File) {
    ZipFile(jar).use { zip ->
        val library =
            zip.getEntry(SKIKO_MAC_ARM64_DYLIB)
                ?: throw GradleException("${jar.name} is missing $SKIKO_MAC_ARM64_DYLIB")
        val sidecar =
            zip.getEntry(SKIKO_MAC_ARM64_DYLIB_SHA256)
                ?: throw GradleException("${jar.name} is missing $SKIKO_MAC_ARM64_DYLIB_SHA256")
        val bytes = zip.getInputStream(library).use { it.readBytes() }
        val expected =
            zip.getInputStream(sidecar).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val actual = sha256Hex(bytes)
        if (
            !expected.matches(Regex("[0-9a-fA-F]{64}")) ||
                !expected.equals(actual, ignoreCase = true)
        ) {
            throw GradleException(
                "$SKIKO_MAC_ARM64_DYLIB sha256 is $actual, sidecar says $expected"
            )
        }
        requireArm64MachO(bytes)
        destination.parentFile?.mkdirs()
        destination.writeBytes(bytes)
    }
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

abstract class ExtractMacSkikoNative : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val macRuntimeJars: ConfigurableFileCollection

    @get:OutputFile abstract val dylib: RegularFileProperty

    @TaskAction
    fun extract() {
        val jar = skikoMacArm64RuntimeJar(macRuntimeJars.files)
        extractSkikoMacArm64Dylib(jar, dylib.get().asFile)
    }
}

private fun requireArm64MachO(bytes: ByteArray) {
    if (bytes.size < 8) {
        throw GradleException("$SKIKO_MAC_ARM64_DYLIB is too small to be a Mach-O library")
    }
    val header = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN)
    val magic = header.int
    val cpuType = header.int
    if (magic != MACH_O_64_MAGIC || cpuType != CPU_TYPE_ARM64) {
        throw GradleException(
            "$SKIKO_MAC_ARM64_DYLIB is not a 64-bit arm64 Mach-O " +
                "(magic=${magic.toUInt().toString(16)} cpu=${cpuType.toUInt().toString(16)})"
        )
    }
}
