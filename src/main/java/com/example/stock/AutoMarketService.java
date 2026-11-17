package com.example.stock;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 서버에서 호가를 생성하며, 체결과 계좌 처리는 기존 트랜잭션 경로에 맡깁니다. */
@Service
public class AutoMarketService {
    public static final int INTERVAL_MILLIS = 3000;
    private static final Logger log = LoggerFactory.getLogger(AutoMarketService.class);
    private final MarketSimulator market;
    private final RandomGenerator random;
    private boolean running;
    private Map<String, Long> anchors = Map.of();
    private Map<String, Long> bidAnchors = Map.of();
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
    public AutoMarketService(MarketSimulator market) {
        this(market, new Random());
    }

    AutoMarketService(MarketSimulator market, RandomGenerator random) {
        this.market = market;
        this.random = random;
    }

    public synchronized Status status() {
        return new Status(running, INTERVAL_MILLIS, updates, skipped, filledQuantity,
                totalAmount, startedAt, lastUpdatedAt, message);
    }

    /** 중복 시작 요청은 실행 상태와 기준 가격을 변경하지 않습니다. 시작 자체는 DB를 쓰지 않습니다. */
    public synchronized Status start() {
        if (running) return status();
        var book = market.getBook();
        if (book.quotes().size() != 6) throw new IllegalStateException("A·B의 3단계 호가를 확인해 주세요.");
        long a = book.quotes().stream().filter(q -> q.market().equals("A") && q.level() == 1).findFirst().orElseThrow().askPrice();
        long b = book.quotes().stream().filter(q -> q.market().equals("B") && q.level() == 1).findFirst().orElseThrow().askPrice();
        anchors = Map.of("A", a, "B", b);
        bidAnchors = Map.of("A", initialBid(book, "A", a), "B", initialBid(book, "B", b));
        runId = UUID.randomUUID().toString().replace("-", "");
        attempts = updates = skipped = filledQuantity = totalAmount = 0;
        startedAt = Instant.now().toString();
        lastUpdatedAt = null;
        message = null;
        running = true;
        return status();
    }

    /** tick과 같은 잠금을 사용해, 정지 응답 뒤에는 진행 중인 자동 반영이 남지 않습니다. */
    public synchronized Status stop() {
        running = false;
        return status();
    }

    @Scheduled(fixedDelay = INTERVAL_MILLIS, initialDelay = INTERVAL_MILLIS)
    public synchronized void tick() {
        if (!running) return;
        try {
            attempts = Math.incrementExact(attempts);
            var book = market.getBook();
            var quotes = new ArrayList<SorService.MarketQuote>();
            for (String venue : java.util.List.of("A", "B")) {
                long current = book.quotes().stream().filter(q -> q.market().equals(venue) && q.level() == 1)
                        .findFirst().orElseThrow().askPrice();
                long anchor = anchors.get(venue), base = nextPrice(current, anchor, random.nextInt(-2, 3));
                long gap = Math.max(1, anchor / 1000);
                long currentBid = book.quotes().stream().filter(q -> q.market().equals(venue) && q.level() == 1)
                        .findFirst().orElseThrow().bidPrice();
                long bidBase = Math.min(nextPrice(Math.max(1, currentBid), bidAnchors.get(venue), random.nextInt(-2, 3)), Math.max(1, base - gap));
                for (int level = 1; level <= 3; level++) {
                    long offset = gap * (level - 1);
                    long price = base > Long.MAX_VALUE - offset ? Long.MAX_VALUE : base + offset;
                    quotes.add(new SorService.MarketQuote(venue, level, price, random.nextInt(2, 7),
                            Math.max(1, bidBase - offset), random.nextInt(2, 7)));
                }
            }
            // Each tick represents new simulated liquidity, with its own durable event ID.
            var receipt = market.updateQuotes("auto-" + runId + "-" + attempts, book.revision(), quotes);
            updates = Math.incrementExact(updates);
            filledQuantity = Math.addExact(filledQuantity, receipt.filledQuantity());
            totalAmount = Math.addExact(totalAmount, receipt.totalAmount());
            lastUpdatedAt = Instant.now().toString();
            message = null;
        } catch (MarketSimulator.OrderConflictException conflict) {
            skipped = Math.incrementExact(skipped);
            message = "다른 호가 반영과 겹쳐 이번 갱신을 건너뛰었어요. 다음 주기에 다시 확인해요.";
        } catch (RuntimeException failure) {
            running = false;
            message = "자동 호가 반영 중 오류가 발생해 정지했어요. 계좌와 서버 연결을 확인한 뒤 다시 시작해 주세요.";
            log.warn("Automatic simulated market stopped after an update failure", failure);
        }
    }

    static long nextPrice(long current, long anchor, int step) {
        if (current <= 0 || anchor <= 0 || step < -2 || step > 2) throw new IllegalArgumentException("가격 범위를 확인해 주세요.");
        long tick = Math.max(1, anchor / 1000);
        long band = tick * 20;
        long minimum = Math.max(1, anchor - band);
        long maximum = anchor > Long.MAX_VALUE - band ? Long.MAX_VALUE : anchor + band;
        long delta = tick * step;
        long candidate = delta > 0 && current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
        return Math.max(minimum, Math.min(maximum, candidate));
    }

    private long initialBid(MarketSimulator.BookSnapshot book, String venue, long ask) {
        long bid = book.quotes().stream().filter(q -> q.market().equals(venue) && q.level() == 1).findFirst().orElseThrow().bidPrice();
        return bid > 0 && bid <= ask ? bid : Math.max(1, ask - Math.max(1, ask / 1000));
    }

    @PreDestroy
    public synchronized void shutdown() { running = false; }

    public record Status(boolean running, int intervalMillis, long updates, long skipped,
                         long filledQuantity, long totalAmount, String startedAt,
                         String lastUpdatedAt, String message) {}
}
