-- Minimal SQL Server fixture for local JDBC, schema-discovery, and query tests.
SET NOCOUNT ON;
SET XACT_ABORT ON;

DROP VIEW IF EXISTS dbo.customer_order_summary;
DROP TABLE IF EXISTS dbo.order_items;
DROP TABLE IF EXISTS dbo.inventory_log;
DROP TABLE IF EXISTS dbo.orders;
DROP TABLE IF EXISTS dbo.products;
DROP TABLE IF EXISTS dbo.customers;
DROP TABLE IF EXISTS dbo.categories;

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
