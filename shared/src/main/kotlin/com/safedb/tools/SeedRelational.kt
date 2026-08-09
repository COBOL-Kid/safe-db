package com.safedb.tools

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.system.exitProcess

fun main(rawArgs: Array<String>) {
    try {
        val args = rawArgs.toList()
        val dialectName = args.firstOrNull() ?: throw UsageError("missing database dialect")
        val dialect =
            when (dialectName.lowercase()) {
                "postgres" -> GeneratedSqlDialect.Postgres
                "mssql" -> GeneratedSqlDialect.Mssql
                "oracle" -> GeneratedSqlDialect.Oracle
                else -> throw UsageError("unsupported generated fixture dialect: $dialectName")
            }
        SeedRelational(dialect, args.drop(1)).run()
    } catch (error: UsageError) {
        System.err.println("error: ${error.message}")
        exitProcess(1)
    } catch (error: RuntimeException) {
        System.err.println("error: ${error.message}")
        exitProcess(1)
    }
}

private class SeedRelational(
    private val dialect: GeneratedSqlDialect,
    rawArgs: List<String>,
) {
    private val options = parseArgs(rawArgs)
    private val repoRoot = Path.of("").toAbsolutePath()
    private val env = System.getenv()
    private val settings = relationalSeedSettings(dialect, env, repoRoot)

    fun run() {
        if (options.help) {
            printRelationalUsage(dialect)
            return
        }
        validateSqlIdentifier(settings.database, settings.databaseVariable)
        validateSqlIdentifier(settings.user, settings.userVariable)
        validateContainerName(settings.container, settings.containerVariable)
        if (!settings.staticSql.exists()) {
            throw RuntimeException("SQL file not found: ${settings.staticSql}")
        }
        requireContainer()
        resetStateIfRequested()
        println("-> loading ${dialect.displayName()} fixture into '${settings.database}'")
        runClient { output ->
            BufferedWriter(OutputStreamWriter(output)).use { sql ->
                writePreamble(sql)
                if (options.static) {
                    settings.staticSql.inputStream().bufferedReader().use { input ->
                        input.forEachLine {
                            sql.write(it)
                            sql.newLine()
                        }
                    }
                } else {
                    RelationalFixtureGenerator(options.generator, dialect, sql).generate()
                }
                writeEpilogue(sql)
            }
        }
        verifyCounts()
        println("done.")
    }

    private fun requireContainer() {
        if (!commandExists("docker")) throw RuntimeException("'docker' not found in PATH")
        val inspect = runCommand(listOf("docker", "inspect", settings.container))
        if (inspect.exitCode != 0) {
            throw RuntimeException(
                "docker container '${settings.container}' not found; " +
                    "start it with scripts/docker_test_databases.sh up"
            )
        }
    }

    private fun resetStateIfRequested() {
        if (!options.resetState) return
        val dataDir = safeDbAppDataDirForStateReset() ?: return
        if (!dataDir.exists()) return
        dataDir.resolve("connections.json").toFile().writeText("[]\n")
        dataDir.resolve("query_history.json").toFile().writeText("[]\n")
        java.nio.file.Files.deleteIfExists(dataDir.resolve("query_history.v1.bak"))
    }

    private fun verifyCounts() {
        val tables =
            if (options.static) listOf("customers", "orders")
            else
                listOf(
                    "categories",
                    "products",
                    "customers",
                    "orders",
                    "order_items",
                    "inventory_log",
                )
        val query = "SELECT " + tables.joinToString(", ") { "(SELECT COUNT(*) FROM $it)" } + ";"
        println("-> verifying row counts (${tables.joinToString(" ")})")
        val output =
            runClient(captureOutput = true) { stream ->
                BufferedWriter(OutputStreamWriter(stream)).use { sql ->
                    writePreamble(sql)
                    sql.write(query)
                    sql.newLine()
                    writeEpilogue(sql)
                }
            }
        output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line -> line.matches(Regex("""\d+(\s+\d+){${tables.size - 1}}""")) }
            ?.let { println("  $it") }
            ?: throw RuntimeException("fixture count query returned no row counts")
    }

    private fun runClient(
        captureOutput: Boolean = false,
        writeInput: (java.io.OutputStream) -> Unit,
    ): String {
        val process =
            ProcessBuilder(clientCommand())
                .apply { environment().putAll(settings.clientEnvironment) }
                .start()
        try {
            process.outputStream.use(writeInput)
        } catch (error: java.io.IOException) {
            process.destroyForcibly()
            throw RuntimeException("database client closed while loading fixture", error)
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException(
                stderr.trim().ifBlank { stdout.trim().ifBlank { "database client failed" } }
            )
        }
        if (!captureOutput && stdout.isNotBlank()) print(stdout)
        return stdout
    }

    private fun clientCommand(): List<String> =
        when (dialect) {
            GeneratedSqlDialect.Postgres ->
                listOf(
                    "docker",
                    "exec",
                    "-i",
                    "-e",
                    "PGPASSWORD",
                    settings.container,
                    "psql",
                    "-X",
                    "-v",
                    "ON_ERROR_STOP=1",
                    "-qAt",
                    "-F",
                    " ",
                    "-U",
                    settings.user,
                    "-d",
                    settings.database,
                )
            GeneratedSqlDialect.Mssql ->
                listOf(
                    "docker",
                    "exec",
                    "-i",
                    "-e",
                    "SQLCMDPASSWORD",
                    settings.container,
                    "/opt/mssql-tools18/bin/sqlcmd",
                    "-S",
                    "localhost",
                    "-U",
                    settings.user,
                    "-C",
                    "-b",
                    "-h",
                    "-1",
                    "-W",
                    "-d",
                    settings.database,
                )
            GeneratedSqlDialect.Oracle ->
                listOf("docker", "exec", "-i", settings.container, "sqlplus", "-s", "/nolog")
            GeneratedSqlDialect.Mysql -> error("MySQL uses SeedMysql")
        }

    private fun writePreamble(sql: BufferedWriter) {
        if (dialect != GeneratedSqlDialect.Oracle) return
        sql.write("WHENEVER OSERROR EXIT FAILURE")
        sql.newLine()
        sql.write("WHENEVER SQLERROR EXIT SQL.SQLCODE")
        sql.newLine()
        sql.write("CONNECT / AS SYSDBA")
        sql.newLine()
        sql.write("ALTER SESSION SET CONTAINER = ${settings.database};")
        sql.newLine()
        sql.write("ALTER SESSION SET CURRENT_SCHEMA = ${settings.user};")
        sql.newLine()
        sql.write("SET HEADING OFF FEEDBACK OFF PAGESIZE 0 LINESIZE 32767 COLSEP ' '")
        sql.newLine()
    }

    private fun writeEpilogue(sql: BufferedWriter) {
        if (dialect == GeneratedSqlDialect.Oracle) {
            sql.write("COMMIT;")
            sql.newLine()
            sql.write("EXIT SUCCESS")
            sql.newLine()
        }
    }
}

