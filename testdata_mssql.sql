-- SQL Server fixture for local JDBC, schema-discovery, query, and risk-gate tests.
-- Load: scripts/docker_test_databases.sh up/seed, or scripts/seed_mssql.sh --static
-- Shop tables stay small. Bulk tables are generated (digit cross-joins):
--   analytics.scan_events  1,000,000  Large, full-scan timeout
--   analytics.wide_rows       50,000  Medium, 24 mixed-type columns
--   analytics.payload_rows    20,000  ~2 KiB text so a column select approaches 10 MiB
--   ops.event_notes           10,000  FK without index, NULLs, unicode
SET NOCOUNT ON;
SET XACT_ABORT ON;

DROP TABLE IF EXISTS ops.event_notes;
DROP TABLE IF EXISTS analytics.payload_rows;
DROP TABLE IF EXISTS analytics.wide_rows;
DROP TABLE IF EXISTS analytics.scan_events;
DROP VIEW IF EXISTS dbo.customer_order_summary;
DROP TABLE IF EXISTS dbo.order_items;
DROP TABLE IF EXISTS dbo.inventory_log;
DROP TABLE IF EXISTS dbo.orders;
DROP TABLE IF EXISTS dbo.products;
DROP TABLE IF EXISTS dbo.customers;
DROP TABLE IF EXISTS dbo.categories;
IF SCHEMA_ID(N'ops') IS NOT NULL EXEC(N'DROP SCHEMA ops');
IF SCHEMA_ID(N'analytics') IS NOT NULL EXEC(N'DROP SCHEMA analytics');

CREATE TABLE dbo.customers (
    id BIGINT NOT NULL CONSTRAINT pk_customers PRIMARY KEY,
    email NVARCHAR(255) NOT NULL CONSTRAINT uq_customers_email UNIQUE,
    phone NVARCHAR(30) NULL,
    loyalty_points INT NOT NULL CONSTRAINT df_customers_loyalty DEFAULT 0,
    is_vip BIT NOT NULL CONSTRAINT df_customers_vip DEFAULT 0,
    signed_up_at DATETIME2(0) NOT NULL
);

CREATE TABLE dbo.orders (
    id BIGINT NOT NULL CONSTRAINT pk_orders PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_date DATETIME2(0) NOT NULL,
    status NVARCHAR(30) NOT NULL,
    total DECIMAL(12,2) NOT NULL,
    notes NVARCHAR(MAX) NULL,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(id)
);

CREATE INDEX idx_orders_customer ON dbo.orders(customer_id);
CREATE INDEX idx_orders_status ON dbo.orders(status);

INSERT INTO dbo.customers (id, email, phone, loyalty_points, is_vip, signed_up_at) VALUES
    (1, N'alice@example.com', N'+1-555-0101', 1420, 1, '2025-01-01T10:00:00'),
    (2, N'bob@example.com',   NULL,              350, 0, '2025-02-01T11:00:00'),
    (3, N'carol@example.com', N'+1-555-0103',  890, 1, '2025-03-01T12:00:00');

INSERT INTO dbo.orders (id, customer_id, order_date, status, total, notes) VALUES
    (1, 1, '2025-06-01T10:23:00', N'delivered', 146.97, NULL),
    (2, 1, '2025-06-15T14:45:00', N'delivered',  97.64, N'Free shipping promo'),
    (3, 2, '2025-07-03T09:12:00', N'shipped',   501.23, NULL),
    (4, 3, '2025-07-10T16:30:00', N'delivered',  87.34, N'Gift order'),
    (5, 2, '2025-08-01T08:00:00', N'pending',    25.68, NULL),
    (6, 3, '2025-08-14T13:20:00', N'pending',   147.03, NULL);

