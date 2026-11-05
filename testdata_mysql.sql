-- safe-db MySQL Test Schema & Seed Data
-- Usage: mysql -h 127.0.0.1 -P 3306 -u root -p < testdata_mysql.sql
-- Shop tables stay small. Bulk tables are generated (digit cross-joins) in safedb_lab:
--   scan_events  1,000,000  Large size class, full-scan timeout, High on uncapped scan
--   wide_rows       50,000  Medium, 24 mixed-type columns
--   payload_rows    20,000  ~2 KiB text so a column select approaches 10 MiB
--   event_notes     10,000  unicode/nulls (InnoDB auto-indexes FKs)

CREATE DATABASE IF NOT EXISTS safedb_test;
CREATE DATABASE IF NOT EXISTS safedb_lab;
CREATE USER IF NOT EXISTS 'safedb'@'%' IDENTIFIED BY 'safedb';
GRANT ALL PRIVILEGES ON safedb_lab.* TO 'safedb'@'%';
FLUSH PRIVILEGES;
USE safedb_test;

-- Drop fixture tables so reruns are idempotent (child tables first).
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS safedb_lab.event_notes;
DROP TABLE IF EXISTS safedb_lab.payload_rows;
DROP TABLE IF EXISTS safedb_lab.wide_rows;
DROP TABLE IF EXISTS safedb_lab.scan_events;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS inventory_log;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS categories;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE categories (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    description TEXT,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_categories_name (name)
) ENGINE=InnoDB;

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

-- Table with diverse types but NO foreign keys (for testing non-indexed joins)
CREATE TABLE inventory_log (
    id          INT AUTO_INCREMENT PRIMARY KEY,
    product_id  INT            NOT NULL,
    change_qty  INT            NOT NULL,
    reason      VARCHAR(100)   NULL,
    logged_by   VARCHAR(100)   NULL,
    logged_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
    -- Deliberately no index on product_id — tests non-indexed join rejection
) ENGINE=InnoDB;

-- ---------------------------------------------------------------------------
-- Seed Data
-- ---------------------------------------------------------------------------

INSERT INTO categories (id, name, description) VALUES
(1, 'Electronics',  'Phones, laptops, tablets, and accessories'),
(2, 'Books',         'Fiction, non-fiction, and technical books'),
(3, 'Clothing',      'Apparel, shoes, and accessories'),
(4, 'Home & Garden', 'Furniture, decor, and gardening supplies'),
(5, 'Sports',        'Athletic equipment, apparel, and outdoor gear');

