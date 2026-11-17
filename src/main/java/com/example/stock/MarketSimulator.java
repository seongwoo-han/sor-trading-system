package com.example.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MarketSimulator {
    private static final int ACCOUNT_ID = 1;
    private static final long LEGACY_PRICE = 70000;
    private final SorService sorService;
    private final SorRepository repository;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public MarketSimulator(SorService sorService, SorRepository repository,
                           PlatformTransactionManager transactionManager) {
        this.sorService = sorService;
        this.repository = repository;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public List<SorService.MarketQuote> getQuotes() { return repository.getQuotes(false); }

    public BookSnapshot getBook() { return repository.getBook(); }

    public AccountSnapshot getAccount() { return repository.getAccount(ACCOUNT_ID, false); }

    public SorService.RoutePlan previewBuy(long limitPrice, int quantity) {
        return sorService.planBuy(limitPrice, quantity, repository.getQuotes(false));
    }

    public SorService.RoutePlan preview(String side, long limitPrice, int quantity) {
        return sorService.plan(side, limitPrice, quantity, repository.getQuotes(false));
    }

    public OrderResult buy(String clientOrderId, long limitPrice, int quantity) {
        return placeOrder("BUY", clientOrderId, limitPrice, quantity);
    }

    public OrderResult sell(String clientOrderId, long limitPrice, int quantity) {
        return placeOrder("SELL", clientOrderId, limitPrice, quantity);
    }

    public OrderResult placeOrder(String direction, String clientOrderId, long limitPrice, int quantity) {
        String side = SorService.side(direction);
        boolean buy = side.equals("BUY");
        if (clientOrderId == null || !clientOrderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("요청 ID는 영문·숫자·하이픈·밑줄 1~64자로 입력해 주세요.");
        }
        if (limitPrice <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("지정가와 주문 수량은 1 이상이어야 합니다.");
        }
        final long commitment;
        try {
            commitment = buy ? Math.multiplyExact(limitPrice, (long) quantity) : 0;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("주문 금액이 처리 가능한 범위를 초과합니다.");
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            // 신규·체결·취소·기존 매매 모두 계좌 행부터 잠급니다.
            repository.getAccount(ACCOUNT_ID, true);
            OrderResult existing = repository.findOrder(ACCOUNT_ID, clientOrderId);
            if (existing != null) {
                if (!existing.side().equals(side) || existing.limitPrice() != limitPrice || existing.requestedQuantity() != quantity) {
                    throw new OrderConflictException("같은 요청 ID로 주문 내용을 변경할 수 없습니다.");
                }
                return existing;
            }
            lockMarket();
            // 새 주문이 이전 대기 주문보다 먼저 같은 물량을 가져가지 않도록 접수 순서대로 처리합니다.
            matchWorkingOrdersLocked();
            AccountSnapshot account = repository.getAccount(ACCOUNT_ID, false);
            if (buy && account.availableCash() < commitment) {
                throw new IllegalArgumentException("주문 가능 금액이 부족합니다. 지정가 × 전체 수량으로 "
                        + commitment + "원이 필요하며, 현재 주문 가능 금액은 " + account.availableCash() + "원입니다.");
            }
            if (!buy && account.availableQuantity() < quantity) {
                throw new IllegalArgumentException("매도 가능 수량이 부족합니다. 요청 " + quantity + "주, 현재 가능 " + account.availableQuantity() + "주입니다.");
            }
            if (buy) repository.reserveCash(ACCOUNT_ID, commitment);
            else repository.reserveShares(ACCOUNT_ID, quantity);
            long orderId = repository.insertOrder(ACCOUNT_ID, clientOrderId, side, limitPrice, quantity,
                    commitment, buy ? 0 : quantity, repository.getAccount(ACCOUNT_ID, false));
            executeOrderLocked(repository.getOrder(ACCOUNT_ID, orderId));
            return repository.getOrder(ACCOUNT_ID, orderId);
        }));
    }

    public OrderResult cancel(long orderId) {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.getAccount(ACCOUNT_ID, true);
            OrderResult order = repository.getOrder(ACCOUNT_ID, orderId);
            if (order.status().equals("CANCELLED")) return order;
            if (!order.cancellable()) {
                throw new OrderConflictException("취소 가능한 대기 주문이 아닙니다. 이미 체결됐거나 이전 단계의 종료 기록입니다.");
            }
            if (order.side().equals("BUY")) repository.releaseCash(ACCOUNT_ID, order.reservedCash());
            else repository.releaseShares(ACCOUNT_ID, order.reservedQuantity());
            repository.cancelOrder(order, repository.getAccount(ACCOUNT_ID, false));
            return repository.getOrder(ACCOUNT_ID, orderId);
        }));
    }

    /** 현재 3단계 호가에서 대기 주문을 접수 순서대로 다시 검사합니다. */
    public List<OrderResult> matchOrders() {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.getAccount(ACCOUNT_ID, true);
            lockMarket();
            return matchWorkingOrdersLocked();
        }));
    }

    private List<OrderResult> matchWorkingOrdersLocked() {
        return matchWorkingOrdersLocked(null);
    }

    private List<OrderResult> matchWorkingOrdersLocked(Long quoteEventId) {
        List<OrderResult> changed = new ArrayList<>();
        for (OrderResult order : repository.getOpenOrders(ACCOUNT_ID)) {
            if (executeOrderLocked(order, quoteEventId)) changed.add(repository.getOrder(ACCOUNT_ID, order.orderId()));
        }
        return List.copyOf(changed);
    }

    private boolean executeOrderLocked(OrderResult order) {
        return executeOrderLocked(order, null);
    }

    private boolean executeOrderLocked(OrderResult order, Long quoteEventId) {
        boolean buy = order.side().equals("BUY");
        SorService.RoutePlan plan = sorService.plan(order.side(), order.limitPrice(), order.remainingQuantity(),
                repository.getQuotes(false));
        if (plan.allocatedQuantity() == 0) return false;
        AccountSnapshot account = repository.getAccount(ACCOUNT_ID, false);
        try {
            if (buy) Math.addExact(account.quantity(), plan.allocatedQuantity());
            else Math.addExact(account.cash(), plan.estimatedAmount());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("체결 후 계좌 금액 또는 보유수량이 처리 가능한 범위를 초과합니다.");
        }
        if (buy) {
            long release = Math.multiplyExact(order.limitPrice(), (long) plan.allocatedQuantity());
            repository.applyFill(ACCOUNT_ID, plan.estimatedAmount(), plan.allocatedQuantity(), release);
        } else repository.applySellFill(ACCOUNT_ID, plan.estimatedAmount(), plan.allocatedQuantity());
        for (SorService.Allocation allocation : plan.allocations()) {
            if (buy) repository.consumeQuote(allocation.market(), allocation.level(), allocation.quantity());
            else repository.consumeBidQuote(allocation.market(), allocation.level(), allocation.quantity());
            long tradeId = repository.insertTrade(ACCOUNT_ID, order.side(), allocation.price(), allocation.quantity());
            long executionId = repository.insertExecution(order.orderId(), tradeId, allocation);
            if (quoteEventId != null) repository.linkQuoteEventExecution(quoteEventId, executionId);
        }
        repository.recordFill(order, plan, repository.getAccount(ACCOUNT_ID, false));
        return true;
    }

    public List<OrderResult> getOrders() {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOrders(ACCOUNT_ID)));
    }

    public List<OrderResult> getOpenOrders() {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOpenOrders(ACCOUNT_ID)));
    }

    public OrderResult getOrder(long orderId) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOrder(ACCOUNT_ID, orderId)));
    }

    public List<SorService.MarketQuote> resetQuotes() {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.getAccount(ACCOUNT_ID, true);
            lockMarket();
            repository.resetQuotes();
            return repository.getQuotes(false);
        }));
    }

    private long lockMarket() {
        long revision = repository.lockBookRevision();
        if (repository.getQuotes(true).size() != 6) {
            throw new IllegalStateException("모의시장 초기 데이터를 확인해 주세요.");
        }
        return revision;
    }

    /** 호가 변경, 대기 주문 재검사, 계좌·체결 반영, 변경 영수증을 함께 커밋합니다. */
    public QuoteUpdateResult updateQuotes(String clientEventId, long expectedRevision,
                                          List<SorService.MarketQuote> submittedQuotes) {
        if (clientEventId == null || !clientEventId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("호가 요청 ID는 영문·숫자·하이픈·밑줄 1~64자로 입력해 주세요.");
        }
        if (expectedRevision < 0) throw new IllegalArgumentException("시장 상태 번호를 확인해 주세요.");
        var quotes = MarketBooks.normalize(submittedQuotes);
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.getAccount(ACCOUNT_ID, true);
            QuoteUpdateResult existing = repository.findQuoteEvent(ACCOUNT_ID, clientEventId);
            if (existing != null) {
                if (existing.expectedRevision() != expectedRevision || !MarketBooks.normalize(existing.submittedQuotes()).equals(quotes)) {
                    throw new OrderConflictException("같은 호가 요청 ID로 내용을 변경할 수 없습니다.");
                }
                // 오래된 버전 번호로 재시도해도 이미 처리한 요청은 저장한 영수증만 반환합니다.
                return existing;
            }
            if (lockMarket() != expectedRevision) {
                throw new OrderConflictException("다른 주문이나 호가 반영으로 시장 상태가 바뀌었습니다. "
                        + "현재 상태를 새로고침하고 가격·잔량을 확인한 뒤 다시 반영해 주세요.");
            }
            repository.setQuotes(MarketBooks.applyTo(quotes, repository.getQuotes(false)));
            long eventId = repository.insertQuoteEvent(ACCOUNT_ID, clientEventId, expectedRevision,
                    quotes, repository.getBook(), repository.getAccount(ACCOUNT_ID, false));
            matchWorkingOrdersLocked(eventId);
            repository.finishQuoteEvent(ACCOUNT_ID, eventId, repository.getBook(),
                    repository.getAccount(ACCOUNT_ID, false));
            return Objects.requireNonNull(repository.findQuoteEvent(ACCOUNT_ID, clientEventId));
        }));
    }

    public List<QuoteUpdateResult> getQuoteEvents() {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getQuoteEvents(ACCOUNT_ID)));
    }

    public void legacyBuy() {
        transaction.executeWithoutResult(status -> {
            AccountSnapshot account = repository.getAccount(ACCOUNT_ID, true);
            if (account.availableCash() < LEGACY_PRICE) {
                throw new IllegalArgumentException("주문 가능 금액이 부족합니다. 대기 주문의 예약금은 사용할 수 없습니다.");
            }
            if (account.quantity() == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("보유수량이 처리 가능한 범위를 초과합니다.");
            }
            repository.legacyBuy(ACCOUNT_ID, LEGACY_PRICE);
            repository.insertTrade(ACCOUNT_ID, "BUY", LEGACY_PRICE, 1);
        });
    }

    public void legacySell() {
        transaction.executeWithoutResult(status -> {
            AccountSnapshot account = repository.getAccount(ACCOUNT_ID, true);
            if (account.availableQuantity() < 1) throw new IllegalArgumentException("매도 가능 주식이 부족합니다. 예약 중인 주식은 매도할 수 없습니다.");
            try { Math.addExact(account.cash(), LEGACY_PRICE); }
            catch (ArithmeticException exception) {
                throw new IllegalArgumentException("계좌 금액이 처리 가능한 범위를 초과합니다.");
            }
            repository.legacySell(ACCOUNT_ID, LEGACY_PRICE);
            repository.insertTrade(ACCOUNT_ID, "SELL", LEGACY_PRICE, 1);
        });
    }

    public record Execution(String market, int level, long price, int quantity, long amount) {}

    public record BookSnapshot(long revision, List<SorService.MarketQuote> quotes) {}

    public record QuoteEventExecution(long orderId, String side, String market, int level, long price, int quantity, long amount) {}

    public record QuoteUpdateResult(long eventId, String clientEventId, long expectedRevision,
                                    BookSnapshot bookAfter, List<SorService.MarketQuote> submittedQuotes,
                                    long filledQuantity, long totalAmount, List<QuoteEventExecution> executions,
                                    AccountSnapshot accountAfter, String createdAt) {}

    /** cash는 총 현금, reservedCash는 그중 예약된 금액입니다. */
    public record AccountSnapshot(int accountId, long cash, long reservedCash,
                                  long availableCash, int quantity, int reservedQuantity, int availableQuantity) {}

    public record OrderResult(long orderId, String clientOrderId, String side, String status,
                              long limitPrice, int requestedQuantity, int filledQuantity,
                              int remainingQuantity, int cancelledQuantity, long reservedCash, int reservedQuantity,
                              boolean cancellable, long totalAmount, List<Execution> executions,
                              AccountSnapshot accountAfter, String createdAt, String updatedAt) {}

    public static class OrderConflictException extends RuntimeException {
        public OrderConflictException(String message) { super(message); }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException() { super("주문을 찾을 수 없습니다."); }
    }
}

