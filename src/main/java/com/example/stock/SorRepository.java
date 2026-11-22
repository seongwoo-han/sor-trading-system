package com.example.stock;

import static com.example.stock.StockAccountSession.PRESENTER_ACCOUNT_ID;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
@DependsOn("sorSchemaMigration")
public class SorRepository {
    private final JdbcTemplate jdbc;
    public SorRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    int insertPracticeAccount(long initialCash) {
        // The caller holds the shared book revision lock while allocating an ID.
        int id = Math.incrementExact(jdbc.queryForObject("SELECT COALESCE(MAX(id), 1) FROM account", Integer.class));
        requireOne(jdbc.update("INSERT INTO account (id, cash, quantity) VALUES (?, ?, 0)", id, initialCash),
                "모의계좌를 만들지 못했습니다.");
        return id;
    }

    void insertAccessCode(int accountId, byte[] hash) {
        jdbc.update("INSERT INTO account_access (account_id, code_hash) VALUES (?, ?)", accountId, hash);
    }

    void replaceAccessCode(int accountId, byte[] hash) {
        if (jdbc.update("UPDATE account_access SET code_hash = ? WHERE account_id = ?", hash, accountId) == 0) {
            insertAccessCode(accountId, hash);
        }
    }

    Integer findAccountByAccessCode(byte[] hash) {
        return jdbc.query("""
                SELECT a.id FROM account_access c JOIN account a ON a.id = c.account_id
                WHERE c.code_hash = ? AND a.trading_closed = FALSE
                """, rows -> rows.next() ? rows.getInt(1) : null, hash);
    }

    List<Integer> getActiveGuestAccountIds() {
        return jdbc.queryForList("""
                SELECT id FROM account WHERE id > ? AND trading_closed = FALSE
                AND NOT EXISTS (SELECT 1 FROM account_access WHERE account_id = account.id) ORDER BY id
                """, Integer.class, PRESENTER_ACCOUNT_ID);
    }

    boolean closeGuestAccount(int accountId) {
        return jdbc.update("""
                UPDATE account SET trading_closed = TRUE WHERE id = ? AND id > ? AND trading_closed = FALSE
                AND NOT EXISTS (SELECT 1 FROM account_access WHERE account_id = account.id)
                """, accountId, PRESENTER_ACCOUNT_ID) == 1;
    }

    Instant sorConsentAt(int accountId, int version) {
        LocalDateTime agreedAt = jdbc.queryForObject("""
                SELECT CASE WHEN sor_consent_version = ? THEN sor_consented_at END
                FROM account WHERE id = ?
                """, LocalDateTime.class, version, accountId);
        return agreedAt == null ? null : agreedAt.toInstant(ZoneOffset.UTC);
    }

    void recordSorConsent(int accountId, int version) {
        jdbc.update("""
                UPDATE account SET sor_consent_version = ?, sor_consented_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND (sor_consent_version <> ? OR sor_consented_at IS NULL)
                """, version, accountId, version);
    }

