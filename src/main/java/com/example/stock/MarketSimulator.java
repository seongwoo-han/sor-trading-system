package com.example.stock;

import static com.example.stock.StockAccountSession.PRESENTER_ACCOUNT_ID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MarketSimulator {
    private static final Logger log = LoggerFactory.getLogger(MarketSimulator.class);
    private static final long PRACTICE_INITIAL_CASH = 10_000_000L;
    private static final int SOR_CONSENT_VERSION = 1;
    private final SorService sorService;
    private final SorRepository repository;
    private final MarketHours marketHours;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;
    private final Set<Integer> endingGuestAccounts = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean demoClockChanged = new AtomicBoolean();
    private Set<String> previousExecutionMarkets = Set.of();

    public MarketSimulator(SorService sorService, SorRepository repository,
                           MarketHours marketHours, PlatformTransactionManager transactionManager) {
        this.sorService = sorService;
        this.repository = repository;
        this.marketHours = marketHours;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
        readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public List<SorService.MarketQuote> getQuotes(StockSymbol symbol) { return repository.getQuotes(symbol); }

    public BookSnapshot getBook(StockSymbol symbol) { return repository.getBook(symbol); }

    public AccountSnapshot getAccount(int accountId) { return getAccount(accountId, StockSymbol.DEFAULT); }

    public AccountSnapshot getAccount(int accountId, StockSymbol symbol) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getAccount(accountId, symbol, false)));
    }

    public MarketHours.Status getMarketHours() { return marketHours.current(); }

    public MarketHours.Status setDemoTime(String value) {
        var time = MarketHours.parseTime(value);
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            marketHours.setDemoTime(time);
            demoClockChanged.set(true);
            return marketHours.current();
        }));
    }

    public PortfolioSnapshot getPortfolio(int accountId, StockSymbol symbol) {
        return Objects.requireNonNull(readTransaction.execute(status -> {
            var account = repository.getAccount(accountId, false);
            var book = repository.getBook(symbol);
            var positions = new ArrayList<PositionSnapshot>();
            var archivedSymbols = new ArrayList<StockSymbol.SymbolInfo>();
            for (StockSymbol instrument : StockSymbol.values()) {
                var position = repository.positionSnapshot(account, instrument);
                if (!instrument.isActive()) {
                    if (position.quantity() > 0 || repository.hasArchivedHistory(accountId, instrument)) {
                        archivedSymbols.add(new StockSymbol.SymbolInfo(instrument.code(), instrument.displayName()));
                    }
                    if (instrument != symbol && position.quantity() == 0) continue;
                }
                var quotes = instrument == symbol ? book.quotes()
                        : position.quantity() > 0 ? repository.getQuotes(instrument) : List.<SorService.MarketQuote>of();
                var bestBid = sorService.plan("SELL", 1L, 1, quotes, RoutingPolicy.PRICE_FIRST, false)
                        .allocations().stream().findFirst().orElse(null);
                var trades = repository.getCurrentPositionTrades(accountId, instrument, position.quantity());
                positions.add(new PositionSnapshot(instrument.code(), instrument.displayName(), position,
                        PortfolioValuation.calculate(position, bestBid, trades)));
            }
            var selected = positions.stream().filter(position -> position.symbol().equals(symbol.code())).findFirst().orElseThrow();
            BigDecimal assets = positions.stream().anyMatch(position -> position.valuation().marketValue() == null)
                    ? null : positions.stream().map(position -> position.valuation().marketValue())
                            .reduce(BigDecimal.valueOf(account.cash()), BigDecimal::add);
            return new PortfolioSnapshot(selected.account(), book, selected.valuation(), marketHours.current(),
                    List.copyOf(positions), repository.getMarketVolume(symbol), assets,
                    !archivedSymbols.isEmpty(), List.copyOf(archivedSymbols), sorConsent(accountId));
        }));
    }

    private SorConsent sorConsent(int accountId) {
        return new SorConsent(SOR_CONSENT_VERSION, repository.sorConsentAt(accountId, SOR_CONSENT_VERSION));
    }

    public SorConsent acceptSorConsent(int accountId, String accountNumber, Integer version, Boolean accepted) {
        StockAccountSession.requireAccountNumber(accountId, accountNumber);
        if (!Objects.equals(version, SOR_CONSENT_VERSION)) {
            throw new OrderRejectionException("SOR_CONSENT_VERSION_MISMATCH", "현재 모의 SOR 동의서를 다시 열어 확인해 주세요.");
        }
        if (!Boolean.TRUE.equals(accepted)) {
            throw new OrderRejectionException("SOR_CONSENT_REQUIRED", "모의 SOR 주문 동의 항목을 확인해 주세요.");
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            checkTradingAccountLocked(accountId);
            repository.recordSorConsent(accountId, SOR_CONSENT_VERSION);
            return sorConsent(accountId);
        }));
    }

    private void checkOrderAccess(int accountId, String accountNumber, boolean retry) {
        // Upgrades can resume a previously accepted request whose stored payload has no account number.
        if (!retry || accountNumber != null) StockAccountSession.requireAccountNumber(accountId, accountNumber);
        if (!retry && repository.sorConsentAt(accountId, SOR_CONSENT_VERSION) == null) {
            throw new OrderRejectionException("SOR_CONSENT_REQUIRED", "주문을 접수하려면 매수·매도에서 모의 SOR 주문에 먼저 동의해 주세요.");
        }
    }

    public SorService.RoutePlan preview(StockSymbol symbol, String side, Long limitPrice, int quantity, RoutingPolicy routingPolicy) {
        symbol.requireActive();
        var hours = marketHours.current();
        boolean marketOrder = limitPrice == null;
        requireOrderAccepted(hours, marketOrder);
        return sorService.plan(side, limitPrice, quantity, executableQuotes(symbol, hours.matchingMarkets(marketOrder)), routingPolicy);
    }

    public OrderResult placeOrder(int accountId, String direction, String clientOrderId, Long limitPrice, int quantity,
                                  RoutingPolicy routingPolicy, StockSymbol symbol, String accountNumber) {
        String side = SorService.side(direction);
        validateOrderRequest(clientOrderId, limitPrice, quantity);
        return Objects.requireNonNull(transaction.execute(status -> {
            // 시장 전체에 영향을 주는 작업은 호가 상태를 먼저 잠근 뒤 계좌를 잠급니다.
            repository.lockTradingState();
            checkTradingAccountLocked(accountId);
            OrderResult existing = findMatchingRequest(accountId, clientOrderId, null, limitPrice, quantity);
            checkOrderAccess(accountId, accountNumber, existing != null);
            if (existing != null) {
                if (!existing.side().equals(side) || existing.routingPolicy() != routingPolicy
                        || !matchesRequestedSymbol(symbol, existing.symbol())) throw requestIdConflict();
                return existing;
            }
            StockSymbol instrument = symbol == null ? StockSymbol.DEFAULT : symbol;
            instrument.requireActive();
            return submitOrderLocked(accountId, clientOrderId, side, limitPrice, quantity, null, routingPolicy, instrument);
        }));
    }

    public OrderResult amend(int accountId, long orderId, String clientOrderId, long limitPrice,
                             int quantity, int expectedRemainingQuantity, String accountNumber) {
        validateOrderRequest(clientOrderId, limitPrice, quantity);
        if (expectedRemainingQuantity <= 0 || quantity > expectedRemainingQuantity) {
            throw new IllegalArgumentException("정정 후 수량은 1주 이상이며 조회한 미체결 수량 이하여야 합니다.");
        }
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            checkTradingAccountLocked(accountId);
            OrderResult existing = findMatchingRequest(accountId, clientOrderId, orderId, limitPrice, quantity);
            checkOrderAccess(accountId, accountNumber, existing != null);
            if (existing != null) return existing;

            OrderResult order = repository.getOrder(accountId, orderId);
            StockSymbol symbol = StockSymbol.from(order.symbol());
            symbol.requireActive();
            if (!order.cancellable() || !"LIMIT".equals(order.orderType())) {
                throw new OrderConflictException("정정할 미체결 지정가 주문이 없습니다. 최신 주문 상태를 확인해 주세요.");
            }
            if (order.remainingQuantity() != expectedRemainingQuantity) {
                throw new OrderConflictException("미체결 수량이 " + order.remainingQuantity()
                        + "주로 바뀌었습니다. 최신 주문을 확인한 뒤 다시 정정해 주세요.");
            }
            if (order.limitPrice() == limitPrice && order.remainingQuantity() == quantity) {
                throw new IllegalArgumentException("지정가 또는 미체결 수량을 변경해 주세요.");
            }

            // 원주문의 잔량 종료, 자산 재예약과 정정 주문 접수를 한 트랜잭션으로 처리합니다.
            closeWorkingOrder(order, quantity);
            return submitOrderLocked(accountId, clientOrderId, order.side(), limitPrice, quantity, orderId,
                    order.routingPolicy(), symbol);
        }));
    }

    private static void validateOrderRequest(String clientOrderId, Long limitPrice, int quantity) {
        if (clientOrderId == null || !clientOrderId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("요청 ID는 영문·숫자·하이픈·밑줄 1~64자로 입력해 주세요.");
        }
        if (quantity <= 0) throw new IllegalArgumentException("주문 수량은 1주 이상이어야 합니다.");
        if (limitPrice != null && limitPrice <= 0) throw new IllegalArgumentException("지정가는 1원 이상이어야 합니다.");
    }

    private OrderResult findMatchingRequest(int accountId, String clientOrderId, Long originalOrderId,
                                            Long limitPrice, int quantity) {
        OrderResult existing = repository.findOrder(accountId, clientOrderId);
        if (existing != null && (!Objects.equals(existing.originalOrderId(), originalOrderId)
                || !Objects.equals(existing.limitPrice(), limitPrice) || existing.requestedQuantity() != quantity)) {
            throw requestIdConflict();
        }
        return existing;
    }

    private static OrderConflictException requestIdConflict() {
        return new OrderConflictException("같은 요청 ID로 주문 내용을 변경할 수 없습니다.");
    }

    private static boolean matchesRequestedSymbol(StockSymbol requestedSymbol, String recordedCode) {
        if (requestedSymbol != null) return requestedSymbol.code().equals(recordedCode);
        // A missing symbol previously meant VIRTUAL01. Already committed requests keep that meaning on retry.
        return StockSymbol.DEFAULT.code().equals(recordedCode) || StockSymbol.VIRTUAL.code().equals(recordedCode);
    }

    private OrderResult submitOrderLocked(int accountId, String clientOrderId, String side,
                                          Long limitPrice, int quantity, Long originalOrderId, RoutingPolicy routingPolicy,
                                          StockSymbol symbol) {
        boolean buy = side.equals("BUY");
        boolean marketOrder = limitPrice == null;
        var hours = marketHours.current();
        requireOrderAccepted(hours, marketOrder);
        final long limitCommitment;
        try {
            limitCommitment = buy && !marketOrder ? SimulatedFees.buyReservation(limitPrice, quantity, true) : 0;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("주문 금액이 처리 가능한 범위를 초과합니다.");
        }
        // 기존 대기 주문에 접수 순서대로 먼저 체결 기회를 줍니다.
        matchWorkingOrdersLocked(symbol, hours, null);
        var plan = sorService.plan(side, limitPrice, quantity, executableQuotes(symbol, hours.matchingMarkets(marketOrder)), routingPolicy);
        if (marketOrder && plan.allocatedQuantity() == 0) {
            throw new OrderRejectionException("NO_LIQUIDITY", "현재 체결 가능한 상대 호가 잔량이 없습니다.");
        }
        long commitment = buy && marketOrder ? plan.settlementAmount() : limitCommitment;
        AccountSnapshot account = repository.getAccount(accountId, symbol, false);
        if (buy && account.availableCash() < commitment) {
            throw new OrderRejectionException("INSUFFICIENT_CASH", "주문 가능 금액이 부족합니다. 수수료 포함 필요 "
                    + commitment + "원, 현재 가능 " + account.availableCash() + "원입니다.");
        }
        if (!buy && account.availableQuantity() < quantity) {
            throw new OrderRejectionException("INSUFFICIENT_SHARES", symbol.displayName() + " 매도 가능 수량이 부족합니다. 요청 "
                    + quantity + "주, 현재 가능 " + account.availableQuantity() + "주입니다.");
        }
        int reservedShares = buy ? 0 : marketOrder ? plan.allocatedQuantity() : quantity;
        if (buy) repository.reserveCash(accountId, commitment);
        else repository.reserveShares(accountId, symbol, reservedShares);
        long orderId = repository.insertOrder(accountId, clientOrderId, side, limitPrice, quantity,
                commitment, reservedShares, repository.getAccount(accountId, symbol, false), originalOrderId, routingPolicy, symbol);
        applyOrderPlanLocked(repository.getOrder(accountId, orderId), plan, null);
        return repository.getOrder(accountId, orderId);
    }

    private static void requireOrderAccepted(MarketHours.Status hours, boolean marketOrder) {
        if (hours.markets().stream().noneMatch(MarketHours.VenueStatus::acceptsLimit)) {
            throw new OrderRejectionException("MARKET_CLOSED",
                    "장 마감 또는 주문 접수 중단 시간입니다. KRX·NXT의 장 상태를 확인해 주세요.");
        }
        if (marketOrder && hours.matchingMarkets(true).isEmpty()) {
            throw new OrderRejectionException("ORDER_TYPE_UNAVAILABLE", "현재 시간에는 지정가 주문만 접수할 수 있습니다.");
        }
    }

    private List<SorService.MarketQuote> executableQuotes(StockSymbol symbol, Set<String> markets) {
        if (markets.isEmpty()) return List.of();
        return repository.getBook(symbol).quotes().stream().filter(quote -> markets.contains(quote.market())).toList();
    }

    public OrderResult cancel(int accountId, long orderId) {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            repository.getAccount(accountId, true);
            OrderResult order = repository.getOrder(accountId, orderId);
            if (order.status().equals("CANCELLED")) return order;
            if (!order.cancellable()) {
                throw new OrderConflictException("취소 가능한 대기 주문이 아닙니다. 이미 체결됐거나 이전 단계의 종료 기록입니다.");
            }
            closeWorkingOrder(order, 0);
            return repository.getOrder(accountId, orderId);
        }));
    }

    private void closeWorkingOrder(OrderResult order, int amendedQuantity) {
        int accountId = order.accountAfter().accountId();
        StockSymbol symbol = StockSymbol.from(order.symbol());
        if (order.side().equals("BUY")) repository.releaseCash(accountId, order.reservedCash());
        else repository.releaseShares(accountId, symbol, order.reservedQuantity());
        repository.closeOrder(order, amendedQuantity, repository.getAccount(accountId, symbol, false));
    }

    public int openGuestAccount() {
        return openPracticeAccount(null);
    }

    int openPracticeAccount(byte[] accessCodeHash) {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            int accountId = repository.insertPracticeAccount(PRACTICE_INITIAL_CASH);
            if (accessCodeHash != null) repository.insertAccessCode(accountId, accessCodeHash);
            return accountId;
        }));
    }

    void replacePresenterAccessCode(byte[] accessCodeHash) {
        transaction.executeWithoutResult(status -> {
            repository.getAccount(PRESENTER_ACCOUNT_ID, true);
            repository.replaceAccessCode(PRESENTER_ACCOUNT_ID, accessCodeHash);
        });
    }

    public int restartGuestAccount(int accountId) {
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            checkTradingAccountLocked(accountId);
            closeGuestAccountLocked(accountId);
            return repository.insertPracticeAccount(PRACTICE_INITIAL_CASH);
        }));
    }

    private void checkTradingAccountLocked(int accountId) {
        if (endingGuestAccounts.contains(accountId)) {
            throw new OrderConflictException("체험이 종료된 계좌입니다. 현재 계좌를 확인해 주세요.");
        }
        repository.getAccount(accountId, true);
    }

    void closeAbandonedGuestAccounts() {
        transaction.executeWithoutResult(status -> {
            repository.lockTradingState();
            repository.getActiveGuestAccountIds().forEach(this::closeGuestAccountLocked);
        });
    }

    void endGuestSession(int accountId) {
        if (endingGuestAccounts.add(accountId)) completeGuestClosure(accountId);
    }

    @Scheduled(fixedDelay = 5_000)
    void retryGuestClosures() {
        endingGuestAccounts.forEach(this::completeGuestClosure);
    }

    private void completeGuestClosure(int accountId) {
        try {
            transaction.executeWithoutResult(status -> {
                repository.lockTradingState();
                closeGuestAccountLocked(accountId);
            });
            endingGuestAccounts.remove(accountId);
        } catch (RuntimeException exception) {
            log.warn("Guest account {} could not be closed and will be retried", accountId, exception);
        }
    }

    private void closeGuestAccountLocked(int accountId) {
        if (repository.closeGuestAccount(accountId)) {
            repository.getOpenOrders(accountId, null).forEach(order -> closeWorkingOrder(order, 0));
        }
    }

    /** 현재 3단계 호가에서 대기 주문을 접수 순서대로 다시 검사합니다. */
    public List<OrderResult> matchOrders(StockSymbol symbol) {
        symbol.requireActive();
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            return matchWorkingOrdersLocked(symbol, marketHours.current(), null).stream()
                    .filter(order -> order.accountAfter().accountId() == PRESENTER_ACCOUNT_ID).toList();
        }));
    }

    @Scheduled(fixedDelay = 1000)
    void matchAfterSessionChange() {
        boolean demoChanged = demoClockChanged.getAndSet(false);
        var markets = marketHours.current().matchingMarkets(false);
        if (markets.isEmpty() || (!demoChanged && markets.equals(previousExecutionMarkets))) {
            previousExecutionMarkets = markets;
            return;
        }
        try {
            previousExecutionMarkets = Objects.requireNonNull(transaction.execute(status -> {
                repository.lockTradingState();
                var hours = marketHours.current();
                for (StockSymbol symbol : StockSymbol.active()) matchWorkingOrdersLocked(symbol, hours, null);
                return hours.matchingMarkets(false);
            }));
        } catch (RuntimeException error) {
            demoClockChanged.set(true);
            throw error;
        }
    }

    private List<OrderResult> matchWorkingOrdersLocked(StockSymbol symbol, MarketHours.Status hours, Long quoteEventId) {
        var markets = hours.matchingMarkets(false);
        if (markets.isEmpty()) return List.of();
        List<OrderResult> changed = new ArrayList<>();
        for (OrderResult order : repository.getOrdersToMatch(symbol)) {
            int accountId = order.accountAfter().accountId();
            if (endingGuestAccounts.contains(accountId)) continue;
            var plan = sorService.plan(order.side(), order.limitPrice(), order.remainingQuantity(),
                    executableQuotes(symbol, markets), order.routingPolicy(), order.feesEnabled());
            if (applyOrderPlanLocked(order, plan, quoteEventId)) {
                changed.add(repository.getOrder(accountId, order.orderId()));
            }
        }
        return List.copyOf(changed);
    }

    private boolean applyOrderPlanLocked(OrderResult order, SorService.RoutePlan plan, Long quoteEventId) {
        if (plan.allocatedQuantity() == 0) return false;
        boolean buy = order.side().equals("BUY");
        int accountId = order.accountAfter().accountId();
        StockSymbol symbol = StockSymbol.from(order.symbol());
        AccountSnapshot account = repository.getAccount(accountId, symbol, true);
        try {
            if (buy) Math.addExact(account.quantity(), plan.allocatedQuantity());
            else Math.addExact(account.cash(), plan.settlementAmount());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("체결 후 계좌 금액 또는 보유수량이 처리 가능한 범위를 초과합니다.");
        }
        long cashReleased = 0;
        if (buy) {
            int remaining = order.remainingQuantity() - plan.allocatedQuantity();
            long remainingReserve = order.limitPrice() == null ? 0
                    : SimulatedFees.buyReservation(order.limitPrice(), remaining, order.feesEnabled());
            cashReleased = order.reservedCash() - remainingReserve;
            repository.applyFill(accountId, symbol, plan.settlementAmount(), plan.allocatedQuantity(), cashReleased);
        } else repository.applySellFill(accountId, symbol, plan.settlementAmount(), plan.allocatedQuantity());
        for (SorService.Allocation allocation : plan.allocations()) {
            if (buy) repository.consumeQuote(symbol, allocation.market(), allocation.level(), allocation.quantity());
            else repository.consumeBidQuote(symbol, allocation.market(), allocation.level(), allocation.quantity());
            long tradeId = repository.insertTrade(accountId, symbol, order.side(), allocation);
            long executionId = repository.insertExecution(order.orderId(), tradeId, allocation);
            if (quoteEventId != null) repository.linkQuoteEventExecution(quoteEventId, executionId);
        }
        repository.recordFill(order, plan, cashReleased, repository.getAccount(accountId, symbol, false));
        return true;
    }

    public List<OrderResult> getOrders(int accountId, StockSymbol symbol) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOrders(accountId, symbol)));
    }

    public List<OrderResult> getOpenOrders(int accountId, StockSymbol symbol) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOpenOrders(accountId, symbol)));
    }

    public OrderResult getOrder(int accountId, long orderId) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getOrder(accountId, orderId)));
    }

    public List<SorService.MarketQuote> resetQuotes(StockSymbol symbol) {
        symbol.requireActive();
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            repository.getBook(symbol);
            repository.setQuotes(symbol, MarketBooks.basicExample(symbol));
            matchWorkingOrdersLocked(symbol, marketHours.current(), null);
            return repository.getQuotes(symbol);
        }));
    }

    /** 한 주기의 모든 종목을 같은 트랜잭션에서 반영합니다. */
    List<QuoteUpdateResult> updateQuoteCycle(String cycleId, List<QuoteChange> changes) {
        return Objects.requireNonNull(transaction.execute(status -> changes.stream().map(change -> updateQuotes(
                cycleId + "-" + change.symbol().bookId(), change.expectedRevision(), change.quotes(), change.symbol())).toList()));
    }

    /** 호가 변경, 대기 주문 재검사, 계좌·체결 반영, 변경 영수증을 함께 커밋합니다. */
    public QuoteUpdateResult updateQuotes(String clientEventId, long expectedRevision,
                                          List<SorService.MarketQuote> submittedQuotes, StockSymbol symbol) {
        if (clientEventId == null || !clientEventId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("호가 요청 ID는 영문·숫자·하이픈·밑줄 1~64자로 입력해 주세요.");
        }
        if (expectedRevision < 0) throw new IllegalArgumentException("시장 상태 번호를 확인해 주세요.");
        var quotes = MarketBooks.normalize(submittedQuotes);
        return Objects.requireNonNull(transaction.execute(status -> {
            repository.lockTradingState();
            repository.getAccount(PRESENTER_ACCOUNT_ID, true);
            QuoteUpdateResult existing = repository.findQuoteEvent(PRESENTER_ACCOUNT_ID, clientEventId);
            if (existing != null) {
                if (!matchesRequestedSymbol(symbol, existing.bookAfter().symbol()) || existing.expectedRevision() != expectedRevision
                        || !MarketBooks.normalize(existing.submittedQuotes()).equals(quotes)) {
                    throw new OrderConflictException("같은 호가 요청 ID로 내용을 변경할 수 없습니다.");
                }
                // 오래된 버전 번호로 재시도해도 이미 처리한 요청은 저장한 영수증만 반환합니다.
                return existing;
            }
            StockSymbol instrument = symbol == null ? StockSymbol.DEFAULT : symbol;
            instrument.requireActive();
            BookSnapshot before = repository.getBook(instrument);
            if (before.revision() != expectedRevision) {
                throw new OrderConflictException("다른 주문이나 호가 반영으로 시장 상태가 바뀌었습니다. "
                        + "현재 상태를 새로고침하고 가격·잔량을 확인한 뒤 다시 반영해 주세요.");
            }
            repository.setQuotes(instrument, MarketBooks.applyTo(quotes, before.quotes()));
            long eventId = repository.insertQuoteEvent(PRESENTER_ACCOUNT_ID, clientEventId, expectedRevision,
                    quotes, repository.getBook(instrument), repository.getAccount(PRESENTER_ACCOUNT_ID, instrument, false));
            matchWorkingOrdersLocked(instrument, marketHours.current(), eventId);
            repository.finishQuoteEvent(PRESENTER_ACCOUNT_ID, eventId, repository.getBook(instrument),
                    repository.getAccount(PRESENTER_ACCOUNT_ID, instrument, false));
            return Objects.requireNonNull(repository.findQuoteEvent(PRESENTER_ACCOUNT_ID, clientEventId));
        }));
    }

    public List<QuoteUpdateResult> getQuoteEvents(StockSymbol symbol) {
        return Objects.requireNonNull(readTransaction.execute(status -> repository.getQuoteEvents(PRESENTER_ACCOUNT_ID, symbol)));
    }

    public record Execution(String market, int level, long price, int quantity, long amount, long fee) {}

    public record BookSnapshot(long revision, List<SorService.MarketQuote> quotes, String symbol) {}

    record QuoteChange(StockSymbol symbol, long expectedRevision, List<SorService.MarketQuote> quotes) {}

    public record PositionSnapshot(String symbol, String name, AccountSnapshot account, PortfolioValuation valuation) {}

    public record PortfolioSnapshot(AccountSnapshot account, BookSnapshot book, PortfolioValuation valuation,
                                    MarketHours.Status marketHours, List<PositionSnapshot> positions,
                                    Map<String, Long> executedVolume,
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAssets,
                                    boolean hasArchivedHistory, List<StockSymbol.SymbolInfo> archivedSymbols,
                                    SorConsent sorConsent) {
        @JsonProperty("feeRatesBps")
        public Map<String, Integer> feeRatesBps() { return SimulatedFees.RATES; }
    }

    public record SorConsent(int version, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant agreedAt) {}

    public record QuoteEventExecution(long orderId, String side, String market, int level, long price, int quantity,
                                      long amount, long fee) {}

    public record QuoteUpdateResult(long eventId, String clientEventId, long expectedRevision,
                                    BookSnapshot bookAfter, List<SorService.MarketQuote> submittedQuotes,
                                    long filledQuantity, long totalAmount, List<QuoteEventExecution> executions,
                                    AccountSnapshot accountAfter, String createdAt) {
        @JsonProperty("totalFee")
        public long totalFee() { return executions.stream().mapToLong(QuoteEventExecution::fee).sum(); }
    }

    /** cash는 총 현금, reservedCash는 그중 예약된 금액입니다. */
    public record AccountSnapshot(int accountId, long cash, long reservedCash,
                                  long availableCash, int quantity, int reservedQuantity, int availableQuantity) {}

    public record OrderResult(long orderId, String clientOrderId, String side, String orderType, String status,
                              Long limitPrice, int requestedQuantity, int filledQuantity,
                              int remainingQuantity, int cancelledQuantity, long reservedCash, int reservedQuantity,
                              boolean cancellable, long totalAmount, List<Execution> executions,
                              AccountSnapshot accountAfter, String createdAt, String updatedAt,
                              Long originalOrderId, int amendedQuantity, Long replacementOrderId, RoutingPolicy routingPolicy,
                              String symbol, boolean feesEnabled) {
        @JsonProperty("totalFee")
        public long totalFee() { return executions.stream().mapToLong(Execution::fee).sum(); }

        @JsonProperty("settlementAmount")
        public long settlementAmount() { return SimulatedFees.settlement(totalAmount, totalFee(), "BUY".equals(side)); }
    }

    public static class OrderConflictException extends RuntimeException {
        public OrderConflictException(String message) { super(message); }
    }

    public static class OrderRejectionException extends IllegalArgumentException {
        private final String code;

        public OrderRejectionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException() { super("주문을 찾을 수 없습니다."); }
    }
}

