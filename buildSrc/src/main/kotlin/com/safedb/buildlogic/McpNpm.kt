package com.safedb.buildlogic

import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Locale
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

data class TemurinArtifact(val url: String, val sha256: String, val filename: String)

data class NpmPlatform(
    val npm: String,
    val os: List<String>,
    val cpu: List<String>,
    val artifact: TemurinArtifact,
)

data class TemurinManifest(
    val releaseName: String,
    val jlinkJdk: TemurinArtifact,
    val platforms: List<NpmPlatform>,
)

fun npmPlatform(osName: String, osArch: String): String {
    val os = osName.lowercase(Locale.ROOT)
    val arch = osArch.lowercase(Locale.ROOT)
    val npmOs =
        when {
            os.startsWith("mac") || os.startsWith("darwin") -> "darwin"
            os.startsWith("win") -> "win32"
            os.startsWith("linux") -> "linux"
            else -> throw GradleException("Unsupported OS for MCP npm packaging: $osName")
        }
    val npmArch =
        when (arch) {
            "x86_64",
            "amd64" -> "x64"
            "aarch64",
            "arm64" -> "arm64"
            else -> throw GradleException("Unsupported arch for MCP npm packaging: $osArch")
        }
    return "$npmOs-$npmArch"
}

fun currentNpmPlatform(): String =
    npmPlatform(System.getProperty("os.name").orEmpty(), System.getProperty("os.arch").orEmpty())

fun javaExecutableName(npmPlatform: String): String =
    if (npmPlatform.startsWith("win32")) "java.exe" else "java"

fun parseTemurinManifest(file: File): TemurinManifest {
    val raw = jsonObject(JsonSlurper().parse(file))
    return TemurinManifest(
        releaseName = raw.string("releaseName"),
        jlinkJdk = parseArtifact(jsonObject(raw.getValue("jlinkJdk"))),
        platforms =
            jsonList(raw.getValue("platforms")).map { platform ->
                val obj = jsonObject(platform)
                NpmPlatform(
                    npm = obj.string("npm"),
                    os = jsonList(obj.getValue("os")).map { it as String },
                    cpu = jsonList(obj.getValue("cpu")).map { it as String },
                    artifact = parseArtifact(obj),
                )
            },
    )
}

private fun parseArtifact(raw: Map<String, Any?>): TemurinArtifact =
    TemurinArtifact(
        url = raw.string("url"),
        sha256 = raw.string("sha256").lowercase(Locale.ROOT),
        filename = raw.string("filename"),
    )

@Suppress("UNCHECKED_CAST")
private fun jsonObject(value: Any?): Map<String, Any?> = value as Map<String, Any?>

@Suppress("UNCHECKED_CAST") private fun jsonList(value: Any?): List<Any?> = value as List<Any?>

private fun Map<String, Any?>.string(key: String): String = getValue(key) as String

fun readJlinkModules(file: File): List<String> =
    file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()

