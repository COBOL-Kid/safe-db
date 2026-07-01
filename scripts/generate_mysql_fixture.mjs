#!/usr/bin/env node

const DEFAULTS = {
	database: 'safedb_test',
	categories: 12,
	products: 500,
	customers: 10_000,
	orders: 50_000,
	seed: 42,
	batchSize: 1000,
	validateOnly: false
};

function usage(exitCode = 0) {
	const out = exitCode === 0 ? process.stdout : process.stderr;
	out.write(`generate_mysql_fixture.mjs — stream generated safe-db MySQL fixture SQL

Usage:
  node scripts/generate_mysql_fixture.mjs [options] > generated.sql
  node scripts/generate_mysql_fixture.mjs --orders 20000 | mysql safedb_test

Options:
  --database <name>    Database name to create/use (default: ${DEFAULTS.database})
  --categories <n>    Number of categories (default: ${DEFAULTS.categories})
  --products <n>      Number of products (default: ${DEFAULTS.products})
  --customers <n>     Number of customers (default: ${DEFAULTS.customers})
  --orders <n>        Number of orders (default: ${DEFAULTS.orders})
  --seed <n>          Deterministic random seed (default: ${DEFAULTS.seed})
  --batch-size <n>    Rows per INSERT statement (default: ${DEFAULTS.batchSize})
  --validate-only     Validate options without writing SQL
  -h, --help          Show this help
`);
	process.exit(exitCode);
}

function parsePositiveInt(raw, label) {
	if (!/^\d+$/.test(String(raw))) {
		throw new Error(`${label} must be a positive integer`);
	}
	const value = Number(raw);
	if (!Number.isSafeInteger(value) || value < 1) {
		throw new Error(`${label} must be a positive safe integer`);
	}
	return value;
}

function parseArgs(argv) {
	const options = { ...DEFAULTS };
	for (let i = 0; i < argv.length; i += 1) {
		const arg = argv[i];
		if (arg === '-h' || arg === '--help') usage(0);
		const next = argv[i + 1];
		switch (arg) {
			case '--database':
				if (!next) throw new Error('--database requires a value');
				options.database = next;
				i += 1;
				break;
			case '--categories':
				options.categories = parsePositiveInt(next, '--categories');
				i += 1;
				break;
			case '--products':
				options.products = parsePositiveInt(next, '--products');
				i += 1;
				break;
			case '--customers':
				options.customers = parsePositiveInt(next, '--customers');
				i += 1;
				break;
			case '--orders':
				options.orders = parsePositiveInt(next, '--orders');
				i += 1;
				break;
			case '--seed':
				options.seed = parsePositiveInt(next, '--seed');
				i += 1;
				break;
			case '--batch-size':
				options.batchSize = parsePositiveInt(next, '--batch-size');
				i += 1;
				break;
			case '--validate-only':
				options.validateOnly = true;
				break;
			default:
				throw new Error(`unknown argument: ${arg}`);
		}
	}

	if (!/^[A-Za-z0-9_.-]+$/.test(options.database)) {
		throw new Error('database must contain only letters, digits, ., _, and -');
	}
	if (options.products < options.categories) {
		throw new Error('--products must be greater than or equal to --categories');
	}
	return options;
}

