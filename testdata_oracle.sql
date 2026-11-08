-- Oracle fixture for local JDBC, schema-discovery, query, and risk-gate tests.
-- Load: scripts/docker_test_databases.sh up/seed, or scripts/seed_oracle.sh --static
-- Shop tables stay small. Bulk tables are generated (CONNECT BY) in the SAFEDB schema:
--   scan_events  1,000,000  Large size class, full-scan timeout, High on uncapped scan
--   wide_rows       50,000  Medium, 24 mixed-type columns
--   payload_rows    20,000  ~2 KiB text so a column select approaches 10 MiB
--   event_notes     10,000  FK without index, NULLs, unicode
SET DEFINE OFF

BEGIN
    EXECUTE IMMEDIATE 'DROP VIEW customer_order_summary';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE event_notes CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE payload_rows CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE wide_rows CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE scan_events CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE order_items CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE inventory_log CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE orders CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE products CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE customers CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/
BEGIN
    EXECUTE IMMEDIATE 'DROP TABLE categories CASCADE CONSTRAINTS PURGE';
EXCEPTION
    WHEN OTHERS THEN
        IF SQLCODE != -942 THEN RAISE; END IF;
END;
/

CREATE TABLE customers (
    id NUMBER(19) CONSTRAINT pk_customers PRIMARY KEY,
    email VARCHAR2(255 CHAR) CONSTRAINT nn_customers_email NOT NULL,
    phone VARCHAR2(30 CHAR),
    loyalty_points NUMBER(10) DEFAULT 0 CONSTRAINT nn_customers_loyalty NOT NULL,
    is_vip NUMBER(1) DEFAULT 0 CONSTRAINT nn_customers_vip NOT NULL,
    signed_up_at TIMESTAMP(0) CONSTRAINT nn_customers_signed_up NOT NULL,
    CONSTRAINT uq_customers_email UNIQUE (email),
    CONSTRAINT ck_customers_vip CHECK (is_vip IN (0, 1))
);

CREATE TABLE orders (
    id NUMBER(19) CONSTRAINT pk_orders PRIMARY KEY,
    customer_id NUMBER(19) CONSTRAINT nn_orders_customer NOT NULL,
    order_date TIMESTAMP(0) CONSTRAINT nn_orders_date NOT NULL,
    status VARCHAR2(30 CHAR) CONSTRAINT nn_orders_status NOT NULL,
    total NUMBER(12,2) CONSTRAINT nn_orders_total NOT NULL,
    notes CLOB,
    CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_status ON orders(status);

INSERT ALL
    INTO customers VALUES (1, 'alice@example.com', '+1-555-0101', 1420, 1, TIMESTAMP '2025-01-01 10:00:00')
    INTO customers VALUES (2, 'bob@example.com',   NULL,              350, 0, TIMESTAMP '2025-02-01 11:00:00')
    INTO customers VALUES (3, 'carol@example.com', '+1-555-0103',   890, 1, TIMESTAMP '2025-03-01 12:00:00')
SELECT 1 FROM dual;

INSERT ALL
    INTO orders VALUES (1, 1, TIMESTAMP '2025-06-01 10:23:00', 'delivered', 146.97, NULL)
    INTO orders VALUES (2, 1, TIMESTAMP '2025-06-15 14:45:00', 'delivered',  97.64, 'Free shipping promo')
    INTO orders VALUES (3, 2, TIMESTAMP '2025-07-03 09:12:00', 'shipped',   501.23, NULL)
    INTO orders VALUES (4, 3, TIMESTAMP '2025-07-10 16:30:00', 'delivered',  87.34, 'Gift order')
    INTO orders VALUES (5, 2, TIMESTAMP '2025-08-01 08:00:00', 'pending',    25.68, NULL)
    INTO orders VALUES (6, 3, TIMESTAMP '2025-08-14 13:20:00', 'pending',   147.03, NULL)
SELECT 1 FROM dual;

CREATE VIEW customer_order_summary AS
SELECT c.id AS customer_id,
       c.email,
       COUNT(o.id) AS order_count,
       NVL(SUM(o.total), 0) AS lifetime_value
FROM customers c
LEFT JOIN orders o ON o.customer_id = c.id
GROUP BY c.id, c.email;

CREATE TABLE scan_events (
    id NUMBER(19) CONSTRAINT pk_scan_events PRIMARY KEY,
    customer_id NUMBER(19),
    created_at TIMESTAMP(0) CONSTRAINT nn_scan_events_created NOT NULL,
    amount NUMBER(12,2) CONSTRAINT nn_scan_events_amount NOT NULL,
    is_active NUMBER(1) CONSTRAINT nn_scan_events_active NOT NULL,
    label VARCHAR2(32 CHAR) CONSTRAINT nn_scan_events_label NOT NULL,
    note VARCHAR2(200 CHAR),
    CONSTRAINT fk_scan_events_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT ck_scan_events_active CHECK (is_active IN (0, 1))
);
CREATE INDEX idx_scan_events_label ON scan_events(label);
CREATE INDEX idx_scan_events_created_at ON scan_events(created_at);

INSERT INTO scan_events (id, customer_id, created_at, amount, is_active, label, note)
SELECT
    n,
    CASE WHEN MOD(n, 17) = 0 THEN NULL ELSE 1 + MOD(n, 3) END,
    TIMESTAMP '2024-01-01 00:00:00' + NUMTODSINTERVAL(MOD(n, 365), 'DAY'),
    MOD(n, 10000) / 100,
    CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END,
    'label-' || MOD(n, 50),
    CASE
        WHEN MOD(n, 11) = 0 THEN NULL
        WHEN MOD(n, 13) = 0 THEN TO_CHAR(UNISTR('\4F60\597D caf\00E9 na\00EFve'))
        ELSE 'note-' || n
    END
FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 1000000);

