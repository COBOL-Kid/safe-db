package com.safedb.tools

import java.io.BufferedWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class MysqlFixtureGenerator(
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

internal fun sqlString(value: String?): String =
    value?.let { "'${it.replace("\\", "\\\\").replace("'", "''")}'" } ?: "NULL"

private fun sqlNumber(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

private fun round(value: Double, decimals: Int): Double = BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).toDouble()

private fun timestamp(daysAgo: Int, secondsOffset: Int): String =
    LocalDateTime.of(2026, 1, 31, 12, 0, 0)
        .atOffset(ZoneOffset.UTC)
        .minusDays(daysAgo.toLong())
        .plusSeconds(secondsOffset.toLong())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
