plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.ncorti.ktfmt.gradle")
    id("com.gradleup.shadow")
    application
}

ktfmt { kotlinLangStyle() }

group = "com.safedb"

version = rootProject.version

kotlin { jvmToolchain(25) }

application { mainClass.set("com.safedb.mcp.MainKt") }

dependencies {
    implementation(project(":shared"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":shared")))
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

val generateMcpVersion =
    tasks.register("generateMcpVersion") {
        val outputDir = layout.buildDirectory.dir("generated/resources/mcpVersion")
        val mcpVersion = provider { version.toString() }
        inputs.property("version", mcpVersion)
        outputs.dir(outputDir)
        doLast {
            val file = outputDir.get().file("mcp-version.txt").asFile
            file.parentFile.mkdirs()
            file.writeText(mcpVersion.get())
        }
    }

sourceSets.named("main") { resources.srcDir(generateMcpVersion) }

tasks.shadowJar {
    archiveBaseName.set("safe-db-mcp")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
    manifest { attributes["Main-Class"] = "com.safedb.mcp.MainKt" }
}

tasks.test { useJUnitPlatform() }