CREATE TABLE wide_rows (
    id NUMBER(19) CONSTRAINT pk_wide_rows PRIMARY KEY,
    indexed_key NUMBER(10) CONSTRAINT nn_wide_indexed NOT NULL,
    col_int NUMBER(10) CONSTRAINT nn_wide_int NOT NULL,
    col_dec NUMBER(12,4) CONSTRAINT nn_wide_dec NOT NULL,
    col_bool NUMBER(1) CONSTRAINT nn_wide_bool NOT NULL,
    col_date DATE CONSTRAINT nn_wide_date NOT NULL,
    col_text VARCHAR2(64 CHAR) CONSTRAINT nn_wide_text NOT NULL,
    col_null VARCHAR2(16 CHAR),
    col_01 NUMBER(10) CONSTRAINT nn_wide_01 NOT NULL,
    col_02 NUMBER(10) CONSTRAINT nn_wide_02 NOT NULL,
    col_03 NUMBER(10) CONSTRAINT nn_wide_03 NOT NULL,
    col_04 NUMBER(10) CONSTRAINT nn_wide_04 NOT NULL,
    col_05 NUMBER(10) CONSTRAINT nn_wide_05 NOT NULL,
    col_06 NUMBER(10) CONSTRAINT nn_wide_06 NOT NULL,
    col_07 NUMBER(10) CONSTRAINT nn_wide_07 NOT NULL,
    col_08 NUMBER(10) CONSTRAINT nn_wide_08 NOT NULL,
    col_09 NUMBER(10) CONSTRAINT nn_wide_09 NOT NULL,
    col_10 NUMBER(10) CONSTRAINT nn_wide_10 NOT NULL,
    col_11 NUMBER(10) CONSTRAINT nn_wide_11 NOT NULL,
    col_12 NUMBER(10) CONSTRAINT nn_wide_12 NOT NULL,
    col_13 NUMBER(10) CONSTRAINT nn_wide_13 NOT NULL,
    col_14 NUMBER(10) CONSTRAINT nn_wide_14 NOT NULL,
    col_15 NUMBER(10) CONSTRAINT nn_wide_15 NOT NULL,
    col_16 NUMBER(10) CONSTRAINT nn_wide_16 NOT NULL,
    CONSTRAINT ck_wide_bool CHECK (col_bool IN (0, 1))
);
CREATE INDEX idx_wide_rows_indexed_key ON wide_rows(indexed_key);

INSERT INTO wide_rows
SELECT
    n,
    MOD(n, 1000),
    n,
    MOD(n, 9999) / 100,
    CASE WHEN MOD(n, 2) = 0 THEN 1 ELSE 0 END,
    DATE '2024-01-01' + MOD(n, 400),
    'wide-' || n,
    CASE WHEN MOD(n, 7) = 0 THEN NULL ELSE 'present' END,
    MOD(n, 10), MOD(n, 11), MOD(n, 12), MOD(n, 13), MOD(n, 14), MOD(n, 15), MOD(n, 16), MOD(n, 17),
    MOD(n, 18), MOD(n, 19), MOD(n, 20), MOD(n, 21), MOD(n, 22), MOD(n, 23), MOD(n, 24), MOD(n, 25)
FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 50000);

CREATE TABLE payload_rows (
    id NUMBER(19) CONSTRAINT pk_payload_rows PRIMARY KEY,
    payload CLOB CONSTRAINT nn_payload NOT NULL
);

INSERT INTO payload_rows
SELECT n, RPAD('x', 2048, 'x')
FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 20000);

CREATE TABLE event_notes (
    id NUMBER(19) CONSTRAINT pk_event_notes PRIMARY KEY,
    event_id NUMBER(19) CONSTRAINT nn_event_notes_event NOT NULL,
    body VARCHAR2(200 CHAR),
    flagged NUMBER(1) CONSTRAINT nn_event_notes_flagged NOT NULL,
    CONSTRAINT fk_event_notes_event FOREIGN KEY (event_id) REFERENCES scan_events(id),
    CONSTRAINT ck_event_notes_flagged CHECK (flagged IN (0, 1))
);

INSERT INTO event_notes (id, event_id, body, flagged)
SELECT
    n,
    n,
    CASE
        WHEN MOD(n, 11) = 0 THEN NULL
        WHEN MOD(n, 13) = 0 THEN TO_CHAR(UNISTR('\6771\4EAC'))
        ELSE 'note body ' || n
    END,
    CASE WHEN MOD(n, 5) = 0 THEN 1 ELSE 0 END
FROM (SELECT LEVEL AS n FROM dual CONNECT BY LEVEL <= 10000);

BEGIN
    DBMS_STATS.GATHER_TABLE_STATS(SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'SCAN_EVENTS');
    DBMS_STATS.GATHER_TABLE_STATS(SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'WIDE_ROWS');
    DBMS_STATS.GATHER_TABLE_STATS(SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'PAYLOAD_ROWS');
    DBMS_STATS.GATHER_TABLE_STATS(SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'), 'EVENT_NOTES');
END;
/

COMMIT;