fun downloadVerified(artifact: TemurinArtifact, cacheDir: File): File {
    cacheDir.mkdirs()
    val dest = File(cacheDir, artifact.filename)
    if (dest.isFile && sha256(dest) == artifact.sha256) {
        return dest
    }
    val tmp = Files.createTempFile(cacheDir.toPath(), artifact.filename, ".part").toFile()
    try {
        val client =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        val request =
            HttpRequest.newBuilder(URI.create(artifact.url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
        if (response.statusCode() !in 200..299) {
            throw GradleException(
                "Download failed (${response.statusCode()}) for ${artifact.filename} from ${artifact.url}"
            )
        }
        val actual = sha256(tmp)
        if (actual != artifact.sha256) {
            throw GradleException(
                "SHA-256 mismatch for ${artifact.filename}: expected ${artifact.sha256}, got $actual"
            )
        }
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return dest
    } finally {
        tmp.delete()
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun extractArchive(
    archive: File,
    dest: File,
    fileSystem: FileSystemOperations,
    archives: ArchiveOperations,
) {
    if (dest.resolve(".extracted").isFile) return
    dest.deleteRecursively()
    dest.mkdirs()
    val tree =
        if (archive.name.endsWith(".zip", ignoreCase = true)) {
            archives.zipTree(archive)
        } else {
            archives.tarTree(archive)
        }
    fileSystem.copy {
        from(tree)
        into(dest)
    }
    dest.resolve(".extracted").writeText(archive.name)
}

fun findJmodsDirectory(extracted: File): File {
    val found =
        extracted.walkTopDown().firstOrNull { it.isFile && it.name == "java.base.jmod" }?.parentFile
    return found ?: throw GradleException("java.base.jmod not found under $extracted")
}

fun findJavaHome(extracted: File): File {
    val java =
        extracted.walkTopDown().firstOrNull {
            it.isFile &&
                (it.name == "java" || it.name == "java.exe") &&
                it.parentFile?.name == "bin"
        } ?: throw GradleException("java executable not found under $extracted")
    return java.parentFile.parentFile
}

fun runJlink(
    exec: ExecOperations,
    jlink: File,
    jmods: File,
    modules: List<String>,
    output: File,
) {
    output.deleteRecursively()
    output.parentFile.mkdirs()
    exec.exec {
        commandLine(
            jlink.absolutePath,
            "--module-path",
            jmods.absolutePath,
            "--add-modules",
            modules.joinToString(","),
            "--output",
            output.absolutePath,
            "--compress",
            "zip-6",
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--include-locales",
            "en",
        )
    }
    val bundledJava =
        output.resolve("bin").let { bin ->
            sequenceOf(bin.resolve("java"), bin.resolve("java.exe")).firstOrNull { it.isFile }
        }
    if (bundledJava == null) {
        throw GradleException(
            "jlink did not produce a java executable under ${output.resolve("bin")}"
        )
    }
}

fun extraModulesFromJdeps(
    exec: ExecOperations,
    jdeps: File,
    jar: File,
    known: Set<String>,
): List<String> {
    if (!jdeps.isFile) {
        throw GradleException("jdeps not found at $jdeps")
    }
    val stdout = ByteArrayOutputStream()
    val result = exec.exec {
        commandLine(
            jdeps.absolutePath,
            "--ignore-missing-deps",
            "--multi-release",
            "25",
            "--print-module-deps",
            jar.absolutePath,
        )
        isIgnoreExitValue = true
        standardOutput = stdout
    }
    val extras =
        extraModulesFromJdepsOutput(stdout.toString(Charsets.UTF_8), known, result.exitValue)
    if (extras.isNotEmpty()) {
        throw GradleException("jdeps reported modules not listed in jlink-modules.txt: $extras")
    }
    return extras
}

fun extraModulesFromJdepsOutput(
    stdout: String,
    known: Set<String>,
    exitValue: Int = 0,
): List<String> {
    if (exitValue != 0) {
        throw GradleException("jdeps failed with exit $exitValue")
    }
    return stdout.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in known }
}

fun String.replaceRequired(sentinel: String, replacement: String): String {
    if (!contains(sentinel)) {
        throw GradleException("Missing package.json sentinel: $sentinel")
    }
    return replace(sentinel, replacement)
}

fun stampMetaPackageJson(template: String, version: String, platforms: List<NpmPlatform>): String {
    val optional =
        platforms.joinToString(",\n    ") { platform ->
            "\"@safe-db/mcp-${platform.npm}\": \"$version\""
        }
    return template
        .replaceRequired("\"version\": \"0.0.0-dev\"", "\"version\": \"$version\"")
        .replaceRequired(
            "\"optionalDependencies\": {}",
            "\"optionalDependencies\": {\n    $optional\n  }",
        )
}

fun stampPlatformPackageJson(template: String, platform: NpmPlatform, version: String): String {
    val os = platform.os.joinToString(", ") { "\"$it\"" }
    val cpu = platform.cpu.joinToString(", ") { "\"$it\"" }
    return template
        .replaceRequired("@safe-db/mcp-PLATFORM", "@safe-db/mcp-${platform.npm}")
        .replaceRequired("PLATFORM jlink runtime", "${platform.npm} jlink runtime")
        .replaceRequired("\"version\": \"0.0.0-dev\"", "\"version\": \"$version\"")
        .replaceRequired("\"os\": []", "\"os\": [$os]")
        .replaceRequired("\"cpu\": []", "\"cpu\": [$cpu]")
}

fun stampMetaPackage(
    sourceDir: File,
    destDir: File,
    version: String,
    platforms: List<NpmPlatform>,
    license: File,
    fileSystem: FileSystemOperations,
) {
    destDir.deleteRecursively()
    destDir.mkdirs()
    fileSystem.copy {
        from(sourceDir.resolve("cli.js"))
        from(sourceDir.resolve("README.md"))
        into(destDir)
    }
    Files.copy(license.toPath(), destDir.resolve("LICENSE").toPath())
    destDir
        .resolve("package.json")
        .writeText(
            stampMetaPackageJson(
                sourceDir.resolve("package.json").readText(),
                version,
                platforms,
            )
        )
}

fun stampPlatformPackage(
    template: File,
    destDir: File,
    platform: NpmPlatform,
    version: String,
    license: File,
    jre: File,
    jar: File,
    fileSystem: FileSystemOperations,
) {
    destDir.deleteRecursively()
    destDir.mkdirs()
    val jreDest = destDir.resolve("jre")
    val libDest = destDir.resolve("lib")
    fileSystem.copy {
        from(jre)
        into(jreDest)
    }
    libDest.mkdirs()
    Files.copy(jar.toPath(), libDest.resolve("safe-db-mcp.jar").toPath())
    Files.copy(license.toPath(), destDir.resolve("LICENSE").toPath())
    destDir
        .resolve("package.json")
        .writeText(stampPlatformPackageJson(template.readText(), platform, version))
}

abstract class AssembleMcpNpm : DefaultTask() {
    @get:Inject abstract val fileSystem: FileSystemOperations

    @get:Inject abstract val archives: ArchiveOperations

    @get:Inject abstract val exec: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val temurinManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jlinkModulesFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val npmSource: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val platformTemplate: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val shadowJar: RegularFileProperty

    @get:Input abstract val packageVersion: Property<String>

    @get:Input abstract val platforms: ListProperty<String>

    @get:Input abstract val downloadJlinkJdk: Property<Boolean>

    @get:Input @get:Optional abstract val hostJavaHome: Property<String>

    @get:Input @get:Optional abstract val hostJavaVersion: Property<String>

    @get:Internal abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun assemble() {
        val manifest = parseTemurinManifest(temurinManifest.get().asFile)
        val requested = platforms.get().toSet()
        val selected = manifest.platforms.filter { it.npm in requested }
        if (selected.size != requested.size) {
            val unknown = requested - selected.map { it.npm }.toSet()
            throw GradleException("Unknown MCP npm platforms: $unknown")
        }
        val cache = cacheDir.get().asFile
        val modules = readJlinkModules(jlinkModulesFile.get().asFile)
        val jlinkHome =
            if (downloadJlinkJdk.get()) {
                val archive = downloadVerified(manifest.jlinkJdk, cache.resolve("downloads"))
                val extracted = cache.resolve("jdk").resolve(manifest.jlinkJdk.sha256)
                extractArchive(archive, extracted, fileSystem, archives)
                findJavaHome(extracted)
            } else {
                File(hostJavaHome.get())
            }
        val jlink = jlinkHome.resolve("bin").resolve(if (isWindowsHost()) "jlink.exe" else "jlink")
        if (!jlink.isFile) {
            throw GradleException("jlink not found at $jlink")
        }
        val jdeps = jlink.parentFile.resolve(if (isWindowsHost()) "jdeps.exe" else "jdeps")
        extraModulesFromJdeps(exec, jdeps, shadowJar.get().asFile, modules.toSet())
        val npmOut = outputDir.get().asFile
        npmOut.deleteRecursively()
        npmOut.mkdirs()
        stampMetaPackage(
            sourceDir = npmSource.get().asFile,
            destDir = npmOut.resolve("@safe-db").resolve("mcp"),
            version = packageVersion.get(),
            platforms = manifest.platforms,
            license = license.get().asFile,
            fileSystem = fileSystem,
        )
        for (platform in selected) {
            logger.lifecycle("Assembling @safe-db/mcp-${platform.npm} with ${manifest.releaseName}")
            val archive = downloadVerified(platform.artifact, cache.resolve("downloads"))
            val extracted = cache.resolve("jmods").resolve(platform.artifact.sha256)
            extractArchive(archive, extracted, fileSystem, archives)
            val jmods = findJmodsDirectory(extracted)
            val jre = cache.resolve("jre").resolve(platform.npm)
            runJlink(exec, jlink, jmods, modules, jre)
            stampPlatformPackage(
                template = platformTemplate.get().asFile,
                destDir = npmOut.resolve("@safe-db").resolve("mcp-${platform.npm}"),
                platform = platform,
                version = packageVersion.get(),
                license = license.get().asFile,
                jre = jre,
                jar = shadowJar.get().asFile,
                fileSystem = fileSystem,
            )
        }
    }
}

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

fun nodeOnPath(): Boolean {
    val node = if (isWindowsHost()) "node.exe" else "node"
    val path = System.getenv("PATH") ?: return false
    return path.split(File.pathSeparator).any { dir -> File(dir, node).isFile }
}

fun isLinuxX64(): Boolean {
    val os = System.getProperty("os.name").orEmpty()
    val arch = System.getProperty("os.arch").orEmpty()
    return os.startsWith("Linux", ignoreCase = true) && (arch == "amd64" || arch == "x86_64")
}
