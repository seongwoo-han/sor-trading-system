package com.example.stock;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MarketHours {
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final LocalDate EXAM_DAY = LocalDate.of(2026, 11, 19);
    private static final List<Window> KRX = List.of(
            window("08:30", "09:00", "시가 단일가 접수", Mode.RECEIPT),
            window("09:00", "15:20", "정규장", Mode.REGULAR),
            window("15:20", "15:30", "종가 단일가 접수", Mode.RECEIPT),
            window("16:00", "20:00", "애프터마켓", Mode.LIMIT_ONLY));
    private static final List<Window> NXT = List.of(
            window("08:00", "08:50", "프리마켓", Mode.LIMIT_ONLY),
            window("09:00:30", "15:20", "메인마켓", Mode.REGULAR),
            window("15:30", "15:40", "애프터마켓 호가 접수", Mode.RECEIPT),
            window("15:40", "20:00", "애프터마켓", Mode.LIMIT_ONLY));
    private static final List<Window> KRX_EXAM_DAY = List.of(
            window("09:30", "10:00", "시가 단일가 접수", Mode.RECEIPT),
            window("10:00", "16:20", "정규장", Mode.REGULAR),
            window("16:20", "16:30", "종가 단일가 접수", Mode.RECEIPT),
            window("17:00", "20:00", "애프터마켓", Mode.LIMIT_ONLY));
    private static final List<Window> NXT_EXAM_DAY = List.of(
            window("10:00:30", "16:20", "메인마켓", Mode.REGULAR),
            window("16:30", "16:40", "애프터마켓 호가 접수", Mode.RECEIPT),
            window("16:40", "20:00", "애프터마켓", Mode.LIMIT_ONLY));

    private final Clock clock;
    private final Set<LocalDate> closedDates;
    private volatile LocalTime demoTime;

    @Autowired
    public MarketHours(@Value("${stock.market.demo-time:}") String demoTime,
                       @Value("${stock.market.closed-dates:}") String closedDates) {
        this(Clock.system(SEOUL), demoTime.isBlank() ? null : parseTime(demoTime),
                Arrays.stream(closedDates.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                        .map(LocalDate::parse).collect(Collectors.toUnmodifiableSet()));
    }

    MarketHours(Clock clock, LocalTime demoTime, Set<LocalDate> closedDates) {
        this.clock = clock;
        this.demoTime = demoTime;
        this.closedDates = Set.copyOf(closedDates);
    }

    static LocalTime parseTime(String value) {
        if (value == null) return null;
        try {
            var time = LocalTime.parse(value);
            if (time.getNano() == 0) return time;
        } catch (DateTimeParseException ignored) {
            // The demo clock uses whole seconds.
        }
        throw new IllegalArgumentException("시연 시각은 HH:mm 또는 HH:mm:ss 형식으로 입력해 주세요.");
    }

    void setDemoTime(LocalTime time) {
        demoTime = time;
    }

    public Status current() {
        LocalTime override = demoTime;
        var now = ZonedDateTime.now(clock.withZone(SEOUL));
        return statusAt(now.toLocalDate(), override == null ? now.toLocalTime() : override, override != null);
    }

    Status previewAt(LocalTime time) {
        return statusAt(LocalDate.now(clock.withZone(SEOUL)), time, true);
    }

    private Status statusAt(LocalDate date, LocalTime time, boolean demo) {
        boolean exam = !demo && date.equals(EXAM_DAY);
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean closed = !demo && (weekend || closedDates.contains(date));
        String calendar = closed ? "휴장일" : exam ? "수능일 변경 시간" : "정상 거래일";
        return new Status(date, time.format(TIME_FORMAT), demo, calendar, List.of(
                describe(MarketVenue.KRX, time, closed ? List.of() : exam ? KRX_EXAM_DAY : KRX),
                describe(MarketVenue.NXT, time, closed ? List.of() : exam ? NXT_EXAM_DAY : NXT)));
    }

    private static VenueStatus describe(MarketVenue market, LocalTime time, List<Window> windows) {
        return windows.stream().filter(window -> window.contains(time)).findFirst()
                .map(window -> new VenueStatus(market.name(), window.label(), window.start().toString(),
                        window.end().toString(), true, window.mode() != Mode.RECEIPT, window.mode() == Mode.REGULAR))
                .orElseGet(() -> new VenueStatus(market.name(), "일반 주문 접수 중단", null, null, false, false, false));
    }

    private static Window window(String start, String end, String label, Mode mode) {
        return new Window(LocalTime.parse(start), LocalTime.parse(end), label, mode);
    }

    private enum Mode { RECEIPT, REGULAR, LIMIT_ONLY }

    private record Window(LocalTime start, LocalTime end, String label, Mode mode) {
        boolean contains(LocalTime time) {
            return !time.isBefore(start) && time.isBefore(end);
        }
    }

    public record VenueStatus(String market, String session, String start, String end,
                              boolean acceptsLimit, boolean executesLimit, boolean acceptsMarket) {}

    public record Status(LocalDate date, String time, boolean demo, String calendar, List<VenueStatus> markets) {
        Set<String> matchingMarkets(boolean marketOrder) {
            return markets.stream().filter(market -> marketOrder ? market.acceptsMarket() : market.executesLimit())
                    .map(VenueStatus::market).collect(Collectors.toUnmodifiableSet());
        }
    }
}
