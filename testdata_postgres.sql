-- PostgreSQL fixture for JDBC contract tests and MCP/manual risk-gate testing.
-- Load: compose postgres init, or scripts/seed_postgres.sh --static
-- Shop tables stay small. Bulk tables are generated (generate_series), not literal inserts.
--   analytics.scan_events  1,000,000  Large, full-scan timeout, Elevated/High/confirm/block
--   analytics.wide_rows       50,000  Medium, 24 mixed-type columns
--   analytics.payload_rows    20,000  ~2 KiB text so a column select approaches 10 MiB
--   ops.event_notes           10,000  FK without index, NULLs, unicode

DROP SCHEMA IF EXISTS ops CASCADE;
DROP SCHEMA IF EXISTS analytics CASCADE;
DROP VIEW IF EXISTS customer_order_summary;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS inventory_log;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS customers;
DROP TABLE IF EXISTS categories;

CREATE TABLE customers (
    id BIGINT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    phone TEXT NULL,
    loyalty_points INTEGER NOT NULL DEFAULT 0,
    is_vip BOOLEAN NOT NULL DEFAULT FALSE,
    signed_up_at TIMESTAMP NOT NULL
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    order_date TIMESTAMP NOT NULL,
    status TEXT NOT NULL,
    total NUMERIC(12,2) NOT NULL,
    notes TEXT NULL
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);

INSERT INTO customers (id, email, phone, loyalty_points, is_vip, signed_up_at) VALUES
    (1, 'alice@example.com', '+1-555-0101', 1420, TRUE,  '2025-01-01 10:00:00'),
    (2, 'bob@example.com',   NULL,            350, FALSE, '2025-02-01 11:00:00'),
    (3, 'carol@example.com', '+1-555-0103',  890, TRUE,  '2025-03-01 12:00:00');

INSERT INTO orders (id, customer_id, order_date, status, total, notes) VALUES
    (1, 1, '2025-06-01 10:23:00', 'delivered', 146.97, NULL),
    (2, 1, '2025-06-15 14:45:00', 'delivered',  97.64, 'Free shipping promo'),
    (3, 2, '2025-07-03 09:12:00', 'shipped',   501.23, NULL),
    (4, 3, '2025-07-10 16:30:00', 'delivered',  87.34, 'Gift order'),
    (5, 2, '2025-08-01 08:00:00', 'pending',    25.68, NULL),
    (6, 3, '2025-08-14 13:20:00', 'pending',   147.03, NULL);

CREATE SCHEMA analytics;
CREATE SCHEMA ops;

CREATE TABLE analytics.scan_events (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NULL REFERENCES public.customers(id),
    created_at TIMESTAMP NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    is_active BOOLEAN NOT NULL,
    label TEXT NOT NULL,
    note TEXT NULL
);
CREATE INDEX idx_scan_events_label ON analytics.scan_events(label);
CREATE INDEX idx_scan_events_created_at ON analytics.scan_events(created_at);

INSERT INTO analytics.scan_events (id, customer_id, created_at, amount, is_active, label, note)
SELECT
    g,
    CASE WHEN g % 17 = 0 THEN NULL ELSE 1 + (g % 3) END,
    TIMESTAMP '2024-01-01' + (g % 365) * INTERVAL '1 day',
    ((g % 10000)::numeric / 100),
    (g % 2 = 0),
    'label-' || (g % 50),
    CASE
        WHEN g % 11 = 0 THEN NULL
        WHEN g % 13 = 0 THEN '你好 café naïve'
        ELSE 'note-' || g
    END
FROM generate_series(1, 1000000) AS g;

CREATE TABLE analytics.wide_rows (
    id BIGINT PRIMARY KEY,
    indexed_key INTEGER NOT NULL,
    col_int INTEGER NOT NULL,
    col_dec NUMERIC(12,4) NOT NULL,
    col_bool BOOLEAN NOT NULL,
    col_date DATE NOT NULL,
    col_text TEXT NOT NULL,
    col_null TEXT NULL,
    col_01 INTEGER NOT NULL,
    col_02 INTEGER NOT NULL,
    col_03 INTEGER NOT NULL,
    col_04 INTEGER NOT NULL,
    col_05 INTEGER NOT NULL,
    col_06 INTEGER NOT NULL,
    col_07 INTEGER NOT NULL,
    col_08 INTEGER NOT NULL,
    col_09 INTEGER NOT NULL,
    col_10 INTEGER NOT NULL,
    col_11 INTEGER NOT NULL,
    col_12 INTEGER NOT NULL,
    col_13 INTEGER NOT NULL,
    col_14 INTEGER NOT NULL,
    col_15 INTEGER NOT NULL,
    col_16 INTEGER NOT NULL
);
CREATE INDEX idx_wide_rows_indexed_key ON analytics.wide_rows(indexed_key);

INSERT INTO analytics.wide_rows
SELECT
    g,
    g % 1000,
    g,
    ((g % 9999)::numeric / 100),
    (g % 2 = 0),
    DATE '2024-01-01' + (g % 400),
    'wide-' || g,
    CASE WHEN g % 7 = 0 THEN NULL ELSE 'present' END,
    g % 10, g % 11, g % 12, g % 13, g % 14, g % 15, g % 16, g % 17,
    g % 18, g % 19, g % 20, g % 21, g % 22, g % 23, g % 24, g % 25
FROM generate_series(1, 50000) AS g;

CREATE TABLE analytics.payload_rows (
    id BIGINT PRIMARY KEY,
    payload TEXT NOT NULL
);

INSERT INTO analytics.payload_rows
SELECT g, repeat('x', 2048)
FROM generate_series(1, 20000) AS g;

CREATE TABLE ops.event_notes (
    id BIGINT PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES analytics.scan_events(id),
    body TEXT NULL,
    flagged BOOLEAN NOT NULL
);

INSERT INTO ops.event_notes (id, event_id, body, flagged)
SELECT
    g,
    g,
    CASE
        WHEN g % 11 = 0 THEN NULL
        WHEN g % 13 = 0 THEN '東京 ☕'
        ELSE 'note body ' || g
    END,
    (g % 5 = 0)
FROM generate_series(1, 10000) AS g;

ANALYZE customers;
ANALYZE orders;
ANALYZE analytics.scan_events;
ANALYZE analytics.wide_rows;
ANALYZE analytics.payload_rows;
ANALYZE ops.event_notes;