INSERT INTO products (id, category_id, sku, name, description, price, cost, stock_qty, is_active, weight_kg) VALUES
(1,  1, 'ELEC-001', 'Wireless Mouse',        'Ergonomic wireless mouse with USB-C charging',           29.99, 12.50, 250,  1, 0.12),
(2,  1, 'ELEC-002', 'Mechanical Keyboard',   'RGB mechanical keyboard, Cherry MX Brown switches',      89.99, 45.00, 120,  1, 0.98),
(3,  1, 'ELEC-003', '27" 4K Monitor',        'IPS 4K monitor, 60Hz, HDR10 support',                    449.99, 280.00, 45,  1, 5.80),
(4,  1, 'ELEC-004', 'USB-C Hub',             '7-in-1 USB-C hub with HDMI and SD card reader',          34.99, 15.00, 400,  1, 0.08),
(5,  1, 'ELEC-005', 'Noise Cancelling Buds', 'True wireless earbuds with ANC',                         129.99, 60.00, 85,   1, 0.05),
(6,  1, 'ELEC-006', 'Laptop Stand',          'Adjustable aluminum laptop stand',                       49.99, 18.00, 200,  1, 1.20),
(7,  2, 'BOOK-001', 'The Rust Programming Language', 'Official Rust book, 2nd edition',                 39.99, 20.00, 150,  1, 0.65),
(8,  2, 'BOOK-002', 'Database Internals',     'Deep dive into storage engines and distributed systems', 49.99, 25.00, 80,   1, 0.78),
(9,  2, 'BOOK-003', 'Clean Code',             'A handbook of agile software craftsmanship',             34.99, 17.00, 300,  1, 0.55),
(10, 2, 'BOOK-004', 'Designing Data-Intensive Apps', 'The big ideas behind reliable scalable systems',  44.99, 22.00, 95,   1, 0.82),
(11, 3, 'CLTH-001', 'Classic T-Shirt',       '100% cotton crew neck t-shirt',                          19.99, 6.00,  500,  1, 0.20),
(12, 3, 'CLTH-002', 'Slim Fit Jeans',        'Dark wash stretch denim jeans',                          59.99, 22.00, 180,  1, 0.60),
(13, 3, 'CLTH-003', 'Running Shoes',         'Lightweight mesh running shoes',                         89.99, 35.00, 110,  1, 0.70),
(14, 3, 'CLTH-004', 'Winter Jacket',         'Insulated waterproof winter parka',                      149.99, 70.00, 60,  1, 1.50),
(15, 4, 'HOME-001', 'Standing Desk',         'Electric height-adjustable standing desk, 60x30"',       399.99, 200.00, 30, 1, 35.00),
(16, 4, 'HOME-002', 'Desk Lamp',             'LED desk lamp with adjustable color temperature',         39.99, 15.00, 180,  1, 1.10),
(17, 4, 'HOME-003', 'Plant Pot Set',         'Set of 3 ceramic plant pots with drainage',              24.99, 8.00,  350,  1, 2.30),
(18, 5, 'SPRT-001', 'Yoga Mat',              'Extra thick 6mm non-slip yoga mat',                      29.99, 10.00, 220,  1, 1.10),
(19, 5, 'SPRT-002', 'Resistance Bands Set',  'Set of 5 resistance bands with door anchor',             19.99, 6.00,  400,  1, 0.35),
(20, 5, 'SPRT-003', 'Tennis Racket',         'Carbon fiber tennis racket, 300g',                       129.99, 55.00, 45, 1, 0.30),
(21, 1, 'ELEC-007', 'Discontinued Webcam',   'Old model — no longer manufactured',                      0.00,  0.00,  0,   0, NULL);

INSERT INTO customers (id, first_name, last_name, email, phone, address_line1, city, state_province, postal_code, country, loyalty_points, is_vip) VALUES
(1,  'Alice',   'Johnson',  'alice@example.com',    '+1-555-0101', '123 Main St',    'Portland',  'OR', '97201', 'US', 1420, 1),
(2,  'Bob',     'Smith',    'bob@example.com',      '+1-555-0102', '456 Oak Ave',    'Seattle',   'WA', '98101', 'US', 350,  0),
(3,  'Carol',   'Williams', 'carol@example.com',    '+1-555-0103', '789 Pine Rd',    'Austin',    'TX', '73301', 'US', 890,  1),
(4,  'Dave',    'Brown',    'dave@example.com',     '+1-555-0104', '321 Elm St',     'Denver',    'CO', '80201', 'US', 120,  0),
(5,  'Eve',     'Davis',    'eve@example.com',      '+1-555-0105', '654 Maple Dr',   'Chicago',   'IL', '60601', 'US', 2100, 1),
(6,  'Frank',   'Miller',   'frank@example.com',    NULL,          '987 Birch Ln',   'Miami',     'FL', '33101', 'US', 0,    0),
(7,  'Grace',   'Wilson',   'grace@example.com',    '+1-555-0107', '147 Cedar Ct',   'Boston',    'MA', '02101', 'US', 75,   0),
(8,  'Hank',    'Moore',    'hank@example.com',     '+1-555-0108', '258 Spruce Way', 'New York',  'NY', '10001', 'US', 460,  0),
(9,  'Iris',    'Taylor',   'iris@example.com',     NULL,          '369 Walnut Pl',  'Unknown',   NULL, NULL,    'CA', 110,  0),
(10, 'Jack',    'Anderson', 'jack@example.com',     '+1-555-0110', '482 Ash Cir',    'London',    NULL, 'EC1A',  'UK', 3200, 1),
(11, 'Karen',   'Thomas',   'karen@example.com',    '+1-555-0111', '753 Poplar Ave', 'Portland',  'OR', '97202', 'US', 680,  0),
(12, 'Leo',     'Jackson',  'leo@example.com',      NULL,          NULL,             'Unknown',   NULL, NULL,    'US', 0,    0),
(13, 'Mia',     'White',    'mia@example.com',      '+1-555-0113', '159 Cherry St',  'Phoenix',   'AZ', '85001', 'US', 1950, 1),
(14, 'Noah',    'Harris',   'noah@example.com',     '+1-555-0114', '357 Hickory Dr', 'Denver',    'CO', '80202', 'US', 40,   0),
(15, 'Olivia',  'Martin',   'olivia@example.com',   '+1-555-0115', '951 Sycamore Ln','Seattle',   'WA', '98102', 'US', 510,  0);