EXEC(N'
CREATE VIEW dbo.customer_order_summary AS
SELECT c.id AS customer_id,
       c.email,
       COUNT_BIG(o.id) AS order_count,
       COALESCE(SUM(o.total), 0) AS lifetime_value
FROM dbo.customers AS c
LEFT JOIN dbo.orders AS o ON o.customer_id = c.id
GROUP BY c.id, c.email
');

IF SCHEMA_ID(N'analytics') IS NULL EXEC(N'CREATE SCHEMA analytics');
IF SCHEMA_ID(N'ops') IS NULL EXEC(N'CREATE SCHEMA ops');

CREATE TABLE analytics.scan_events (
    id BIGINT NOT NULL CONSTRAINT pk_scan_events PRIMARY KEY,
    customer_id BIGINT NULL,
    created_at DATETIME2(0) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    is_active BIT NOT NULL,
    label NVARCHAR(32) NOT NULL,
    note NVARCHAR(200) NULL,
    CONSTRAINT fk_scan_events_customer FOREIGN KEY (customer_id) REFERENCES dbo.customers(id)
);
CREATE INDEX idx_scan_events_label ON analytics.scan_events(label);
CREATE INDEX idx_scan_events_created_at ON analytics.scan_events(created_at);

WITH digits AS (
    SELECT n FROM (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS v(n)
)
INSERT INTO analytics.scan_events (id, customer_id, created_at, amount, is_active, label, note)
SELECT
    a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1,
    CASE WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 17 = 0
        THEN NULL ELSE 1 + ((a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 3) END,
    DATEADD(day, (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 365, '20240101'),
    ((a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 10000) / 100.0,
    CASE WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 2 = 0 THEN 1 ELSE 0 END,
    CONCAT(N'label-', (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000) % 50),
    CASE
        WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 11 = 0 THEN NULL
        WHEN (a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1) % 13 = 0 THEN N'你好 café naïve'
        ELSE CONCAT(N'note-', a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + f.n * 100000 + 1)
    END
FROM digits a
CROSS JOIN digits b
CROSS JOIN digits c
CROSS JOIN digits d
CROSS JOIN digits e
CROSS JOIN digits f;

CREATE TABLE analytics.wide_rows (
    id BIGINT NOT NULL CONSTRAINT pk_wide_rows PRIMARY KEY,
    indexed_key INT NOT NULL,
    col_int INT NOT NULL,
    col_dec DECIMAL(12,4) NOT NULL,
    col_bool BIT NOT NULL,
    col_date DATE NOT NULL,
    col_text NVARCHAR(64) NOT NULL,
    col_null NVARCHAR(16) NULL,
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
    col_16 INT NOT NULL
);
CREATE INDEX idx_wide_rows_indexed_key ON analytics.wide_rows(indexed_key);

WITH digits AS (
    SELECT n FROM (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS v(n)
)
INSERT INTO analytics.wide_rows
SELECT
    n,
    n % 1000,
    n,
    (n % 9999) / 100.0,
    CASE WHEN n % 2 = 0 THEN 1 ELSE 0 END,
    DATEADD(day, n % 400, '20240101'),
    CONCAT(N'wide-', n),
    CASE WHEN n % 7 = 0 THEN NULL ELSE N'present' END,
    n % 10, n % 11, n % 12, n % 13, n % 14, n % 15, n % 16, n % 17,
    n % 18, n % 19, n % 20, n % 21, n % 22, n % 23, n % 24, n % 25
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
) seq
WHERE n <= 50000;

CREATE TABLE analytics.payload_rows (
    id BIGINT NOT NULL CONSTRAINT pk_payload_rows PRIMARY KEY,
    payload NVARCHAR(MAX) NOT NULL
);

WITH digits AS (
    SELECT n FROM (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS v(n)
)
INSERT INTO analytics.payload_rows
SELECT n, REPLICATE(N'x', 2048)
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + e.n * 10000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e
) seq
WHERE n <= 20000;

CREATE TABLE ops.event_notes (
    id BIGINT NOT NULL CONSTRAINT pk_event_notes PRIMARY KEY,
    event_id BIGINT NOT NULL,
    body NVARCHAR(200) NULL,
    flagged BIT NOT NULL,
    CONSTRAINT fk_event_notes_event FOREIGN KEY (event_id) REFERENCES analytics.scan_events(id)
);

WITH digits AS (
    SELECT n FROM (VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)) AS v(n)
)
INSERT INTO ops.event_notes (id, event_id, body, flagged)
SELECT
    n,
    n,
    CASE
        WHEN n % 11 = 0 THEN NULL
        WHEN n % 13 = 0 THEN N'東京 ☕'
        ELSE CONCAT(N'note body ', n)
    END,
    CASE WHEN n % 5 = 0 THEN 1 ELSE 0 END
FROM (
    SELECT a.n + b.n * 10 + c.n * 100 + d.n * 1000 + 1 AS n
    FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d
) seq
WHERE n <= 10000;

UPDATE STATISTICS analytics.scan_events;
UPDATE STATISTICS analytics.wide_rows;
UPDATE STATISTICS analytics.payload_rows;
UPDATE STATISTICS ops.event_notes;
