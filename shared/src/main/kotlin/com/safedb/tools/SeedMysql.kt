package com.safedb.tools

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(rawArgs: Array<String>) {
    try {
        SeedMysql(rawArgs.toList()).run()
    } catch (error: UsageError) {
        System.err.println("error: ${error.message}")
        printUsage(System.err)
        exitProcess(1)
    } catch (error: RuntimeException) {
        System.err.println("error: ${error.message}")
        exitProcess(1)
    }
}

private class SeedMysql(rawArgs: List<String>) {
    private val repoRoot = Path.of("").toAbsolutePath()
    private val staticSql = repoRoot.resolve("testdata_mysql.sql")

    private val env = System.getenv()
    private val host = env["SAFEDB_TEST_MYSQL_HOST"] ?: "localhost"
    private val port = env["SAFEDB_TEST_MYSQL_PORT"] ?: "3306"
    private val user = env["SAFEDB_TEST_MYSQL_USER"] ?: "root"
    private val database = env["SAFEDB_TEST_MYSQL_DATABASE"] ?: DEFAULT_DATABASE
    private val dockerPin = env["SAFEDB_TEST_MYSQL_DOCKER"].orEmpty()
    private var password = env["SAFEDB_TEST_MYSQL_PASSWORD"].orEmpty()

    private val options = parseArgs(rawArgs)
    private var dockerContainer = ""
    private var defaultsFile: Path? = null

    fun run() {
        if (options.help) {
            printUsage(System.out)
            return
        }

        validateEnvironment()
        if (options.static && !staticSql.exists()) {
            throw RuntimeException("SQL file not found: ${staticSql.absolute()}")
        }

        resolveMysqlClient()
        resolveDockerPassword()
        createDefaultsFileIfNeeded()

        try {
            checkConnection()
            resetStateIfRequested()
            resetDatabaseIfRequested()
            loadFixture()
            verifyCounts()
            printDone()
        } finally {
            defaultsFile?.let { Files.deleteIfExists(it) }
        }
    }

    private fun validateEnvironment() {
        sanitizeIdentifier(database, "SAFEDB_TEST_MYSQL_DATABASE")
        sanitizeIdentifier(host, "SAFEDB_TEST_MYSQL_HOST")
        sanitizeIdentifier(user, "SAFEDB_TEST_MYSQL_USER")
        if (password.contains('\n') || password.contains('\r')) {
            throw RuntimeException("invalid SAFEDB_TEST_MYSQL_PASSWORD (must not contain newlines)")
        }
        if (dockerPin.isNotEmpty()) {
            sanitizeIdentifier(dockerPin, "SAFEDB_TEST_MYSQL_DOCKER")
        }
        val parsedPort = port.toIntOrNull()
        if (parsedPort == null || parsedPort !in 1..65535) {
            throw RuntimeException("invalid SAFEDB_TEST_MYSQL_PORT (must be 1-65535)")
        }
    }

    private fun resolveMysqlClient() {
        dockerContainer =
            when {
                dockerPin.isNotEmpty() -> {
                    if (!commandExists("docker")) {
                        throw RuntimeException(
                            "SAFEDB_TEST_MYSQL_DOCKER set but 'docker' not found in PATH"
                        )
                    }
                    if (runCommand(listOf("docker", "inspect", dockerPin)).exitCode != 0) {
                        val running =
                            runCommand(
                                    listOf(
                                        "docker",
                                        "ps",
                                        "--format",
                                        "    {{.Names}} ({{.Image}})",
                                    )
                                )
                                .stdout
                        throw RuntimeException(
                            "docker container '$dockerPin' not found\n  running containers:\n$running"
                        )
                    }
                    dockerPin
                }
                commandExists("mysql") -> ""
                commandExists("docker") -> {
                    val matches = runningMysqlContainers()
                    when (matches.size) {
                        0 ->
                            throw RuntimeException(
                                "no 'mysql' client on PATH and no running mysql/mariadb container\n" +
                                    "  install a client (brew install mysql-client / apt install default-mysql-client)\n" +
                                    "  or start a MySQL container, or set SAFEDB_TEST_MYSQL_DOCKER=<name>"
                            )
                        1 -> matches.single()
                        else ->
                            throw RuntimeException(
                                "multiple mysql/mariadb containers running; pin one:\n" +
                                    matches.joinToString("\n") { "    $it" } +
                                    "\n  hint: SAFEDB_TEST_MYSQL_DOCKER=<name>"
                            )
                    }
                }
                else ->
                    throw RuntimeException(
                        "no 'mysql' client in PATH and 'docker' not found either\n" +
                            "  install with: brew install mysql-client (macOS) or apt install default-mysql-client (Debian/Ubuntu)"
                    )
            }
    }

    private fun resolveDockerPassword() {
        if (dockerContainer.isNotEmpty() && password.isEmpty() && user == "root") {
            password = dockerEnvVar(dockerContainer, "MYSQL_ROOT_PASSWORD")
        }

        if (
            dockerContainer.isEmpty() &&
                password.isEmpty() &&
                user == "root" &&
                isLocalHost(host) &&
                commandExists("docker")
        ) {
            val hostDocker =
                runCommand(
                        listOf(
                            "docker",
                            "ps",
                            "--filter",
                            "status=running",
                            "--filter",
                            "publish=$port",
                            "--format",
                            "{{.Names}}\t{{.Image}}",
                        )
                    )
                    .stdout
                    .lineSequence()
                    .mapNotNull { parseMysqlContainerLine(it) }
                    .firstOrNull()
            if (hostDocker != null) {
                password = dockerEnvVar(hostDocker, "MYSQL_ROOT_PASSWORD")
            }
        }
    }

