package com.example.stock;

import static com.example.stock.StockAccountSession.PRESENTER_ACCOUNT_ID;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.WebUtils;

@RestController
public class AccountAccessController {
    private static final String NEW_CODE = AccountAccessController.class.getName() + ".newCode";
    private final MarketSimulator market;
    private final SorRepository repository;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;
    private final String accessPage;
    private final String presenterPage;

    public AccountAccessController(MarketSimulator market, SorRepository repository,
                                   SecurityContextRepository contexts, CsrfTokenRepository csrfTokens,
                                   @Value("classpath:static/login.html") Resource accessPage,
                                   @Value("classpath:static/presenter.html") Resource presenterPage) throws IOException {
        this.market = market;
        this.repository = repository;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
        this.accessPage = accessPage.getContentAsString(StandardCharsets.UTF_8);
        this.presenterPage = presenterPage.getContentAsString(StandardCharsets.UTF_8);
    }

    @GetMapping(value = "/login.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> accountPage(HttpServletRequest request, Authentication authentication, CsrfToken csrf) {
        IssuedCode issued = issuedTo(request.getSession(false), authentication);
        boolean connected = authentication != null;
        String page = accessPage
                .replace("{{title}}", issued == null ? "모의계좌 접속" : "모의계좌 발급 완료")
                .replace("{{entryHidden}}", connected ? "hidden" : "")
                .replace("{{issuedHidden}}", issued == null ? "hidden" : "")
                .replace("{{activeHidden}}", connected && issued == null ? "" : "hidden")
                .replace("{{workspacePath}}", request.isUserInRole("TRADER") ? "/sor-app.html?mode=presenter" : "/sor-app.html")
                .replace("{{accountNumber}}", issued == null ? "" : HtmlUtils.htmlEscape(issued.accountNumber()))
                .replace("{{accessCode}}", issued == null ? "" : HtmlUtils.htmlEscape(issued.code()));
        String message = "";
        boolean error = true;
        if (request.getParameter("invalid-code") != null) {
            message = "체험 코드가 올바르지 않아요. 발급받은 코드를 다시 입력해 주세요.";
        } else if (request.getParameter("expired") != null) {
            message = "접속 화면을 갱신했어요. 체험 코드를 다시 입력해 주세요.";
        } else if (request.getParameter("logout") != null) {
            message = "계좌 접속이 종료됐어요. 같은 코드로 다시 접속할 수 있어요.";
            error = false;
        } else if (request.getParameter("unavailable") != null) {
            message = "계좌 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.";
        }
        return accessForm(page, csrf, message, error);
    }

    @GetMapping(value = "/presenter.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> presenterPage(HttpServletRequest request, CsrfToken csrf) {
        String message = "";
        boolean error = true;
        if (request.getParameter("error") != null) {
            message = "아이디와 비밀번호를 다시 확인해 주세요.";
        } else if (request.getParameter("expired") != null) {
            message = "접속 화면을 갱신했어요. 아이디와 비밀번호를 다시 입력해 주세요.";
        } else if (request.getParameter("session") != null) {
            message = "발표자 접속을 확인할 수 없어요. 이 사이트의 쿠키를 허용하고 다시 접속해 주세요.";
        } else if (request.getParameter("logout") != null) {
            message = "발표자 접속이 종료됐어요.";
            error = false;
        }
        return accessForm(presenterPage, csrf, message, error);
    }

    private static ResponseEntity<String> accessForm(String page, CsrfToken csrf, String message, boolean error) {
        // 폼과 같은 응답에 토큰을 넣어 브라우저가 바로 제출할 수 있게 합니다.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .body(page.replace("{{csrfParameter}}", HtmlUtils.htmlEscape(csrf.getParameterName()))
                        .replace("{{csrfToken}}", HtmlUtils.htmlEscape(csrf.getToken()))
                        .replace("{{messageClass}}", error ? "error" : "")
                        .replace("{{messageHidden}}", message.isEmpty() ? "hidden" : "")
                        .replace("{{message}}", HtmlUtils.htmlEscape(message)));
    }

    @PostMapping("/access/open")
    public ResponseEntity<Void> open(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            Authentication current = contexts.loadDeferredContext(request).get().getAuthentication();
            if (current != null) return alreadyConnected(session, current, request);

            AccountAccessCode code = AccountAccessCode.issue();
            int accountId = market.openPracticeAccount(code.hash());
            connect(accountId, request, response);
            // Keep the issue receipt until the new account holder opens the trading screen.
            session.setAttribute(NEW_CODE, new IssuedCode(accountId, code.value()));
            return redirect(request, "/login.html?issued");
        }
    }

    @PostMapping("/access/connect")
    public ResponseEntity<Void> connect(@RequestParam(name = "code", required = false) String code,
                                        HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession();
        synchronized (WebUtils.getSessionMutex(session)) {
            Authentication current = contexts.loadDeferredContext(request).get().getAuthentication();
            if (current != null) return alreadyConnected(session, current, request);

            byte[] hash = AccountAccessCode.hashOf(code);
            Integer accountId = hash == null ? null : repository.findAccountByAccessCode(hash);
            if (accountId == null) return redirect(request, "/login.html?invalid-code");

            connect(accountId, request, response);
            return redirect(request, "/sor-app.html");
        }
    }

    @GetMapping("/api/account-access/issued")
    public ResponseEntity<IssuedCode> issued(Authentication authentication, HttpSession session) {
        return ResponseEntity.ofNullable(issuedTo(session, authentication));
    }

    @PostMapping("/access/continue")
    public ResponseEntity<Void> continueToTrading(HttpSession session, HttpServletRequest request) {
        session.removeAttribute(NEW_CODE);
        return redirect(request, "/sor-app.html");
    }

    @PostMapping("/api/account-access/presenter-code")
    public IssuedCode issuePresenterCode() {
        AccountAccessCode code = AccountAccessCode.issue();
        market.replacePresenterAccessCode(code.hash());
        return new IssuedCode(PRESENTER_ACCOUNT_ID, code.value());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> unavailable(HttpServletRequest request) {
        if (request.getServletPath().startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "체험 코드를 발급하지 못했어요. 잠시 후 다시 시도해 주세요."));
        }
        return redirect(request, "/login.html?unavailable");
    }

    private void connect(int accountId, HttpServletRequest request, HttpServletResponse response) {
        request.changeSessionId();
        csrfTokens.saveToken(null, request, response);
        StockAccountSession.signIn(accountId, "ROLE_ACCOUNT", contexts, request, response);
    }

    private static IssuedCode issuedTo(HttpSession session, Authentication authentication) {
        if (session != null && session.getAttribute(NEW_CODE) instanceof IssuedCode issued
                && authentication != null && authentication.getPrincipal() instanceof StockAccountSession account
                && account.accountId() == issued.accountId()) {
            return issued;
        }
        return null;
    }

    private static ResponseEntity<Void> alreadyConnected(HttpSession session, Authentication authentication,
                                                         HttpServletRequest request) {
        return redirect(request, issuedTo(session, authentication) != null ? "/login.html?issued" : "/login.html?connected");
    }

    private static ResponseEntity<Void> redirect(HttpServletRequest request, String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(request.getContextPath() + path)).build();
    }

    public record IssuedCode(int accountId, String code) implements Serializable {
        @JsonProperty("accountNumber")
        public String accountNumber() {
            return StockAccountSession.numberFor(accountId);
        }
    }
}