INSERT INTO orders (id, customer_id, order_date, status, subtotal, tax, shipping_cost, total, shipping_city, notes) VALUES
(1,  1,  '2025-06-01 10:23:00', 'delivered',  129.98, 11.05, 5.99,  146.97, 'Portland', NULL),
(2,  1,  '2025-06-15 14:45:00', 'delivered',  89.99,  7.65,  0.00,  97.64,  'Portland', 'Free shipping promo'),
(3,  2,  '2025-07-03 09:12:00', 'shipped',    449.99, 38.25, 12.99, 501.23, 'Seattle',  NULL),
(4,  3,  '2025-07-10 16:30:00', 'delivered',  74.98,  6.37,  5.99,  87.34,  'Austin',   'Gift order'),
(5,  4,  '2025-07-22 11:05:00', 'delivered',  199.97, 17.00, 8.99,  225.96, 'Denver',   NULL),
(6,  5,  '2025-08-01 08:00:00', 'delivered',  399.99, 34.00, 0.00,  433.99, 'Chicago',  NULL),
(7,  5,  '2025-08-14 13:20:00', 'shipped',    129.99, 11.05, 5.99,  147.03, 'Chicago',  NULL),
(8,  6,  '2025-08-20 10:45:00', 'pending',    19.99,  1.70,  3.99,  25.68,  'Miami',    NULL),
(9,  7,  '2025-09-01 15:10:00', 'delivered',  39.99,  3.40,  5.99,  49.38,  'Boston',   NULL),
(10, 8,  '2025-09-12 09:30:00', 'delivered',  59.99,  5.10,  5.99,  71.08,  'New York', NULL),
(11, 9,  '2025-09-25 17:00:00', 'cancelled',  149.99, 12.75, 0.00,  162.74, NULL,       'Customer cancelled'),
(12, 10,'2025-10-01 12:00:00', 'delivered',  89.99,  7.65,  0.00,  97.64,  'London',   'International'),
(13, 11,'2025-10-15 08:45:00', 'shipped',    179.97, 15.30, 8.99,  204.26, 'Portland', NULL),
(14, 12,'2025-10-20 14:30:00', 'pending',    34.99,  2.97,  5.99,  43.95,  NULL,       NULL),
(15, 13,'2025-11-01 10:00:00', 'delivered',  529.97, 45.05, 12.99, 588.01, 'Phoenix',  NULL),
(16, 13,'2025-11-10 16:15:00', 'shipped',    399.99, 34.00, 0.00,  433.99, 'Phoenix',  NULL),
(17, 14,'2025-11-20 11:30:00', 'delivered',  24.99,  2.12,  3.99,  31.10,  'Denver',   NULL),
(18, 15,'2025-12-01 09:00:00', 'delivered',  29.99,  2.55,  5.99,  38.53,  'Seattle',  NULL),
(19, 3,  '2025-12-10 13:45:00', 'pending',    89.99,  7.65,  5.99,  103.63, 'Austin',   NULL),
(20, 2,  '2025-12-15 10:20:00', 'pending',    39.99,  3.40,  5.99,  49.38,  'Seattle',  NULL),
(21, 1,  '2025-12-18 15:00:00', 'pending',    49.99,  4.25,  5.99,  60.23,  'Portland', NULL),
(22, 5,  '2025-12-20 09:30:00', 'pending',    129.99, 11.05, 5.99,  147.03, 'Chicago',  NULL),
(23, 10, '2026-01-05 12:30:00', 'delivered',  249.98, 21.25, 0.00,  271.23, 'London',   'International'),
(24, 4,  '2026-01-12 14:00:00', 'shipped',    149.99, 12.75, 8.99,  171.73, 'Denver',   NULL),
(25, 8,  '2026-01-20 10:15:00', 'delivered',  89.99,  7.65,  5.99,  103.63, 'New York', NULL);

