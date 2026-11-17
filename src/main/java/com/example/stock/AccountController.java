package com.example.stock;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기존 화면/API를 유지하되 대기 주문이 예약한 현금은 기존 매수에도 사용하지 않습니다. */
@RestController
public class AccountController {
    private final MarketSimulator market;
    private final JdbcTemplate jdbc;

    public AccountController(MarketSimulator market, JdbcTemplate jdbc) {
        this.market = market;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/account")
    public Map<String, Object> getAccount() { return result("계좌를 조회했습니다."); }

    @PostMapping("/api/buy")
    public ResponseEntity<Map<String, Object>> buy() {
        try {
            market.legacyBuy();
            return ResponseEntity.ok(result("1주 매수 완료"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(result(exception.getMessage()));
        }
    }

    @PostMapping("/api/sell")
    public ResponseEntity<Map<String, Object>> sell() {
        try {
            market.legacySell();
            return ResponseEntity.ok(result("1주 매도 완료"));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(result(exception.getMessage()));
        }
    }

    @GetMapping("/api/trades")
    public List<Map<String, Object>> getTrades() {
        var trades = jdbc.queryForList("""
                SELECT id, side, price, quantity, created_at FROM trade_history
                WHERE account_id = 1 ORDER BY id DESC LIMIT 100
                """);
        for (var trade : trades) trade.put("created_at", trade.get("created_at").toString());
        return trades;
    }

    private Map<String, Object> result(String message) {
        var account = market.getAccount();
        return Map.of("cash", account.cash(), "reservedCash", account.reservedCash(),
                "availableCash", account.availableCash(), "quantity", account.quantity(),
                "reservedQuantity", account.reservedQuantity(), "availableQuantity", account.availableQuantity(),
                "price", 70000, "message", message);
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> databaseError(RuntimeException exception) {
        return ResponseEntity.internalServerError().body(Map.of("message",
                "DB 처리 결과를 확인하지 못했습니다. 계좌와 거래내역을 조회해 주세요."));
    }
}
