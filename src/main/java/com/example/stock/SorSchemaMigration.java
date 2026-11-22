package com.example.stock;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 기존 계좌·주문·호가를 보존하면서 필요한 열·제약과 단계별 호가 테이블을 추가합니다. */
@Component("sorSchemaMigration")
@DependsOnDatabaseInitialization
public class SorSchemaMigration implements InitializingBean {
    private final JdbcTemplate jdbc;

    public SorSchemaMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void afterPropertiesSet() throws SQLException {
        String schema;
        boolean mysql;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            mysql = connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(Locale.ROOT).contains("mysql");
            schema = mysql ? connection.getCatalog() : connection.getSchema();
        }
        addColumn(schema, "account", "reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        addColumn(schema, "account", "reserved_quantity", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "account", "trading_closed", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumn(schema, "account", "sor_consent_version", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "account", "sor_consented_at", "DATETIME(6) NULL");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account_access (
                    account_id INT NOT NULL PRIMARY KEY,
                    code_hash BINARY(32) NOT NULL UNIQUE,
                    CONSTRAINT account_access_account_fk FOREIGN KEY (account_id) REFERENCES account (id)
                ) ENGINE = InnoDB
                """);
        addColumn(schema, "sor_order", "order_type", "VARCHAR(8) NOT NULL DEFAULT 'LIMIT'");
        addColumn(schema, "sor_order", "routing_policy", "VARCHAR(16) NOT NULL DEFAULT 'PRICE_FIRST'");
        addCheck(schema, "sor_order", "sor_order_routing_policy_valid",
                "routing_policy IN ('PRICE_FIRST', 'QUANTITY_FIRST')");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_price_positive");
        int requiredLimitPrice = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'sor_order'
                  AND LOWER(column_name) = 'limit_price' AND is_nullable = 'NO'
                """, Integer.class, schema);
        if (requiredLimitPrice > 0) {
            jdbc.execute(mysql ? "ALTER TABLE sor_order MODIFY COLUMN limit_price BIGINT NULL"
                    : "ALTER TABLE sor_order ALTER COLUMN limit_price DROP NOT NULL");
        }
        addCheck(schema, "sor_order", "sor_order_type_price_valid",
                "(order_type = 'LIMIT' AND limit_price IS NOT NULL AND limit_price > 0) "
                + "OR (order_type = 'MARKET' AND limit_price IS NULL)");
        addColumn(schema, "sor_order", "cancelled_quantity", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "amended_quantity", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "original_order_id", "BIGINT NULL");
        addConstraint(schema, "sor_order", "sor_order_amendment_source_unique", "UNIQUE (original_order_id)");
        addConstraint(schema, "sor_order", "sor_order_amendment_source_fk",
                "FOREIGN KEY (original_order_id) REFERENCES sor_order (id)");
        addColumn(schema, "sor_order", "reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        // 기존 주문의 미체결분을 소급해서 대기시키거나 예약하지 않습니다.
        addColumn(schema, "sor_order", "reservation_enabled", "BOOLEAN NOT NULL DEFAULT FALSE");
        // 기존 주문은 접수 당시 수수료와 예약금을 유지합니다.
        addColumn(schema, "sor_order", "fees_enabled", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumn(schema, "trade_history", "fee", "BIGINT NOT NULL DEFAULT 0");
        addCheck(schema, "trade_history", "trade_history_fee_nonnegative", "fee >= 0");
        addColumn(schema, "sor_order", "reserved_cash_after", "BIGINT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "reserved_quantity", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "reserved_quantity_after", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sim_quote_event", "reserved_quantity_after", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "updated_at",
                "DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_status_valid");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_quantities_valid");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_side_valid");
        addCheck(schema, "sor_order", "sor_order_side_both_valid", "side IN ('BUY', 'SELL')");
        addCheck(schema, "account", "sor_account_reserved_valid",
                "reserved_cash >= 0 AND reserved_cash <= cash");
        addCheck(schema, "sor_order", "sor_order_status_v5",
                "status IN ('OPEN', 'FILLED', 'PARTIALLY_FILLED', 'UNFILLED', 'CANCELLED', 'AMENDED')");
        addCheck(schema, "sor_order", "sor_order_quantities_v5",
                "filled_quantity >= 0 AND remaining_quantity >= 0 AND cancelled_quantity >= 0 "
                + "AND filled_quantity + remaining_quantity + cancelled_quantity + amended_quantity = requested_quantity "
                + "AND ((status = 'AMENDED' AND amended_quantity > 0 AND remaining_quantity = 0) "
                + "OR (status <> 'AMENDED' AND amended_quantity = 0))");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_status_v4");
        dropOldCheck(schema, mysql, "sor_order", "sor_order_quantities_v4");
        addCheck(schema, "sor_order", "sor_order_reserved_v4", "reserved_cash >= 0");
        addCheck(schema, "sor_order", "sor_order_reserved_after_v4",
                "reserved_cash_after >= 0 AND reserved_cash_after <= cash_after");
        addCheck(schema, "account", "sor_account_shares_valid", "reserved_quantity >= 0 AND reserved_quantity <= quantity");
        addCheck(schema, "sor_order", "sor_order_shares_valid", "reserved_quantity >= 0 AND reserved_quantity_after >= 0 AND reserved_quantity_after <= quantity_after");
        addCheck(schema, "sor_order", "sor_order_asset_side_valid", "(side = 'BUY' AND reserved_quantity = 0) OR (side = 'SELL' AND reserved_cash = 0)");
        addColumn(schema, "sor_execution", "quote_level", "INT NOT NULL DEFAULT 1");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sim_market_level (
                    market VARCHAR(8) NOT NULL,
                    level_no INT NOT NULL,
                    ask_price BIGINT NOT NULL,
                    ask_quantity INT NOT NULL,
                    PRIMARY KEY (market, level_no),
                    CONSTRAINT sim_level_market_fk FOREIGN KEY (market) REFERENCES sim_market_quote (market),
                    CONSTRAINT sim_level_number_valid CHECK (level_no BETWEEN 1 AND 3),
                    CONSTRAINT sim_level_price_positive CHECK (ask_price > 0),
                    CONSTRAINT sim_level_quantity_nonnegative CHECK (ask_quantity >= 0)
                ) ENGINE = InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sim_quote_event_level (
                    event_id BIGINT NOT NULL,
                    market VARCHAR(8) NOT NULL,
                    level_no INT NOT NULL,
                    ask_price BIGINT NOT NULL,
                    quantity_input INT NOT NULL,
                    quantity_after INT NOT NULL,
                    PRIMARY KEY (event_id, market, level_no),
                    CONSTRAINT sim_event_level_event_fk FOREIGN KEY (event_id) REFERENCES sim_quote_event (id),
                    CONSTRAINT sim_event_level_market_fk FOREIGN KEY (market) REFERENCES sim_market_quote (market),
                    CONSTRAINT sim_event_level_number_valid CHECK (level_no BETWEEN 1 AND 3),
                    CONSTRAINT sim_event_level_price_valid CHECK (ask_price > 0),
                    CONSTRAINT sim_event_level_quantity_valid CHECK (
                        quantity_input >= 0 AND quantity_after >= 0 AND quantity_after <= quantity_input)
                ) ENGINE = InnoDB
                """);
        addColumn(schema, "sim_market_level", "bid_price", "BIGINT NOT NULL DEFAULT 0");
        addColumn(schema, "sim_market_level", "bid_quantity", "INT NOT NULL DEFAULT 0");
        initializeSymbolStorage(schema);
        for (String column : new String[]{"bid_price_input", "bid_price_after"})
            addColumn(schema, "sim_quote_event_level", column, "BIGINT NOT NULL DEFAULT 0");
        for (String column : new String[]{"bid_quantity_input", "bid_quantity_after"})
            addColumn(schema, "sim_quote_event_level", column, "INT NOT NULL DEFAULT 0");
        addCheck(schema, "sim_market_level", "sim_level_bid_valid", "bid_price >= 0 AND bid_quantity >= 0 AND (bid_price > 0 OR bid_quantity = 0)");
        // 첫 업그레이드: 기존 1호가·잔량을 복사하고 새 2·3호가는 0주로 시작합니다.
        // 이후 재시작에서는 이미 있는 호가·주문·계좌·영수증을 덮어쓰지 않습니다.
        for (int level = 1; level <= 3; level++) {
            jdbc.update("""
                    INSERT INTO sim_market_level (market, level_no, ask_price, ask_quantity)
                    SELECT q.market, ?, q.ask_price, CASE WHEN ? = 1 THEN q.ask_quantity ELSE 0 END
                    FROM sim_market_quote q WHERE q.market IN ('A', 'B')
                    AND NOT EXISTS (SELECT 1 FROM sim_market_level l
                        WHERE l.market = q.market AND l.level_no = ? AND l.symbol = ?)
                    """, level, level, level, StockSymbol.VIRTUAL.code());
        }
        // 새 호가와 이전 버전의 미사용 기본 호가에만 초기 매수 물량을 넣습니다.
        boolean untouched = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sim_book_state WHERE id = 1 AND revision = 0)", Boolean.class));
        for (var seed : MarketBooks.basicExample().stream().filter(quote -> quote.level() == 1).toList()) {
            String storageCode = MarketVenue.from(seed.market()).storageCode();
            long base = jdbc.queryForObject("SELECT ask_price FROM sim_market_level WHERE market = ? AND level_no = 1 AND symbol = ?",
                    Long.class, storageCode, StockSymbol.VIRTUAL.code());
            long initialBid = Math.max(1, base - (seed.askPrice() - seed.bidPrice()));
            long previousGap = Math.max(1, base / 1000);
            for (int level = 1; level <= 3; level++) {
                jdbc.update("""
                        UPDATE sim_market_level SET bid_price = ?, bid_quantity = ?
                        WHERE market = ? AND level_no = ? AND symbol = ? AND bid_quantity = 0
                          AND (bid_price = 0 OR (? AND bid_price = ?))
                        """, initialBid, level == 1 ? seed.bidQuantity() : 0, storageCode, level, StockSymbol.VIRTUAL.code(), untouched,
                        Math.max(1, base - previousGap * level));
            }
        }
        seedAddedBooks();
    }

    private void initializeSymbolStorage(String schema) {
        for (String table : new String[]{"sor_order", "trade_history", "sim_quote_event", "sim_market_level"}) {
            addColumn(schema, table, "symbol", "VARCHAR(16) NOT NULL DEFAULT 'VIRTUAL01'");
        }
        boolean keyIncludesSymbol = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.key_column_usage
                WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'sim_market_level'
                  AND LOWER(constraint_name) = 'primary' AND LOWER(column_name) = 'symbol'
                """, Integer.class, schema) > 0;
        if (!keyIncludesSymbol) {
            // Keep an index for the venue reference throughout the primary key change.
            boolean venueIndexExists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.statistics
                    WHERE LOWER(table_schema) = LOWER(?) AND LOWER(table_name) = 'sim_market_level'
                      AND LOWER(index_name) = 'sim_level_venue_index'
                    """, Integer.class, schema) > 0;
            if (!venueIndexExists) jdbc.execute("CREATE INDEX sim_level_venue_index ON sim_market_level (market)");
            jdbc.execute("ALTER TABLE sim_market_level DROP PRIMARY KEY, ADD PRIMARY KEY (market, level_no, symbol)");
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS account_position (
                    account_id INT NOT NULL,
                    symbol VARCHAR(16) NOT NULL,
                    quantity INT NOT NULL DEFAULT 0,
                    reserved_quantity INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (account_id, symbol),
                    CONSTRAINT account_position_account_fk FOREIGN KEY (account_id) REFERENCES account (id),
                    CONSTRAINT account_position_shares_valid CHECK (reserved_quantity >= 0 AND quantity >= reserved_quantity)
                ) ENGINE = InnoDB
                """);
    }

    private void seedAddedBooks() {
        for (StockSymbol symbol : StockSymbol.active()) {
            jdbc.update("""
                    INSERT INTO sim_book_state (id, revision)
                    SELECT ?, 0 WHERE NOT EXISTS (SELECT 1 FROM sim_book_state WHERE id = ?)
                    """, symbol.bookId(), symbol.bookId());
            for (var quote : MarketBooks.depthExample(symbol)) {
                String venue = MarketVenue.from(quote.market()).storageCode();
                jdbc.update("""
                        INSERT INTO sim_market_level (market, level_no, symbol, ask_price, ask_quantity, bid_price, bid_quantity)
                        SELECT ?, ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (
                            SELECT 1 FROM sim_market_level WHERE market = ? AND level_no = ? AND symbol = ?)
                        """, venue, quote.level(), symbol.code(), quote.askPrice(), quote.askQuantity(),
                        quote.bidPrice(), quote.bidQuantity(), venue, quote.level(), symbol.code());
            }
        }
    }

    private void addColumn(String schema, String table, String column, String definition) {
        int count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE LOWER(table_schema) = LOWER(?)
                  AND LOWER(table_name) = ? AND LOWER(column_name) = ?
                """, Integer.class, schema, table, column);
        if (count == 0) jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private boolean hasConstraint(String schema, String table, String name) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE LOWER(table_schema) = LOWER(?)
                  AND LOWER(table_name) = ? AND LOWER(constraint_name) = ?
                """, Integer.class, schema, table, name) > 0;
    }

    private void dropOldCheck(String schema, boolean mysql, String table, String name) {
        if (hasConstraint(schema, table, name)) {
            jdbc.execute("ALTER TABLE " + table + (mysql ? " DROP CHECK " : " DROP CONSTRAINT ") + name);
        }
    }

    private void addCheck(String schema, String table, String name, String condition) {
        addConstraint(schema, table, name, "CHECK (" + condition + ")");
    }

    private void addConstraint(String schema, String table, String name, String definition) {
        if (!hasConstraint(schema, table, name)) {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + name + " " + definition);
        }
    }
}
