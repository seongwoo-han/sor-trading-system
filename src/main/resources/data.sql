-- Create account 1 on the first run.
-- If it already exists, keep its existing cash and quantity.
INSERT INTO account (id, cash, quantity)
VALUES (1, 100000, 0)
ON DUPLICATE KEY UPDATE id = 1;