    public MarketSimulator.AccountSnapshot getAccount(int accountId, boolean forUpdate) {
        var accounts = jdbc.query("SELECT id, cash, reserved_cash, quantity, reserved_quantity, trading_closed FROM account WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : ""), (rs, rowNum) -> {
            if (forUpdate && rs.getBoolean("trading_closed")) {
                throw new MarketSimulator.OrderConflictException("체험이 종료된 계좌입니다. 현재 계좌를 확인해 주세요.");
            }
            return snapshot(rs.getInt("id"), rs.getLong("cash"), rs.getLong("reserved_cash"),
                    rs.getInt("quantity"), rs.getInt("reserved_quantity"));
        }, accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("계좌가 없습니다. 초기 데이터 설정을 확인해 주세요.");
        }
        return accounts.get(0);
    }

    public MarketSimulator.AccountSnapshot positionSnapshot(MarketSimulator.AccountSnapshot account, StockSymbol symbol) {
        if (symbol == StockSymbol.VIRTUAL) return account;
        var position = jdbc.query("""
                SELECT quantity, reserved_quantity FROM account_position WHERE account_id = ? AND symbol = ?
                """, (rs, row) -> snapshot(account.accountId(), account.cash(), account.reservedCash(),
                rs.getInt("quantity"), rs.getInt("reserved_quantity")), account.accountId(), symbol.code());
        return position.isEmpty() ? snapshot(account.accountId(), account.cash(), account.reservedCash(), 0, 0)
                : position.getFirst();
    }

    public MarketSimulator.AccountSnapshot getAccount(int accountId, StockSymbol symbol, boolean forUpdate) {
        return positionSnapshot(getAccount(accountId, forUpdate), symbol);
    }

    private MarketSimulator.AccountSnapshot snapshot(int id, long cash, long reserved, int quantity, int reservedShares) {
        return new MarketSimulator.AccountSnapshot(id, cash, reserved, cash - reserved, quantity, reservedShares, quantity - reservedShares);
    }

    public List<PortfolioValuation.Trade> getCurrentPositionTrades(int accountId, StockSymbol symbol, int currentQuantity) {
        if (currentQuantity == 0) return List.of();
        return jdbc.query("""
                SELECT side, price, quantity, fee FROM trade_history WHERE account_id = ? AND symbol = ? ORDER BY id DESC
                """, rows -> {
            // Find when the current position started by rolling the share count back to zero.
            var trades = new ArrayDeque<PortfolioValuation.Trade>();
            long quantity = currentQuantity;
            while (rows.next()) {
                boolean buy = rows.getString("side").equals("BUY");
                int filled = rows.getInt("quantity");
                trades.addFirst(new PortfolioValuation.Trade(buy, rows.getLong("price"), filled, rows.getLong("fee")));
                quantity += buy ? -filled : filled;
                if (quantity == 0) return List.copyOf(trades);
                if (quantity < 0 || quantity > Integer.MAX_VALUE) break;
            }
            // Some pre-existing positions may have been seeded without trade history.
            return List.of();
        }, accountId, symbol.code());
    }

    public List<SorService.MarketQuote> getQuotes(StockSymbol symbol) {
        return jdbc.query("""
                SELECT market, level_no, ask_price, ask_quantity, bid_price, bid_quantity FROM sim_market_level
                WHERE symbol = ? AND market IN ('A', 'B') ORDER BY market, level_no
                """, (rs, rowNum) ->
                new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"),
                        rs.getLong("ask_price"), rs.getInt("ask_quantity"), rs.getLong("bid_price"), rs.getInt("bid_quantity")), symbol.code());
    }

    public MarketSimulator.BookSnapshot getBook(StockSymbol symbol) {
        // One statement gives a consistent version and quote snapshot.
        var rows = jdbc.query("""
                SELECT b.revision, q.market, q.level_no, q.ask_price, q.ask_quantity, q.bid_price, q.bid_quantity
                FROM sim_book_state b CROSS JOIN sim_market_level q
                WHERE b.id = ? AND q.symbol = ? AND q.market IN ('A', 'B') ORDER BY q.market, q.level_no
                """, (rs, rowNum) -> new BookRow(rs.getLong("revision"),
                new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"), rs.getLong("ask_price"),
                        rs.getInt("ask_quantity"), rs.getLong("bid_price"), rs.getInt("bid_quantity"))), symbol.bookId(), symbol.code());
        if (rows.isEmpty() && !symbol.isActive()) throw new IllegalArgumentException("이전 종목의 호가 기록이 없습니다.");
        if (rows.size() != 6) throw new IllegalStateException("모의시장 3단계 초기 데이터가 필요합니다.");
        return new MarketSimulator.BookSnapshot(rows.getFirst().revision(), rows.stream().map(BookRow::quote).toList(), symbol.code());
    }

    public void lockTradingState() {
        // All symbols share account cash. Keep one write lock ahead of account and book updates.
        jdbc.queryForObject("SELECT revision FROM sim_book_state WHERE id = 1 FOR UPDATE", Long.class);
    }

    private void incrementBookRevision(StockSymbol symbol) {
        requireOne(jdbc.update("""
                UPDATE sim_book_state SET revision = revision + 1
                WHERE id = ? AND revision < 9223372036854775807
                """, symbol.bookId()), "시장 상태 번호를 변경하지 못했습니다.");
    }

    public void setQuotes(StockSymbol symbol, List<SorService.MarketQuote> quotes) {
        for (var quote : quotes) {
            String storageCode = MarketVenue.from(quote.market()).storageCode();
            // MySQL may report 0 affected rows when the submitted values are unchanged.
            jdbc.update("""
                    UPDATE sim_market_level SET ask_price = ?, ask_quantity = ?, bid_price = ?, bid_quantity = ?
                    WHERE market = ? AND level_no = ? AND symbol = ?
                    """, quote.askPrice(), quote.askQuantity(), quote.bidPrice(), quote.bidQuantity(), storageCode, quote.level(), symbol.code());
            if (symbol == StockSymbol.VIRTUAL && quote.level() == 1) jdbc.update("UPDATE sim_market_quote SET ask_price = ?, ask_quantity = ? WHERE market = ?",
                    quote.askPrice(), quote.askQuantity(), storageCode);
        }
        incrementBookRevision(symbol);
    }

    public Map<String, Long> getMarketVolume(StockSymbol symbol) {
        var volume = new LinkedHashMap<String, Long>();
        for (String venue : MarketVenue.NAMES) volume.put(venue, 0L);
        jdbc.query("""
                SELECT e.market, SUM(e.quantity) AS volume FROM sor_execution e
                JOIN sor_order o ON o.id = e.order_id WHERE o.symbol = ? GROUP BY e.market
                """, rows -> {
            volume.put(MarketVenue.from(rows.getString("market")).name(), rows.getLong("volume"));
        }, symbol.code());
        return Map.copyOf(volume);
    }

    public MarketSimulator.OrderResult findOrder(int accountId, String clientOrderId) {
        var results = jdbc.query("SELECT * FROM sor_order WHERE account_id = ? AND client_order_id = ?",
                orderMapper(), accountId, clientOrderId);
        return results.isEmpty() ? null : withExecutions(results.get(0));
    }

    public MarketSimulator.OrderResult getOrder(int accountId, long orderId) {
        var results = jdbc.query("SELECT * FROM sor_order WHERE account_id = ? AND id = ?",
                orderMapper(), accountId, orderId);
        if (results.isEmpty()) throw new MarketSimulator.OrderNotFoundException();
        return withExecutions(results.get(0));
    }

    public List<MarketSimulator.OrderResult> getOrders(int accountId, StockSymbol symbol) {
        String sql = "SELECT * FROM sor_order WHERE account_id = ?";
        String limit = " ORDER BY id DESC LIMIT 100";
        var orders = symbol == null ? jdbc.query(sql + limit, orderMapper(), accountId)
                : jdbc.query(sql + " AND symbol = ?" + limit, orderMapper(), accountId, symbol.code());
        return orders.stream().map(this::withExecutions).toList();
    }

    public boolean hasArchivedHistory(int accountId, StockSymbol symbol) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sor_order WHERE account_id = ? AND symbol = ?)
                    OR EXISTS (SELECT 1 FROM trade_history WHERE account_id = ? AND symbol = ?)
                """, Boolean.class, accountId, symbol.code(), accountId, symbol.code()));
    }

    private static final String WORKING_ORDERS = """
            SELECT * FROM sor_order WHERE reservation_enabled = TRUE
            AND order_type = 'LIMIT' AND remaining_quantity > 0 AND status IN ('OPEN', 'PARTIALLY_FILLED')
            """;

    public List<MarketSimulator.OrderResult> getOpenOrders(int accountId, StockSymbol symbol) {
        String sql = WORKING_ORDERS + " AND account_id = ?";
        var orders = symbol == null ? jdbc.query(sql + " ORDER BY id", orderMapper(), accountId)
                : jdbc.query(sql + " AND symbol = ? ORDER BY id", orderMapper(), accountId, symbol.code());
        return orders.stream().map(this::withExecutions).toList();
    }

    List<MarketSimulator.OrderResult> getOrdersToMatch(StockSymbol symbol) {
        return jdbc.query(WORKING_ORDERS + " AND symbol = ? ORDER BY id", orderMapper(), symbol.code());
    }

    public long insertOrder(int accountId, String clientOrderId, String side, Long limitPrice, int quantity,
                            long reserved, int reservedShares, MarketSimulator.AccountSnapshot account, Long originalOrderId,
                            RoutingPolicy routingPolicy, StockSymbol symbol) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sor_order
                    (account_id, client_order_id, side, order_type, status, limit_price,
                     requested_quantity, filled_quantity, remaining_quantity, cancelled_quantity,
                     total_amount, reserved_cash, reserved_quantity, reservation_enabled, fees_enabled,
                     cash_after, reserved_cash_after, quantity_after, reserved_quantity_after, original_order_id, routing_policy, symbol)
                    VALUES (?, ?, ?, ?, 'OPEN', ?, ?, 0, ?, 0, 0, ?, ?, TRUE, TRUE, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setInt(1, accountId);
            statement.setString(2, clientOrderId);
            statement.setString(3, side);
            statement.setString(4, limitPrice == null ? "MARKET" : "LIMIT");
            statement.setObject(5, limitPrice, Types.BIGINT);
            statement.setInt(6, quantity);
            statement.setInt(7, quantity);
            statement.setLong(8, reserved);
            statement.setInt(9, reservedShares);
            statement.setLong(10, account.cash());
            statement.setLong(11, account.reservedCash());
            statement.setInt(12, account.quantity());
            statement.setInt(13, account.reservedQuantity());
            statement.setObject(14, originalOrderId, Types.BIGINT);
            statement.setString(15, routingPolicy.name());
            statement.setString(16, symbol.code());
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("주문 번호를 생성하지 못했습니다.");
        return key.getKey().longValue();
    }

    public void reserveCash(int accountId, long amount) {
        requireOne(jdbc.update("""
                UPDATE account SET reserved_cash = reserved_cash + ?
                WHERE id = ? AND cash - reserved_cash >= ?
                """, amount, accountId, amount), "예약금을 설정하지 못했습니다.");
    }

    public void releaseCash(int accountId, long amount) {
        requireOne(jdbc.update("""
                UPDATE account SET reserved_cash = reserved_cash - ?
                WHERE id = ? AND reserved_cash >= ?
                """, amount, accountId, amount), "예약금을 해제하지 못했습니다.");
    }

    public void applyFill(int accountId, StockSymbol symbol, long amount, int quantity, long releasedReserve) {
        int legacyQuantity = symbol == StockSymbol.VIRTUAL ? quantity : 0;
        requireOne(jdbc.update("""
                UPDATE account
                SET cash = cash - ?, reserved_cash = reserved_cash - ?, quantity = quantity + ?
                WHERE id = ? AND cash >= ? AND reserved_cash >= ?
                  AND cash - ? >= reserved_cash - ?
                """, amount, releasedReserve, legacyQuantity, accountId, amount, releasedReserve,
                amount, releasedReserve), "체결을 계좌에 반영하지 못했습니다.");
        if (symbol != StockSymbol.VIRTUAL) {
            jdbc.update("""
                    INSERT INTO account_position (account_id, symbol, quantity) VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE quantity = account_position.quantity + ?
                    """, accountId, symbol.code(), quantity, quantity);
        }
    }

    public void reserveShares(int accountId, StockSymbol symbol, int quantity) {
        int updated = symbol == StockSymbol.VIRTUAL
                ? jdbc.update("UPDATE account SET reserved_quantity = reserved_quantity + ? WHERE id = ? AND quantity - reserved_quantity >= ?",
                        quantity, accountId, quantity)
                : jdbc.update("""
                        UPDATE account_position SET reserved_quantity = reserved_quantity + ?
                        WHERE account_id = ? AND symbol = ? AND quantity - reserved_quantity >= ?
                        """, quantity, accountId, symbol.code(), quantity);
        requireOne(updated, "매도 수량을 예약하지 못했습니다.");
    }

    public void releaseShares(int accountId, StockSymbol symbol, int quantity) {
        int updated = symbol == StockSymbol.VIRTUAL
                ? jdbc.update("UPDATE account SET reserved_quantity = reserved_quantity - ? WHERE id = ? AND reserved_quantity >= ?",
                        quantity, accountId, quantity)
                : jdbc.update("""
                        UPDATE account_position SET reserved_quantity = reserved_quantity - ?
                        WHERE account_id = ? AND symbol = ? AND reserved_quantity >= ?
                        """, quantity, accountId, symbol.code(), quantity);
        requireOne(updated, "매도 수량 예약을 해제하지 못했습니다.");
    }

    public void applySellFill(int accountId, StockSymbol symbol, long amount, int quantity) {
        int legacyQuantity = symbol == StockSymbol.VIRTUAL ? quantity : 0;
        requireOne(jdbc.update("""
                UPDATE account SET cash = cash + ?, quantity = quantity - ?, reserved_quantity = reserved_quantity - ?
                WHERE id = ? AND quantity >= ? AND reserved_quantity >= ? AND cash <= 9223372036854775807 - ?
                """, amount, legacyQuantity, legacyQuantity, accountId, legacyQuantity, legacyQuantity, amount),
                "매도 체결을 계좌에 반영하지 못했습니다.");
        if (symbol != StockSymbol.VIRTUAL) {
            requireOne(jdbc.update("""
                    UPDATE account_position SET quantity = quantity - ?, reserved_quantity = reserved_quantity - ?
                    WHERE account_id = ? AND symbol = ? AND quantity >= ? AND reserved_quantity >= ?
                    """, quantity, quantity, accountId, symbol.code(), quantity, quantity),
                    "매도 체결 수량을 종목 보유 내역에 반영하지 못했습니다.");
        }
    }

    public void recordFill(MarketSimulator.OrderResult order, SorService.RoutePlan plan, long cashReleased,
                           MarketSimulator.AccountSnapshot account) {
        int remaining = order.remainingQuantity() - plan.allocatedQuantity();
        int autoCancelled = order.orderType().equals("MARKET") ? remaining : 0;
        String status = remaining == 0 ? "FILLED" : autoCancelled > 0 ? "CANCELLED" : "PARTIALLY_FILLED";
        requireOne(jdbc.update("""
                UPDATE sor_order SET status = ?, filled_quantity = ?, remaining_quantity = ?,
                    cancelled_quantity = cancelled_quantity + ?,
                    total_amount = ?, reserved_cash = ?, reserved_quantity = ?, cash_after = ?, reserved_cash_after = ?,
                    quantity_after = ?, reserved_quantity_after = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_id = ? AND remaining_quantity = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED') AND reservation_enabled = TRUE
                """, status, order.filledQuantity() + plan.allocatedQuantity(), remaining - autoCancelled, autoCancelled,
                Math.addExact(order.totalAmount(), plan.estimatedAmount()), order.reservedCash() - cashReleased,
                order.side().equals("SELL") ? order.reservedQuantity() - plan.allocatedQuantity() : 0,
                account.cash(), account.reservedCash(), account.quantity(), account.reservedQuantity(),
                order.orderId(), account.accountId(), order.remainingQuantity()),
                "주문 체결 상태를 변경하지 못했습니다.");
    }

    public void closeOrder(MarketSimulator.OrderResult order, int amendedQuantity, MarketSimulator.AccountSnapshot account) {
        String status = amendedQuantity > 0 ? "AMENDED" : "CANCELLED";
        requireOne(jdbc.update("""
                UPDATE sor_order SET status = ?, remaining_quantity = 0,
                    cancelled_quantity = cancelled_quantity + ?, amended_quantity = ?, reserved_cash = 0, reserved_quantity = 0,
                    cash_after = ?, reserved_cash_after = ?, quantity_after = ?, reserved_quantity_after = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_id = ? AND remaining_quantity = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED') AND reservation_enabled = TRUE
                """, status, order.remainingQuantity() - amendedQuantity, amendedQuantity,
                account.cash(), account.reservedCash(), account.quantity(), account.reservedQuantity(),
                order.orderId(), account.accountId(), order.remainingQuantity()),
                "미체결 주문의 종료 상태를 변경하지 못했습니다.");
    }

    public void consumeQuote(StockSymbol symbol, String market, int level, int quantity) {
        String storageCode = MarketVenue.from(market).storageCode();
        requireOne(jdbc.update("""
                UPDATE sim_market_level SET ask_quantity = ask_quantity - ?
                WHERE market = ? AND level_no = ? AND symbol = ? AND ask_quantity >= ?
                """, quantity, storageCode, level, symbol.code(), quantity), "모의시장 잔량을 변경하지 못했습니다.");
        if (symbol == StockSymbol.VIRTUAL && level == 1) requireOne(jdbc.update("""
                UPDATE sim_market_quote SET ask_quantity = ask_quantity - ? WHERE market = ? AND ask_quantity >= ?
                """, quantity, storageCode, quantity), "기존 1호가 잔량을 동기화하지 못했습니다.");
        incrementBookRevision(symbol);
    }

    public void consumeBidQuote(StockSymbol symbol, String market, int level, int quantity) {
        requireOne(jdbc.update("""
                UPDATE sim_market_level SET bid_quantity = bid_quantity - ?
                WHERE market = ? AND level_no = ? AND symbol = ? AND bid_quantity >= ?
                """, quantity, MarketVenue.from(market).storageCode(), level, symbol.code(), quantity), "매수호가 잔량을 변경하지 못했습니다.");
        incrementBookRevision(symbol);
    }

    public long insertTrade(int accountId, StockSymbol symbol, String side, SorService.Allocation allocation) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trade_history (account_id, side, price, quantity, symbol, fee) VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setInt(1, accountId);
            statement.setString(2, side);
            statement.setLong(3, allocation.price());
            statement.setInt(4, allocation.quantity());
            statement.setString(5, symbol.code());
            statement.setLong(6, allocation.fee());
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("거래 번호를 생성하지 못했습니다.");
        return key.getKey().longValue();
    }

    public long insertExecution(long orderId, long tradeId, SorService.Allocation allocation) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sor_execution (order_id, market, price, quantity, amount, trade_history_id, quote_level)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setLong(1, orderId);
            statement.setString(2, MarketVenue.from(allocation.market()).storageCode());
            statement.setLong(3, allocation.price());
            statement.setInt(4, allocation.quantity());
            statement.setLong(5, allocation.amount());
            statement.setLong(6, tradeId);
            statement.setInt(7, allocation.level());
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("체결 번호를 생성하지 못했습니다.");
        return key.getKey().longValue();
    }

    public long insertQuoteEvent(int accountId, String clientEventId, long expectedRevision,
                                  List<SorService.MarketQuote> quotes,
                                  MarketSimulator.BookSnapshot book,
                                  MarketSimulator.AccountSnapshot account) {
        var a = quotes.get(0);
        var b = quotes.get(3);
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sim_quote_event
                    (account_id, client_event_id, expected_revision, a_price, a_quantity_input,
                     b_price, b_quantity_input, revision_after, a_quantity_after, b_quantity_after,
                     cash_after, reserved_cash_after, quantity_after, reserved_quantity_after, symbol)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setInt(1, accountId);
            statement.setString(2, clientEventId);
            statement.setLong(3, expectedRevision);
            statement.setLong(4, a.askPrice());
            statement.setInt(5, a.askQuantity());
            statement.setLong(6, b.askPrice());
            statement.setInt(7, b.askQuantity());
            statement.setLong(8, book.revision());
            statement.setInt(9, a.askQuantity());
            statement.setInt(10, b.askQuantity());
            statement.setLong(11, account.cash());
            statement.setLong(12, account.reservedCash());
            statement.setInt(13, account.quantity());
            statement.setInt(14, account.reservedQuantity());
            statement.setString(15, book.symbol());
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("호가 반영 번호를 생성하지 못했습니다.");
        long eventId = key.getKey().longValue();
        for (var quote : quotes) {
            var applied = book.quotes().stream().filter(q -> q.market().equals(quote.market()) && q.level() == quote.level()).findFirst().orElseThrow();
            jdbc.update("""
                INSERT INTO sim_quote_event_level (event_id, market, level_no, ask_price, quantity_input, quantity_after,
                    bid_price_input, bid_quantity_input, bid_price_after, bid_quantity_after)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, MarketVenue.from(quote.market()).storageCode(), quote.level(), quote.askPrice(), quote.askQuantity(), quote.askQuantity(),
                quote.bidPrice(), quote.bidQuantity(), applied.bidPrice(), applied.bidQuantity());
        }
        return eventId;
    }

    public void linkQuoteEventExecution(long eventId, long executionId) {
        jdbc.update("INSERT INTO sim_quote_event_execution (event_id, execution_id) VALUES (?, ?)", eventId, executionId);
    }

    public void finishQuoteEvent(int accountId, long eventId, MarketSimulator.BookSnapshot book,
                                 MarketSimulator.AccountSnapshot account) {
        requireOne(jdbc.update("""
                UPDATE sim_quote_event SET revision_after = ?, a_quantity_after = ?, b_quantity_after = ?,
                    cash_after = ?, reserved_cash_after = ?, quantity_after = ?, reserved_quantity_after = ?, finalized = TRUE
                WHERE id = ? AND account_id = ? AND finalized = FALSE
                """, book.revision(), book.quotes().get(0).askQuantity(), book.quotes().get(3).askQuantity(),
                account.cash(), account.reservedCash(), account.quantity(), account.reservedQuantity(), eventId, accountId),
                "호가 반영 결과를 저장하지 못했습니다.");
        for (var quote : book.quotes()) {
            jdbc.update("""
                    UPDATE sim_quote_event_level SET quantity_after = ?, bid_price_after = ?, bid_quantity_after = ?
                    WHERE event_id = ? AND market = ? AND level_no = ?
                    """, quote.askQuantity(), quote.bidPrice(), quote.bidQuantity(), eventId,
                    MarketVenue.from(quote.market()).storageCode(), quote.level());
        }
    }

    public MarketSimulator.QuoteUpdateResult findQuoteEvent(int accountId, String clientEventId) {
        var rows = jdbc.query("""
                SELECT * FROM sim_quote_event
                WHERE account_id = ? AND client_event_id = ? AND finalized = TRUE
                """, quoteEventMapper(), accountId, clientEventId);
        return rows.isEmpty() ? null : withEventExecutions(rows.get(0));
    }

    public List<MarketSimulator.QuoteUpdateResult> getQuoteEvents(int accountId, StockSymbol symbol) {
        return jdbc.query("""
                SELECT * FROM sim_quote_event WHERE account_id = ? AND symbol = ? AND finalized = TRUE
                ORDER BY id DESC LIMIT 20
                """, quoteEventMapper(), accountId, symbol.code()).stream().map(this::withEventExecutions).toList();
    }

    private RowMapper<MarketSimulator.QuoteUpdateResult> quoteEventMapper() {
        return (rs, rowNum) -> new MarketSimulator.QuoteUpdateResult(
                rs.getLong("id"), rs.getString("client_event_id"), rs.getLong("expected_revision"),
                new MarketSimulator.BookSnapshot(rs.getLong("revision_after"), List.of(
                        new SorService.MarketQuote("KRX", rs.getLong("a_price"), rs.getInt("a_quantity_after")),
                        new SorService.MarketQuote("NXT", rs.getLong("b_price"), rs.getInt("b_quantity_after"))), rs.getString("symbol")),
                List.of(new SorService.MarketQuote("KRX", rs.getLong("a_price"), rs.getInt("a_quantity_input")),
                        new SorService.MarketQuote("NXT", rs.getLong("b_price"), rs.getInt("b_quantity_input"))),
                0, 0, List.of(), snapshot(rs.getInt("account_id"), rs.getLong("cash_after"),
                        rs.getLong("reserved_cash_after"), rs.getInt("quantity_after"), rs.getInt("reserved_quantity_after")),
                rs.getTimestamp("created_at").toLocalDateTime().toString());
    }

    private MarketSimulator.QuoteUpdateResult withEventExecutions(MarketSimulator.QuoteUpdateResult event) {
        var executions = jdbc.query("""
                SELECT e.order_id, o.side, e.market, e.quote_level, e.price, e.quantity, e.amount, t.fee
                FROM sim_quote_event_execution l JOIN sor_execution e ON e.id = l.execution_id
                JOIN sor_order o ON o.id = e.order_id
                JOIN trade_history t ON t.id = e.trade_history_id
                WHERE l.event_id = ? ORDER BY e.id
                """, (rs, rowNum) -> new MarketSimulator.QuoteEventExecution(rs.getLong("order_id"),
                rs.getString("side"), MarketVenue.from(rs.getString("market")).name(),
                rs.getInt("quote_level"), rs.getLong("price"), rs.getInt("quantity"), rs.getLong("amount"), rs.getLong("fee")), event.eventId());
        var inputs = jdbc.query("""
                SELECT market, level_no, ask_price, quantity_input, bid_price_input, bid_quantity_input FROM sim_quote_event_level
                WHERE event_id = ? ORDER BY market, level_no
                """, (rs, n) -> new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"),
                rs.getLong("ask_price"), rs.getInt("quantity_input"), rs.getLong("bid_price_input"), rs.getInt("bid_quantity_input")), event.eventId());
        var after = jdbc.query("""
                SELECT market, level_no, ask_price, quantity_after, bid_price_after, bid_quantity_after FROM sim_quote_event_level
                WHERE event_id = ? ORDER BY market, level_no
                """, (rs, n) -> new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"),
                rs.getLong("ask_price"), rs.getInt("quantity_after"), rs.getLong("bid_price_after"), rs.getInt("bid_quantity_after")), event.eventId());
        if (!inputs.isEmpty() && (inputs.size() != 6 || after.size() != 6)) {
            throw new IllegalStateException("호가 반영 영수증의 단계별 기록을 확인해 주세요.");
        }
        return new MarketSimulator.QuoteUpdateResult(event.eventId(), event.clientEventId(), event.expectedRevision(),
                inputs.isEmpty() ? event.bookAfter() : new MarketSimulator.BookSnapshot(event.bookAfter().revision(), after, event.bookAfter().symbol()),
                inputs.isEmpty() ? event.submittedQuotes() : inputs,
                executions.stream().mapToLong(MarketSimulator.QuoteEventExecution::quantity).sum(),
                executions.stream().mapToLong(MarketSimulator.QuoteEventExecution::amount).sum(),
                List.copyOf(executions), event.accountAfter(), event.createdAt());
    }

    private record BookRow(long revision, SorService.MarketQuote quote) {}

    private void requireOne(int count, String message) {
        if (count != 1) throw new IllegalStateException(message);
    }

    private RowMapper<MarketSimulator.OrderResult> orderMapper() {
        return (rs, rowNum) -> {
            String status = rs.getString("status");
            boolean cancellable = rs.getBoolean("reservation_enabled") && rs.getInt("remaining_quantity") > 0
                    && (status.equals("OPEN") || status.equals("PARTIALLY_FILLED"));
            return new MarketSimulator.OrderResult(
                    rs.getLong("id"), rs.getString("client_order_id"), rs.getString("side"), rs.getString("order_type"), status,
                    rs.getObject("limit_price", Long.class), rs.getInt("requested_quantity"),
                    rs.getInt("filled_quantity"), rs.getInt("remaining_quantity"),
                    rs.getInt("cancelled_quantity"), rs.getLong("reserved_cash"), rs.getInt("reserved_quantity"), cancellable,
                    rs.getLong("total_amount"), List.of(),
                    snapshot(rs.getInt("account_id"), rs.getLong("cash_after"),
                            rs.getLong("reserved_cash_after"), rs.getInt("quantity_after"), rs.getInt("reserved_quantity_after")),
                    rs.getTimestamp("created_at").toLocalDateTime().toString(),
                    rs.getTimestamp("updated_at").toLocalDateTime().toString(),
                    rs.getObject("original_order_id", Long.class), rs.getInt("amended_quantity"), null,
                    RoutingPolicy.valueOf(rs.getString("routing_policy")), rs.getString("symbol"), rs.getBoolean("fees_enabled"));
        };
    }

    private MarketSimulator.OrderResult withExecutions(MarketSimulator.OrderResult order) {
        var executions = jdbc.query("""
                SELECT e.market, e.quote_level, e.price, e.quantity, e.amount, t.fee
                FROM sor_execution e JOIN trade_history t ON t.id = e.trade_history_id
                WHERE e.order_id = ? ORDER BY e.id
                """, (rs, rowNum) -> new MarketSimulator.Execution(MarketVenue.from(rs.getString("market")).name(),
                rs.getInt("quote_level"), rs.getLong("price"), rs.getInt("quantity"), rs.getLong("amount"), rs.getLong("fee")), order.orderId());
        Long replacementOrderId = "AMENDED".equals(order.status())
                ? jdbc.queryForObject("SELECT id FROM sor_order WHERE original_order_id = ? AND account_id = ?",
                    Long.class, order.orderId(), order.accountAfter().accountId())
                : null;
        return new MarketSimulator.OrderResult(order.orderId(), order.clientOrderId(), order.side(),
                order.orderType(), order.status(), order.limitPrice(), order.requestedQuantity(), order.filledQuantity(),
                order.remainingQuantity(), order.cancelledQuantity(), order.reservedCash(), order.reservedQuantity(), order.cancellable(),
                order.totalAmount(), List.copyOf(executions), order.accountAfter(), order.createdAt(), order.updatedAt(),
                order.originalOrderId(), order.amendedQuantity(), replacementOrderId, order.routingPolicy(), order.symbol(), order.feesEnabled());
    }
}