INSERT INTO order_items (id, order_id, product_id, quantity, unit_price, line_total) VALUES
(1,  1,  1,  2, 29.99,  59.98),
(2,  1,  4,  2, 34.99,  69.98),
(3,  2,  2,  1, 89.99,  89.99),
(4,  3,  3,  1, 449.99, 449.99),
(5,  4,  1,  1, 29.99,  29.99),
(6,  4,  11, 2, 22.49,  44.98),  -- on-sale price
(7,  5,  7,  2, 39.99,  79.98),
(8,  5,  8,  1, 49.99,  49.99),
(9,  5,  9,  2, 34.99,  69.98),
(10, 6,  15, 1, 399.99, 399.99),
(11, 7,  5,  1, 129.99, 129.99),
(12, 8,  19, 1, 19.99,  19.99),
(13, 9,  10, 1, 39.99,  39.99),  -- on-sale price
(14, 10, 12, 1, 59.99,  59.99),
(15, 11, 14, 1, 149.99, 149.99),
(16, 12, 2,  1, 89.99,  89.99),
(17, 13, 2,  1, 89.99,  89.99),
(18, 13, 5,  1, 89.99,  89.99),  -- on-sale price
(19, 14, 4,  1, 34.99,  34.99),
(20, 15, 3,  1, 449.99, 449.99),
(21, 15, 16, 2, 39.99,  79.98),
(22, 16, 15, 1, 399.99, 399.99),
(23, 17, 17, 1, 24.99,  24.99),
(24, 18, 18, 1, 29.99,  29.99),
(25, 19, 2,  1, 89.99,  89.99),
(26, 20, 7,  1, 39.99,  39.99),
(27, 21, 6,  1, 49.99,  49.99),
(28, 22, 5,  1, 129.99, 129.99),
(29, 23, 13, 2, 89.99,  179.98),
(30, 23, 19, 2, 19.99,  39.98),
(31, 23, 18, 1, 29.99,  29.99),
(32, 24, 14, 1, 149.99, 149.99),
(33, 25, 2,  1, 89.99,  89.99);

INSERT INTO inventory_log (id, product_id, change_qty, reason, logged_by, logged_at) VALUES
(1,  1,  -5, 'order #1 shipped',       'warehouse', '2025-06-02 08:00:00'),
(2,  1,  -2, 'order #4 shipped',       'warehouse', '2025-07-11 08:00:00'),
(3,  2,  -1, 'order #2 shipped',       'warehouse', '2025-06-16 08:00:00'),
(4,  3,  -1, 'order #3 shipped',       'warehouse', '2025-07-04 08:00:00'),
(5,  3,  -1, 'order #15 shipped',      'warehouse', '2025-11-02 08:00:00'),
(6,  5,  -1, 'order #7 shipped',       'warehouse', '2025-08-15 08:00:00'),
(7,  7,  -2, 'order #5 shipped',       'warehouse', '2025-07-23 08:00:00'),
(8,  8,  -1, 'order #5 shipped',       'warehouse', '2025-07-23 08:00:00'),
(9,  9,  -2, 'order #5 shipped',       'warehouse', '2025-07-23 08:00:00'),
(10, 15, -1, 'order #6 shipped',       'warehouse', '2025-08-02 08:00:00'),
(11, 10, -1, 'order #9 shipped',       'warehouse', '2025-09-02 08:00:00'),
(12, 12, -1, 'order #10 shipped',      'warehouse', '2025-09-13 08:00:00'),
(13, 2,  -1, 'order #12 shipped',      'warehouse', '2025-10-02 08:00:00'),
(14, 17, -1, 'order #17 shipped',      'warehouse', '2025-11-21 08:00:00'),
(15, 18, -1, 'order #18 shipped',      'warehouse', '2025-12-02 08:00:00'),
(16, 3,   20, 'restock from supplier',  'admin',     '2025-12-05 10:00:00'),
(17, 1,   100, 'restock from supplier', 'admin',     '2026-01-10 10:00:00'),
(18, 14, -1, 'order #24 shipped',      'warehouse', '2026-01-13 08:00:00'),
(19, 2,  -1, 'order #25 shipped',      'warehouse', '2026-01-21 08:00:00');

