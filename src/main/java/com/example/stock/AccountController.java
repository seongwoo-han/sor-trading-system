package com.example.stock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 기존 1주 주문 화면도 기본 종목의 SOR 주문을 이용합니다. */
@RestController
public class AccountController {
    private final MarketSimulator market;
    private final JdbcTemplate jdbc;

    public AccountController(MarketSimulator market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/account")
    public Map<String, Object> getAccount(Authentication authentication) {
        var account = market.getAccount(StockAccountSession.from(authentication).accountId());
        return result(account, StockSymbol.DEFAULT.referencePrice(), "계좌를 조회했습니다.");
    }

    @PostMapping("/api/buy")
    public ResponseEntity<Map<String, Object>> buy(Authentication authentication) {
        return tradeOneShare(StockAccountSession.from(authentication), "BUY", "1주 매수 완료");
    }

    @PostMapping("/api/sell")
    public ResponseEntity<Map<String, Object>> sell(Authentication authentication) {
        return tradeOneShare(StockAccountSession.from(authentication), "SELL", "1주 매도 완료");
    }

    private ResponseEntity<Map<String, Object>> tradeOneShare(StockAccountSession session, String side, String message) {
        int accountId = session.accountId();
        StockSymbol symbol = StockSymbol.DEFAULT;
        try {
            var order = market.placeOrder(accountId, side, UUID.randomUUID().toString(), null, 1,
                    RoutingPolicy.PRICE_FIRST, symbol, session.accountNumber());
            return ResponseEntity.ok(result(order.accountAfter(), order.totalAmount(),
                    message + " · 수수료 " + order.totalFee() + "원 · 정산 " + order.settlementAmount() + "원"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(result(market.getAccount(accountId), symbol.referencePrice(), exception.getMessage()));
        }
    }

    @GetMapping("/api/trades")
    public List<Map<String, Object>> getTrades(Authentication authentication,
                                               @RequestParam(name = "symbol", required = false) String symbol) {
        var trades = jdbc.queryForList("""
                SELECT id, side, price, quantity, created_at, symbol, fee FROM trade_history
                WHERE account_id = ? AND symbol = ? ORDER BY id DESC LIMIT 100
                """, StockAccountSession.from(authentication).accountId(), StockSymbol.from(symbol).code());
        for (var trade : trades) trade.put("created_at", trade.get("created_at").toString());
        return trades;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    private Map<String, Object> result(MarketSimulator.AccountSnapshot account, long price, String message) {
        return Map.of("cash", account.cash(), "reservedCash", account.reservedCash(),
                "availableCash", account.availableCash(), "quantity", account.quantity(),
                "reservedQuantity", account.reservedQuantity(), "availableQuantity", account.availableQuantity(),
                "price", price, "message", message);
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> databaseError(RuntimeException exception) {
        return ResponseEntity.internalServerError().body(Map.of("message",
                "DB 처리 결과를 확인하지 못했습니다. 계좌와 거래내역을 조회해 주세요."));
    }
}
