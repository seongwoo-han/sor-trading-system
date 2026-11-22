package com.example.stock;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/** 서버에서 호가를 생성하며, 체결과 계좌 처리는 기존 트랜잭션 경로에 맡깁니다. */
@Service
public class AutoMarketService {
    private static final Logger log = LoggerFactory.getLogger(AutoMarketService.class);
    private static final int UPDATE_INTERVAL_MILLIS = 5_000;
    private static final int MAX_PRICE_CHANGE = 50;
    private final MarketSimulator market;
    private final TaskScheduler scheduler;
    private final RandomGenerator random;
    private ScheduledFuture<?> scheduledUpdate;
    private Map<StockSymbol, Map<String, Long>> anchors = Map.of();
    private String runId;
    private long attempts;
    private long updates;
    private long skipped;
    private long filledQuantity;
    private long totalAmount;
    private String startedAt;
    private String lastUpdatedAt;
    private String message;

    @Autowired
    public AutoMarketService(MarketSimulator market, TaskScheduler scheduler) {
        this(market, scheduler, new Random());
    }

    AutoMarketService(MarketSimulator market, TaskScheduler scheduler, RandomGenerator random) {
        this.market = market;
        this.scheduler = scheduler;
        this.random = random;
    }

    public synchronized Status status() {
        List<String> symbols = anchors.keySet().stream().map(StockSymbol::code).toList();
        return new Status(scheduledUpdate != null, UPDATE_INTERVAL_MILLIS, MAX_PRICE_CHANGE, updates, skipped, filledQuantity,
                totalAmount, startedAt, lastUpdatedAt, message, symbols.size() == 1 ? symbols.getFirst() : null, symbols);
    }

    /** 중복 시작 요청은 실행 상태와 기준 가격을 변경하지 않습니다. 시작 자체는 DB를 쓰지 않습니다. */
    public synchronized Status start(StockSymbol symbol) {
        if (symbol != null) symbol.requireActive();
        if (scheduledUpdate != null) return status();
        var symbols = symbol == null ? StockSymbol.active() : List.of(symbol);
        var initialPrices = new EnumMap<StockSymbol, Map<String, Long>>(StockSymbol.class);
        for (StockSymbol instrument : symbols) {
            var book = market.getBook(instrument);
            initialPrices.put(instrument, Map.of("KRX", topLevel(book, "KRX").askPrice(),
                    "NXT", topLevel(book, "NXT").askPrice()));
        }
        String updateRun = UUID.randomUUID().toString().replace("-", "");
        Instant startTime = scheduler.getClock().instant();
        Duration delay = Duration.ofMillis(UPDATE_INTERVAL_MILLIS);
        scheduledUpdate = scheduler.scheduleWithFixedDelay(() -> tick(updateRun), startTime.plus(delay), delay);
        anchors = initialPrices;
        runId = updateRun;
        attempts = updates = skipped = filledQuantity = totalAmount = 0;
        startedAt = startTime.toString();
        lastUpdatedAt = null;
        message = null;
        return status();
    }

    /** tick과 같은 잠금을 사용해, 정지 응답 뒤에는 진행 중인 자동 반영이 남지 않습니다. */
    public synchronized Status stop() {
        stopUpdates();
        return status();
    }

    private synchronized void tick(String updateRun) {
        if (scheduledUpdate == null || !updateRun.equals(runId)) return;
        try {
            attempts = Math.incrementExact(attempts);
            var changes = new ArrayList<MarketSimulator.QuoteChange>();
            for (var entry : anchors.entrySet()) {
                var book = market.getBook(entry.getKey());
                changes.add(new MarketSimulator.QuoteChange(entry.getKey(), book.revision(), nextQuotes(book, entry.getValue())));
            }
            var receipts = market.updateQuoteCycle("auto-" + runId + "-" + attempts, changes);
            updates = Math.incrementExact(updates);
            for (var receipt : receipts) {
                filledQuantity = Math.addExact(filledQuantity, receipt.filledQuantity());
                totalAmount = Math.addExact(totalAmount, receipt.totalAmount());
            }
            lastUpdatedAt = scheduler.getClock().instant().toString();
            message = null;
        } catch (MarketSimulator.OrderConflictException conflict) {
            skipped = Math.incrementExact(skipped);
            message = "다른 호가 반영과 겹쳐 이번 갱신을 건너뛰었어요. 다음 주기에 다시 확인해요.";
        } catch (RuntimeException failure) {
            stopUpdates();
            message = "자동 호가 반영 중 오류가 발생해 정지했어요. 계좌와 서버 연결을 확인한 뒤 다시 시작해 주세요.";
            log.warn("Automatic simulated market stopped after an update failure", failure);
        }
    }

    private List<SorService.MarketQuote> nextQuotes(MarketSimulator.BookSnapshot book, Map<String, Long> initialPrices) {
        var quotes = new ArrayList<SorService.MarketQuote>();
        for (String venue : MarketVenue.NAMES) {
            var top = topLevel(book, venue);
            long anchor = initialPrices.get(venue);
            long gap = Math.max(1, anchor / 1000);
            long ask = top.askPrice();
            long bid = top.bidPrice() > 0 && top.bidPrice() <= ask ? top.bidPrice() : Math.max(1, ask - gap);
            int change = random.nextInt(-MAX_PRICE_CHANGE, MAX_PRICE_CHANGE + 1);
            long move = priceMove(ask, bid, anchor, change);
            long askBase = ask + move;
            long bidBase = bid + move;
            for (int level = 1; level <= 3; level++) {
                long offset = gap * (level - 1);
                quotes.add(new SorService.MarketQuote(venue, level, addPriceOffset(askBase, offset), random.nextInt(2, 7),
                        Math.max(1, bidBase - offset), random.nextInt(2, 7)));
            }
        }
        return quotes;
    }

    static long priceMove(long ask, long bid, long anchor, int change) {
        // The simulated prices stay within ten moves of the starting ask. If an external
        // update moves the book outside that band, auto moves it back without a jump.
        long band = 10L * MAX_PRICE_CHANGE;
        long minimum = Math.max(1, anchor - band);
        long maximum = addPriceOffset(anchor, band);
        long lowerMove = Math.max(1 - bid, Math.min(0, minimum - ask));
        long upperMove = Math.max(0, maximum - ask);
        return Math.max(lowerMove, Math.min(upperMove, change));
    }

    private static long addPriceOffset(long price, long offset) {
        return price > Long.MAX_VALUE - offset ? Long.MAX_VALUE : price + offset;
    }

    private static SorService.MarketQuote topLevel(MarketSimulator.BookSnapshot book, String venue) {
        return book.quotes().stream().filter(q -> q.market().equals(venue) && q.level() == 1)
                .findFirst().orElseThrow(() -> new IllegalStateException("KRX·NXT의 1호가를 확인해 주세요."));
    }

    private void stopUpdates() {
        if (scheduledUpdate != null) {
            scheduledUpdate.cancel(false);
            scheduledUpdate = null;
        }
    }

    @PreDestroy
    public synchronized void shutdown() { stopUpdates(); }

    public record Status(boolean running, int intervalMillis, int maxPriceChange, long updates, long skipped,
                         long filledQuantity, long totalAmount, String startedAt,
                         String lastUpdatedAt, String message, String symbol, List<String> symbols) {}
}