USE safedb_lab;

CREATE TABLE scan_events (
    id BIGINT PRIMARY KEY,
    customer_id INT NULL,
    created_at TIMESTAMP NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    is_active TINYINT(1) NOT NULL,
    label VARCHAR(32) NOT NULL,
    note TEXT NULL,
    INDEX idx_scan_events_label (label),
    INDEX idx_scan_events_created_at (created_at),
    CONSTRAINT fk_scan_events_customer FOREIGN KEY (customer_id) REFERENCES safedb_test.customers(id)
) ENGINE=InnoDB;

INSERT INTO scan_events (id, customer_id, created_at, amount, is_active, label, note)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
)
SELECT
    a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1 AS id,
    CASE WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 17 = 0
        THEN NULL ELSE 1 + ((a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 3) END,
    TIMESTAMP('2024-01-01') + INTERVAL (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 365 DAY,
    ((a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 10000) / 100,
    (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 2,
    CONCAT('label-', (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 50),
    CASE
        WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 11 = 0 THEN NULL
        WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 13 = 0 THEN '你好 café naïve'
        ELSE CONCAT('note-', a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1)
    END
FROM digits a
CROSS JOIN digits b
CROSS JOIN digits c
CROSS JOIN digits d
CROSS JOIN digits e
CROSS JOIN digits f;

CREATE TABLE wide_rows (
    id BIGINT PRIMARY KEY,
    indexed_key INT NOT NULL,
    col_int INT NOT NULL,
    col_dec DECIMAL(12,4) NOT NULL,
    col_bool TINYINT(1) NOT NULL,
    col_date DATE NOT NULL,
    col_text VARCHAR(64) NOT NULL,
    col_null VARCHAR(16) NULL,
    col_01 INT NOT NULL,
    col_02 INT NOT NULL,
    col_03 INT NOT NULL,
    col_04 INT NOT NULL,
    col_05 INT NOT NULL,
    col_06 INT NOT NULL,
    col_07 INT NOT NULL,
    col_08 INT NOT NULL,
    col_09 INT NOT NULL,
    col_10 INT NOT NULL,
    col_11 INT NOT NULL,
    col_12 INT NOT NULL,
    col_13 INT NOT NULL,
    col_14 INT NOT NULL,
    col_15 INT NOT NULL,
    col_16 INT NOT NULL,
    INDEX idx_wide_rows_indexed_key (indexed_key)
) ENGINE=InnoDB;

INSERT INTO wide_rows
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
)
SELECT
    n,
    n % 1000,
    n,
    (n % 9999) / 100,
    (n % 2 = 0),
    DATE('2024-01-01') + INTERVAL (n % 400) DAY,
    CONCAT('wide-', n),
    CASE WHEN n % 7 = 0 THEN NULL ELSE 'present' END,
    n % 10, n % 11, n % 12, n % 13, n % 14, n % 15, n % 16, n % 17,
    n % 18, n % 19, n % 20, n % 21, n % 22, n % 23, n % 24, n % 25
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
) seq
WHERE n <= 50000;

CREATE TABLE payload_rows (
    id BIGINT PRIMARY KEY,
    payload TEXT NOT NULL
) ENGINE=InnoDB;

INSERT INTO payload_rows
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
)
SELECT n, REPEAT('x', 2048)
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
) seq
WHERE n <= 20000;

CREATE TABLE event_notes (
    id BIGINT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    body TEXT NULL,
    flagged TINYINT(1) NOT NULL,
    CONSTRAINT fk_event_notes_event FOREIGN KEY (event_id) REFERENCES scan_events(id)
) ENGINE=InnoDB;

INSERT INTO event_notes (id, event_id, body, flagged)
WITH digits AS (
    SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
    UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9
)
SELECT
    n,
    n,
    CASE
        WHEN n % 11 = 0 THEN NULL
        WHEN n % 13 = 0 THEN '東京 ☕'
        ELSE CONCAT('note body ', n)
    END,
    n % 5 = 0
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
) seq
WHERE n <= 10000;

ANALYZE TABLE scan_events, wide_rows, payload_rows, event_notes;
USE safedb_test;
