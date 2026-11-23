package com.example.stock;

import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingEvent;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

@Component
public class GuestSessionCleanup implements ServletContextListener, HttpSessionAttributeListener {
    private final MarketSimulator market;

    public GuestSessionCleanup(MarketSimulator market) {
        this.market = market;
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        // 이전 버전에서 발급한 임시 게스트 계좌를 정리합니다.
        market.closeAbandonedGuestAccounts();
    }

    @Override
    public void attributeRemoved(HttpSessionBindingEvent event) {
        if (!SPRING_SECURITY_CONTEXT_KEY.equals(event.getName())) return;
        var previous = guestAccount(event.getValue());
        if (previous != null) market.endGuestSession(previous.accountId());
    }

    @Override
    public void attributeReplaced(HttpSessionBindingEvent event) {
        if (!SPRING_SECURITY_CONTEXT_KEY.equals(event.getName())) return;
        var previous = guestAccount(event.getValue());
        if (previous == null) return;

        Object replacement;
        try {
            replacement = event.getSession().getAttribute(SPRING_SECURITY_CONTEXT_KEY);
        } catch (IllegalStateException invalidatedSession) {
            replacement = null;
        }
        if (!previous.equals(guestAccount(replacement))) market.endGuestSession(previous.accountId());
    }

    private StockAccountSession guestAccount(Object value) {
        if (value instanceof SecurityContext context) {
            var authentication = context.getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof StockAccountSession account
                    && authentication.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_GUEST"))) {
                return account;
            }
        }
        return null;
    }
}
