package com.example.stock;

import java.sql.PreparedStatement;
import java.util.List;
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

    public MarketSimulator.AccountSnapshot getAccount(int accountId, boolean forUpdate) {
        var accounts = jdbc.query("SELECT id, cash, reserved_cash, quantity, reserved_quantity FROM account WHERE id = ?"
                + (forUpdate ? " FOR UPDATE" : ""), (rs, rowNum) -> snapshot(rs.getInt("id"),
                rs.getLong("cash"), rs.getLong("reserved_cash"), rs.getInt("quantity"), rs.getInt("reserved_quantity")), accountId);
        if (accounts.isEmpty()) {
            throw new IllegalArgumentException("계좌가 없습니다. 초기 데이터 설정을 확인해 주세요.");
        }
        return accounts.get(0);
    }

    private MarketSimulator.AccountSnapshot snapshot(int id, long cash, long reserved, int quantity, int reservedShares) {
        return new MarketSimulator.AccountSnapshot(id, cash, reserved, cash - reserved, quantity, reservedShares, quantity - reservedShares);
    }

    public List<SorService.MarketQuote> getQuotes(boolean forUpdate) {
        return jdbc.query("""
                SELECT market, level_no, ask_price, ask_quantity, bid_price, bid_quantity FROM sim_market_level
                WHERE market IN ('A', 'B') ORDER BY market, level_no
                """ + (forUpdate ? " FOR UPDATE" : ""), (rs, rowNum) ->
                new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"),
                        rs.getLong("ask_price"), rs.getInt("ask_quantity"), rs.getLong("bid_price"), rs.getInt("bid_quantity")));
    }

    public MarketSimulator.BookSnapshot getBook() {
        // One statement gives a consistent version and quote snapshot.
        var rows = jdbc.query("""
                SELECT b.revision, q.market, q.level_no, q.ask_price, q.ask_quantity, q.bid_price, q.bid_quantity
                FROM sim_book_state b CROSS JOIN sim_market_level q
                WHERE b.id = 1 AND q.market IN ('A', 'B') ORDER BY q.market, q.level_no
                """, (rs, rowNum) -> new BookRow(rs.getLong("revision"),
                new SorService.MarketQuote(rs.getString("market"), rs.getInt("level_no"), rs.getLong("ask_price"), rs.getInt("ask_quantity"), rs.getLong("bid_price"), rs.getInt("bid_quantity"))));
        if (rows.size() != 6) throw new IllegalStateException("모의시장 3단계 초기 데이터가 필요합니다.");
        return new MarketSimulator.BookSnapshot(rows.get(0).revision(), rows.stream().map(BookRow::quote).toList());
    }

    public long lockBookRevision() {
        return jdbc.queryForObject("SELECT revision FROM sim_book_state WHERE id = 1 FOR UPDATE", Long.class);
    }

    public void incrementBookRevision() {
        requireOne(jdbc.update("""
                UPDATE sim_book_state SET revision = revision + 1
                WHERE id = 1 AND revision < 9223372036854775807
                """), "시장 상태 번호를 변경하지 못했습니다.");
    }

    public void setQuotes(List<SorService.MarketQuote> quotes) {
        for (var quote : quotes) {
            // MySQL may report 0 affected rows when the submitted values are unchanged.
            jdbc.update("UPDATE sim_market_level SET ask_price = ?, ask_quantity = ?, bid_price = ?, bid_quantity = ? WHERE market = ? AND level_no = ?",
                    quote.askPrice(), quote.askQuantity(), quote.bidPrice(), quote.bidQuantity(), quote.market(), quote.level());
            if (quote.level() == 1) jdbc.update("UPDATE sim_market_quote SET ask_price = ?, ask_quantity = ? WHERE market = ?",
                    quote.askPrice(), quote.askQuantity(), quote.market());
        }
        incrementBookRevision();
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

    public List<MarketSimulator.OrderResult> getOrders(int accountId) {
        return jdbc.query("SELECT * FROM sor_order WHERE account_id = ? ORDER BY id DESC LIMIT 100",
                orderMapper(), accountId).stream().map(this::withExecutions).toList();
    }

    public List<MarketSimulator.OrderResult> getOpenOrders(int accountId) {
        return jdbc.query("""
                SELECT * FROM sor_order WHERE account_id = ? AND reservation_enabled = TRUE
                AND remaining_quantity > 0 AND status IN ('OPEN', 'PARTIALLY_FILLED')
                ORDER BY id
                """, orderMapper(), accountId).stream().map(this::withExecutions).toList();
    }

    public long insertOrder(int accountId, String clientOrderId, String side, long limitPrice, int quantity,
                            long reserved, int reservedShares, MarketSimulator.AccountSnapshot account) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sor_order
                    (account_id, client_order_id, side, status, limit_price,
                     requested_quantity, filled_quantity, remaining_quantity, cancelled_quantity,
                     total_amount, reserved_cash, reserved_quantity, reservation_enabled,
                     cash_after, reserved_cash_after, quantity_after, reserved_quantity_after)
                    VALUES (?, ?, ?, 'OPEN', ?, ?, 0, ?, 0, 0, ?, ?, TRUE, ?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setInt(1, accountId);
            statement.setString(2, clientOrderId);
            statement.setString(3, side);
            statement.setLong(4, limitPrice);
            statement.setInt(5, quantity);
            statement.setInt(6, quantity);
            statement.setLong(7, reserved);
            statement.setInt(8, reservedShares);
            statement.setLong(9, account.cash());
            statement.setLong(10, account.reservedCash());
            statement.setInt(11, account.quantity());
            statement.setInt(12, account.reservedQuantity());
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

    public void applyFill(int accountId, long amount, int quantity, long releasedReserve) {
        requireOne(jdbc.update("""
                UPDATE account
                SET cash = cash - ?, reserved_cash = reserved_cash - ?, quantity = quantity + ?
                WHERE id = ? AND cash >= ? AND reserved_cash >= ?
                  AND cash - ? >= reserved_cash - ?
                """, amount, releasedReserve, quantity, accountId, amount, releasedReserve,
                amount, releasedReserve), "체결을 계좌에 반영하지 못했습니다.");
    }

    public void reserveShares(int accountId, int quantity) {
        requireOne(jdbc.update("UPDATE account SET reserved_quantity = reserved_quantity + ? WHERE id = ? AND quantity - reserved_quantity >= ?",
                quantity, accountId, quantity), "매도 수량을 예약하지 못했습니다.");
    }

    public void releaseShares(int accountId, int quantity) {
        requireOne(jdbc.update("UPDATE account SET reserved_quantity = reserved_quantity - ? WHERE id = ? AND reserved_quantity >= ?",
                quantity, accountId, quantity), "매도 수량 예약을 해제하지 못했습니다.");
    }

    public void applySellFill(int accountId, long amount, int quantity) {
        requireOne(jdbc.update("""
                UPDATE account SET cash = cash + ?, quantity = quantity - ?, reserved_quantity = reserved_quantity - ?
                WHERE id = ? AND quantity >= ? AND reserved_quantity >= ? AND cash <= 9223372036854775807 - ?
                """, amount, quantity, quantity, accountId, quantity, quantity, amount), "매도 체결을 계좌에 반영하지 못했습니다.");
    }

    public void recordFill(MarketSimulator.OrderResult order, SorService.RoutePlan plan,
                           MarketSimulator.AccountSnapshot account) {
        int remaining = order.remainingQuantity() - plan.allocatedQuantity();
        boolean buy = order.side().equals("BUY");
        long reserved = buy ? Math.multiplyExact(order.limitPrice(), (long) remaining) : 0;
        requireOne(jdbc.update("""
                UPDATE sor_order SET status = ?, filled_quantity = ?, remaining_quantity = ?,
                    total_amount = ?, reserved_cash = ?, reserved_quantity = ?, cash_after = ?, reserved_cash_after = ?,
                    quantity_after = ?, reserved_quantity_after = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_id = ? AND remaining_quantity = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED') AND reservation_enabled = TRUE
                """, remaining == 0 ? "FILLED" : "PARTIALLY_FILLED",
                order.filledQuantity() + plan.allocatedQuantity(), remaining,
                Math.addExact(order.totalAmount(), plan.estimatedAmount()), reserved, buy ? 0 : remaining,
                account.cash(), account.reservedCash(), account.quantity(), account.reservedQuantity(),
                order.orderId(), account.accountId(), order.remainingQuantity()),
                "주문 체결 상태를 변경하지 못했습니다.");
    }

    public void cancelOrder(MarketSimulator.OrderResult order, MarketSimulator.AccountSnapshot account) {
        requireOne(jdbc.update("""
                UPDATE sor_order SET status = 'CANCELLED', remaining_quantity = 0,
                    cancelled_quantity = cancelled_quantity + ?, reserved_cash = 0, reserved_quantity = 0,
                    cash_after = ?, reserved_cash_after = ?, quantity_after = ?, reserved_quantity_after = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND account_id = ? AND remaining_quantity = ?
                  AND status IN ('OPEN', 'PARTIALLY_FILLED') AND reservation_enabled = TRUE
                """, order.remainingQuantity(), account.cash(), account.reservedCash(), account.quantity(), account.reservedQuantity(),
                order.orderId(), account.accountId(), order.remainingQuantity()),
                "주문 취소 상태를 변경하지 못했습니다.");
    }

    public void consumeQuote(String market, int level, int quantity) {
        requireOne(jdbc.update("""
                UPDATE sim_market_level SET ask_quantity = ask_quantity - ?
                WHERE market = ? AND level_no = ? AND ask_quantity >= ?
                """, quantity, market, level, quantity), "모의시장 잔량을 변경하지 못했습니다.");
        if (level == 1) requireOne(jdbc.update("""
                UPDATE sim_market_quote SET ask_quantity = ask_quantity - ? WHERE market = ? AND ask_quantity >= ?
                """, quantity, market, quantity), "기존 1호가 잔량을 동기화하지 못했습니다.");
        incrementBookRevision();
    }

    public void consumeBidQuote(String market, int level, int quantity) {
        requireOne(jdbc.update("""
                UPDATE sim_market_level SET bid_quantity = bid_quantity - ?
                WHERE market = ? AND level_no = ? AND bid_quantity >= ?
                """, quantity, market, level, quantity), "매수호가 잔량을 변경하지 못했습니다.");
        incrementBookRevision();
    }

    public long insertTrade(int accountId, String side, long price, int quantity) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trade_history (account_id, side, price, quantity) VALUES (?, ?, ?, ?)
                    """, new String[]{"id"});
            statement.setInt(1, accountId);
            statement.setString(2, side);
            statement.setLong(3, price);
            statement.setInt(4, quantity);
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
            statement.setString(2, allocation.market());
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

    public void legacyBuy(int accountId, long price) {
        requireOne(jdbc.update("""
                UPDATE account SET cash = cash - ?, quantity = quantity + 1
                WHERE id = ? AND cash - reserved_cash >= ?
                """, price, accountId, price), "기존 매수를 계좌에 반영하지 못했습니다.");
    }

    public void legacySell(int accountId, long price) {
        requireOne(jdbc.update("""
                UPDATE account SET cash = cash + ?, quantity = quantity - 1
                WHERE id = ? AND quantity - reserved_quantity >= 1
                """, price, accountId), "기존 매도를 계좌에 반영하지 못했습니다.");
    }

    public void resetQuotes() {
        setQuotes(MarketBooks.basicExample());
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
                     cash_after, reserved_cash_after, quantity_after, reserved_quantity_after)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                """, eventId, quote.market(), quote.level(), quote.askPrice(), quote.askQuantity(), quote.askQuantity(),
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
                    """, quote.askQuantity(), quote.bidPrice(), quote.bidQuantity(), eventId, quote.market(), quote.level());
        }
    }

    public MarketSimulator.QuoteUpdateResult findQuoteEvent(int accountId, String clientEventId) {
        var rows = jdbc.query("""
                SELECT * FROM sim_quote_event
                WHERE account_id = ? AND client_event_id = ? AND finalized = TRUE
                """, quoteEventMapper(), accountId, clientEventId);
        return rows.isEmpty() ? null : withEventExecutions(rows.get(0));
    }

    public List<MarketSimulator.QuoteUpdateResult> getQuoteEvents(int accountId) {
        return jdbc.query("""
                SELECT * FROM sim_quote_event WHERE account_id = ? AND finalized = TRUE
                ORDER BY id DESC LIMIT 20
                """, quoteEventMapper(), accountId).stream().map(this::withEventExecutions).toList();
    }

    private RowMapper<MarketSimulator.QuoteUpdateResult> quoteEventMapper() {
        return (rs, rowNum) -> new MarketSimulator.QuoteUpdateResult(
                rs.getLong("id"), rs.getString("client_event_id"), rs.getLong("expected_revision"),
                new MarketSimulator.BookSnapshot(rs.getLong("revision_after"), List.of(
                        new SorService.MarketQuote("A", rs.getLong("a_price"), rs.getInt("a_quantity_after")),
                        new SorService.MarketQuote("B", rs.getLong("b_price"), rs.getInt("b_quantity_after")))),
                List.of(new SorService.MarketQuote("A", rs.getLong("a_price"), rs.getInt("a_quantity_input")),
                        new SorService.MarketQuote("B", rs.getLong("b_price"), rs.getInt("b_quantity_input"))),
                0, 0, List.of(), snapshot(rs.getInt("account_id"), rs.getLong("cash_after"),
                        rs.getLong("reserved_cash_after"), rs.getInt("quantity_after"), rs.getInt("reserved_quantity_after")),
                rs.getTimestamp("created_at").toLocalDateTime().toString());
    }

    private MarketSimulator.QuoteUpdateResult withEventExecutions(MarketSimulator.QuoteUpdateResult event) {
        var executions = jdbc.query("""
                SELECT e.order_id, o.side, e.market, e.quote_level, e.price, e.quantity, e.amount
                FROM sim_quote_event_execution l JOIN sor_execution e ON e.id = l.execution_id
                JOIN sor_order o ON o.id = e.order_id
                WHERE l.event_id = ? ORDER BY e.id
                """, (rs, rowNum) -> new MarketSimulator.QuoteEventExecution(rs.getLong("order_id"),
                rs.getString("side"), rs.getString("market"), rs.getInt("quote_level"), rs.getLong("price"), rs.getInt("quantity"), rs.getLong("amount")), event.eventId());
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
                inputs.isEmpty() ? event.bookAfter() : new MarketSimulator.BookSnapshot(event.bookAfter().revision(), after),
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
                    rs.getLong("id"), rs.getString("client_order_id"), rs.getString("side"), status,
                    rs.getLong("limit_price"), rs.getInt("requested_quantity"),
                    rs.getInt("filled_quantity"), rs.getInt("remaining_quantity"),
                    rs.getInt("cancelled_quantity"), rs.getLong("reserved_cash"), rs.getInt("reserved_quantity"), cancellable,
                    rs.getLong("total_amount"), List.of(),
                    snapshot(rs.getInt("account_id"), rs.getLong("cash_after"),
                            rs.getLong("reserved_cash_after"), rs.getInt("quantity_after"), rs.getInt("reserved_quantity_after")),
                    rs.getTimestamp("created_at").toLocalDateTime().toString(),
                    rs.getTimestamp("updated_at").toLocalDateTime().toString());
        };
    }

    private MarketSimulator.OrderResult withExecutions(MarketSimulator.OrderResult order) {
        var executions = jdbc.query("""
                SELECT market, quote_level, price, quantity, amount FROM sor_execution WHERE order_id = ? ORDER BY id
                """, (rs, rowNum) -> new MarketSimulator.Execution(rs.getString("market"),
                rs.getInt("quote_level"), rs.getLong("price"), rs.getInt("quantity"), rs.getLong("amount")), order.orderId());
        return new MarketSimulator.OrderResult(order.orderId(), order.clientOrderId(), order.side(),
                order.status(), order.limitPrice(), order.requestedQuantity(), order.filledQuantity(),
                order.remainingQuantity(), order.cancelledQuantity(), order.reservedCash(), order.reservedQuantity(), order.cancellable(),
                order.totalAmount(), List.copyOf(executions), order.accountAfter(), order.createdAt(), order.updatedAt());
    }
}

