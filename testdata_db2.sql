-- Minimal Db2 fixture for local JDBC, schema-discovery, and query tests.
-- Db2 LUW syntax: VARCHAR/INTEGER/TIMESTAMP with FETCH FIRST pagination.
-- Db2 LUW has no DROP ... IF EXISTS, so this file intentionally contains no drops:
-- docker/test-databases/db2-init.sh and the Db2 seed path issue best-effort drops
-- (ignoring SQL0204N for objects that do not exist yet) before loading this file.
-- Unquoted names fold to uppercase, so introspection sees CUSTOMERS/ORDERS.

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
