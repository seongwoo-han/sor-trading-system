-- Create the demo account only if it does not already exist.
-- Existing cash and holdings are never overwritten by this initialization.
INSERT INTO account (id, cash, quantity)
SELECT 1, 10000000, 0
WHERE NOT EXISTS (SELECT 1 FROM account WHERE id = 1);

-- Preserve the latest market quantities when the application restarts.
INSERT INTO sim_market_quote (market, ask_price, ask_quantity)
SELECT 'A', 70000, 10
WHERE NOT EXISTS (SELECT 1 FROM sim_market_quote WHERE market = 'A');

INSERT INTO sim_market_quote (market, ask_price, ask_quantity)
SELECT 'B', 69900, 4
WHERE NOT EXISTS (SELECT 1 FROM sim_market_quote WHERE market = 'B');

INSERT INTO sim_book_state (id, revision)
SELECT 1, 0 WHERE NOT EXISTS (SELECT 1 FROM sim_book_state WHERE id = 1);
