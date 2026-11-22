package com.example.stock;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestParam(name = "symbol", required = false) String symbolCode,
            @RequestParam(name = "limitPrice", required = false) Long limitPrice,
            @RequestParam(name = "quantity", defaultValue = "10") int quantity,
            @RequestParam(name = "baselineMarket", defaultValue = "KRX") String baselineMarket,
            @RequestParam(name = "side", defaultValue = "BUY") String side,
            @RequestParam(name = "source", defaultValue = "EXAMPLE") String source,
            @RequestParam(name = "comparisonTime", required = false) String comparisonTime) {
        StockSymbol symbol = StockSymbol.from(symbolCode);
        symbol.requireActive();
        long price = limitPrice == null ? symbol.referencePrice() : limitPrice;
        LocalTime time = MarketHours.parseTime(comparisonTime);
        if ("EXAMPLE".equals(source) || "DEPTH_EXAMPLE".equals(source)) {
            return comparisonService.compareExample(symbol, side, price, quantity, baselineMarket,
                    "DEPTH_EXAMPLE".equals(source), time);
        }
        if ("CURRENT".equals(source)) {
            return comparisonService.compareCurrent(side, marketSimulator.getBook(symbol), price, quantity, baselineMarket, time);
        }
        throw new IllegalArgumentException("비교 호가는 EXAMPLE, DEPTH_EXAMPLE 또는 CURRENT를 선택해 주세요.");
    }

    @GetMapping("/symbols")
    public List<StockSymbol.SymbolInfo> symbols() { return StockSymbol.catalog(); }

    @GetMapping("/example-quotes")
    public List<SorService.MarketQuote> exampleQuotes(@RequestParam(name = "symbol", required = false) String symbolCode,
                                                      @RequestParam(name = "depth", defaultValue = "false") boolean depth) {
        StockSymbol symbol = StockSymbol.from(symbolCode);
        symbol.requireActive();
        return depth ? MarketBooks.depthExample(symbol) : MarketBooks.basicExample(symbol);
    }

    @GetMapping("/quotes")
    public List<SorService.MarketQuote> getQuotes(@RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getQuotes(StockSymbol.from(symbol));
    }

    @GetMapping("/book")
    public MarketSimulator.BookSnapshot getBook(@RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getBook(StockSymbol.from(symbol));
    }

    @GetMapping("/market-hours")
    public MarketHours.Status marketHours() {
        return marketSimulator.getMarketHours();
    }

    @PostMapping("/market-hours")
    public MarketHours.Status setMarketTime(@RequestBody MarketTimeRequest request) {
        return marketSimulator.setDemoTime(request.time());
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
        StockSymbol symbol = request.symbol() == null ? null : StockSymbol.from(request.symbol());
        return marketSimulator.updateQuotes(request.clientEventId(), request.expectedRevision(), quotes, symbol);
    }

    @GetMapping("/quote-events")
    public List<MarketSimulator.QuoteUpdateResult> getQuoteEvents(@RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getQuoteEvents(StockSymbol.from(symbol));
    }

    @GetMapping("/account")
    public MarketSimulator.AccountSnapshot getAccount(Authentication authentication,
                                                       @RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getAccount(StockAccountSession.from(authentication).accountId(), StockSymbol.from(symbol));
    }

    @GetMapping("/portfolio")
    public MarketSimulator.PortfolioSnapshot getPortfolio(Authentication authentication,
                                                           @RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getPortfolio(StockAccountSession.from(authentication).accountId(), StockSymbol.from(symbol));
    }

    @GetMapping("/preview")
    public SorService.RoutePlan previewOrder(
            @RequestParam(name = "symbol", required = false) String symbolCode,
            @RequestParam(name = "side", defaultValue = "BUY") String side,
            @RequestParam(name = "orderType", defaultValue = "LIMIT") String orderType,
            @RequestParam(name = "limitPrice", required = false) Long limitPrice,
            @RequestParam(name = "quantity", defaultValue = "10") int quantity,
            @RequestParam(name = "routingPolicy", required = false) String routingPolicy) {
        StockSymbol symbol = StockSymbol.from(symbolCode);
        if (limitPrice == null && orderType.equals("LIMIT")) limitPrice = symbol.referencePrice();
        return marketSimulator.preview(symbol, side, orderPrice(orderType, limitPrice), quantity, RoutingPolicy.from(routingPolicy));
    }

    @PostMapping("/orders")
    public MarketSimulator.OrderResult placeOrder(Authentication authentication, @RequestBody OrderRequest request,
                                         @RequestHeader(name = "X-SOR-Account-Id", required = false) Integer expectedAccountId) {
        int accountId = StockAccountSession.from(authentication).checkAccount(expectedAccountId);
        StockSymbol symbol = request.symbol() == null ? null : StockSymbol.from(request.symbol());
        return marketSimulator.placeOrder(accountId, request.side(), request.clientOrderId(),
                orderPrice(request.orderType(), request.limitPrice()), request.quantity(), RoutingPolicy.from(request.routingPolicy()),
                symbol, request.accountNumber());
    }

    @PostMapping("/consent")
    public MarketSimulator.SorConsent acceptConsent(Authentication authentication, @RequestBody ConsentRequest request,
            @RequestHeader(name = "X-SOR-Account-Id", required = false) Integer expectedAccountId) {
        int accountId = StockAccountSession.from(authentication).checkAccount(expectedAccountId);
        return marketSimulator.acceptSorConsent(accountId, request.accountNumber(), request.version(), request.accepted());
    }

    private static Long orderPrice(String orderType, Long limitPrice) {
        if (orderType == null || orderType.equals("LIMIT")) {
            if (limitPrice == null || limitPrice <= 0) {
                throw new IllegalArgumentException("지정가는 1원 이상이어야 합니다.");
            }
            return limitPrice;
        }
        if (!orderType.equals("MARKET")) {
            throw new IllegalArgumentException("주문 방식은 지정가(LIMIT) 또는 시장가(MARKET)를 선택해 주세요.");
        }
        if (limitPrice != null) {
            throw new IllegalArgumentException("시장가 주문에는 지정가를 입력할 수 없습니다.");
        }
        return null;
    }

    @GetMapping("/orders")
    public List<MarketSimulator.OrderResult> getOrders(Authentication authentication,
                                                       @RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getOrders(StockAccountSession.from(authentication).accountId(),
                symbol == null ? null : StockSymbol.from(symbol));
    }

    @GetMapping("/orders/open")
    public List<MarketSimulator.OrderResult> getOpenOrders(Authentication authentication,
                                                           @RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.getOpenOrders(StockAccountSession.from(authentication).accountId(),
                symbol == null ? null : StockSymbol.from(symbol));
    }

    @GetMapping("/orders/{orderId}")
    public MarketSimulator.OrderResult getOrder(Authentication authentication, @PathVariable("orderId") long orderId) {
        return marketSimulator.getOrder(StockAccountSession.from(authentication).accountId(), orderId);
    }

    @PostMapping("/orders/{orderId}/amend")
    public MarketSimulator.OrderResult amend(Authentication authentication, @PathVariable("orderId") long orderId,
                                              @RequestBody AmendOrderRequest request,
                                              @RequestHeader(name = "X-SOR-Account-Id", required = false) Integer expectedAccountId) {
        int accountId = StockAccountSession.from(authentication).checkAccount(expectedAccountId);
        return marketSimulator.amend(accountId, orderId, request.clientOrderId(), request.limitPrice(),
                request.quantity(), request.expectedRemainingQuantity(), request.accountNumber());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public MarketSimulator.OrderResult cancel(Authentication authentication, @PathVariable("orderId") long orderId,
                                            @RequestHeader(name = "X-SOR-Account-Id", required = false) Integer expectedAccountId) {
        int accountId = StockAccountSession.from(authentication).checkAccount(expectedAccountId);
        return marketSimulator.cancel(accountId, orderId);
    }

    @PostMapping("/match")
    public List<MarketSimulator.OrderResult> matchOrders(@RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.matchOrders(StockSymbol.from(symbol));
    }

    @PostMapping("/reset")
    public List<SorService.MarketQuote> resetQuotes(@RequestParam(name = "symbol", required = false) String symbol) {
        return marketSimulator.resetQuotes(StockSymbol.from(symbol));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(MarketSimulator.OrderRejectionException.class)
    public ResponseEntity<Map<String, String>> rejectedOrder(MarketSimulator.OrderRejectionException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", exception.code(), "message", exception.getMessage()));
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

    public record OrderRequest(String clientOrderId, String side, String orderType, Long limitPrice, int quantity,
                               String routingPolicy, String symbol, String accountNumber) {
    }

    public record AmendOrderRequest(String clientOrderId, long limitPrice, int quantity, int expectedRemainingQuantity,
                                    String accountNumber) {}

    public record ConsentRequest(String accountNumber, Integer version, Boolean accepted) {}

    public record MarketTimeRequest(String time) {}

    public record QuoteUpdateRequest(String clientEventId, Long expectedRevision,
                                     List<QuoteInput> quotes, String symbol) {}

    public record QuoteInput(String market, Integer level, Long askPrice, Integer askQuantity, Long bidPrice, Integer bidQuantity) {}
}
