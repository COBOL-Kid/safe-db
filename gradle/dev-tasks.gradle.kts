import com.safedb.buildlogic.splitSeedTaskArgs

val render = extensions.getByType<SourceSetContainer>().getByName("render")

// Resolved lazily from inside task configuration blocks, after :shared has been evaluated.
fun sharedToolsRuntimeClasspath(): FileCollection =
    project(":shared")
        .extensions
        .getByType<SourceSetContainer>()
        .getByName("tools")
        .runtimeClasspath

fun seedTaskArgs(taskName: String): List<String> {
    val propertyName = "${taskName}Args"
    return splitSeedTaskArgs(providers.gradleProperty(propertyName).orElse("").get(), propertyName)
}

tasks.register<JavaExec>("seedMysql") {
    group = "safe-db"
    description = "Seed the local safe-db MySQL test database."
    classpath = sharedToolsRuntimeClasspath()
    mainClass.set("com.safedb.tools.SeedMysqlKt")
    workingDir = projectDir
    args(seedTaskArgs("seedMysql"))
}

fun registerRelationalSeedTask(
    taskName: String,
    dialect: String,
) {
    tasks.register<JavaExec>(taskName) {
        group = "safe-db"
        description = "Seed the local safe-db $dialect test database."
        classpath = sharedToolsRuntimeClasspath()
        mainClass.set("com.safedb.tools.SeedRelationalKt")
        workingDir = projectDir
        args(dialect, *seedTaskArgs(taskName).toTypedArray())
    }
}

registerRelationalSeedTask("seedPostgres", "postgres")

registerRelationalSeedTask("seedMssql", "mssql")

registerRelationalSeedTask("seedOracle", "oracle")

registerRelationalSeedTask("seedDb2", "db2")

tasks.register<JavaExec>("renderPreview") {
    group = "safe-db"
    description = "Render main screens headlessly to /tmp/safedb-preview for visual checks."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderPreviewKt")
}

tasks.register<JavaExec>("renderReportExport") {
    group = "safe-db"
    description = "Export a genuine Explore HTML report to /tmp/safedb-preview/report."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderReportExportKt")
}

tasks.register<JavaExec>("renderHeroFrames") {
    group = "safe-db"
    description = "Render the query-builder build-up sequence to /tmp/safedb-preview/hero."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.HeroFramesKt")
}

tasks.register<JavaExec>("renderThemeGallery") {
    group = "safe-db"
    description = "Render the color scheme picker and Connections screen for every scheme."
    classpath = render.runtimeClasspath
    mainClass.set("com.safedb.tools.RenderThemeGalleryKt")
}