internal data class SeedSettings(
    val database: String,
    val databaseVariable: String,
    val user: String,
    val userVariable: String,
    val container: String,
    val containerVariable: String,
    val staticSql: Path,
    val clientEnvironment: Map<String, String>,
)

internal fun relationalSeedSettings(
    dialect: GeneratedSqlDialect,
    env: Map<String, String>,
    repoRoot: Path,
): SeedSettings {
    fun value(name: String, fallback: String) = env[name] ?: fallback

    fun password(testName: String, dockerName: String, fallback: String) =
        env[testName] ?: env[dockerName]?.takeIf(String::isNotEmpty) ?: fallback

    return when (dialect) {
        GeneratedSqlDialect.Postgres ->
            SeedSettings(
                database = value("SAFEDB_TEST_POSTGRES_DATABASE", "safedb_test"),
                databaseVariable = "SAFEDB_TEST_POSTGRES_DATABASE",
                user = value("SAFEDB_TEST_POSTGRES_USER", "postgres"),
                userVariable = "SAFEDB_TEST_POSTGRES_USER",
                container = value("SAFEDB_TEST_POSTGRES_DOCKER", "safedb-test-postgres"),
                containerVariable = "SAFEDB_TEST_POSTGRES_DOCKER",
                staticSql = repoRoot.resolve("testdata_postgres.sql"),
                clientEnvironment =
                    mapOf(
                        "PGPASSWORD" to
                            password(
                                "SAFEDB_TEST_POSTGRES_PASSWORD",
                                "SAFEDB_DOCKER_POSTGRES_PASSWORD",
                                "postgres",
                            )
                    ),
            )
        GeneratedSqlDialect.Mssql ->
            SeedSettings(
                database = value("SAFEDB_TEST_MSSQL_DATABASE", "safedb_ssl"),
                databaseVariable = "SAFEDB_TEST_MSSQL_DATABASE",
                user = value("SAFEDB_TEST_MSSQL_USER", "sa"),
                userVariable = "SAFEDB_TEST_MSSQL_USER",
                container = value("SAFEDB_TEST_MSSQL_DOCKER", "safedb-test-mssql"),
                containerVariable = "SAFEDB_TEST_MSSQL_DOCKER",
                staticSql = repoRoot.resolve("testdata_mssql.sql"),
                clientEnvironment =
                    mapOf(
                        "SQLCMDPASSWORD" to
                            password(
                                "SAFEDB_TEST_MSSQL_PASSWORD",
                                "SAFEDB_DOCKER_MSSQL_PASSWORD",
                                "SafeDb_Ssl_Passw0rd!",
                            )
                    ),
            )
        GeneratedSqlDialect.Oracle ->
            SeedSettings(
                database = value("SAFEDB_TEST_ORACLE_DATABASE", "FREEPDB1"),
                databaseVariable = "SAFEDB_TEST_ORACLE_DATABASE",
                user = value("SAFEDB_TEST_ORACLE_USER", "safedb").uppercase(),
                userVariable = "SAFEDB_TEST_ORACLE_USER",
                container = value("SAFEDB_TEST_ORACLE_DOCKER", "safedb-test-oracle"),
                containerVariable = "SAFEDB_TEST_ORACLE_DOCKER",
                staticSql = repoRoot.resolve("testdata_oracle.sql"),
                clientEnvironment = emptyMap(),
            )
        GeneratedSqlDialect.Mysql -> error("MySQL uses SeedMysql")
    }
}

