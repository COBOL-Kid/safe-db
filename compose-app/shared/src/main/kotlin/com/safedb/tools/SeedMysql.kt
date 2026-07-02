package com.safedb.tools

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.writeText
import kotlin.system.exitProcess

private const val DEFAULT_DATABASE = "safedb_test"
private const val DEFAULT_CATEGORIES = 12
private const val DEFAULT_PRODUCTS = 500
private const val DEFAULT_CUSTOMERS = 10_000
private const val DEFAULT_ORDERS = 50_000
private const val DEFAULT_SEED = 42
private const val DEFAULT_BATCH_SIZE = 1000

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
    private val repoRoot = Path.of("").toAbsolutePath().parent
        ?: throw RuntimeException("cannot resolve repository root from compose-app")
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
        dockerContainer = when {
            dockerPin.isNotEmpty() -> {
                if (!commandExists("docker")) {
                    throw RuntimeException("SAFEDB_TEST_MYSQL_DOCKER set but 'docker' not found in PATH")
                }
                if (runCommand(listOf("docker", "inspect", dockerPin)).exitCode != 0) {
                    val running = runCommand(listOf("docker", "ps", "--format", "    {{.Names}} ({{.Image}})")).stdout
                    throw RuntimeException("docker container '$dockerPin' not found\n  running containers:\n$running")
                }
                dockerPin
            }
            commandExists("mysql") -> ""
            commandExists("docker") -> {
                val matches = runningMysqlContainers()
                when (matches.size) {
                    0 -> throw RuntimeException(
                        "no 'mysql' client on PATH and no running mysql/mariadb container\n" +
                            "  install a client (brew install mysql-client / apt install default-mysql-client)\n" +
                            "  or start a MySQL container, or set SAFEDB_TEST_MYSQL_DOCKER=<name>",
                    )
                    1 -> matches.single()
                    else -> throw RuntimeException(
                        "multiple mysql/mariadb containers running; pin one:\n" +
                            matches.joinToString("\n") { "    $it" } +
                            "\n  hint: SAFEDB_TEST_MYSQL_DOCKER=<name>",
                    )
                }
            }
            else -> throw RuntimeException(
                "no 'mysql' client in PATH and 'docker' not found either\n" +
                    "  install with: brew install mysql-client (macOS) or apt install default-mysql-client (Debian/Ubuntu)",
            )
        }
    }

    private fun resolveDockerPassword() {
        if (dockerContainer.isNotEmpty() && password.isEmpty() && user == "root") {
            password = dockerEnvVar(dockerContainer, "MYSQL_ROOT_PASSWORD")
        }

        if (dockerContainer.isEmpty() && password.isEmpty() && user == "root" && isLocalHost(host) && commandExists("docker")) {
            val hostDocker = runCommand(
                listOf(
                    "docker",
                    "ps",
                    "--filter",
                    "status=running",
                    "--filter",
                    "publish=$port",
                    "--format",
                    "{{.Names}}\t{{.Image}}",
                ),
            ).stdout
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
        defaultsFile = Files.createTempFile("safedb-seed.", ".cnf").also { file ->
            file.writeText(
                """
                [client]
                host=$host
                port=$port
                user=$user
                password=$password
                protocol=TCP
                """.trimIndent() + "\n",
            )
            runCommand(listOf("chmod", "600", file.toString()))
        }
    }

    private fun checkConnection() {
        if (dockerContainer.isNotEmpty()) {
            println("-> using docker container: $dockerContainer  (connecting to 127.0.0.1:3306 as $user)")
        } else {
            println("-> checking connection to $user@$host:$port")
        }
        val probe = mysqlRun("-e", "SELECT VERSION()")
        if (probe.exitCode != 0) {
            if (dockerContainer.isNotEmpty()) {
                throw RuntimeException(
                    "cannot connect to MySQL inside container '$dockerContainer' as $user\n" +
                        "  set SAFEDB_TEST_MYSQL_PASSWORD (or MYSQL_ROOT_PASSWORD on the container)\n" +
                        "  and SAFEDB_TEST_MYSQL_USER if not using root",
                )
            }
            throw RuntimeException(
                "cannot connect to MySQL at $user@$host:$port\n" +
                    "  set SAFEDB_TEST_MYSQL_HOST / PORT / USER / PASSWORD and retry",
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
        val dataDir = safeDbAppDataDir()
        if (dataDir == null) {
            println("-> skipping safe-db app state reset (unknown platform)")
            return
        }
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
            }.requireSuccess()
            return
        }

        println("-> generating fixture SQL and loading it into '$database'")
        mysqlRunWithInput(emptyList()) { writer ->
            BufferedWriter(OutputStreamWriter(writer)).use { sql ->
                MysqlFixtureGenerator(options.generator.copy(database = database), sql).generate()
            }
        }.requireSuccess()
    }

    private fun verifyCounts() {
        println("-> verifying row counts")
        val result = mysqlRun(
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
            """.trimIndent(),
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

    private fun mysqlRun(vararg args: String): CommandResult = runCommand(mysqlCommand(args.toList()))

    private fun mysqlRunWithInput(args: List<String>, writeInput: (java.io.OutputStream) -> Unit): CommandResult =
        runCommand(mysqlCommand(args), writeInput)

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
            listOf("mysql", "--defaults-file=${defaultsFile ?: error("missing defaults file")}") + args
        }
}

private data class SeedOptions(
    val help: Boolean = false,
    val static: Boolean = false,
    val reset: Boolean = false,
    val resetState: Boolean = false,
    val generator: GeneratorOptions = GeneratorOptions(),
)

private data class GeneratorOptions(
    val database: String = DEFAULT_DATABASE,
    val categories: Int = DEFAULT_CATEGORIES,
    val products: Int = DEFAULT_PRODUCTS,
    val customers: Int = DEFAULT_CUSTOMERS,
    val orders: Int = DEFAULT_ORDERS,
    val seed: Int = DEFAULT_SEED,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
)

private fun parseArgs(args: List<String>): SeedOptions {
    var help = false
    var static = false
    var reset = false
    var resetState = false
    var generator = GeneratorOptions()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        fun nextValue(): String {
            if (index + 1 >= args.size) throw UsageError("missing value for $arg")
            index += 1
            return args[index]
        }
        when (arg) {
            "-h", "--help" -> help = true
            "--static" -> static = true
            "--generated" -> static = false
            "--reset" -> reset = true
            "--reset-state" -> resetState = true
            "--orders" -> generator = generator.copy(orders = parsePositiveInt(nextValue(), arg))
            "--customers" -> generator = generator.copy(customers = parsePositiveInt(nextValue(), arg))
            "--products" -> generator = generator.copy(products = parsePositiveInt(nextValue(), arg))
            "--categories" -> generator = generator.copy(categories = parsePositiveInt(nextValue(), arg))
            "--seed" -> generator = generator.copy(seed = parsePositiveInt(nextValue(), arg))
            "--batch-size" -> generator = generator.copy(batchSize = parsePositiveInt(nextValue(), arg))
            "--" -> {
                if (index + 1 < args.size) {
                    val nested = parseArgs(args.drop(index + 1))
                    static = nested.static
                    reset = reset || nested.reset
                    resetState = resetState || nested.resetState
                    generator = nested.generator
                }
                index = args.size
                continue
            }
            else -> {
                if (arg.startsWith("-")) throw UsageError("unknown argument: $arg")
                throw UsageError("unexpected positional argument: $arg")
            }
        }
        index += 1
    }
    if (generator.products < generator.categories) {
        throw UsageError("--products must be greater than or equal to --categories")
    }
    return SeedOptions(help, static, reset, resetState, generator)
}

private fun parsePositiveInt(raw: String, label: String): Int {
    if (!raw.all { it in '0'..'9' }) throw UsageError("$label must be a positive integer")
    val value = raw.toLongOrNull() ?: throw UsageError("$label must be a positive safe integer")
    if (value < 1 || value > Int.MAX_VALUE) throw UsageError("$label must be a positive safe integer")
    return value.toInt()
}

private fun printUsage(out: java.io.PrintStream) {
    out.print(
        """
        scripts/seed_mysql.sh - populate the safe-db MySQL test database.

        Usage:
          scripts/seed_mysql.sh
          scripts/seed_mysql.sh --orders 20000 --customers 5000
          scripts/seed_mysql.sh --static
          scripts/seed_mysql.sh --reset
          scripts/seed_mysql.sh --reset-state
          cd compose-app && ./gradlew seedMysql -PseedMysqlArgs="--orders 20000 --customers 5000"

        Options:
          --static          Load the bundled testdata_mysql.sql fixture instead of generated data
          --generated       Accepted for compatibility; generated data is already the default
          --reset           Drop and recreate the target database before loading data
          --reset-state     Wipe safe-db connections.json and query_history.json only
          --orders <n>      Number of generated orders (default: $DEFAULT_ORDERS)
          --customers <n>   Number of generated customers (default: $DEFAULT_CUSTOMERS)
          --products <n>    Number of generated products (default: $DEFAULT_PRODUCTS)
          --categories <n>  Number of generated categories (default: $DEFAULT_CATEGORIES)
          --seed <n>        Deterministic random seed (default: $DEFAULT_SEED)
          --batch-size <n>  Rows per INSERT statement (default: $DEFAULT_BATCH_SIZE)
          -h, --help        Show this help

        Env vars:
          SAFEDB_TEST_MYSQL_HOST      localhost
          SAFEDB_TEST_MYSQL_PORT      3306
          SAFEDB_TEST_MYSQL_USER      root
          SAFEDB_TEST_MYSQL_PASSWORD  empty
          SAFEDB_TEST_MYSQL_DATABASE  safedb_test
          SAFEDB_TEST_MYSQL_DOCKER    pin a container name

        """.trimIndent(),
    )
}

private class MysqlFixtureGenerator(
    private val options: GeneratorOptions,
    private val out: BufferedWriter,
) {
    private val random = SeededRandom(options.seed)

    fun generate() {
        emitSchema()
        emitBatched("categories", listOf("id", "name", "description"), makeCategories())
        emitBatched(
            "products",
            listOf(
                "id",
                "category_id",
                "sku",
                "name",
                "description",
                "price",
                "cost",
                "stock_qty",
                "is_active",
                "weight_kg",
            ),
            makeProducts(),
        )
        emitBatched(
            "customers",
            listOf(
                "id",
                "first_name",
                "last_name",
                "email",
                "phone",
                "address_line1",
                "city",
                "state_province",
                "postal_code",
                "country",
                "loyalty_points",
                "is_vip",
                "signed_up_at",
            ),
            makeCustomers(),
        )
        emitOrders()
        out.flush()
    }

    private fun emitSchema() {
        write("-- Generated safe-db MySQL fixture")
        write("CREATE DATABASE IF NOT EXISTS `${options.database}`;")
        write("USE `${options.database}`;")
        write()
        write("SET FOREIGN_KEY_CHECKS = 0;")
        write("DROP TABLE IF EXISTS order_items;")
        write("DROP TABLE IF EXISTS inventory_log;")
        write("DROP TABLE IF EXISTS orders;")
        write("DROP TABLE IF EXISTS products;")
        write("DROP TABLE IF EXISTS customers;")
        write("DROP TABLE IF EXISTS categories;")
        write("SET FOREIGN_KEY_CHECKS = 1;")
        write()
        write(
            """
            CREATE TABLE categories (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                name        VARCHAR(100)  NOT NULL,
                description TEXT,
                created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_categories_name (name)
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
        write(
            """
            CREATE TABLE products (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                category_id INT            NOT NULL,
                sku         VARCHAR(50)    NOT NULL UNIQUE,
                name        VARCHAR(200)   NOT NULL,
                description TEXT,
                price       DECIMAL(10,2)  NOT NULL,
                cost        DECIMAL(10,2)  NOT NULL,
                stock_qty   INT            NOT NULL DEFAULT 0,
                is_active   TINYINT(1)     NOT NULL DEFAULT 1,
                weight_kg   FLOAT          NULL,
                created_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP      NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
                INDEX idx_products_category (category_id),
                INDEX idx_products_active  (is_active),
                INDEX idx_products_price   (price),
                CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id)
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
        write(
            """
            CREATE TABLE customers (
                id              INT AUTO_INCREMENT PRIMARY KEY,
                first_name      VARCHAR(100)  NOT NULL,
                last_name       VARCHAR(100)  NOT NULL,
                email           VARCHAR(255)  NOT NULL UNIQUE,
                phone           VARCHAR(30)   NULL,
                address_line1   VARCHAR(255)  NULL,
                city            VARCHAR(100)  NULL DEFAULT 'Unknown',
                state_province  VARCHAR(100)  NULL,
                postal_code     VARCHAR(20)   NULL,
                country         VARCHAR(100)  NOT NULL DEFAULT 'US',
                loyalty_points  INT           NOT NULL DEFAULT 0,
                is_vip          TINYINT(1)    NOT NULL DEFAULT 0,
                signed_up_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_customers_name  (last_name, first_name),
                INDEX idx_customers_city  (city),
                INDEX idx_customers_vip   (is_vip)
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
        write(
            """
            CREATE TABLE orders (
                id              INT AUTO_INCREMENT PRIMARY KEY,
                customer_id     INT            NOT NULL,
                order_date      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
                status          VARCHAR(30)    NOT NULL DEFAULT 'pending',
                subtotal        DECIMAL(12,2)  NOT NULL,
                tax             DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
                shipping_cost   DECIMAL(12,2)  NOT NULL DEFAULT 0.00,
                total           DECIMAL(12,2)  NOT NULL,
                shipping_city   VARCHAR(100)   NULL,
                notes           TEXT           NULL,
                INDEX idx_orders_customer  (customer_id),
                INDEX idx_orders_status    (status),
                INDEX idx_orders_date      (order_date),
                CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
        write(
            """
            CREATE TABLE order_items (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                order_id    INT            NOT NULL,
                product_id  INT            NOT NULL,
                quantity    INT            NOT NULL,
                unit_price  DECIMAL(10,2)  NOT NULL,
                line_total  DECIMAL(12,2)  NOT NULL,
                INDEX idx_items_order   (order_id),
                INDEX idx_items_product (product_id),
                CONSTRAINT fk_items_order   FOREIGN KEY (order_id)   REFERENCES orders(id),
                CONSTRAINT fk_items_product FOREIGN KEY (product_id) REFERENCES products(id)
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
        write(
            """
            CREATE TABLE inventory_log (
                id          INT AUTO_INCREMENT PRIMARY KEY,
                product_id  INT            NOT NULL,
                change_qty  INT            NOT NULL,
                reason      VARCHAR(100)   NULL,
                logged_by   VARCHAR(100)   NULL,
                logged_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB;
            """.trimIndent(),
        )
        write()
    }

    private fun makeCategories(): Sequence<List<String>> {
        val names = listOf(
            "Electronics",
            "Books",
            "Clothing",
            "Home & Garden",
            "Sports",
            "Office",
            "Beauty",
            "Toys",
            "Automotive",
            "Grocery",
            "Pet Supplies",
            "Music",
        )
        return (1..options.categories).asSequence().map { id ->
            val name = names[(id - 1) % names.size]
            listOf(
                id.toString(),
                sqlString(name),
                sqlString("$name products for generated reporting and query-builder testing"),
            )
        }
    }

    private fun makeProducts(): Sequence<List<String>> {
        val adjectives = listOf("Compact", "Premium", "Classic", "Eco", "Wireless", "Smart", "Travel", "Pro")
        val nouns = listOf("Kit", "Stand", "Pack", "Device", "Guide", "Jacket", "Lamp", "Bottle", "Mat", "Hub")
        return (1..options.products).asSequence().map { id ->
            val categoryId = ((id - 1) % options.categories) + 1
            val basePrice = random.float(8.0, 500.0)
            val cost = maxOf(1.0, basePrice * random.float(0.35, 0.72))
            val sku = "GEN-${categoryId.toString().padStart(2, '0')}-${id.toString().padStart(6, '0')}"
            val name = "${random.pick(adjectives)} ${random.pick(nouns)} $id"
            listOf(
                id.toString(),
                categoryId.toString(),
                sqlString(sku),
                sqlString(name),
                sqlString("Generated ${name.lowercase()} used for local reporting scenarios"),
                sqlNumber(basePrice),
                sqlNumber(cost),
                random.int(0, 1200).toString(),
                if (random.chance(0.94)) "1" else "0",
                if (random.chance(0.08)) "NULL" else random.float(0.05, 30.0, 3).toString(),
            )
        }
    }

    private fun makeCustomers(): Sequence<List<String>> {
        val firstNames = listOf("Alex", "Sam", "Jordan", "Taylor", "Morgan", "Riley", "Casey", "Jamie", "Avery", "Quinn")
        val lastNames = listOf("Johnson", "Smith", "Williams", "Brown", "Davis", "Miller", "Wilson", "Moore", "Taylor", "Anderson")
        val cities = listOf("Portland", "Seattle", "Austin", "Denver", "Chicago", "Miami", "Boston", "New York", "Phoenix", "London")
        val states = listOf("OR", "WA", "TX", "CO", "IL", "FL", "MA", "NY", "AZ", null)
        return (1..options.customers).asSequence().map { id ->
            val cityIndex = random.int(0, cities.lastIndex)
            val signedUpDaysAgo = random.int(30, 1600)
            listOf(
                id.toString(),
                sqlString(random.pick(firstNames)),
                sqlString(random.pick(lastNames)),
                sqlString("customer${id.toString().padStart(6, '0')}@example.test"),
                if (random.chance(0.18)) "NULL" else sqlString("+1-555-${(1000 + id).toString().takeLast(4)}"),
                if (random.chance(0.08)) "NULL" else sqlString("${random.int(100, 9999)} Generated Ave"),
                sqlString(cities[cityIndex]),
                sqlString(states[cityIndex]),
                sqlString((90000 + random.int(0, 8999)).toString()),
                sqlString(if (cityIndex == 9) "UK" else "US"),
                random.int(0, 5000).toString(),
                if (random.chance(0.12)) "1" else "0",
                sqlString(timestamp(signedUpDaysAgo, random.int(0, 86400))),
            )
        }
    }

    private fun emitOrders() {
        val statuses = listOf("pending", "shipped", "delivered", "delivered", "delivered", "cancelled")
        val cities = listOf("Portland", "Seattle", "Austin", "Denver", "Chicago", "Miami", "Boston", "New York", "Phoenix", "London")
        val orderRows = mutableListOf<List<String>>()
        val itemRows = mutableListOf<List<String>>()
        val inventoryRows = mutableListOf<List<String>>()
        var itemId = 1
        var inventoryId = 1

        fun flush(force: Boolean = false) {
            if (!force && orderRows.size < options.batchSize) return
            emitInsert(
                "orders",
                listOf("id", "customer_id", "order_date", "status", "subtotal", "tax", "shipping_cost", "total", "shipping_city", "notes"),
                orderRows,
            )
            orderRows.clear()
            emitInsert("order_items", listOf("id", "order_id", "product_id", "quantity", "unit_price", "line_total"), itemRows)
            itemRows.clear()
            emitInsert("inventory_log", listOf("id", "product_id", "change_qty", "reason", "logged_by", "logged_at"), inventoryRows)
            inventoryRows.clear()
        }

        for (orderId in 1..options.orders) {
            val itemCount = random.int(1, 5)
            val orderDate = timestamp(random.int(0, 540), random.int(0, 86400))
            var subtotal = 0.0
            repeat(itemCount) {
                val productId = random.int(1, options.products)
                val quantity = random.int(1, 4)
                val unitPrice = random.float(8.0, 500.0)
                val lineTotal = round(unitPrice * quantity, 2)
                subtotal += lineTotal
                itemRows.add(listOf(itemId.toString(), orderId.toString(), productId.toString(), quantity.toString(), sqlNumber(unitPrice), sqlNumber(lineTotal)))
                inventoryRows.add(
                    listOf(
                        inventoryId.toString(),
                        productId.toString(),
                        "-$quantity",
                        sqlString("generated order #$orderId"),
                        sqlString(random.pick(listOf("warehouse", "system", "batch-loader"))),
                        sqlString(orderDate),
                    ),
                )
                itemId += 1
                inventoryId += 1
            }
            subtotal = round(subtotal, 2)
            val tax = round(subtotal * random.float(0.04, 0.095), 2)
            val shipping = if (subtotal > 150 || random.chance(0.2)) 0.0 else random.float(3.99, 18.99)
            val total = round(subtotal + tax + shipping, 2)
            orderRows.add(
                listOf(
                    orderId.toString(),
                    random.int(1, options.customers).toString(),
                    sqlString(orderDate),
                    sqlString(random.pick(statuses)),
                    sqlNumber(subtotal),
                    sqlNumber(tax),
                    sqlNumber(shipping),
                    sqlNumber(total),
                    sqlString(random.pick(cities)),
                    if (random.chance(0.08)) sqlString("Generated reporting fixture order") else "NULL",
                ),
            )
            flush()
        }
        flush(force = true)
    }

    private fun emitBatched(table: String, columns: List<String>, rows: Sequence<List<String>>) {
        val batch = mutableListOf<List<String>>()
        for (row in rows) {
            batch.add(row)
            if (batch.size >= options.batchSize) {
                emitInsert(table, columns, batch)
                batch.clear()
            }
        }
        emitInsert(table, columns, batch)
    }

    private fun emitInsert(table: String, columns: List<String>, rows: List<List<String>>) {
        if (rows.isEmpty()) return
        write("INSERT INTO $table (${columns.joinToString(", ")}) VALUES")
        write(rows.joinToString(",\n", postfix = ";") { row -> "(${row.joinToString(", ")})" })
        write()
    }

    private fun write(line: String = "") {
        out.write(line)
        out.newLine()
    }
}

private class SeededRandom(seed: Int) {
    private var state = seed

    fun int(min: Int, max: Int): Int = (nextDouble() * (max - min + 1)).toInt() + min

    fun float(min: Double, max: Double, decimals: Int = 2): Double = round(nextDouble() * (max - min) + min, decimals)

    fun <T> pick(values: List<T>): T = values[int(0, values.lastIndex)]

    fun chance(probability: Double): Boolean = nextDouble() < probability

    private fun nextDouble(): Double {
        state += 0x6d2b79f5
        var t = state
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        return ((t xor (t ushr 14)).toUInt().toDouble()) / 4294967296.0
    }
}

private fun sqlString(value: String?): String =
    value?.let { "'${it.replace("\\", "\\\\").replace("'", "''")}'" } ?: "NULL"

private fun sqlNumber(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun round(value: Double, decimals: Int): Double = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

private fun timestamp(daysAgo: Int, secondsOffset: Int): String =
    LocalDateTime.of(2026, 1, 31, 12, 0, 0)
        .atOffset(ZoneOffset.UTC)
        .minusDays(daysAgo.toLong())
        .plusSeconds(secondsOffset.toLong())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

private fun safeDbAppDataDir(): Path? {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    return when {
        os.contains("mac") || os.contains("darwin") -> Path.of(home, "Library", "Application Support", "com.safedb.app")
        os.contains("win") -> System.getenv("APPDATA")?.let { Path.of(it, "com.safedb.app") }
        os.contains("linux") -> Path.of(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "com.safedb.app")
        else -> null
    }
}

private fun runningMysqlContainers(): List<String> =
    runCommand(listOf("docker", "ps", "--filter", "status=running", "--format", "{{.Names}}\t{{.Image}}"))
        .stdout
        .lineSequence()
        .mapNotNull { parseMysqlContainerLine(it) }
        .toList()

private fun parseMysqlContainerLine(line: String): String? {
    val parts = line.split('\t')
    if (parts.size < 2) return null
    val image = parts[1].lowercase()
    return parts[0].takeIf { image.contains("mysql") || image.contains("mariadb") }
}

private fun dockerEnvVar(container: String, key: String): String {
    val result = runCommand(listOf("docker", "inspect", container, "--format", "{{range .Config.Env}}{{println .}}{{end}}"))
    if (result.exitCode != 0) return ""
    return result.stdout.lineSequence()
        .firstOrNull { it.substringBefore('=') == key }
        ?.substringAfter('=', "")
        .orEmpty()
}

private fun sanitizeIdentifier(value: String, label: String) {
    if (!Regex("""^[A-Za-z0-9_.-]+$""").matches(value)) {
        throw RuntimeException("invalid $label (allowed: letters, digits, ., _, -)")
    }
}

private fun isLocalHost(value: String): Boolean = value == "localhost" || value == "127.0.0.1"

private fun commandExists(command: String): Boolean =
    runCommand(listOf("sh", "-c", "command -v ${shellQuote(command)} >/dev/null 2>&1")).exitCode == 0

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

private data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    fun requireSuccess() {
        if (exitCode != 0) {
            throw RuntimeException(stderr.ifBlank { "command failed with exit code $exitCode" })
        }
    }
}

private fun runCommand(command: List<String>, writeInput: ((java.io.OutputStream) -> Unit)? = null): CommandResult {
    val process = ProcessBuilder(command).start()
    if (writeInput != null) {
        process.outputStream.use(writeInput)
    } else {
        process.outputStream.close()
    }
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return CommandResult(exitCode, stdout, stderr.trimEnd())
}

private class UsageError(message: String) : RuntimeException(message)
