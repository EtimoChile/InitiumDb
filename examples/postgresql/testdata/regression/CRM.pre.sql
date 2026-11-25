-- Optional cleanup run by idb-prepare before the REFRESH. REFRESH never deletes rows.
DELETE FROM crm_owner.account WHERE customer_id IN (1001, 1002);
DELETE FROM crm_owner.customer WHERE customer_id IN (1001, 1002);
