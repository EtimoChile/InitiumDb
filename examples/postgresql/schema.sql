-- Example CRM model for the case in testdata/regression. Run it ONLY on a disposable
-- development database, as a user allowed to create the crm_owner objects.
\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS crm_owner CASCADE;
CREATE SCHEMA crm_owner;

CREATE TABLE crm_owner.customer (
  customer_id integer      NOT NULL PRIMARY KEY,
  name        varchar(100) NOT NULL,
  status      varchar(10)  NOT NULL
);

CREATE TABLE crm_owner.account (
  account_id  integer NOT NULL PRIMARY KEY,
  customer_id integer NOT NULL REFERENCES crm_owner.customer (customer_id),
  balance     numeric(14,2)
);
