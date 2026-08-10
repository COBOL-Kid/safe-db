-- Minimal Oracle fixture for local JDBC, schema-discovery, and query tests.
SET DEFINE OFF

BEGIN
    EXECUTE IMMEDIATE 'DROP VIEW customer_order_summary';
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

COMMIT;
