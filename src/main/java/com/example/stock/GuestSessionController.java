package com.example.stock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.WebUtils;

@RestController
public class GuestSessionController {
    private final MarketSimulator market;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    public GuestSessionController(MarketSimulator market, SecurityContextRepository contexts,
                                  CsrfTokenRepository csrfTokens) {
        this.market = market;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @PostMapping("/guest/start")
    public ResponseEntity<Void> start(HttpServletRequest request, HttpServletResponse response) {
        synchronized (WebUtils.getSessionMutex(request.getSession())) {
            if (contexts.loadDeferredContext(request).get().getAuthentication() == null) {
                int accountId = market.openGuestAccount();
                request.changeSessionId();
                csrfTokens.saveToken(null, request, response);
                StockAccountSession.signIn(accountId, "ROLE_GUEST", contexts, request, response);
            }
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(request.getContextPath() + "/sor-app.html")).build();
    }

    @GetMapping("/api/session")
    public StockAccountSession session(Authentication authentication) {
        return StockAccountSession.from(authentication);
    }

    @PostMapping("/api/guest/restart")
    public StockAccountSession restart(@RequestBody RestartRequest body, HttpServletRequest request,
                                       HttpServletResponse response) {
        synchronized (WebUtils.getSessionMutex(request.getSession())) {
            var current = StockAccountSession.from(contexts.loadDeferredContext(request).get().getAuthentication());
            if (!current.guest()) throw new AccessDeniedException("게스트 계좌에서 체험을 다시 시작해 주세요.");
            // Repeating a request for the previous account returns the already-issued account.
            if (body.accountId() != current.accountId()) return current;
            return StockAccountSession.signIn(market.restartGuestAccount(current.accountId()), "ROLE_GUEST", contexts,
                    request, response);
        }
    }

    public record RestartRequest(int accountId) {}
}
