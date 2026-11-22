-- Preserve existing account balances, holdings and successful trades.
CREATE TABLE IF NOT EXISTS account (
    id INT NOT NULL PRIMARY KEY,
    cash BIGINT NOT NULL,
    quantity INT NOT NULL,
    sor_consent_version INT NOT NULL DEFAULT 0,
    sor_consented_at DATETIME(6) NULL,
    CONSTRAINT account_cash_nonnegative CHECK (cash >= 0),
    CONSTRAINT account_quantity_nonnegative CHECK (quantity >= 0)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS trade_history (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id INT NOT NULL,
    symbol VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL01',
    side VARCHAR(4) NOT NULL,
    price BIGINT NOT NULL,
    quantity INT NOT NULL,
    fee BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX trade_history_account_index (account_id, id),
    CONSTRAINT trade_history_account_fk FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT trade_history_side_valid CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT trade_history_price_positive CHECK (price > 0),
    CONSTRAINT trade_history_quantity_positive CHECK (quantity > 0),
    CONSTRAINT trade_history_fee_nonnegative CHECK (fee >= 0)
) ENGINE = InnoDB;