    private fun createDefaultsFileIfNeeded() {
        if (dockerContainer.isNotEmpty()) return
        defaultsFile =
            Files.createTempFile("safedb-seed.", ".cnf").also { file ->
                file.writeText(
                    """
                [client]
                host=$host
                port=$port
                user=$user
                password=$password
                protocol=TCP
                """
                        .trimIndent() + "\n"
                )
                runCommand(listOf("chmod", "600", file.toString()))
            }
    }

    private fun checkConnection() {
        if (dockerContainer.isNotEmpty()) {
            println(
                "-> using docker container: $dockerContainer  (connecting to 127.0.0.1:3306 as $user)"
            )
        } else {
            println("-> checking connection to $user@$host:$port")
        }
        val probe = mysqlRun("-e", "SELECT VERSION()")
        if (probe.exitCode != 0) {
            if (dockerContainer.isNotEmpty()) {
                throw RuntimeException(
                    "cannot connect to MySQL inside container '$dockerContainer' as $user\n" +
                        "  set SAFEDB_TEST_MYSQL_PASSWORD (or MYSQL_ROOT_PASSWORD on the container)\n" +
                        "  and SAFEDB_TEST_MYSQL_USER if not using root"
                )
            }
            throw RuntimeException(
                "cannot connect to MySQL at $user@$host:$port\n" +
                    "  set SAFEDB_TEST_MYSQL_HOST / PORT / USER / PASSWORD and retry"
            )
        }
        val version = mysqlRun("-N", "-e", "SELECT VERSION()").stdout.trim()
        println("  server version: $version")
    }

    private fun resetStateIfRequested() {
        if (!options.resetState) {
            println("-> keeping safe-db connections and query history (pass --reset-state to wipe)")
            return
        }
        val dataDir = safeDbAppDataDirForStateReset() ?: return
        if (!dataDir.exists()) {
            println("-> no safe-db app data at $dataDir; skipping connection/history reset")
            return
        }
        println("-> resetting safe-db connections and query history ($dataDir)")
        dataDir.resolve("connections.json").writeText("[]\n")
        dataDir.resolve("query_history.json").writeText("[]\n")
        Files.deleteIfExists(dataDir.resolve("query_history.v1.bak"))
    }

    private fun resetDatabaseIfRequested() {
        if (!options.reset) return
        println("-> dropping database '$database' (--reset)")
        mysqlRun("-e", "DROP DATABASE IF EXISTS `$database`").requireSuccess()
    }

    private fun loadFixture() {
        if (options.static) {
            println("-> loading $staticSql into '$database'")
            mysqlRunWithInput(emptyList()) { writer ->
                    staticSql.inputStream().use { input -> input.copyTo(writer) }
                }
                .requireSuccess()
            return
        }

        println("-> generating fixture SQL and loading it into '$database'")
        mysqlRunWithInput(emptyList()) { writer ->
                BufferedWriter(OutputStreamWriter(writer)).use { sql ->
                    MysqlFixtureGenerator(options.generator.copy(database = database), sql)
                        .generate()
                }
            }
            .requireSuccess()
    }

    private fun verifyCounts() {
        println("-> verifying row counts")
        val result =
            mysqlRun(
                database,
                "--skip-column-names",
                "-e",
                """
            SELECT CONCAT('  ', t.table_name, ': ', c.cnt)
            FROM information_schema.tables t
            JOIN (
              SELECT 'categories'    AS table_name, COUNT(*) AS cnt FROM categories    UNION ALL
              SELECT 'products',                COUNT(*)         FROM products       UNION ALL
              SELECT 'customers',               COUNT(*)         FROM customers      UNION ALL
              SELECT 'orders',                  COUNT(*)         FROM orders         UNION ALL
              SELECT 'order_items',             COUNT(*)         FROM order_items    UNION ALL
              SELECT 'inventory_log',           COUNT(*)         FROM inventory_log
            ) c ON c.table_name = t.table_name
            WHERE t.table_schema = '$database'
            ORDER BY t.table_name;
            """
                    .trimIndent(),
            )
        result.requireSuccess()
        print(result.stdout)
    }

    private fun printDone() {
        println("done.")
        if (dockerContainer.isNotEmpty()) {
            println("  connect with: docker exec -it $dockerContainer mysql -u $user $database")
        } else {
            println("  connect with: mysql --defaults-file=${defaultsFile?.absolute()} $database")
        }
    }

    private fun mysqlRun(vararg args: String): CommandResult =
        runCommand(mysqlCommand(args.toList()))

    private fun mysqlRunWithInput(
        args: List<String>,
        writeInput: (java.io.OutputStream) -> Unit,
    ): CommandResult = runCommand(mysqlCommand(args), writeInput)

    private fun mysqlCommand(args: List<String>): List<String> =
        if (dockerContainer.isNotEmpty()) {
            listOf(
                "docker",
                "exec",
                "-i",
                "-e",
                "MYSQL_PWD=$password",
                dockerContainer,
                "mysql",
                "-h",
                "127.0.0.1",
                "-P",
                "3306",
                "-u",
                user,
            ) + args
        } else {
            listOf("mysql", "--defaults-file=${defaultsFile ?: error("missing defaults file")}") +
                args
        }
}