function mulberry32(seed) {
	let state = seed >>> 0;
	return () => {
		state += 0x6d2b79f5;
		let t = state;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

const random = (() => {
	let rng = Math.random;
	return {
		setSeed(seed) {
			rng = mulberry32(seed);
		},
		int(min, max) {
			return Math.floor(rng() * (max - min + 1)) + min;
		},
		float(min, max, decimals = 2) {
			return Number((rng() * (max - min) + min).toFixed(decimals));
		},
		pick(values) {
			return values[this.int(0, values.length - 1)];
		},
		chance(probability) {
			return rng() < probability;
		}
	};
})();

function sqlString(value) {
	if (value === null || value === undefined) return 'NULL';
	return `'${String(value).replaceAll('\\', '\\\\').replaceAll("'", "''")}'`;
}

function sqlNumber(value) {
	return Number(value).toFixed(2);
}

function timestamp(daysAgo, secondsOffset = 0) {
	const base = new Date(Date.UTC(2026, 0, 31, 12, 0, 0));
	base.setUTCDate(base.getUTCDate() - daysAgo);
	base.setUTCSeconds(base.getUTCSeconds() + secondsOffset);
	return base.toISOString().slice(0, 19).replace('T', ' ');
}

function write(line = '') {
	process.stdout.write(`${line}\n`);
}

function emitInsert(table, columns, rows) {
	if (rows.length === 0) return;
	write(`INSERT INTO ${table} (${columns.join(', ')}) VALUES`);
	write(`${rows.map((row) => `(${row.join(', ')})`).join(',\n')};`);
	write();
}

function emitBatched(table, columns, rows, batchSize) {
	let batch = [];
	for (const row of rows) {
		batch.push(row);
		if (batch.length >= batchSize) {
			emitInsert(table, columns, batch);
			batch = [];
		}
	}
	emitInsert(table, columns, batch);
}

function* makeCategories(count) {
	const names = [
		'Electronics',
		'Books',
		'Clothing',
		'Home & Garden',
		'Sports',
		'Office',
		'Beauty',
		'Toys',
		'Automotive',
		'Grocery',
		'Pet Supplies',
		'Music'
	];
	for (let id = 1; id <= count; id += 1) {
		const name = names[(id - 1) % names.length] ?? `Category ${id}`;
		yield [
			id,
			sqlString(name),
			sqlString(`${name} products for generated reporting and query-builder testing`)
		];
	}
}

function* makeProducts(count, categoryCount) {
	const adjectives = ['Compact', 'Premium', 'Classic', 'Eco', 'Wireless', 'Smart', 'Travel', 'Pro'];
	const nouns = ['Kit', 'Stand', 'Pack', 'Device', 'Guide', 'Jacket', 'Lamp', 'Bottle', 'Mat', 'Hub'];
	for (let id = 1; id <= count; id += 1) {
		const categoryId = ((id - 1) % categoryCount) + 1;
		const basePrice = random.float(8, 500);
		const cost = Math.max(1, basePrice * random.float(0.35, 0.72));
		const sku = `GEN-${String(categoryId).padStart(2, '0')}-${String(id).padStart(6, '0')}`;
		const name = `${random.pick(adjectives)} ${random.pick(nouns)} ${id}`;
		yield [
			id,
			categoryId,
			sqlString(sku),
			sqlString(name),
			sqlString(`Generated ${name.toLowerCase()} used for local reporting scenarios`),
			sqlNumber(basePrice),
			sqlNumber(cost),
			random.int(0, 1200),
			random.chance(0.94) ? 1 : 0,
			random.chance(0.08) ? 'NULL' : random.float(0.05, 30, 3)
		];
	}
}

function* makeCustomers(count) {
	const firstNames = ['Alex', 'Sam', 'Jordan', 'Taylor', 'Morgan', 'Riley', 'Casey', 'Jamie', 'Avery', 'Quinn'];
	const lastNames = ['Johnson', 'Smith', 'Williams', 'Brown', 'Davis', 'Miller', 'Wilson', 'Moore', 'Taylor', 'Anderson'];
	const cities = ['Portland', 'Seattle', 'Austin', 'Denver', 'Chicago', 'Miami', 'Boston', 'New York', 'Phoenix', 'London'];
	const states = ['OR', 'WA', 'TX', 'CO', 'IL', 'FL', 'MA', 'NY', 'AZ', null];
	for (let id = 1; id <= count; id += 1) {
		const cityIndex = random.int(0, cities.length - 1);
		const signedUpDaysAgo = random.int(30, 1600);
		yield [
			id,
			sqlString(random.pick(firstNames)),
			sqlString(random.pick(lastNames)),
			sqlString(`customer${String(id).padStart(6, '0')}@example.test`),
			random.chance(0.18) ? 'NULL' : sqlString(`+1-555-${String(1000 + id).slice(-4)}`),
			random.chance(0.08) ? 'NULL' : sqlString(`${random.int(100, 9999)} Generated Ave`),
			sqlString(cities[cityIndex]),
			sqlString(states[cityIndex]),
			sqlString(String(90000 + random.int(0, 8999))),
			sqlString(cityIndex === 9 ? 'UK' : 'US'),
			random.int(0, 5000),
			random.chance(0.12) ? 1 : 0,
			sqlString(timestamp(signedUpDaysAgo, random.int(0, 86400)))
		];
	}
}

function emitSchema(database) {
	write('-- Generated safe-db MySQL fixture');
	write(`CREATE DATABASE IF NOT EXISTS \`${database}\`;`);
	write(`USE \`${database}\`;`);
	write();
	write('SET FOREIGN_KEY_CHECKS = 0;');
	write('DROP TABLE IF EXISTS order_items;');
	write('DROP TABLE IF EXISTS inventory_log;');
	write('DROP TABLE IF EXISTS orders;');
	write('DROP TABLE IF EXISTS products;');
	write('DROP TABLE IF EXISTS customers;');
	write('DROP TABLE IF EXISTS categories;');
	write('SET FOREIGN_KEY_CHECKS = 1;');
	write();
	write(`CREATE TABLE categories (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description TEXT,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_categories_name (name)
) ENGINE=InnoDB;`);
	write();
	write(`CREATE TABLE products (
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
) ENGINE=InnoDB;`);
	write();
	write(`CREATE TABLE customers (
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
) ENGINE=InnoDB;`);
	write();
	write(`CREATE TABLE orders (
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
) ENGINE=InnoDB;`);
	write();
	write(`CREATE TABLE order_items (
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
) ENGINE=InnoDB;`);
	write();
	write(`CREATE TABLE inventory_log (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    product_id  INT            NOT NULL,
    change_qty  INT            NOT NULL,
    reason      VARCHAR(100)   NULL,
    logged_by   VARCHAR(100)   NULL,
    logged_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;`);
	write();
}

function generate(options) {
	random.setSeed(options.seed);
	emitSchema(options.database);
	emitBatched(
		'categories',
		['id', 'name', 'description'],
		makeCategories(options.categories),
		options.batchSize
	);
	emitBatched(
		'products',
		[
			'id',
			'category_id',
			'sku',
			'name',
			'description',
			'price',
			'cost',
			'stock_qty',
			'is_active',
			'weight_kg'
		],
		makeProducts(options.products, options.categories),
		options.batchSize
	);
	emitBatched(
		'customers',
		[
			'id',
			'first_name',
			'last_name',
			'email',
			'phone',
			'address_line1',
			'city',
			'state_province',
			'postal_code',
			'country',
			'loyalty_points',
			'is_vip',
			'signed_up_at'
		],
		makeCustomers(options.customers),
		options.batchSize
	);

	let orderRows = [];
	let itemRows = [];
	let inventoryRows = [];
	let itemId = 1;
	let inventoryId = 1;
	const statuses = ['pending', 'shipped', 'delivered', 'delivered', 'delivered', 'cancelled'];
	const cities = ['Portland', 'Seattle', 'Austin', 'Denver', 'Chicago', 'Miami', 'Boston', 'New York', 'Phoenix', 'London'];

	function flushOrderBatches(force = false) {
		if (!force && orderRows.length < options.batchSize) return;
		emitInsert(
			'orders',
			[
				'id',
				'customer_id',
				'order_date',
				'status',
				'subtotal',
				'tax',
				'shipping_cost',
				'total',
				'shipping_city',
				'notes'
			],
			orderRows
		);
		orderRows = [];
		emitInsert(
			'order_items',
			['id', 'order_id', 'product_id', 'quantity', 'unit_price', 'line_total'],
			itemRows
		);
		itemRows = [];
		emitInsert(
			'inventory_log',
			['id', 'product_id', 'change_qty', 'reason', 'logged_by', 'logged_at'],
			inventoryRows
		);
		inventoryRows = [];
	}

	for (let orderId = 1; orderId <= options.orders; orderId += 1) {
		const itemCount = random.int(1, 5);
		const orderDate = timestamp(random.int(0, 540), random.int(0, 86400));
		let subtotal = 0;
		for (let i = 0; i < itemCount; i += 1) {
			const productId = random.int(1, options.products);
			const quantity = random.int(1, 4);
			const unitPrice = random.float(8, 500);
			const lineTotal = Number((unitPrice * quantity).toFixed(2));
			subtotal += lineTotal;
			itemRows.push([
				itemId,
				orderId,
				productId,
				quantity,
				sqlNumber(unitPrice),
				sqlNumber(lineTotal)
			]);
			inventoryRows.push([
				inventoryId,
				productId,
				-quantity,
				sqlString(`generated order #${orderId}`),
				sqlString(random.pick(['warehouse', 'system', 'batch-loader'])),
				sqlString(orderDate)
			]);
			itemId += 1;
			inventoryId += 1;
		}
		subtotal = Number(subtotal.toFixed(2));
		const tax = Number((subtotal * random.float(0.04, 0.095)).toFixed(2));
		const shipping = subtotal > 150 || random.chance(0.2) ? 0 : random.float(3.99, 18.99);
		const total = Number((subtotal + tax + shipping).toFixed(2));
		orderRows.push([
			orderId,
			random.int(1, options.customers),
			sqlString(orderDate),
			sqlString(random.pick(statuses)),
			sqlNumber(subtotal),
			sqlNumber(tax),
			sqlNumber(shipping),
			sqlNumber(total),
			sqlString(random.pick(cities)),
			random.chance(0.08) ? sqlString('Generated reporting fixture order') : 'NULL'
		]);
		flushOrderBatches(false);
	}
	flushOrderBatches(true);
}

try {
	const options = parseArgs(process.argv.slice(2));
	if (options.validateOnly) {
		process.exit(0);
	}
	generate(options);
} catch (error) {
	process.stderr.write(`error: ${error.message}\n`);
	usage(1);
}
