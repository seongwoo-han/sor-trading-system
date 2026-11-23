-- Initialize the same order book as MarketBooks.depthExample() for a fresh demo database.
INSERT INTO sim_market_quote (market, ask_price, ask_quantity)
VALUES ('A', 1000, 2), ('B', 990, 3)
ON DUPLICATE KEY UPDATE ask_price = sim_market_quote.ask_price;

INSERT INTO sim_market_level
    (market, level_no, ask_price, ask_quantity, bid_price, bid_quantity)
VALUES
    ('A', 1, 1000, 2, 970, 2),
    ('A', 2, 1020, 4, 950, 4),
    ('A', 3, 1040, 4, 930, 4),
    ('B', 1, 990, 3, 980, 3),
    ('B', 2, 1010, 3, 960, 3),
    ('B', 3, 1030, 4, 940, 4)
ON DUPLICATE KEY UPDATE ask_price = sim_market_level.ask_price;
