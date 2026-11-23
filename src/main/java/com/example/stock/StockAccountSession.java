package com.example.stock;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

public record StockAccountSession(int accountId, boolean guest) implements Serializable {
    static final int PRESENTER_ACCOUNT_ID = 1;

    @JsonProperty("accountNumber")
    public String accountNumber() {
        return numberFor(accountId);
    }

    static String numberFor(int accountId) {
        return String.format(Locale.ROOT, "SIM-%010d", accountId);
    }

    static void requireAccountNumber(int accountId, String number) {
        if (number == null || number.isBlank()) {
            throw new MarketSimulator.OrderRejectionException("ACCOUNT_NUMBER_REQUIRED", "주문 계좌번호를 입력해 주세요.");
        }
        if (!numberFor(accountId).equals(number.strip())) {
            throw new MarketSimulator.OrderRejectionException("ACCOUNT_NUMBER_MISMATCH",
                    "입력한 계좌번호가 현재 접속한 계좌와 일치하지 않습니다. 내 계좌번호를 사용해 주세요.");
        }
    }

    static StockAccountSession from(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            if (authentication.getPrincipal() instanceof StockAccountSession session) return session;
            if (authentication.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_TRADER"))) {
                return new StockAccountSession(PRESENTER_ACCOUNT_ID, false);
            }
        }
        throw new AccessDeniedException("접속한 계좌를 확인할 수 없습니다.");
    }

    static StockAccountSession signIn(int accountId, String authority, SecurityContextRepository contexts,
                                      HttpServletRequest request, HttpServletResponse response) {
        var account = new StockAccountSession(accountId, true);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null,
                AuthorityUtils.createAuthorityList(authority));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return account;
    }

    int checkAccount(Integer expectedAccountId) {
        // The expected ID only detects a stale tab. The session always selects the account.
        if (expectedAccountId != null && expectedAccountId != accountId) {
            throw new MarketSimulator.OrderConflictException("계좌가 바뀌었습니다. 화면을 새로고침해 주세요.");
        }
        return accountId;
    }
}
