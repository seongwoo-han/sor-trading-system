package com.example.stock;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sor/auto-market")
public class AutoMarketController {
    private final AutoMarketService autoMarket;
    public AutoMarketController(AutoMarketService autoMarket) { this.autoMarket = autoMarket; }

    @GetMapping
    public AutoMarketService.Status status() { return autoMarket.status(); }

    @PostMapping("/start")
    public AutoMarketService.Status start(@RequestParam(name = "symbol", required = false) String symbol) {
        return autoMarket.start(symbol == null ? null : StockSymbol.from(symbol));
    }

    @PostMapping("/stop")
    public AutoMarketService.Status stop() { return autoMarket.stop(); }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException failure) {
        return ResponseEntity.badRequest().body(Map.of("message", failure.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, DataAccessException.class})
    public ResponseEntity<Map<String, String>> unavailable(RuntimeException failure) {
        return ResponseEntity.status(503).body(Map.of("message",
                "자동시장 상태를 변경하지 못했어요. 서버와 DB 연결을 확인해 주세요."));
    }
}
