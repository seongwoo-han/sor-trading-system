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
        addColumn(schema, "sor_order", "cancelled_quantity", "INT NOT NULL DEFAULT 0");
        addColumn(schema, "sor_order", "reserved_cash", "BIGINT NOT NULL DEFAULT 0");
        // 기존 주문의 미체결분을 소급해서 대기시키거나 예약하지 않습니다.
        addColumn(schema, "sor_order", "reservation_enabled", "BOOLEAN NOT NULL DEFAULT FALSE");
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
        addCheck(schema, "sor_order", "sor_order_status_v4",
                "status IN ('OPEN', 'FILLED', 'PARTIALLY_FILLED', 'UNFILLED', 'CANCELLED')");
        addCheck(schema, "sor_order", "sor_order_quantities_v4",
                "filled_quantity >= 0 AND remaining_quantity >= 0 AND cancelled_quantity >= 0 "
                + "AND filled_quantity + remaining_quantity + cancelled_quantity = requested_quantity");
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
                    AND NOT EXISTS (SELECT 1 FROM sim_market_level l WHERE l.market = q.market AND l.level_no = ?)
                    """, level, level, level);
        }
        // 기존 매도호가·잔량은 그대로 두고, 새 매수호가는 빈 물량으로만 초기화합니다.
        for (String market : new String[]{"A", "B"}) {
            long base = jdbc.queryForObject("SELECT ask_price FROM sim_market_level WHERE market = ? AND level_no = 1", Long.class, market);
            long gap = Math.max(1, base / 1000);
            for (int level = 1; level <= 3; level++) jdbc.update("""
                    UPDATE sim_market_level SET bid_price = ? WHERE market = ? AND level_no = ? AND bid_price = 0 AND bid_quantity = 0
                    """, Math.max(1, base - gap * level), market, level);
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

    private boolean hasCheck(String schema, String table, String name) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE LOWER(table_schema) = LOWER(?)
                  AND LOWER(table_name) = ? AND LOWER(constraint_name) = ?
                """, Integer.class, schema, table, name) > 0;
    }

    private void dropOldCheck(String schema, boolean mysql, String table, String name) {
        if (hasCheck(schema, table, name)) {
            jdbc.execute("ALTER TABLE " + table + (mysql ? " DROP CHECK " : " DROP CONSTRAINT ") + name);
        }
    }

    private void addCheck(String schema, String table, String name, String condition) {
        if (!hasCheck(schema, table, name)) {
            jdbc.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + name + " CHECK (" + condition + ")");
        }
    }
}
