import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "com.safedb"
version = "0.0.1"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.desktop {
    application {
        mainClass = "com.safedb.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage, TargetFormat.Rpm)
            packageName = "safe-db"
            packageVersion = "0.1.0"
            vendor = "safe-db"
            description = "Safely explore production databases"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("integrationTest") {
    group = "verification"
    description = "Runs :shared JDBC integration tests."
    dependsOn(":shared:integrationTest")
}

tasks.register<JavaExec>("seedMysql") {
    group = "safe-db"
    description = "Seed the local safe-db MySQL test database."
    val shared = project(":shared")
    val kotlinExt = shared.extensions.getByType<KotlinMultiplatformExtension>()
    val mainCompilation = kotlinExt.targets.getByName("jvm").compilations.getByName("main")
    val sharedJar = shared.tasks.named("jvmJar")
    dependsOn(sharedJar)
    classpath = files(sharedJar) + (mainCompilation.runtimeDependencyFiles ?: files())
    mainClass.set("com.safedb.tools.SeedMysqlKt")
    workingDir = projectDir
    args(splitSeedMysqlArgs(providers.gradleProperty("seedMysqlArgs").orElse("").get()))
}

fun splitSeedMysqlArgs(raw: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaping = false
    for (char in raw) {
        when {
            escaping -> {
                current.append(char)
                escaping = false
            }
            char == '\\' -> escaping = true
            quote != null && char == quote -> quote = null
            quote == null && (char == '\'' || char == '"') -> quote = char
            quote == null && char.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    args.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(char)
        }
    }
    if (escaping) current.append('\\')
    if (quote != null) throw GradleException("Unclosed quote in seedMysqlArgs")
    if (current.isNotEmpty()) args.add(current.toString())
    return args
}

tasks.register<JavaExec>("renderPreview") {
    group = "safe-db"
    description = "Render main screens headlessly to /tmp/safedb-preview for visual checks."
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("com.safedb.tools.RenderPreviewKt")
}
