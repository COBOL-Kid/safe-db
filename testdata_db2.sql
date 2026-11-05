-- Db2 fixture for local JDBC, schema-discovery, query, and risk-gate tests.
-- Db2 LUW syntax: VARCHAR/INTEGER/TIMESTAMP with FETCH FIRST pagination.
-- Db2 LUW has no DROP ... IF EXISTS, so this file intentionally contains no drops:
-- docker/test-databases/db2-init.sh and the Db2 seed path issue best-effort drops
-- (ignoring SQL0204N for objects that do not exist yet) before loading this file.
-- Unquoted names fold to uppercase, so introspection sees CUSTOMERS/ORDERS.
-- Shop tables stay small. Bulk tables are generated (digit cross-joins):
--   ANALYTICS.SCAN_EVENTS  1,000,000  Large size class, full-scan timeout, High on uncapped scan
--   ANALYTICS.WIDE_ROWS       50,000  Medium, 24 mixed-type columns
--   ANALYTICS.PAYLOAD_ROWS    20,000  ~2 KiB text so a column select approaches 10 MiB
--   ANALYTICS.EVENT_NOTES     10,000  FK without index, NULLs, unicode

CREATE TABLE customers (
    id INTEGER NOT NULL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(30),
    loyalty_points INTEGER NOT NULL DEFAULT 0,
    is_vip SMALLINT NOT NULL DEFAULT 0,
    signed_up_at TIMESTAMP NOT NULL
);

CREATE TABLE orders (
    id INTEGER NOT NULL PRIMARY KEY,
    customer_id INTEGER NOT NULL,
    order_date TIMESTAMP NOT NULL,
    status VARCHAR(30) NOT NULL,
    total DECIMAL(12,2) NOT NULL,
    notes CLOB,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);

INSERT INTO customers (id, email, phone, loyalty_points, is_vip, signed_up_at) VALUES
    (1, 'alice@example.com', '+1-555-0101', 1420, 1, '2025-01-01 10:00:00'),
    (2, 'bob@example.com',   NULL,            350, 0, '2025-02-01 11:00:00'),
    (3, 'carol@example.com', '+1-555-0103',  890, 1, '2025-03-01 12:00:00');

INSERT INTO orders (id, customer_id, order_date, status, total, notes) VALUES
    (1, 1, '2025-06-01 10:23:00', 'delivered', 146.97, NULL),
    (2, 1, '2025-06-15 14:45:00', 'delivered',  97.64, 'Free shipping promo'),
    (3, 2, '2025-07-03 09:12:00', 'shipped',   501.23, NULL),
    (4, 3, '2025-07-10 16:30:00', 'delivered',  87.34, 'Gift order'),
    (5, 2, '2025-08-01 08:00:00', 'pending',    25.68, NULL),
    (6, 3, '2025-08-14 13:20:00', 'pending',   147.03, NULL);

CREATE OR REPLACE VIEW customer_order_summary AS
SELECT c.id AS customer_id,
       c.email,
       COUNT(o.id) AS order_count,
       COALESCE(SUM(o.total), 0) AS lifetime_value
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.email;

CREATE SCHEMA analytics;

CREATE TABLE analytics.scan_events (
    id INTEGER NOT NULL PRIMARY KEY,
    customer_id INTEGER,
    created_at TIMESTAMP NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    is_active SMALLINT NOT NULL,
    label VARCHAR(32) NOT NULL,
    note VARCHAR(200),
    CONSTRAINT fk_scan_events_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);
CREATE INDEX idx_scan_events_label ON analytics.scan_events(label);
CREATE INDEX idx_scan_events_created_at ON analytics.scan_events(created_at);

