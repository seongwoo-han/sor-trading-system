package com.example.stock;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sor")
public class SorController {

    private final MarketSimulator marketSimulator;
    private final SorComparisonService comparisonService;

    public SorController(MarketSimulator marketSimulator, SorComparisonService comparisonService) {
        this.marketSimulator = marketSimulator;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/compare")
    public SorComparisonService.ComparisonResult compare(
            @RequestParam(name = "limitPrice", defaultValue = "70000") long limitPrice,
            @RequestParam(name = "quantity", defaultValue = "10") int quantity,
            @RequestParam(name = "baselineMarket", defaultValue = "A") String baselineMarket,
            @RequestParam(name = "side", defaultValue = "BUY") String side,
            @RequestParam(name = "source", defaultValue = "EXAMPLE") String source) {
        if ("EXAMPLE".equals(source)) {
            return comparisonService.compareExample(side, limitPrice, quantity, baselineMarket);
        }
        if ("CURRENT".equals(source)) {
            return comparisonService.compareCurrent(side, marketSimulator.getBook(), limitPrice, quantity, baselineMarket);
        }
        if ("DEPTH_EXAMPLE".equals(source)) {
            return comparisonService.compareDepthExample(side, limitPrice, quantity, baselineMarket);
        }
        throw new IllegalArgumentException("비교 호가는 EXAMPLE, DEPTH_EXAMPLE 또는 CURRENT를 선택해 주세요.");
    }

    @GetMapping("/quotes")
    public List<SorService.MarketQuote> getQuotes() {
        return marketSimulator.getQuotes();
    }

    @GetMapping("/book")
    public MarketSimulator.BookSnapshot getBook() {
        return marketSimulator.getBook();
    }

    @PostMapping("/quotes")
    public MarketSimulator.QuoteUpdateResult updateQuotes(@RequestBody QuoteUpdateRequest request) {
        if (request.expectedRevision() == null || request.quotes() == null
                || request.quotes().stream().anyMatch(q -> q == null || q.market() == null
                    || q.askPrice() == null || q.askQuantity() == null
                    || ((q.bidPrice() == null) != (q.bidQuantity() == null))
                    || (q.bidPrice() != null && q.bidPrice() <= 0))) {
            throw new IllegalArgumentException("시장 상태 번호와 각 시장의 가격·수량을 모두 입력해 주세요.");
        }
        var quotes = request.quotes().stream().map(q ->
                new SorService.MarketQuote(q.market(), q.level() == null ? 1 : q.level(), q.askPrice(), q.askQuantity(),
                        q.bidPrice() == null ? 0 : q.bidPrice(), q.bidQuantity() == null ? 0 : q.bidQuantity())).toList();
        return marketSimulator.updateQuotes(request.clientEventId(), request.expectedRevision(), quotes);
    }

    @GetMapping("/quote-events")
    public List<MarketSimulator.QuoteUpdateResult> getQuoteEvents() {
        return marketSimulator.getQuoteEvents();
    }

    @GetMapping("/account")
    public MarketSimulator.AccountSnapshot getAccount() {
        return marketSimulator.getAccount();
    }

    @GetMapping("/preview")
    public SorService.RoutePlan previewBuy(
            @RequestParam(name = "side", defaultValue = "BUY") String side,
            @RequestParam(name = "limitPrice", defaultValue = "70000") long limitPrice,
            @RequestParam(name = "quantity", defaultValue = "10") int quantity) {
        return marketSimulator.preview(side, limitPrice, quantity);
    }

    @PostMapping("/orders")
    public MarketSimulator.OrderResult buy(@RequestBody BuyRequest request) {
        return marketSimulator.placeOrder(request.side(), request.clientOrderId(), request.limitPrice(), request.quantity());
    }

    @GetMapping("/orders")
    public List<MarketSimulator.OrderResult> getOrders() {
        return marketSimulator.getOrders();
    }

    @GetMapping("/orders/open")
    public List<MarketSimulator.OrderResult> getOpenOrders() {
        return marketSimulator.getOpenOrders();
    }

    @GetMapping("/orders/{orderId}")
    public MarketSimulator.OrderResult getOrder(@PathVariable("orderId") long orderId) {
        return marketSimulator.getOrder(orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public MarketSimulator.OrderResult cancel(@PathVariable("orderId") long orderId) {
        return marketSimulator.cancel(orderId);
    }

    @PostMapping("/match")
    public List<MarketSimulator.OrderResult> matchOrders() {
        return marketSimulator.matchOrders();
    }

    @PostMapping("/reset")
    public List<SorService.MarketQuote> resetQuotes() {
        return marketSimulator.resetQuotes();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MarketSimulator.OrderConflictException.class)
    public ResponseEntity<Map<String, String>> conflictingOrder(
            MarketSimulator.OrderConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MarketSimulator.OrderNotFoundException.class)
    public ResponseEntity<Map<String, String>> missingOrder(MarketSimulator.OrderNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> databaseError(RuntimeException exception) {
        return ResponseEntity.internalServerError().body(Map.of("message",
                "DB 처리 결과를 확인하지 못했습니다. 연결을 확인한 뒤 같은 요청으로 다시 시도해 주세요."));
    }

    public record BuyRequest(String clientOrderId, String side, long limitPrice, int quantity) {
    }

    public record QuoteUpdateRequest(String clientEventId, Long expectedRevision,
                                      List<QuoteInput> quotes) {}

    public record QuoteInput(String market, Integer level, Long askPrice, Integer askQuantity, Long bidPrice, Integer bidQuantity) {}
}