private fun validateSqlIdentifier(value: String, label: String) {
    if (!Regex("""^[A-Za-z][A-Za-z0-9_]*$""").matches(value)) {
        throw RuntimeException("invalid $label")
    }
}

private fun validateContainerName(value: String, label: String) {
    if (!Regex("""^[A-Za-z0-9][A-Za-z0-9_.-]*$""").matches(value)) {
        throw RuntimeException("invalid $label")
    }
}

private fun GeneratedSqlDialect.displayName(): String =
    when (this) {
        GeneratedSqlDialect.Postgres -> "PostgreSQL"
        GeneratedSqlDialect.Mssql -> "SQL Server"
        GeneratedSqlDialect.Oracle -> "Oracle"
        GeneratedSqlDialect.Mysql -> "MySQL"
    }

private fun printRelationalUsage(dialect: GeneratedSqlDialect) {
    val task =
        dialect.displayName().replace("SQL Server", "Mssql").replace("PostgreSQL", "Postgres")
    println(
        """
        ./gradlew seed$task - generate and load a safe-db ${dialect.displayName()} fixture.

        Options (pass with -Pseed${task}Args="..."):
          --static          Load the checked-in minimal fixture
          --orders <n>      Generated order count (default: 50000)
          --customers <n>   Generated customer count (default: 10000)
          --products <n>    Generated product count (default: 500)
          --categories <n>  Generated category count (default: 12)
          --seed <n>        Deterministic random seed (default: 42)
          --batch-size <n>  Rows per INSERT statement (default: 1000)
          --reset-state     Wipe local safe-db connections and query history
          -h, --help        Show this help
        """
            .trimIndent()
    )
}
