--
-- PostgreSQL database dump
--


-- Dumped from database version 17.6
-- Dumped by pg_dump version 17.12 (Debian 17.12-0+deb13u1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: sales; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA sales;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: accounts; Type: TABLE; Schema: sales; Owner: -
--

CREATE TABLE sales.accounts (
    id integer NOT NULL,
    name text NOT NULL,
    region_id integer NOT NULL,
    segment text NOT NULL,
    created_on date NOT NULL
);


--
-- Name: order_items; Type: TABLE; Schema: sales; Owner: -
--

CREATE TABLE sales.order_items (
    id integer NOT NULL,
    order_id integer NOT NULL,
    product_id integer NOT NULL,
    quantity integer NOT NULL,
    line_total numeric(12,2) NOT NULL
);


--
-- Name: orders; Type: TABLE; Schema: sales; Owner: -
--

CREATE TABLE sales.orders (
    id integer NOT NULL,
    account_id integer NOT NULL,
    order_date date NOT NULL,
    status text NOT NULL,
    channel text NOT NULL
);


--
-- Name: products; Type: TABLE; Schema: sales; Owner: -
--

CREATE TABLE sales.products (
    id integer NOT NULL,
    name text NOT NULL,
    category text NOT NULL,
    unit_price numeric(10,2) NOT NULL
);


--
-- Name: regions; Type: TABLE; Schema: sales; Owner: -
--

CREATE TABLE sales.regions (
    id integer NOT NULL,
    name text NOT NULL
);


--
-- Name: accounts accounts_pkey; Type: CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.accounts
    ADD CONSTRAINT accounts_pkey PRIMARY KEY (id);


--
-- Name: order_items order_items_pkey; Type: CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);


--
-- Name: orders orders_pkey; Type: CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);


--
-- Name: products products_pkey; Type: CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);


--
-- Name: regions regions_pkey; Type: CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.regions
    ADD CONSTRAINT regions_pkey PRIMARY KEY (id);


--
-- Name: accounts_region_id_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX accounts_region_id_idx ON sales.accounts USING btree (region_id);


--
-- Name: order_items_order_id_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX order_items_order_id_idx ON sales.order_items USING btree (order_id);


--
-- Name: order_items_product_id_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX order_items_product_id_idx ON sales.order_items USING btree (product_id);


--
-- Name: orders_account_id_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX orders_account_id_idx ON sales.orders USING btree (account_id);


--
-- Name: orders_order_date_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX orders_order_date_idx ON sales.orders USING btree (order_date);


--
-- Name: orders_status_idx; Type: INDEX; Schema: sales; Owner: -
--

CREATE INDEX orders_status_idx ON sales.orders USING btree (status);


--
-- Name: accounts accounts_region_id_fkey; Type: FK CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.accounts
    ADD CONSTRAINT accounts_region_id_fkey FOREIGN KEY (region_id) REFERENCES sales.regions(id);


--
-- Name: order_items order_items_order_id_fkey; Type: FK CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.order_items
    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES sales.orders(id);


--
-- Name: order_items order_items_product_id_fkey; Type: FK CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.order_items
    ADD CONSTRAINT order_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES sales.products(id);


--
-- Name: orders orders_account_id_fkey; Type: FK CONSTRAINT; Schema: sales; Owner: -
--

ALTER TABLE ONLY sales.orders
    ADD CONSTRAINT orders_account_id_fkey FOREIGN KEY (account_id) REFERENCES sales.accounts(id);


--
-- PostgreSQL database dump complete
--


