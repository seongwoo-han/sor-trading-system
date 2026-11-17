-- Existing account and trade_history tables are preserved.
CREATE TABLE IF NOT EXISTS sim_market_quote (
    market VARCHAR(8) NOT NULL PRIMARY KEY,
    ask_price BIGINT NOT NULL,
    ask_quantity INT NOT NULL,
    CONSTRAINT sim_quote_price_positive CHECK (ask_price > 0),
    CONSTRAINT sim_quote_quantity_nonnegative CHECK (ask_quantity >= 0)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sor_order (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id INT NOT NULL,
    client_order_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    side VARCHAR(4) NOT NULL,
    status VARCHAR(24) NOT NULL,
    limit_price BIGINT NOT NULL,
    requested_quantity INT NOT NULL,
    filled_quantity INT NOT NULL,
    remaining_quantity INT NOT NULL,
    cancelled_quantity INT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL,
    reserved_cash BIGINT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    reservation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    cash_after BIGINT NOT NULL,
    reserved_cash_after BIGINT NOT NULL DEFAULT 0,
    reserved_quantity_after INT NOT NULL DEFAULT 0,
    quantity_after INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY sor_order_request_unique (account_id, client_order_id),
    CONSTRAINT sor_order_account_fk FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT sor_order_side_both_valid CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT sor_order_status_v4 CHECK (status IN ('OPEN', 'FILLED', 'PARTIALLY_FILLED', 'UNFILLED', 'CANCELLED')),
    CONSTRAINT sor_order_price_positive CHECK (limit_price > 0),
    CONSTRAINT sor_order_requested_positive CHECK (requested_quantity > 0),
    CONSTRAINT sor_order_quantities_v4 CHECK (
        filled_quantity >= 0 AND remaining_quantity >= 0 AND cancelled_quantity >= 0
        AND filled_quantity + remaining_quantity + cancelled_quantity = requested_quantity
    ),
    CONSTRAINT sor_order_reserved_v4 CHECK (reserved_cash >= 0),
    CONSTRAINT sor_order_reserved_after_v4 CHECK (reserved_cash_after >= 0 AND reserved_cash_after <= cash_after),
    CONSTRAINT sor_order_amount_nonnegative CHECK (total_amount >= 0),
    CONSTRAINT sor_order_cash_after_nonnegative CHECK (cash_after >= 0),
    CONSTRAINT sor_order_quantity_after_nonnegative CHECK (quantity_after >= 0)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sor_execution (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    market VARCHAR(8) NOT NULL,
    quote_level INT NOT NULL DEFAULT 1,
    price BIGINT NOT NULL,
    quantity INT NOT NULL,
    amount BIGINT NOT NULL,
    trade_history_id BIGINT NOT NULL,
    CONSTRAINT sor_execution_order_fk FOREIGN KEY (order_id) REFERENCES sor_order (id),
    CONSTRAINT sor_execution_market_fk FOREIGN KEY (market) REFERENCES sim_market_quote (market),
    CONSTRAINT sor_execution_trade_fk FOREIGN KEY (trade_history_id) REFERENCES trade_history (id),
    CONSTRAINT sor_execution_price_positive CHECK (price > 0),
    CONSTRAINT sor_execution_quantity_positive CHECK (quantity > 0),
    CONSTRAINT sor_execution_amount_positive CHECK (amount > 0)
) ENGINE = InnoDB;

-- Depth rows are seeded once by SorSchemaMigration after database initialization.
CREATE TABLE IF NOT EXISTS sim_market_level (
    market VARCHAR(8) NOT NULL,
    level_no INT NOT NULL,
    ask_price BIGINT NOT NULL,
    ask_quantity INT NOT NULL,
    bid_price BIGINT NOT NULL DEFAULT 0,
    bid_quantity INT NOT NULL DEFAULT 0,
    PRIMARY KEY (market, level_no),
    CONSTRAINT sim_level_market_fk FOREIGN KEY (market) REFERENCES sim_market_quote (market),
    CONSTRAINT sim_level_number_valid CHECK (level_no BETWEEN 1 AND 3),
    CONSTRAINT sim_level_price_positive CHECK (ask_price > 0),
    CONSTRAINT sim_level_quantity_nonnegative CHECK (ask_quantity >= 0)
) ENGINE = InnoDB;

-- A market revision changes whenever prices/quantities are changed or consumed.
CREATE TABLE IF NOT EXISTS sim_book_state (
    id INT NOT NULL PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT sim_book_revision_valid CHECK (revision >= 0)
) ENGINE = InnoDB;

-- Immutable receipt for one market update and the executions it caused.
CREATE TABLE IF NOT EXISTS sim_quote_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id INT NOT NULL,
    client_event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_revision BIGINT NOT NULL,
    a_price BIGINT NOT NULL,
    a_quantity_input INT NOT NULL,
    b_price BIGINT NOT NULL,
    b_quantity_input INT NOT NULL,
    revision_after BIGINT NOT NULL,
    a_quantity_after INT NOT NULL,
    b_quantity_after INT NOT NULL,
    cash_after BIGINT NOT NULL,
    reserved_cash_after BIGINT NOT NULL,
    reserved_quantity_after INT NOT NULL DEFAULT 0,
    quantity_after INT NOT NULL,
    finalized BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY sim_quote_event_request_unique (account_id, client_event_id),
    CONSTRAINT sim_quote_event_account_fk FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT sim_quote_event_revision_valid CHECK (expected_revision >= 0 AND revision_after > expected_revision),
    CONSTRAINT sim_quote_event_prices_valid CHECK (a_price > 0 AND b_price > 0),
    CONSTRAINT sim_quote_event_quantities_valid CHECK (
        a_quantity_input >= 0 AND b_quantity_input >= 0
        AND a_quantity_after >= 0 AND a_quantity_after <= a_quantity_input
        AND b_quantity_after >= 0 AND b_quantity_after <= b_quantity_input
    ),
    CONSTRAINT sim_quote_event_account_valid CHECK (
        cash_after >= 0 AND reserved_cash_after >= 0 AND reserved_cash_after <= cash_after AND quantity_after >= 0
    )
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS sim_quote_event_execution (
    event_id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    PRIMARY KEY (event_id, execution_id),
    UNIQUE KEY sim_quote_event_execution_unique (execution_id),
    CONSTRAINT sim_quote_event_execution_event_fk FOREIGN KEY (event_id) REFERENCES sim_quote_event (id),
    CONSTRAINT sim_quote_event_execution_fill_fk FOREIGN KEY (execution_id) REFERENCES sor_execution (id)
) ENGINE = InnoDB;

-- New events keep all six input/remaining levels; old receipts remain unchanged.
CREATE TABLE IF NOT EXISTS sim_quote_event_level (
    event_id BIGINT NOT NULL,
    market VARCHAR(8) NOT NULL,
    level_no INT NOT NULL,
    ask_price BIGINT NOT NULL,
    quantity_input INT NOT NULL,
    quantity_after INT NOT NULL,
    bid_price_input BIGINT NOT NULL DEFAULT 0,
    bid_quantity_input INT NOT NULL DEFAULT 0,
    bid_price_after BIGINT NOT NULL DEFAULT 0,
    bid_quantity_after INT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, market, level_no),
    CONSTRAINT sim_event_level_event_fk FOREIGN KEY (event_id) REFERENCES sim_quote_event (id),
    CONSTRAINT sim_event_level_market_fk FOREIGN KEY (market) REFERENCES sim_market_quote (market),
    CONSTRAINT sim_event_level_number_valid CHECK (level_no BETWEEN 1 AND 3),
    CONSTRAINT sim_event_level_price_valid CHECK (ask_price > 0),
    CONSTRAINT sim_event_level_quantity_valid CHECK (
        quantity_input >= 0 AND quantity_after >= 0 AND quantity_after <= quantity_input)
) ENGINE = InnoDB;
