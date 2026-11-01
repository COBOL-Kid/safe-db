package com.safedb.buildlogic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException

class SkikoMacNativeTest {
    @Test
    fun selectsTheSingleMacArm64RuntimeJar() {
        val dir = Files.createTempDirectory("skiko-jars").toFile()
        try {
            val runtime = dir.resolve("skiko-awt-runtime-macos-arm64-0.150.1.jar")
            val classes = dir.resolve("skiko-awt-0.150.1.jar")
            runtime.writeBytes(byteArrayOf(1))
            classes.writeBytes(byteArrayOf(2))
            assertEquals(runtime, skikoMacArm64RuntimeJar(listOf(classes, runtime)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsZeroOrSeveralRuntimeJars() {
        val none = assertFailsWith<GradleException> { skikoMacArm64RuntimeJar(emptyList()) }
        assertTrue(none.message!!.contains("found none"))

        val dir = Files.createTempDirectory("skiko-jars").toFile()
        try {
            val first = dir.resolve("skiko-awt-runtime-macos-arm64-0.1.jar")
            val second = dir.resolve("skiko-awt-runtime-macos-arm64-0.2.jar")
            first.writeBytes(byteArrayOf(1))
            second.writeBytes(byteArrayOf(2))
            val several =
                assertFailsWith<GradleException> { skikoMacArm64RuntimeJar(listOf(first, second)) }
            assertTrue(several.message!!.contains("0.1.jar"))
            assertTrue(several.message!!.contains("0.2.jar"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extractsTheSidecarMatchedArm64Library() {
        val dir = Files.createTempDirectory("skiko-extract").toFile()
        try {
            val payload = arm64MachO("payload")
            val jar = dir.resolve("skiko-awt-runtime-macos-arm64-0.150.1.jar")
            writeJar(jar, payload, sha256Hex(payload))
            val dest = dir.resolve("out/libskiko-macos-arm64.dylib")
            extractSkikoMacArm64Dylib(jar, dest)
            assertTrue(dest.readBytes().contentEquals(payload))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsASidecarThatDoesNotMatchTheLibraryBytes() {
        val dir = Files.createTempDirectory("skiko-mismatch").toFile()
        try {
            val jar = dir.resolve("skiko-awt-runtime-macos-arm64-0.150.1.jar")
            writeJar(jar, arm64MachO("signed-later"), "a".repeat(64))
            val failure =
                assertFailsWith<GradleException> {
                    extractSkikoMacArm64Dylib(jar, dir.resolve("libskiko-macos-arm64.dylib"))
                }
            assertTrue(failure.message!!.contains("sidecar says"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsAnIntelMachOMislabelledAsArm64() {
        val dir = Files.createTempDirectory("skiko-x64").toFile()
        try {
            val payload = machO(cpuType = 0x01000007, body = "x64")
            val jar = dir.resolve("skiko-awt-runtime-macos-arm64-0.150.1.jar")
            writeJar(jar, payload, sha256Hex(payload))
            val failure =
                assertFailsWith<GradleException> {
                    extractSkikoMacArm64Dylib(jar, dir.resolve("libskiko-macos-arm64.dylib"))
                }
            assertTrue(failure.message!!.contains("not a 64-bit arm64 Mach-O"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun writeJar(jar: File, library: ByteArray, sha256: String) {
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(SKIKO_MAC_ARM64_DYLIB))
            zip.write(library)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(SKIKO_MAC_ARM64_DYLIB_SHA256))
            zip.write(sha256.toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("libskiko-macos-x64.dylib"))
            zip.write(machO(cpuType = 0x01000007, body = "ignore"))
            zip.closeEntry()
        }
    }

    private fun arm64MachO(body: String): ByteArray = machO(cpuType = 0x0100000c, body = body)

    private fun machO(cpuType: Int, body: String): ByteArray {
        val header = ByteArray(8)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0xfeedfacf.toInt())
        buffer.putInt(cpuType)
        return header + body.toByteArray(Charsets.US_ASCII)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
