plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "com.safedb"
version = "0.0.1"

kotlin {
    jvmToolchain(25)

    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
        compilations.create("integrationTest") {
            associateWith(this@jvm.compilations.getByName("main"))
            defaultSourceSet {
                kotlin.srcDirs("src/jvmIntegrationTest/kotlin")
                dependencies {
                    implementation(kotlin("test"))
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                    implementation("org.junit.jupiter:junit-jupiter:5.12.2")
                    runtimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
                }
            }
        }
    }

    sourceSets {
        val jvmMain = getByName("jvmMain")
        jvmMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

            implementation("com.zaxxer:HikariCP:6.3.0")
            implementation("org.postgresql:postgresql:42.7.7")
            implementation("com.mysql:mysql-connector-j:9.3.0")
            implementation("com.microsoft.sqlserver:mssql-jdbc:12.10.0.jre11")
            implementation("com.oracle.database.jdbc:ojdbc11:23.8.0.25.04")

            implementation("com.github.javakeyring:java-keyring:1.0.4")
        }
        val jvmTest = getByName("jvmTest")
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            implementation("org.junit.jupiter:junit-jupiter:5.12.2")
        }
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs JDBC integration tests tagged @Tag(\"integration\"); requires MySQL when not skipped."
    group = "verification"
    val compilation = kotlin.jvm().compilations.getByName("integrationTest")
    dependsOn(compilation.compileTaskProvider)
    val integrationClasses = layout.buildDirectory.dir("classes/kotlin/jvm/integrationTest")
    val mainClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
    testClassesDirs = files(integrationClasses)
    classpath = (compilation.runtimeDependencyFiles ?: files()) + files(integrationClasses, mainClasses)
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.named("jvmTest"))
}
