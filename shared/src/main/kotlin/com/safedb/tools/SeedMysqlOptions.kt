package com.safedb.tools

internal const val DEFAULT_DATABASE = "safedb_test"
private const val DEFAULT_CATEGORIES = 12
private const val DEFAULT_PRODUCTS = 500
private const val DEFAULT_CUSTOMERS = 10_000
private const val DEFAULT_ORDERS = 50_000
private const val DEFAULT_SEED = 42
private const val DEFAULT_BATCH_SIZE = 1000

internal data class SeedOptions(
    val help: Boolean = false,
    val static: Boolean = false,
    val reset: Boolean = false,
    val resetState: Boolean = false,
    val generator: GeneratorOptions = GeneratorOptions(),
)

internal data class GeneratorOptions(
    val database: String = DEFAULT_DATABASE,
    val categories: Int = DEFAULT_CATEGORIES,
    val products: Int = DEFAULT_PRODUCTS,
    val customers: Int = DEFAULT_CUSTOMERS,
    val orders: Int = DEFAULT_ORDERS,
    val seed: Int = DEFAULT_SEED,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
)

internal fun parseArgs(args: List<String>): SeedOptions {
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

internal fun printUsage(out: java.io.PrintStream) {
    out.print(
        """
        scripts/seed_mysql.sh - populate the safe-db MySQL test database.

        Usage:
          scripts/seed_mysql.sh
          scripts/seed_mysql.sh --orders 20000 --customers 5000
          scripts/seed_mysql.sh --static
          scripts/seed_mysql.sh --reset
          scripts/seed_mysql.sh --reset-state
          ./gradlew seedMysql -PseedMysqlArgs="--orders 20000 --customers 5000"

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
internal class UsageError(message: String) : RuntimeException(message)