INSERT INTO analytics.scan_events (id, customer_id, created_at, amount, is_active, label, note)
WITH digits(n) AS (
    SELECT 0 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 1 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 2 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 3 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 4 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 5 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 6 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 7 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 8 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 9 FROM SYSIBM.SYSDUMMY1
)
SELECT
    a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1,
    CASE WHEN MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1, 17) = 0
        THEN NULL ELSE 1 + MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000, 3) END,
    TIMESTAMP('2024-01-01-00.00.00') + (MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000, 365)) DAYS,
    DECIMAL(MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1, 10000), 12, 2) / 100,
    CASE WHEN MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000, 2) = 0 THEN 1 ELSE 0 END,
    'label-' || CHAR(MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000, 50)),
    CASE
        WHEN MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1, 11) = 0 THEN NULL
        WHEN MOD(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1, 13) = 0 THEN 'cafe naive'
        ELSE 'note-' || CHAR(a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1)
    END
FROM digits a, digits b, digits c, digits d, digits e, digits f;

CREATE TABLE analytics.wide_rows (
    id INTEGER NOT NULL PRIMARY KEY,
    indexed_key INTEGER NOT NULL,
    col_int INTEGER NOT NULL,
    col_dec DECIMAL(12,4) NOT NULL,
    col_bool SMALLINT NOT NULL,
    col_date DATE NOT NULL,
    col_text VARCHAR(64) NOT NULL,
    col_null VARCHAR(16),
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
WITH digits(n) AS (
    SELECT 0 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 1 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 2 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 3 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 4 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 5 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 6 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 7 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 8 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 9 FROM SYSIBM.SYSDUMMY1
)
SELECT
    n,
    MOD(n, 1000),
    n,
    DECIMAL(MOD(n, 9999), 12, 4) / 100,
    CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END,
    DATE('2024-01-01') + (MOD(n, 400)) DAYS,
    'wide-' || CHAR(n),
    CASE WHEN MOD(n, 7) = 0 THEN NULL ELSE 'present' END,
    MOD(n, 10), MOD(n, 11), MOD(n, 12), MOD(n, 13), MOD(n, 14), MOD(n, 15), MOD(n, 16), MOD(n, 17),
    MOD(n, 18), MOD(n, 19), MOD(n, 20), MOD(n, 21), MOD(n, 22), MOD(n, 23), MOD(n, 24), MOD(n, 25)
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a, digits b, digits c, digits d, digits e
) seq
WHERE n <= 50000;

CREATE TABLE analytics.payload_rows (
    id INTEGER NOT NULL PRIMARY KEY,
    payload CLOB NOT NULL
);

INSERT INTO analytics.payload_rows
WITH digits(n) AS (
    SELECT 0 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 1 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 2 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 3 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 4 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 5 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 6 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 7 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 8 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 9 FROM SYSIBM.SYSDUMMY1
)
SELECT n, REPEAT('x', 2048)
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a, digits b, digits c, digits d, digits e
) seq
WHERE n <= 20000;

CREATE TABLE analytics.event_notes (
    id INTEGER NOT NULL PRIMARY KEY,
    event_id INTEGER NOT NULL,
    body VARCHAR(200),
    flagged SMALLINT NOT NULL,
    CONSTRAINT fk_event_notes_event FOREIGN KEY (event_id) REFERENCES analytics.scan_events(id)
);

INSERT INTO analytics.event_notes (id, event_id, body, flagged)
WITH digits(n) AS (
    SELECT 0 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 1 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 2 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 3 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 4 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 5 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 6 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 7 FROM SYSIBM.SYSDUMMY1
    UNION ALL SELECT 8 FROM SYSIBM.SYSDUMMY1 UNION ALL SELECT 9 FROM SYSIBM.SYSDUMMY1
)
SELECT
    n,
    n,
    CASE
        WHEN MOD(n, 11) = 0 THEN NULL
        WHEN MOD(n, 13) = 0 THEN 'tokyo'
        ELSE 'note body ' || CHAR(n)
    END,
    CASE WHEN MOD(n, 5) = 0 THEN 1 ELSE 0 END
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + 1 AS n
    FROM digits a, digits b, digits c, digits d
) seq
WHERE n <= 10000;

CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE ANALYTICS.SCAN_EVENTS');
CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE ANALYTICS.WIDE_ROWS');
CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE ANALYTICS.PAYLOAD_ROWS');
CALL SYSPROC.ADMIN_CMD('RUNSTATS ON TABLE ANALYTICS.EVENT_NOTES');
