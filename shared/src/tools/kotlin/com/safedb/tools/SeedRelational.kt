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
                "db2" -> GeneratedSqlDialect.Db2
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
        dropDb2ObjectsIfPresent()
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

    // Db2 LUW has no DROP ... IF EXISTS, so neither the static fixture nor the generated
    // DDL can drop objects itself. Issue best-effort drops up front (ignoring SQL0204N)
    // so both fresh and repeat seeds load cleanly.
    private fun dropDb2ObjectsIfPresent() {
        if (dialect != GeneratedSqlDialect.Db2) return
        val drops =
            listOf(
                "DROP VIEW CUSTOMER_ORDER_SUMMARY",
                "DROP TABLE ORDER_ITEMS",
                "DROP TABLE INVENTORY_LOG",
                "DROP TABLE ORDERS",
                "DROP TABLE PRODUCTS",
                "DROP TABLE CUSTOMERS",
                "DROP TABLE CATEGORIES",
            )
        val loop = drops.joinToString(" ") { "\"$it\"" }
        // settings.user/database are validated SQL identifiers above, so interpolation is safe.
        val script =
            $$". \"$(getent passwd $${settings.user} | cut -d: -f6)/sqllib/db2profile\" && " +
                $$"db2 CONNECT TO $${settings.database} >/dev/null && " +
                $$"for s in $$loop; do db2 \"$s\" >/dev/null 2>&1 || true; done; true"
        println("-> dropping existing Db2 objects (best-effort)")
        runCommand(
            listOf(
                "docker",
                "exec",
                "-i",
                "-u",
                settings.user,
                settings.container,
                "bash",
                "-c",
                script,
            )
        )
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
        val query = relationalVerifyCountQuery(dialect, tables)
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
            GeneratedSqlDialect.Db2 ->
                // The CLP runs as the instance owner so local CONNECT needs no password;
                // -s stops the load on the first real error, -x keeps count output parseable.
                listOf(
                    "docker",
                    "exec",
                    "-i",
                    "-u",
                    settings.user,
                    settings.container,
                    "bash",
                    "-c",
                    $$". \"$(getent passwd $${settings.user} | cut -d: -f6)/sqllib/db2profile\" && db2 -s -t -x",
                )
            GeneratedSqlDialect.Mysql -> error("MySQL uses SeedMysql")
        }

    private fun writePreamble(sql: BufferedWriter) {
        if (dialect == GeneratedSqlDialect.Db2) {
            sql.write("CONNECT TO ${settings.database};")
            sql.newLine()
            return
        }
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
        GeneratedSqlDialect.Db2 ->
            SeedSettings(
                database = value("SAFEDB_TEST_DB2_DATABASE", "safedb_test"),
                databaseVariable = "SAFEDB_TEST_DB2_DATABASE",
                user = value("SAFEDB_TEST_DB2_USER", "db2inst1"),
                userVariable = "SAFEDB_TEST_DB2_USER",
                container = value("SAFEDB_TEST_DB2_DOCKER", "safedb-test-db2"),
                containerVariable = "SAFEDB_TEST_DB2_DOCKER",
                staticSql = repoRoot.resolve("testdata_db2.sql"),
                // Local CONNECT as the instance owner needs no password.
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

// Db2 rejects a top-level SELECT that has no FROM clause. The other clients accept it.
internal fun relationalVerifyCountQuery(
    dialect: GeneratedSqlDialect,
    tables: List<String>,
): String {
    val fromClause = if (dialect == GeneratedSqlDialect.Db2) " FROM SYSIBM.SYSDUMMY1" else ""
    return "SELECT " + tables.joinToString(", ") { "(SELECT COUNT(*) FROM $it)" } + "$fromClause;"
}

private fun GeneratedSqlDialect.displayName(): String =
    when (this) {
        GeneratedSqlDialect.Postgres -> "PostgreSQL"
        GeneratedSqlDialect.Mssql -> "SQL Server"
        GeneratedSqlDialect.Oracle -> "Oracle"
        GeneratedSqlDialect.Db2 -> "Db2"
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
