-- Minimal PostgreSQL fixture for scheduled JDBC contract tests.
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
