plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "com.safedb"
version = "0.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.mysql:mysql-connector-j:9.3.0")
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.10.0.jre11")
    implementation("com.oracle.database.jdbc:ojdbc11:23.8.0.25.04")

    implementation("com.github.javakeyring:java-keyring:1.0.4")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.test {
    useJUnitPlatform()
}
