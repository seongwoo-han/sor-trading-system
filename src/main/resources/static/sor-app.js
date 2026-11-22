/* SOR Desk: existing Spring Boot API client. No local account or simulated fills. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const basePendingKey = 'stock.sor.pendingAction.v4';
  const pendingKey = () => state.session.guest ? `${basePendingKey}.${state.session.accountId}` : basePendingKey;
  const verificationKey = 'stock.sor.check.active.v1';
  const intMax = 2147483647;
  const defaultSymbol = '005930';
  const previousDefaultSymbol = 'VIRTUAL01';
  const marketLabels = {KRX: '한국거래소', NXT: '넥스트레이드'};
  const marketNames = Object.keys(marketLabels);
  const routingLabels = {PRICE_FIRST: '가격 우선', QUANTITY_FIRST: '수량 우선'};
  const routingLabel = policy => routingLabels[policy ?? 'PRICE_FIRST'];
  const levelOf = quote => quote.level ?? 1;
  const sourceLabel = source => source === 'EXAMPLE' ? '기본 예시' : source === 'DEPTH_EXAMPLE' ? '3단계 예시' : '조회한 현재 시장';
  const sideText = side => side === 'SELL' ? '매도' : '매수';
  const priceOf = (q,side) => side === 'SELL' ? q.bidPrice : q.askPrice;
  const quantityOf = (q,side) => side === 'SELL' ? q.bidQuantity : q.askQuantity;
  const reservedAsset = order => order.side === 'SELL' ? '예약 수량 '+shares(order.reservedQuantity) : '예약금 '+money(order.reservedCash);
  const views = {
    dashboard: ['대시보드', '계좌와 시장을 한눈에', '두 시장의 호가를 살펴보고, 더 나은 조건으로 주문하세요.'],
    trade: ['매수·매도', '주식 매수·매도', '호가를 보고 가격과 수량을 정한 뒤 주문하세요.'],
    orders: ['주문내역', '주문·체결내역', '선택 종목의 체결, 정정, 취소 내역을 확인하세요.'],
    analysis: ['SOR 분석', '한 시장과 두 시장의 차이', '장 시간대를 골라 시장별 주문 가능 여부와 예상 체결을 비교하세요.'],
    markets: ['시장관리', '시장 상황을 직접 바꿔 보세요', '두 모의시장의 가격과 잔량을 바꾸고 대기 주문에 반영하세요.']
  };
  const state = {side:'BUY', busy: false, connected: false, session: null, account: null, book: null, hours: null, valuation: null, orders: [], open: [], trades: [],
    symbol: defaultSymbol, symbols: [], positions: [], executedVolume: null, totalAssets: null,
    archivedHistory: null, archivedSymbols: [], feeRatesBps: {}, sorConsent: null,
    events: null, view: 'dashboard', pending: null, storageAvailable: true, orderSeeded: false,
    draftRevision: null, marketDirty: false, preview: null, comparison: null, comparisonStale: false,
    selectedOrder: null, detailTrigger: null, amendDraft: null, auto: null, autoKnown: false, autoUncertain: false,
    polling: false, operationVersion: 0};
  let pollTimer = null;
  const isActiveSymbol = code => state.symbols.some(symbol => symbol.code === code);
  const symbolName = code => state.symbols.find(symbol => symbol.code === code)?.name
    ?? state.archivedSymbols.find(symbol => symbol.code === code)?.name
    ?? (code === defaultSymbol ? '삼성전자' : code);
  const symbolQuery = symbol => '?symbol=' + encodeURIComponent(symbol);
  const activeAutoBook = () => state.autoUncertain || (state.auto?.running
    && (state.auto.symbols?.includes(state.symbol) ?? state.auto.symbol === state.symbol));

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  const num = value => typeof value === 'number' && Number.isFinite(value)
    ? value.toLocaleString('ko-KR', {maximumFractionDigits: 2}) : '—';
  const money = value => value == null ? '—' : num(value) + '원';
  const maxFeeRate = () => BigInt(Math.max(0, ...Object.values(state.feeRatesBps)));
  const feeBasis = 10_000n;
  const feeRateText = market => state.feeRatesBps[market] == null ? '—'
    : (state.feeRatesBps[market] / 100).toFixed(2) + '%';
  const settlementKind = side => side === 'SELL' ? '수령액' : '결제액';
  function limitBuyReserve(price, quantity) {
    const total = BigInt(price) * BigInt(quantity) * (feeBasis + maxFeeRate()) / feeBasis;
    return total <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(total) : null;
  }
  function fillAmount(amount, fee) {
    const value = el('span', 'fill-amount', money(amount));
    value.append(el('small', '', '수수료 ' + money(fee ?? 0)));
    return value;
  }
  const orderPriceText = order => order.orderType === 'MARKET' ? '시장가' : money(order.limitPrice);
  const valuationFormat = new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 2});
  const signedFormat = new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 2, signDisplay: 'exceptZero'});
  const valuationMoney = value => value == null ? '—' : valuationFormat.format(value) + '원';
  const shares = value => value == null ? '—' : num(value) + '주';
  const idValid = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
  const symbolValid = value => value == null || value === previousDefaultSymbol || (typeof value === 'string' && /^[0-9]{6}$/.test(value));
  // getRandomValues also works when the app is opened over HTTP from a LAN address.
  const requestId = () => Array.from(crypto.getRandomValues(new Uint8Array(16)),
    byte => byte.toString(16).padStart(2, '0')).join('');
  const safePositive = value => Number.isSafeInteger(value) && value > 0;
  function quantityValid(value, allowZero = false) {
    return Number.isInteger(value) && value >= (allowZero ? 0 : 1) && value <= intMax;
  }
  function validLimitTerms(price, quantity) {
    return safePositive(price) && quantityValid(quantity) && Number.isSafeInteger(price * quantity);
  }
  function validOrder(body) {
    if (!body || !idValid(body.clientOrderId) || !quantityValid(body.quantity)
        || !['BUY','SELL'].includes(body.side ?? 'BUY')
        || !symbolValid(body.symbol)
        || !Object.hasOwn(routingLabels, body.routingPolicy ?? 'PRICE_FIRST')) return false;
    if (body.orderType === 'MARKET') return body.limitPrice == null;
    return (body.orderType ?? 'LIMIT') === 'LIMIT' && validLimitTerms(body.limitPrice, body.quantity);
  }
  function validAmendment(body) {
    return body && validLimitTerms(body.limitPrice, body.quantity)
      && quantityValid(body.expectedRemainingQuantity) && body.quantity <= body.expectedRemainingQuantity;
  }
  function validQuotes(body) {
    return body && idValid(body.clientEventId) && symbolValid(body.symbol) && Number.isSafeInteger(body.expectedRevision)
      && body.expectedRevision >= 0 && validQuoteRows(body.quotes);
  }
  function validQuoteRows(quotes) {
    if (!Array.isArray(quotes) || ![2, 6].includes(quotes.length) || quotes.some(q => !q)) return false;
    const depth = quotes.length / 2;
    const bids = quotes.filter(q => (q.bidPrice ?? 0) > 0).length;
    if (bids !== 0 && bids !== quotes.length) return false;
    return marketNames.every(market => {
      const rows = quotes.filter(q => q.market === market).sort((a,b) => levelOf(a) - levelOf(b));
      return rows.length === depth && rows.every((q,i) => levelOf(q) === i+1 && safePositive(q.askPrice)
        && quantityValid(q.askQuantity, true) && (!i || q.askPrice >= rows[i-1].askPrice)
        && Number.isSafeInteger(q.bidPrice ?? 0) && (q.bidPrice ?? 0) >= 0 && quantityValid(q.bidQuantity ?? 0,true)
        && ((q.bidPrice ?? 0) > 0 || (q.bidQuantity ?? 0) === 0)
        && (!i || (q.bidPrice ?? 0) <= (rows[i-1].bidPrice ?? 0)));
    });
  }
  function validPending(value) {
    return value && symbolValid(value.symbol) && ((['BUY','SELL'].includes(value.kind) && validOrder(value.body) && (value.body.side ?? 'BUY') === value.kind)
      || (value.kind === 'QUOTES' && validQuotes(value.body))
      || (value.kind === 'CANCEL' && safePositive(value.orderId))
      || (value.kind === 'AMEND' && safePositive(value.orderId) && validAmendment(value.body) && idValid(value.body.clientOrderId)));
  }
  function verificationActive() {
    if (state.session?.guest !== false) return false;
    try { return Boolean(localStorage.getItem(verificationKey)); } catch { return false; }
  }
  function restorePending() {
    try {
      const raw = sessionStorage.getItem(pendingKey());
      if (raw) {
        const saved = JSON.parse(raw);
        if (saved?.kind === 'QUOTES' && Array.isArray(saved.body?.quotes)) {
          const aliases = {A: 'KRX', B: 'NXT'};
          saved.body.quotes = saved.body.quotes.map(quote =>
            quote && {...quote, market: aliases[quote.market] ?? quote.market});
        }
        if (!validPending(saved)) throw new Error('보관한 요청을 읽지 못했어요. 기존 주문 화면에서 이전 요청을 확인해 주세요.');
        state.pending = saved;
      } else {
        const old = state.session.guest ? null : JSON.parse(sessionStorage.getItem('stock.sor.pendingBuy'));
        if (validOrder(old)) {
          state.pending = {kind: 'BUY', body: old};
          sessionStorage.setItem(pendingKey(), JSON.stringify(state.pending));
          sessionStorage.removeItem('stock.sor.pendingBuy');
        } else state.pending = null;
      }
      if (state.pending) {
        const symbol = state.pending.body?.symbol ?? state.pending.symbol ?? previousDefaultSymbol;
        if (state.pending.body && ['BUY', 'SELL', 'QUOTES'].includes(state.pending.kind)) {
          state.pending.body.symbol = symbol;
        }
        // Keep an older pending request pointed at its original symbol without selecting it for new orders.
        if (!isActiveSymbol(symbol)) return;
        state.symbol = symbol;
      }
      if (['BUY','SELL'].includes(state.pending?.kind)) {
        state.side = state.pending.body.side ?? 'BUY';
        $('order-type').value = state.pending.body.orderType ?? 'LIMIT';
        $('routing-policy').value = state.pending.body.routingPolicy ?? 'PRICE_FIRST';
        if (state.pending.body.limitPrice != null) $('limit-price').value = state.pending.body.limitPrice;
        $('quantity').value = state.pending.body.quantity;
        state.orderSeeded = true;
      }
      if (state.pending?.kind === 'QUOTES') fillMarket(state.pending.body.quotes, state.pending.body.expectedRevision, true);
    } catch (error) {
      state.storageAvailable = false;
      notify(error.message || '브라우저의 요청 기록을 확인하지 못했어요.', 'error');
    }
  }
  function rememberPending(value) {
    // Persist before sending a mutation, so a reload can retry exactly the same ID.
    value = {...value, symbol: state.symbol};
    try { sessionStorage.setItem(pendingKey(), JSON.stringify(value)); }
    catch { throw new Error('요청 ID를 보관할 수 없어 주문을 전송하지 않았어요. 브라우저 저장 설정을 확인해 주세요.'); }
    state.pending = value;
    controls();
  }
  function clearPending() {
    state.pending = null;
    try { sessionStorage.removeItem(pendingKey()); }
    catch { state.storageAvailable = false; }
  }
  function notify(text, type = 'success') {
    const node = $('message');
    node.hidden = false; node.className = 'message' + (type === 'success' ? '' : ' ' + type);
    node.textContent = text;
    if ($('order-dialog').open) {
      $('detail-message').hidden = false; $('detail-message').className = node.className + ' detail-message';
      $('detail-message').textContent = text;
    }
  }
  function connection() {
    $('connection').className = 'connection ' + (state.connected ? 'connected' : 'offline');
    $('connection').querySelector('span').textContent = state.connected ? '서버 연결됨' : '연결 확인 필요';
  }
  function controls() {
    const checking = verificationActive();
    const presenter = state.session?.guest === false;
    document.querySelectorAll('[data-request]').forEach(node => { node.disabled = state.busy; });
    document.querySelectorAll('[data-edit], [data-mutate]').forEach(node => {
      node.disabled = state.busy || Boolean(state.pending) || checking || !state.storageAvailable || !state.connected;
    });
    document.querySelectorAll('#analysis-form input, #analysis-form select').forEach(node => { node.disabled = state.busy; });
    $('symbol-select').disabled = state.busy || Boolean(state.pending) || state.positions.length === 0;
    $('pending-banner').hidden = !state.pending;
    $('check-banner').hidden = !checking;
    $('retry').disabled = state.busy || checking || !state.pending || !state.storageAvailable;
    $('detail-retry').hidden = !state.pending;
    $('detail-retry').disabled = $('retry').disabled;
    if (state.pending) {
      const {kind, orderId} = state.pending;
      $('pending-description').textContent = ({
        BUY: '접수한 매수 주문을 원래 요청 ID로 확인해요.',
        SELL: '접수한 매도 주문을 원래 요청 ID로 확인해요.',
        CANCEL: '주문 #' + orderId + '의 취소 결과를 확인해요.',
        AMEND: '주문 #' + orderId + '의 정정 결과를 원래 요청 ID로 확인해요.',
        QUOTES: '처음 보낸 요청으로 호가 반영 결과를 확인해요.'
      })[kind];
    }
    const autoRunning = state.auto?.running || state.autoUncertain;
    if (activeAutoBook()) {
      document.querySelectorAll('#market-form [data-edit], #market-form [data-mutate]').forEach(node => { node.disabled = true; });
    }
    $('toggle-auto').disabled = !presenter || state.busy || !state.autoKnown || state.auto === null
      || (!autoRunning && (Boolean(state.pending) || checking || !state.connected || !state.storageAvailable));
    $('toggle-auto').textContent = state.autoUncertain ? '자동시장 정지 · 상태 확인' : state.auto?.running ? '자동시장 정지' : '자동시장 시작';
    $('toggle-auto').className = 'button ' + (autoRunning ? 'button-outline' : 'button-primary');
    $('detail-stop-auto').hidden = !presenter || !autoRunning;
    $('detail-stop-auto').disabled = state.busy;
    $('show-sor-consent').disabled = state.busy || !state.connected || !state.sorConsent;
    $('save-sor-consent').disabled = state.busy || !state.connected || !state.sorConsent
      || Boolean(state.sorConsent.agreedAt);
    updateCommitment();
    updateAmendment();
  }
  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const headers = new Headers(options.headers);
      if (options.method === 'POST' && state.session) headers.set('X-SOR-Account-Id', state.session.accountId);
      const response = await sorAuth.request(path, {...options, headers, cache: 'no-store', signal: controller.signal});
      let data;
      try { data = await response.json(); }
      catch { throw new Error('서버 응답을 읽지 못했어요. 연결 상태를 확인해 주세요.'); }
      if (!response.ok) {
        const error = new Error(data.message || data.detail || '요청을 처리하지 못했어요. (' + response.status + ')');
        error.status = response.status;
        error.code = data.code;
        throw error;
      }
      return data;
    } catch (error) {
      if (error.name === 'AbortError') throw new Error('서버 응답을 기다리는 시간이 초과됐어요.');
      throw error;
    } finally { clearTimeout(timeout); }
  }
  const post = (path, body) => request(path, {method: 'POST', headers: {'Content-Type': 'application/json'},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  async function run(action) {
    if (state.busy) return;
    state.operationVersion++;
    state.busy = true; controls();
    try { await action(); }
    catch (error) { notify(error.message || '요청을 완료하지 못했어요.', 'error'); }
    finally { state.busy = false; controls(); }
  }
  function requireWritable() {
    if (verificationActive()) throw new Error('안정성 검증 화면에서 테스트 주문 취소를 먼저 마쳐 주세요.');
    if (!state.storageAvailable) throw new Error('요청 기록을 보관할 수 없어 새 주문을 보낼 수 없어요. 브라우저 저장 설정을 확인해 주세요.');
    if (state.pending) throw new Error('결과 확인 · 재시도로 이전 요청을 먼저 확인해 주세요.');
    if (!state.connected || !state.account || !state.book) throw new Error('현재 계좌와 시장을 먼저 불러와 주세요.');
  }
  function validateSnapshot(account, book) {
    if (!['cash', 'reservedCash', 'availableCash', 'quantity','reservedQuantity','availableQuantity'].every(key => Number.isSafeInteger(account[key]))
        || account.cash < 0 || account.reservedCash < 0 || account.availableCash < 0
        || account.availableCash !== account.cash - account.reservedCash
        || !Number.isSafeInteger(book.revision) || !Array.isArray(book.quotes)
        || account.quantity < 0 || account.reservedQuantity < 0 || account.availableQuantity < 0
        || account.availableQuantity !== account.quantity-account.reservedQuantity
        || !validQuoteRows(book.quotes) || !book.quotes.every(q => safePositive(q.bidPrice))) {
      throw new Error('매수·매도 계좌와 호가를 확인하지 못했어요. 새 Java·SQL 파일 적용과 서버 재시작을 확인해 주세요.');
    }
  }
  async function autoStatus() {
    try { return await request('/api/sor/auto-market'); }
    catch (error) { if (error.status === 404) return null; throw error; }
  }
  function renderAuto() {
    const auto = state.auto;
    const target = auto?.running && auto.symbol ? symbolName(auto.symbol) : '전체 종목';
    $('auto-indicator').className = 'auto-indicator' + (auto?.running ? ' running' : '');
    $('auto-title').textContent = state.autoUncertain ? '자동시장 상태 확인 필요'
      : auto?.running ? target + ' 자동 모의시장 실행 중' : '자동 모의시장 정지';
    $('auto-description').textContent = state.autoUncertain ? '실행 상태를 확인하지 못했어요. 정지 버튼으로 상태를 다시 확인해 주세요.'
      : !state.autoKnown ? '서버 상태를 확인하고 있어요.'
      : !auto ? '자동시장 기능에 연결할 수 없어요. 서버 업데이트를 확인해 주세요.'
      : auto.message || `${target} · ${num(auto.intervalMillis / 1000)}초마다 호가 갱신 · 1호가 최대 ±${money(auto.maxPriceChange)}`;
    $('auto-metrics').hidden = !auto?.startedAt;
    $('auto-metrics').textContent = auto ? '이번 실행 ' + num(auto.updates) + '회 갱신 · 체결 ' + shares(auto.filledQuantity) : '';
  }
  function renderSession() {
    const {accountNumber, guest} = state.session;
    sorAuth.setAccountSession(state.session);
    document.body.classList.toggle('guest-session', guest);
    document.querySelectorAll('[data-presenter]').forEach(node => { node.hidden = guest; });
    $('analysis-hours-link').hidden = !guest;
    $('account-avatar').textContent = accountNumber.slice(-2);
    $('sidebar-account-id').textContent = accountNumber;
    $('dashboard-account-number').textContent = accountNumber;
    $('order-account-number').value = accountNumber;
    $('account-summary').hidden = false;
    navigate();
  }
  async function loadArchivedHistory() {
    const histories = await Promise.all(state.archivedSymbols.map(async ({code}) => {
      const query = symbolQuery(code);
      const [orders, open, trades] = await Promise.all([
        request('/api/sor/orders' + query), request('/api/sor/orders/open' + query), request('/api/trades' + query)
      ]);
      return {orders: [...orders, ...open], trades};
    }));
    const recent = new Map(histories.flatMap(history => history.orders).map(order => [order.orderId, order]));
    return {
      orders: [...recent.values()].sort((a, b) => b.orderId - a.orderId),
      trades: histories.flatMap(history => history.trades).sort((a, b) => b.id - a.id)
    };
  }
  async function refresh({symbol, includeEvents = state.events !== null, background = false, version = state.operationVersion} = {}) {
    try {
      if (!state.session) {
        const [session, symbols] = await Promise.all([request('/api/session'), request('/api/sor/symbols')]);
        state.session = session;
        state.symbols = symbols;
        renderSession();
        restorePending();
      }
      const selectedSymbol = symbol ?? state.symbol;
      const query = symbolQuery(selectedSymbol);
      const presenter = !state.session.guest;
      const selectedId = $('order-dialog').open ? state.selectedOrder?.orderId : null;
      const [portfolio, orders, open, trades, events, auto, selected, archivedHistory] = await Promise.all([
        request('/api/sor/portfolio' + query), request('/api/sor/orders' + query),
        request('/api/sor/orders/open' + query), request('/api/trades' + query),
        presenter && includeEvents ? request('/api/sor/quote-events' + query) : Promise.resolve(null),
        presenter ? autoStatus() : Promise.resolve(null),
        selectedId ? request('/api/sor/orders/' + selectedId).catch(error => {
          if (error.status === 404) return null;
          throw error;
        }) : Promise.resolve(null),
        selectedSymbol === state.symbol && state.archivedHistory ? loadArchivedHistory() : Promise.resolve(null)
      ]);
      if (background && (state.busy || state.operationVersion !== version)) return false;
      const {account, book, valuation, marketHours, positions, executedVolume, totalAssets, archivedSymbols,
        feeRatesBps = {}, sorConsent} = portfolio;
      if (account.accountId !== state.session.accountId) {
        location.reload();
        return false;
      }
      validateSnapshot(account, book);
      if (!state.session.accountNumber || !Number.isInteger(sorConsent?.version)) {
        throw new Error('계좌번호와 SOR 동의 상태를 확인하지 못했어요. 서버를 새 버전으로 다시 실행해 주세요.');
      }
      if (book.symbol !== selectedSymbol || !Array.isArray(positions) || !positions.some(item => item.symbol === selectedSymbol)
          || !marketNames.every(name => Number.isSafeInteger(executedVolume?.[name]) && executedVolume[name] >= 0)) {
        throw new Error('선택 종목의 호가와 보유 내역을 확인하지 못했어요. 서버를 새 버전으로 다시 실행해 주세요.');
      }
      if (selectedSymbol !== state.symbol || (state.book && state.book.symbol !== selectedSymbol)) {
        state.symbol = selectedSymbol;
        if (!state.pending) { state.orderSeeded = false; state.marketDirty = false; }
        state.events = null;
        state.comparison = null;
        state.comparisonStale = false;
        $('analysis-empty').hidden = false;
        $('analysis-results').hidden = true;
        $('analysis-caption').textContent = '선택 종목의 배분 결과를 비교할 수 있어요.';
        invalidatePreview('거래 종목이 바뀌었어요. 주문 미리보기를 다시 눌러 주세요.');
        $('trade-preview').hidden = true;
      }
      const changed = state.book && state.book.revision !== book.revision;
      const sessionsChanged = state.hours && state.hours.markets.some((market, i) => market.session !== marketHours.markets[i].session);
      const previousAuto = state.auto;
      Object.assign(state, {account, book, valuation, positions, executedVolume, totalAssets, hours: marketHours,
        orders, open, trades, archivedHistory, archivedSymbols, feeRatesBps, sorConsent,
        connected: true, auto, autoKnown: true, autoUncertain: false});
      if (selected && state.selectedOrder?.orderId === selectedId) state.selectedOrder = selected;
      if (events !== null) state.events = events;
      if (!state.orderSeeded) {
        const best = bestQuote();
        $('limit-price').value = best ? priceOf(best,state.side) : priceOf(book.quotes[0],state.side);
        state.orderSeeded = true;
      }
      if (!state.marketDirty && state.pending?.kind !== 'QUOTES') fillMarket(book.quotes, book.revision);
      if (state.preview && (changed || sessionsChanged)) invalidatePreview('호가나 장 상태가 바뀌었어요. 주문 미리보기를 다시 눌러 주세요.');
      if (state.comparison?.source === 'CURRENT' && (state.comparison.bookRevision !== book.revision
          || (sessionsChanged && !state.comparison.comparisonHours))) {
        state.comparisonStale = true;
        $('analysis-caption').textContent = '호가나 장 상태가 바뀌었어요. 결과 비교하기로 다시 계산할 수 있어요.';
      }
      $('last-updated').textContent = '최근 조회 ' + new Date().toLocaleTimeString('ko-KR', {hour12: false});
      connection(); renderAll();
      if (background && !state.pending && auto && previousAuto && auto.startedAt === previousAuto.startedAt
          && auto.filledQuantity > previousAuto.filledQuantity) {
        const target = auto.symbol ? symbolName(auto.symbol) : '전체 종목';
        notify('자동시장 ' + target + '의 대기 주문 중 ' + shares(auto.filledQuantity - previousAuto.filledQuantity) + '가 체결됐어요.');
      }
      return true;
    } catch (error) {
      if (background && (state.busy || state.operationVersion !== version)) return false;
      if (error.status === 403 && state.session) {
        const current = await request('/api/session').catch(() => null);
        if (current && current.accountId !== state.session.accountId) {
          location.reload();
          return false;
        }
      }
      state.connected = false; connection();
      $('last-updated').textContent = '이전 조회 값이 표시될 수 있어요.';
      controls();
      throw error;
    }
  }
  async function selectSymbol(symbol) {
    if (state.pending || symbol === state.symbol || !isActiveSymbol(symbol)) return;
    try {
      await refresh({symbol, includeEvents: state.view === 'markets' || state.events !== null});
    } finally {
      $('symbol-select').value = state.symbol;
    }
    await analysisDefaults();
    if (state.view === 'analysis') await compare();
  }
  async function changeAuto(running) {
    if (running) requireWritable();
    let status;
    try { status = await post('/api/sor/auto-market/' + (running ? 'start' : 'stop')); }
    catch (error) {
      try { status = await autoStatus(); }
      catch {
        state.autoUncertain = true; renderAuto();
        throw new Error('자동시장 상태를 확인하지 못했어요. 자동시장 정지 · 상태 확인을 눌러 주세요.');
      }
      if (!status || status.running !== running) {
        state.auto = status; state.autoKnown = true; state.autoUncertain = false; renderAuto();
        throw new Error(error.message + (status?.running ? ' 현재 자동시장은 실행 중이에요.' : ' 현재 자동시장은 정지 상태예요.'));
      }
    }
    state.auto = status; state.autoKnown = true; state.autoUncertain = false; renderAuto();
    notify(status.running ? (status.symbol ? symbolName(status.symbol) : '전체 종목') + '의 자동시장을 시작했어요.'
      : '자동시장을 정지했어요. 현재 호가와 주문 기록은 유지돼요.');
    try { await refresh(); }
    catch { notify('자동시장 ' + (status.running ? '시작' : '정지') + '은 확인했어요. 현재 계좌는 연결이 복구되면 갱신돼요.', 'warning'); }
  }
  async function poll() {
    if (state.busy || state.polling || document.hidden) return;
    state.polling = true;
    try {
      await refresh({background: true, version: state.operationVersion, includeEvents: state.view === 'markets'});
    } catch { /* Connection state is visible; do not repeat error messages every two seconds. */ }
    finally { state.polling = false; }
  }
  function startPolling() {
    if (pollTimer === null) pollTimer = setInterval(poll, 2000);
  }
  function executionMarkets(marketOrder) {
    return (state.hours?.markets ?? []).filter(m => marketOrder ? m.acceptsMarket : m.executesLimit).map(m => m.market);
  }
  function renderMarketClock() {
    const hours = state.hours;
    if (!hours) return;
    const text = (hours.demo ? '시연 ' : '현재 한국시간 ') + hours.time + ' · ' + hours.calendar;
    $('trade-clock').textContent = text;
    $('market-clock-display').textContent = text;
  }
  async function applyMarketTime(time) {
    const hours = await post('/api/sor/market-hours', {time});
    if (hours.demo) $('market-clock-input').value = hours.time;
    await refresh();
    notify(hours.demo ? '시연 시각을 ' + hours.time + '로 적용했어요.' : '현재 한국시간으로 장 상태를 확인해요.');
  }
  function bestQuote(side = state.side) {
    const active = executionMarkets($('order-type').value === 'MARKET');
    return state.book?.quotes.filter(q => active.includes(q.market) && quantityOf(q,side) > 0).sort((a,b) =>
      (side === 'SELL' ? priceOf(b,side)-priceOf(a,side) : priceOf(a,side)-priceOf(b,side))
      || a.market.localeCompare(b.market) || levelOf(a)-levelOf(b))[0] ?? null;
  }
  function marketSymbol(market) { return el('span', 'market-symbol ' + (market === 'KRX' ? 'market-a' : 'market-b'), market); }
  function renderMarketCards(target) {
    const best = bestQuote();
    const cards = marketNames.map(market => {
      const rows = (state.book?.quotes ?? []).filter(q => q.market === market).sort((a,b) => levelOf(a)-levelOf(b));
      const first = rows.find(q => quantityOf(q,state.side) > 0);
      const isBest = Boolean(first && market === best?.market);
      const card = el('article', 'market-card' + (isBest ? ' best' : ''));
      const heading = el('div', 'market-card-heading'), identity = el('div', 'market-identity');
      identity.append(marketSymbol(market), el('span', '', marketLabels[market])); heading.append(identity);
      if (isBest) heading.append(el('span', 'best-label', state.side === 'SELL' ? '최고 매수호가' : '최저 매도호가'));
      const prices = el('div','two-way-prices');
      for (const side of ['BUY','SELL']) {
        const q = rows.find(row => quantityOf(row,side)>0), box=el('div',side==='SELL'?'bid-head':'ask-head');
        box.append(el('span','',side==='SELL'?'팔 때 · 최고 매수호가':'살 때 · 최저 매도호가'),el('strong','',q?money(priceOf(q,side)):'물량 없음')); prices.append(box);
      }
      const book = el('div', 'depth-book');
      const labels = el('div', 'depth-row depth-heading two-way-row'); labels.append(el('span','','단계'),el('span','','매도호가'),el('span','','잔량'),el('span','','매수호가'),el('span','','잔량')); book.append(labels);
      for (const q of rows) {
        const row = el('div', 'depth-row two-way-row');
        const chosen = best && q.market === best.market && levelOf(q) === levelOf(best);
        row.append(el('span','',levelOf(q)+'호가'),el('strong',q.askQuantity===0?'depth-empty':chosen&&state.side==='BUY'?'depth-selected':'',money(q.askPrice)),
          el('span',q.askQuantity===0?'depth-empty':'',shares(q.askQuantity)),el('strong',q.bidQuantity===0?'depth-empty':chosen&&state.side==='SELL'?'depth-selected':'',money(q.bidPrice)),
          el('span',q.bidQuantity===0?'depth-empty':'',shares(q.bidQuantity))); book.append(row);
      }
      const volume = el('div', 'quote-volume'); volume.append(el('span', '', '매도 / 매수 잔량'), el('strong', '', state.book ? shares(rows.reduce((sum,q)=>sum+q.askQuantity,0))+' / '+shares(rows.reduce((sum,q)=>sum+q.bidQuantity,0)) : '—'));
      const turnover = el('div', 'quote-volume market-turnover');
      turnover.append(el('span', '', '누적 모의 체결량'), el('strong', '', shares(state.executedVolume?.[market])));
      const hours = state.hours?.markets.find(m => m.market === market);
      const session = el('div', 'market-session');
      if (hours) {
        session.append(el('strong', hours.executesLimit ? 'session-open' : hours.acceptsLimit ? 'session-receipt' : '', hours.session));
        if (hours.start) session.append(el('span', '', hours.start + '–' + hours.end));
        if (hours.acceptsLimit) {
          const allowed = hours.acceptsMarket ? '지정가·시장가' : hours.executesLimit ? '지정가' : '지정가 접수만';
          session.append(el('span', '', allowed));
        }
      }
      const fee = el('div', 'quote-volume');
      fee.append(el('span', '', '모의 수수료'), el('strong', '', feeRateText(market)));
      card.append(heading, session, prices, book, volume, turnover, fee); return card;
    });
    $(target).replaceChildren(...cards);
  }
  function statusLabel(order) {
    if (order.status === 'FILLED') return '체결 완료';
    if (order.status === 'AMENDED') return '정정 완료';
    if (order.status === 'CANCELLED' && order.orderType === 'MARKET') return '부분 체결 · 잔량 자동 취소';
    if (order.status === 'CANCELLED') return order.filledQuantity > 0 ? '부분체결 후 취소' : '취소 완료';
    if (order.status === 'PARTIALLY_FILLED') return order.cancellable ? '부분체결 · 대기' : '부분체결 · 종료';
    return order.status === 'OPEN' ? '대기' : '미체결 종료';
  }
  function statusBadge(order) {
    const style = order.status === 'OPEN' ? 'open' : order.status === 'FILLED' ? 'filled'
      : order.status === 'CANCELLED' ? 'cancelled' : order.status === 'PARTIALLY_FILLED' ? 'partial' : 'closed';
    return el('span', 'badge ' + style, statusLabel(order));
  }
  function sideBadge(side) { return el('span','side-label'+(side==='SELL'?' sell':''),sideText(side)); }
  function renderSide() {
    const sell=state.side==='SELL', name=sideText(state.side);
    const market = $('order-type').value === 'MARKET';
    const policy = $('routing-policy').value;
    document.querySelectorAll('[data-side]').forEach(node => node.setAttribute('aria-pressed',String(node.dataset.side===state.side)));
    $('order-title').textContent=name+' 주문';$('order-pill').textContent=name+(market?' 시장가':' 지정가');$('order-pill').classList.toggle('sell',sell);
    $('limit-fields').hidden = market;
    $('limit-price').required = !market;
    $('use-max').hidden = market && !sell;
    $('limit-price-label').textContent=name+' 지정가';$('buy').textContent=name+' 주문하기';$('buy').classList.toggle('button-sell',sell);
    $('use-best').textContent=sell?'최고 매수호가':'최저 매도호가';$('use-max').textContent=sell?'매도 가능 전량':'최대 가능';
    $('trade-available-label').textContent=sell?'매도 가능 수량':'주문 가능 금액';
    $('commitment-label').textContent=sell?'매도 요청 수량':market?'예상 필요 금액':'접수에 필요한 금액';
    const active = executionMarkets(market);
    if (active.length) {
      $('routing-hint').textContent = active.join('·') + ' 체결 가능 · ' + routingHint(state.side, market, policy);
    } else if (state.hours?.markets.some(m => m.acceptsLimit)) {
      $('routing-hint').textContent = market ? '현재는 지정가 주문만 접수할 수 있어요.' : '지정가 주문은 체결 가능한 시간이 될 때까지 대기해요.';
    } else {
      $('routing-hint').textContent = state.hours ? '현재 KRX·NXT의 지정가·시장가 주문 접수가 중단됐어요.' : '장 상태를 확인하고 있어요.';
    }
    $('order-form-note').textContent = market
      ? '현재 호가에서 가능한 수량을 즉시 체결하고, 남은 수량은 자동 취소해요.'
      : sell?'접수 시 전체 수량을 예약해요. 체결된 주식만 보유수량에서 차감하고 대금을 더해요. 남은 예약 수량은 취소할 때 해제돼요.':'접수 시 지정가 × 전체 수량을 확보해요. 실제 체결대금을 차감하고 남은 수량의 금액을 예약해요.';
  }
  function routingHint(side, market, policy) {
    if (policy === 'QUANTITY_FIRST') return '체결 가능한 수량이 가장 많은 시장부터 배분하고, 수량이 같으면 정산 금액을 비교해요.';
    const sell = side === 'SELL';
    const eligible = market ? '현재 ' : sell ? '지정가 이상의 ' : '지정가 이하의 ';
    return sideText(side) + '는 ' + eligible + '호가 중 수수료를 포함한 주당 ' + settlementKind(side)
      + (sell ? '이 높은 순서로' : '이 낮은 순서로') + ' 배분해요.';
  }
  function cell(row, value, className = '') {
    const node = el('td', className);
    node.append(value instanceof Node ? value : document.createTextNode(String(value))); row.append(node); return node;
  }
  function emptyTable(id, columns, text) {
    const row = el('tr'), td = el('td', 'empty-cell', text); td.colSpan = columns; row.append(td); $(id).replaceChildren(row);
  }
  function detailButton(orderId, label = '상세') {
    const button = el('button', 'table-action', label); button.type = 'button'; button.dataset.detail = String(orderId);
    button.setAttribute('data-request', '');
    button.setAttribute('aria-label', label === '상세' ? '주문 ' + orderId + ' 상세 보기' : label + ' 상세 보기'); return button;
  }
  function cancelButton(orderId) {
    const button = el('button', 'table-action cancel-action', '취소'); button.type = 'button';
    button.dataset.cancel = String(orderId); button.setAttribute('data-mutate', '');
    button.setAttribute('aria-label', '주문 ' + orderId + ' 잔여 수량 취소'); return button;
  }
  function orderActions(order, className) {
    const actions = el('div', className);
    actions.append(detailButton(order.orderId));
    if (order.cancellable && isActiveSymbol(order.symbol)) {
      const amend = el('button', 'table-action', '정정');
      amend.type = 'button';
      amend.dataset.amend = String(order.orderId);
      amend.setAttribute('data-mutate', '');
      amend.setAttribute('aria-label', '주문 ' + order.orderId + ' 정정');
      actions.append(amend);
    }
    if (order.cancellable) actions.append(cancelButton(order.orderId));
    return actions;
  }
  function remaining(order) { return order.cancellable ? order.remainingQuantity : 0; }
  function renderDashboardOrders() {
    if (!state.orders.length) { emptyTable('dashboard-orders', 6, state.connected ? '아직 접수한 주문이 없어요.' : '서버 연결 후 주문을 확인할 수 있어요.'); return; }
    $('dashboard-orders').replaceChildren(...state.orders.slice(0, 5).map(order => {
      const row = el('tr'); cell(row, '#' + order.orderId, 'row-id'); cell(row, sideBadge(order.side)); cell(row, orderPriceText(order));
      cell(row, num(order.filledQuantity) + ' / ' + shares(order.requestedQuantity)); cell(row, statusBadge(order));
      cell(row, detailButton(order.orderId)); return row;
    }));
  }
  function renderWaiting(target, maximum) {
    if (!state.open.length) {
      const empty = el('div', 'empty-state'); empty.append(el('span', 'empty-symbol', state.account ? '✓' : '…'),
        el('strong', '', state.account ? '대기 중인 주문이 없어요.' : '계좌 연결 후 대기 주문을 확인해요.'),
        el('p', '', state.account ? '미체결 주문이 생기면 여기에 표시됩니다.' : '연결을 확인하고 새로고침해 주세요.'));
      $(target).replaceChildren(empty); return;
    }
    $(target).replaceChildren(...state.open.slice(0, maximum).map(order => {
      const item = el('div', 'waiting-item'), content = el('div');
      content.append(el('strong', '', sideText(order.side) + ' #' + order.orderId + ' · ' + money(order.limitPrice) + ' · 대기 ' + shares(order.remainingQuantity)),
        el('p', '', routingLabel(order.routingPolicy) + ' · 체결 ' + shares(order.filledQuantity) + ' / ' + reservedAsset(order)));
      item.append(content, orderActions(order, 'waiting-actions')); return item;
    }));
  }
  function renderOrders() {
    const filter = $('order-filter').value, search = $('order-search').value.trim().toLowerCase();
    const orders = (state.archivedHistory?.orders ?? state.orders).filter(order => {
      const matches = filter === 'ALL' || (filter === 'WORKING' && order.cancellable)
        || order.status === filter || (filter === 'CLOSED' && !order.cancellable && !['FILLED', 'CANCELLED', 'AMENDED'].includes(order.status));
      return matches && (!search || String(order.orderId).includes(search) || order.clientOrderId.toLowerCase().includes(search));
    });
    $('order-count').textContent = num(orders.length) + '건';
    if (!orders.length) { emptyTable('orders-table', 9, search || filter !== 'ALL' ? '조건에 맞는 주문이 없어요.' : '아직 접수한 주문이 없어요.'); return; }
    $('orders-table').replaceChildren(...orders.map(order => {
      const row = el('tr');
      const id = cell(row, '#' + order.orderId, 'row-id');
      if (state.archivedHistory) id.append(el('span', 'position-code', symbolName(order.symbol)));
      cell(row, sideBadge(order.side)); cell(row, orderPriceText(order));
      cell(row, shares(order.requestedQuantity)); cell(row, shares(order.filledQuantity)); cell(row, shares(remaining(order)));
      cell(row, shares(order.cancelledQuantity)); cell(row, statusBadge(order));
      cell(row, orderActions(order, 'table-actions')); return row;
    }));
  }
  function displayTime(value) {
    if (!value) return '—';
    const parsed = new Date(String(value).replace(' ', 'T'));
    return Number.isNaN(parsed.getTime()) ? String(value).replace('T', ' ').slice(0, 19)
      : parsed.toLocaleString('ko-KR', {month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false});
  }
  function renderTrades() {
    const trades = state.archivedHistory?.trades ?? state.trades;
    $('trade-count').textContent = num(trades.length) + '건';
    if (!trades.length) { emptyTable('trades-table', 8, '아직 체결된 거래가 없어요.'); return; }
    $('trades-table').replaceChildren(...trades.map(trade => {
      const row = el('tr');
      const id = cell(row, '#' + trade.id, 'row-id');
      if (state.archivedHistory) id.append(el('span', 'position-code', symbolName(trade.symbol)));
      cell(row, el('span', 'side-label' + (trade.side === 'SELL' ? ' sell' : ''), trade.side === 'SELL' ? '매도' : '매수'));
      cell(row, money(trade.price)); cell(row, shares(trade.quantity));
      const amount = trade.price * trade.quantity, fee = trade.fee ?? 0;
      cell(row, money(amount)); cell(row, money(fee));
      cell(row, money(trade.side === 'SELL' ? amount - fee : amount + fee));
      cell(row, displayTime(trade.created_at)); return row;
    }));
  }
  function renderEvents() {
    if (state.events === null) { emptyTable('events-table', 6, '내역 불러오기를 누르면 최근 호가 반영 기록을 볼 수 있어요.'); return; }
    if (!state.events.length) { emptyTable('events-table', 6, '아직 호가를 반영한 기록이 없어요.'); return; }
    $('events-table').replaceChildren(...state.events.map(event => {
      const row = el('tr');
      cell(row, '#' + event.eventId, 'row-id');
      for (const market of marketNames) {
        const levels = el('div','event-levels');
        for (const q of event.submittedQuotes.filter(q => q.market === market)) levels.append(el('span','',levelOf(q)+'호가 · 매도 '+money(q.askPrice)+'/'+shares(q.askQuantity)+' · 매수 '+(q.bidPrice>0?money(q.bidPrice)+'/'+shares(q.bidQuantity):'변경 없음')));
        cell(row,levels);
      }
      cell(row, shares(event.filledQuantity)); cell(row, fillAmount(event.totalAmount, event.totalFee));
      cell(row, displayTime(event.createdAt)); return row;
    }));
  }
  function renderValuation() {
    const position = state.valuation;
    if (!position) return;
    $('average-purchase-price').textContent = valuationMoney(position.averagePurchasePrice);
    $('purchase-amount').textContent = valuationMoney(position.purchaseAmount);
    $('valuation-price').textContent = money(position.referencePrice);
    $('valuation-source').hidden = position.referenceMarket == null;
    $('valuation-source').textContent = position.referenceMarket == null ? ''
      : '시장 ' + position.referenceMarket + ' · ' + position.referenceLevel + '호가';
    $('market-value').textContent = valuationMoney(position.marketValue);
    $('total-assets').textContent = valuationMoney(state.totalAssets);
    const profit = position.profitLoss;
    const percent = position.returnPercent;
    const tone = Number(profit) > 0 ? ' gain' : Number(profit) < 0 ? ' loss' : '';
    $('profit-loss').textContent = profit == null ? '—' : signedFormat.format(profit) + '원';
    $('profit-loss').className = tone.trim();
    $('return-percent').hidden = percent == null;
    $('return-percent').textContent = percent == null ? '' : '수익률 ' + signedFormat.format(percent) + '%';
    $('return-percent').className = 'valuation-detail' + tone;
    const notes = [];
    if (state.account.quantity > 0 && position.referencePrice == null) notes.push('두 시장에 매수 잔량이 생기면 평가금액을 표시해요.');
    if (state.account.quantity > 0 && position.averagePurchasePrice == null) notes.push('평단가와 평가손익을 계산하려면 기존 보유 주식의 매입 기록이 필요해요.');
    $('valuation-note').hidden = notes.length === 0;
    $('valuation-note').textContent = notes.join(' ');
  }
  function renderSymbols() {
    const select = $('symbol-select');
    if (state.symbols.length && !Array.from(select.options).some(option => option.value === defaultSymbol)) {
      select.replaceChildren(...state.symbols.map(({code, name}) => {
        const option = el('option', '', name + ' · ' + code);
        option.value = code;
        return option;
      }));
    }
    if (state.symbols.length) select.value = state.symbol;
    document.querySelectorAll('[data-symbol-name]').forEach(node => { node.textContent = symbolName(state.symbol); });
    $('symbol-volume').textContent = shares(state.executedVolume
      ? marketNames.reduce((sum, market) => sum + state.executedVolume[market], 0) : null);
  }
  function renderPositions() {
    if (!state.positions.length) {
      emptyTable('positions-table', 6, '보유 내역을 불러오고 있어요.');
      return;
    }
    $('positions-table').replaceChildren(...state.positions.map(({symbol, name, account, valuation}) => {
      const row = el('tr');
      const active = isActiveSymbol(symbol);
      if (symbol === state.symbol) row.setAttribute('aria-current', 'true');
      const label = el('strong', '', name);
      label.append(el('span', 'position-code', active ? symbol : '이전 보유'));
      cell(row, label);
      cell(row, shares(account.quantity));
      cell(row, active ? shares(account.availableQuantity) : '—');
      cell(row, valuationMoney(valuation.averagePurchasePrice));
      cell(row, valuationMoney(valuation.marketValue));
      const profit = valuation.profitLoss;
      cell(row, profit == null ? '—' : signedFormat.format(profit) + '원',
        Number(profit) > 0 ? 'gain' : Number(profit) < 0 ? 'loss' : '');
      return row;
    }));
  }
  function renderAll() {
    renderSorConsent();
    renderSymbols();
    $('archived-history').hidden = state.archivedSymbols.length === 0;
    $('archived-history').setAttribute('aria-pressed', String(Boolean(state.archivedHistory)));
    $('archived-history').textContent = state.archivedHistory ? '선택 종목 내역으로' : '이전 종목 내역';
    $('archived-history-label').hidden = !state.archivedHistory;
    renderSide();
    renderMarketClock();
    if (state.account) {
      $('cash').textContent = money(state.account.cash); $('reserved').textContent = money(state.account.reservedCash);
      $('available').textContent = money(state.account.availableCash); $('holdings').textContent = shares(state.account.quantity);
      $('trade-available').textContent = state.side==='SELL'?shares(state.account.availableQuantity):money(state.account.availableCash);
      $('holdings-note').textContent = '매도 예약 '+shares(state.account.reservedQuantity)+' · 가능 '+shares(state.account.availableQuantity);
    }
    renderValuation(); renderPositions();
    renderMarketCards('dashboard-markets'); renderMarketCards('trade-markets');
    const best = bestQuote(); $('best-market-label').textContent = best
      ? sideText(state.side)+'는 ' + best.market + '의 ' + levelOf(best) + '호가부터 배분할 수 있어요.'
      : state.hours ? '현재 주문 방식으로 체결할 수 있는 물량이 없어요.' : '시장 상태를 조회하고 있어요.';
    $('open-summary').textContent = '선택 종목 ' + num(state.open.length) + '건 · 계좌 예약금 ' + money(state.account?.reservedCash)+' · 예약 주식 '+shares(state.account?.reservedQuantity);
    $('open-badge').textContent = num(state.open.length);
    $('nav-open-count').textContent = num(state.open.length); $('nav-open-count').hidden = !state.open.length;
    renderDashboardOrders(); renderWaiting('dashboard-open', 3); renderWaiting('trade-open', state.open.length);
    renderOrders(); renderTrades(); renderEvents(); marketDraftNote(); renderAuto();
    if (state.selectedOrder) {
      const latest = state.orders.find(order => order.orderId === state.selectedOrder.orderId);
      if (latest) { state.selectedOrder = latest; renderDetail(latest); }
    }
    controls();
  }

  function renderSorConsent() {
    const agreedAt = state.sorConsent?.agreedAt;
    const status = $('sor-consent-status');
    const label = agreedAt ? '동의 완료' : state.sorConsent ? '동의 필요' : '동의 상태 조회 중';
    if (status.textContent !== label) status.textContent = label;
    status.classList.toggle('agreed', Boolean(agreedAt));
    $('sor-consent-record').hidden = !agreedAt;
    if (agreedAt) {
      $('sor-consent-record').textContent = '동의 일시 · '
        + new Date(agreedAt).toLocaleString('ko-KR', {timeZone: 'Asia/Seoul', hour12: false});
    }
    $('sor-consent-confirmation').hidden = Boolean(agreedAt);
    $('sor-consent-accepted').disabled = Boolean(agreedAt);
    $('save-sor-consent').hidden = Boolean(agreedAt);
  }

  function openSorConsent() {
    const accountNumber = state.sorConsent.agreedAt ? state.session.accountNumber : $('order-account-number').value.trim();
    $('sor-consent-account').textContent = accountNumber ? '대상 가상계좌 · ' + accountNumber : '주문 계좌번호를 입력해 주세요.';
    $('sor-consent-accepted').checked = false;
    $('sor-consent-error').hidden = true;
    renderSorConsent();
    $('sor-consent-dialog').showModal();
  }

  async function acceptSorConsent() {
    const message = $('sor-consent-error');
    message.hidden = true;
    try {
      state.sorConsent = await post('/api/sor/consent', {
        accountNumber: $('order-account-number').value,
        version: state.sorConsent.version,
        accepted: $('sor-consent-accepted').checked
      });
      renderSorConsent();
      $('sor-consent-dialog').close();
      $('order-rejection').hidden = true;
      notify('현재 가상계좌에 모의 SOR 주문 동의를 기록했어요.');
    } catch (error) {
      message.textContent = error.message;
      message.hidden = false;
    }
  }
  function orderInputs() {
    const orderType = $('order-type').value;
    const routingPolicy = $('routing-policy').value;
    const quantity = Number($('quantity').value);
    if (!quantityValid(quantity)) throw new Error('주문 수량은 1주 이상이어야 해요.');
    const order = {symbol: state.symbol, side: state.side, orderType, quantity, routingPolicy};
    if (orderType === 'MARKET') return order;
    const limitPrice = Number($('limit-price').value);
    if (!validLimitTerms(limitPrice, quantity) || (order.side === 'BUY' && limitBuyReserve(limitPrice, quantity) === null)) {
      throw new Error('지정가와 수량을 확인해 주세요. 정확히 표시할 수 있는 양의 정수가 필요해요.');
    }
    return {...order, limitPrice};
  }
  function updateCommitment() {
    let input=null;
    try { input=orderInputs(); } catch {}
    const sell = state.side === 'SELL', market = $('order-type').value === 'MARKET';
    if (market) $('limit-price').disabled = true;
    let needed = null;
    if (input) {
      if (sell) needed = input.quantity;
      else if (market) needed = state.preview?.settlementAmount ?? null;
      else needed = limitBuyReserve(input.limitPrice, input.quantity);
    }
    $('commitment').textContent=sell?shares(needed):money(needed);
    const available=state.account?(sell?state.account.availableQuantity:state.account.availableCash):null;
    const insufficient=needed!==null&&available!==null&&needed>available;
    $('funds-hint').className='funds-hint'+(insufficient?' insufficient':'');
    $('funds-hint').textContent=insufficient?(sell?'매도 가능 수량이 '+shares(needed-available)+' 부족해요.':'주문 가능 금액이 '+money(needed-available)+' 부족해요.')
      :(sell?'전체 주문 수량만큼 매도 가능한 주식이 필요해요.':market?'접수 시점의 호가와 수수료로 결제액을 계산해요.':'지정가 × 전체 수량에 최대 모의 수수료를 더해 예약해요.');
    if (input === null) $('buy').disabled = true;
  }
  function invalidatePreview(caption = '주문 조건이 바뀌었어요. 주문 미리보기를 다시 눌러 주세요.') {
    $('order-rejection').hidden = true;
    state.preview = null;
    $('preview-caption').textContent = caption;
    $('preview-content').hidden = true;
    $('preview-content').replaceChildren();
  }
  async function preview() {
    const body = orderInputs();
    const market = body.orderType === 'MARKET';
    const plan = await request('/api/sor/preview?' + new URLSearchParams(body));
    state.preview = plan;
    const content = $('preview-content'), stats = el('div', 'preview-stats');
    const statsRows = [['체결 가능', shares(plan.allocatedQuantity)], [market?'자동 취소 예상':'미체결', shares(plan.unallocatedQuantity)],
      ['예상 체결금액', money(plan.estimatedAmount)], ['예상 수수료', money(plan.estimatedFee)],
      ['예상 ' + settlementKind(body.side), money(plan.settlementAmount)]];
    for (const [label, value] of statsRows) {
      const item = el('div'); item.append(el('span', '', label), el('strong', '', value)); stats.append(item);
    }
    content.replaceChildren(stats);
    for (const [index, allocation] of plan.allocations.entries()) {
      const row = el('div', 'preview-line'), name = el('div'); name.append(marketSymbol(allocation.market),
        el('span', '', (index+1) + '. ' + levelOf(allocation) + '호가 · ' + money(allocation.price) + ' × ' + shares(allocation.quantity)));
      row.append(name, fillAmount(allocation.amount, allocation.fee)); content.append(row);
    }
    content.append(el('p','routing-note',$('routing-hint').textContent));
    const priceLabel = market ? '시장가' : '지정가 ' + money(body.limitPrice);
    $('preview-caption').textContent = symbolName(body.symbol) + ' · ' + sideText(body.side) + ' ' + priceLabel + ' · ' + shares(body.quantity)
      + ' · ' + routingLabel(plan.routingPolicy);
    content.hidden = false;
    $('trade-preview').hidden = false;
    $('trade-preview').scrollIntoView({block: 'nearest'});
    notify('예상 배분을 계산했어요. 잔액과 시장 물량은 바뀌지 않았어요.');
  }
  async function performPending() {
    if (verificationActive()) throw new Error('안정성 검증을 먼저 마쳐 주세요.');
    const action = state.pending;
    if (!action) return;
    let result;
    try {
      if (action.kind === 'CANCEL' || action.kind === 'AMEND') {
        result = await post('/api/sor/orders/' + action.orderId + '/' + action.kind.toLowerCase(), action.body);
      } else {
        result = await post(action.kind === 'QUOTES' ? '/api/sor/quotes' : '/api/sor/orders', action.body);
      }
    } catch (error) {
      if (error.status >= 400 && error.status < 500) {
        clearPending();
        if (action.kind === 'QUOTES') {
          state.marketDirty = false;
          try { await refresh(); } catch { /* Preserve the rejection message. */ }
        } else { try { await refresh(); } catch { /* Preserve the rejection message. */ } }
        if (error.code && ['BUY','SELL'].includes(action.kind)) {
          error.message = '주문 거절 · ' + error.message;
          if (state.view === 'trade') {
            $('message').hidden = true;
            $('order-rejection').textContent = error.message;
            $('order-rejection').hidden = false;
            return;
          }
        }
        if (action.kind === 'AMEND') error.message = '정정 거절 · ' + error.message;
        throw error;
      }
      try { await refresh(); } catch { /* Unknown mutation remains available to retry. */ }
      throw new Error('처리 결과를 확인하지 못했어요. 위의 결과 확인 · 재시도로 같은 요청을 확인해 주세요.');
    }
    clearPending();
    if (action.kind === 'QUOTES') {
      state.marketDirty = false;
      notify('호가를 반영했어요. 대기 주문 중 ' + shares(result.filledQuantity) + '가 추가 체결됐어요.');
    } else {
      state.amendDraft = null;
      state.selectedOrder = result;
      $('detail-message').hidden = true;
      const prefix = action.kind === 'AMEND' ? '주문 #' + action.orderId + ' 정정 → ' : '';
      notify(prefix + '주문 #' + result.orderId + ' · ' + statusLabel(result) + ' · 체결 ' + shares(result.filledQuantity)
        + ' / 대기 ' + shares(remaining(result)) + ' / 취소 ' + shares(result.cancelledQuantity));
      renderDetail(result);
      $('detail-message').hidden = true;
      if (!$('order-dialog').open) { state.detailTrigger = document.activeElement; $('order-dialog').showModal(); }
    }
    try {
      await refresh({includeEvents: action.kind === 'QUOTES' || state.events !== null});
    } catch {
      notify('요청 처리는 확인했어요. 현재 상태를 다시 불러오지 못했으니 새로고침으로 확인해 주세요.', 'warning');
    }
    if (!state.storageAvailable) notify('요청 처리는 확인했지만 브라우저 기록을 정리하지 못했어요. 새 주문 전에 저장 설정을 확인해 주세요.', 'warning');
  }
  async function buy() {
    requireWritable();
    const body = orderInputs();
    $('order-rejection').hidden = true;
    rememberPending({kind:body.side,body:{clientOrderId:requestId(),...body,accountNumber:$('order-account-number').value}});
    await performPending();
  }
  async function cancel(orderId) {
    requireWritable();
    rememberPending({kind: 'CANCEL', orderId}); await performPending();
  }
  async function showDetail(orderId) {
    const order = await request('/api/sor/orders/' + orderId);
    state.selectedOrder = order;
    if (!$('order-dialog').open) state.detailTrigger = document.activeElement;
    $('detail-message').hidden = true;
    renderDetail(order); if (!$('order-dialog').open) $('order-dialog').showModal();
    return order;
  }
  async function showAmend(orderId) {
    await run(async () => {
      requireWritable();
      const order = await showDetail(orderId);
      if (!order.cancellable || !isActiveSymbol(order.symbol) || order.orderType !== 'LIMIT') throw new Error('정정할 미체결 지정가 주문이 없어요.');
      state.amendDraft = {orderId, expectedRemainingQuantity: order.remainingQuantity};
      $('amend-context').textContent = symbolName(order.symbol) + ' · ' + sideText(order.side) + ' ' + money(order.limitPrice)
        + ' · 미체결 ' + shares(order.remainingQuantity) + ' · ' + routingLabel(order.routingPolicy);
      $('amend-price').value = order.limitPrice;
      $('amend-quantity').value = order.remainingQuantity;
      $('amend-quantity').max = order.remainingQuantity;
      renderDetail(order);
    });
    if (state.amendDraft?.orderId === orderId) $('amend-price').focus();
  }
  function amendmentInputs() {
    return {limitPrice: Number($('amend-price').value), quantity: Number($('amend-quantity').value),
      expectedRemainingQuantity: state.amendDraft.expectedRemainingQuantity};
  }
  function updateAmendment() {
    if (!state.amendDraft) return;
    const order = state.selectedOrder, inputs = amendmentInputs();
    const buy = order.side === 'BUY';
    const validTerms = validAmendment(inputs);
    const reservation = validTerms && buy ? limitBuyReserve(inputs.limitPrice, inputs.quantity) : null;
    const valid = validTerms && (!buy || reservation !== null);
    if (valid) {
      $('amend-reservation').textContent = buy
        ? '현재 예약금 ' + money(order.reservedCash) + ' → 정정 접수에 필요한 금액 ' + money(reservation)
        : '현재 예약 수량 ' + shares(order.reservedQuantity) + ' → 정정 접수에 필요한 수량 ' + shares(inputs.quantity);
    } else {
      $('amend-reservation').textContent = '지정가와 정정 수량을 확인해 주세요. 최대 ' + shares(inputs.expectedRemainingQuantity) + '까지 정정할 수 있어요.';
    }
    if (!valid || (inputs.limitPrice === order.limitPrice && inputs.quantity === order.remainingQuantity)) {
      $('amend-submit').disabled = true;
    }
  }
  async function amend() {
    requireWritable();
    if (!state.amendDraft) throw new Error('정정할 주문을 다시 선택해 주세요.');
    const body = amendmentInputs();
    if (!validAmendment(body)) throw new Error('정정 지정가와 수량을 확인해 주세요.');
    rememberPending({kind: 'AMEND', orderId: state.amendDraft.orderId,
      body: {clientOrderId: requestId(), ...body, accountNumber: state.session.accountNumber}});
    await performPending();
  }
  function renderDetail(order) {
    const draft = state.amendDraft;
    if (draft && (draft.orderId !== order.orderId || !order.cancellable
        || draft.expectedRemainingQuantity !== order.remainingQuantity)) {
      state.amendDraft = null;
      if (draft.orderId === order.orderId) notify('미체결 수량이 바뀌었어요. 최신 주문을 확인해 주세요.', 'warning');
    }
    const editing = Boolean(state.amendDraft);
    $('detail-title').textContent = '주문 #' + order.orderId;
    const root = $('detail-body'), meta = el('div', 'detail-meta');
    root.hidden = editing;
    $('amend-form').hidden = !editing;
    meta.append(el('span', '', symbolName(order.symbol) + ' · ' + sideText(order.side)), statusBadge(order));
    const grid = el('div', 'detail-grid');
    const rows = [['주문 가격', orderPriceText(order)], ['SOR 배분 기준', routingLabel(order.routingPolicy)], ['요청 수량', shares(order.requestedQuantity)],
      ['체결 수량', shares(order.filledQuantity)], ['대기 수량', shares(remaining(order))],
      ['취소 수량', shares(order.cancelledQuantity)], [order.side==='SELL'?'예약 수량':'예약금', order.side==='SELL'?shares(order.reservedQuantity):money(order.reservedCash)]];
    if (order.filledQuantity > 0) rows.push(['수수료 합계', money(order.totalFee)],
      [settlementKind(order.side), money(order.settlementAmount)]);
    if (order.amendedQuantity > 0) rows.push(['정정 주문으로 넘긴 수량', shares(order.amendedQuantity)]);
    for (const [label, value] of rows) {
      const item = el('div'); item.append(el('span', '', label), el('strong', '', value)); grid.append(item);
    }
    root.replaceChildren(meta, grid);
    const links = el('div', 'order-links');
    if (order.originalOrderId != null) links.append(detailButton(order.originalOrderId, '원주문 #' + order.originalOrderId));
    if (order.replacementOrderId != null) links.append(detailButton(order.replacementOrderId, '정정 주문 #' + order.replacementOrderId));
    if (links.childElementCount) root.append(links);
    root.append(el('h3', 'detail-section-title', '시장별 '+sideText(order.side)+' 체결 · 총 ' + money(order.totalAmount)));
    if (!order.executions.length) root.append(el('p', 'form-note', '아직 체결된 물량이 없어요.'));
    for (const execution of order.executions) {
      const row = el('div', 'detail-execution'), name = el('div');
      name.append(marketSymbol(execution.market), el('strong', '', levelOf(execution) + '호가 · ' + money(execution.price) + ' × ' + shares(execution.quantity)));
      row.append(name, fillAmount(execution.amount, execution.fee)); root.append(row);
    }
    const times = el('div', 'detail-times');
    times.append(el('p', '', '접수 ' + displayTime(order.createdAt)), el('p', '', '최근 변경 ' + displayTime(order.updatedAt)));
    if (order.status === 'CANCELLED' && order.filledQuantity > 0) times.append(el('p', '', '체결된 수량은 유지하고, 남은 수량만 취소한 주문이에요.'));
    if (!order.cancellable && order.remainingQuantity > 0) times.append(el('p', '', '이전 단계에서 종료한 주문 기록이에요. 추가로 대기하는 주문은 없어요.'));
    root.append(times);
    $('detail-amend').hidden = editing || !order.cancellable || !isActiveSymbol(order.symbol) || order.orderType !== 'LIMIT';
    $('detail-cancel').hidden = editing || !order.cancellable;
  }
  function closeDetail() { $('order-dialog').close(); state.detailTrigger?.focus?.(); }

  function presenterCodeStatus(text, error = false) {
    const message = $('presenter-code-message');
    message.textContent = text;
    message.classList.toggle('error', error);
    message.hidden = !text;
  }

  async function issuePresenterCode() {
    presenterCodeStatus('체험 코드를 발급하고 있어요…');
    try {
      const result = await post('/api/account-access/presenter-code');
      $('presenter-code').value = result.code;
      $('presenter-code-result').hidden = false;
      $('issue-presenter-code').hidden = true;
      $('copy-presenter-code').focus();
      presenterCodeStatus('체험 코드를 발급했어요. 닫기 전에 복사해 주세요.');
    } catch (error) {
      presenterCodeStatus(error.message, true);
    }
  }
  function editorId(market, level, kind) { return 'market-' + market.toLowerCase() + (level === 1 ? '' : '-'+level) + '-'+kind; }
  function initMarketEditor() {
    const body = $('market-inputs');
    for (const market of marketNames) for (let level=1;level<=3;level++) {
      const row = el('tr',level === 1 ? 'depth-market-start' : '');
      const identity = el('div','market-level-identity'); identity.append(marketSymbol(market),el('span','',level+'호가')); cell(row,identity);
      for (const kind of ['price','quantity','bid-price','bid-quantity']) {
        const id = editorId(market,level,kind), label = el('label','sr-only',market+'시장 '+level+'호가 '+((kind.startsWith('bid-')?'매수':'매도')+' '+(kind.endsWith('price')?'가격':'전체 잔량')));
        label.htmlFor = id;
        const input = el('input'); Object.assign(input,{id,type:'number',min:kind.endsWith('price')?'1':'0',max:kind.endsWith('price')?'9007199254740991':String(intMax),step:'1',required:true}); input.setAttribute('data-edit','');
        const td = el('td'); td.append(label,input); row.append(td);
      }
      body.append(row);
    }
  }
  function fillMarket(quotes, revision, dirty = false) {
    for (const market of marketNames) for (let level=1;level<=3;level++) {
      const quote = quotes.find(q => q.market === market && levelOf(q) === level);
      const first = quotes.find(q => q.market === market);
      $(editorId(market,level,'price')).value = quote?.askPrice ?? first?.askPrice ?? '';
      $(editorId(market,level,'quantity')).value = quote?.askQuantity ?? 0;
      $(editorId(market,level,'bid-price')).value = quote?.bidPrice || first?.bidPrice || '';
      $(editorId(market,level,'bid-quantity')).value = quote?.bidQuantity ?? 0;
    }
    state.draftRevision = revision; state.marketDirty = dirty; marketDraftNote();
  }
  function marketDraftNote() {
    const stale = state.book && state.draftRevision !== state.book.revision;
    $('market-draft-note').textContent = state.pending?.kind === 'QUOTES' ? '처음 전송한 호가를 보관 중이에요. 결과 확인 · 재시도를 눌러 주세요.'
      : activeAutoBook() ? '자동시장이 호가를 관리하고 있어요. 직접 입력하려면 자동시장을 정지해 주세요.'
      : stale ? '입력 이후 시장 상태가 바뀌었어요. 현재 호가 불러오기로 확인한 뒤 다시 반영해 주세요.'
      : state.marketDirty ? '입력한 전체 잔량으로 시장이 변경되고, 기존 대기 주문이 체결될 수 있어요.'
      : '현재 호가를 불러왔어요. 수량은 기존 잔량에 더하는 값이 아니라 새 전체 잔량이에요.';
  }
  async function applyMarket() {
    requireWritable();
    if (activeAutoBook()) throw new Error('직접 호가를 반영하려면 자동시장을 먼저 정지해 주세요.');
    const body = {symbol: state.symbol, clientEventId: requestId(), expectedRevision: state.draftRevision,
      quotes: marketNames.flatMap(market => [1,2,3].map(level => ({market, level,
        askPrice:Number($(editorId(market,level,'price')).value),askQuantity:Number($(editorId(market,level,'quantity')).value),
        bidPrice:Number($(editorId(market,level,'bid-price')).value),bidQuantity:Number($(editorId(market,level,'bid-quantity')).value)})))};
    if (!validQuotes(body)||body.quotes.some(q=>!safePositive(q.bidPrice))) throw new Error('가격은 양의 정수, 잔량은 0 이상의 정수로 입력해 주세요. 1→3 매도호가는 오름차순, 매수호가는 내림차순이어야 해요.');
    if (body.expectedRevision !== state.book.revision) throw new Error('입력 이후 호가가 바뀌었어요. 현재 호가를 다시 불러와 주세요.');
    rememberPending({kind: 'QUOTES', body}); await performPending();
  }
  async function loadEvents() { state.events = await request('/api/sor/quote-events' + symbolQuery(state.symbol)); renderEvents(); }
  const exampleQuotes = depth => request('/api/sor/example-quotes' + symbolQuery(state.symbol) + '&depth=' + depth);
  async function loadExample(depth) {
    const quotes = await exampleQuotes(depth);
    fillMarket(quotes, state.book.revision, true);
    notify(symbolName(state.symbol) + ' ' + (depth ? '3단계' : '기본') + ' 예시를 채웠어요. 호가 반영 버튼으로 적용할 수 있어요.');
  }
  function comparisonInputs() {
    const input = {symbol: state.symbol, side:$('analysis-side').value,source: $('analysis-source').value, baselineMarket: $('analysis-market').value,
      limitPrice: Number($('analysis-price').value), quantity: Number($('analysis-quantity').value)};
    if (!['BUY','SELL'].includes(input.side)||!['EXAMPLE', 'DEPTH_EXAMPLE', 'CURRENT'].includes(input.source) || !marketNames.includes(input.baselineMarket)
        || !safePositive(input.limitPrice) || !quantityValid(input.quantity) || !Number.isSafeInteger(input.limitPrice * input.quantity)) {
      throw new Error('비교할 지정가·수량과 기준 시장을 확인해 주세요.');
    }
    const time = $('analysis-time').value;
    if (time) input.comparisonTime = time;
    return input;
  }
  async function compare() {
    const input = comparisonInputs();
    try {
      const result = await request('/api/sor/compare?' + new URLSearchParams(input));
      state.comparison = result; state.comparisonStale = false; renderComparison(result);
      const active = marketNames.filter(market => result.executionMarkets.includes(market));
      let caption = sourceLabel(result.source) + '의 호가로 비교했어요.';
      if (result.comparisonHours) {
        caption = active.length ? '선택 시각의 지정가 체결 가능 시장 · ' + active.join('·')
          : '선택 시각에 지정가 주문을 즉시 체결할 수 있는 시장이 없어요.';
      } else if (result.source === 'CURRENT') {
        caption = active.length ? '현재 체결 가능 시장 · ' + active.join('·')
          : '현재 지정가 주문을 체결할 수 있는 시장이 없어요.';
      }
      $('analysis-caption').textContent = caption;
    } catch (error) {
      state.comparisonStale = true;
      $('analysis-caption').textContent = state.comparison ? '아래는 이전 비교 결과예요. 새로운 비교 결과를 불러오지 못했어요.' : '비교 결과를 불러오지 못했어요.';
      throw error;
    }
  }
  function allocationBar(target, strategy, requested) {
    const root = $(target); root.replaceChildren();
    for (const item of strategy.allocations) {
      const node = el('span', item.market === 'KRX' ? 'segment-a' : 'segment-b', item.market + ' ' + shares(item.quantity));
      node.style.width = (item.quantity / requested * 100) + '%';
      node.title = '시장 ' + item.market + ' · ' + levelOf(item) + '호가 · ' + shares(item.quantity) + ' · ' + money(item.amount); root.append(node);
    }
    if (strategy.remainingQuantity > 0) {
      const node = el('span', 'segment-empty', shares(strategy.remainingQuantity));
      node.style.width = (strategy.remainingQuantity / requested * 100) + '%'; root.append(node);
    }
    root.setAttribute('aria-label', '체결 가능 ' + shares(strategy.filledQuantity) + ', 미체결 ' + shares(strategy.remainingQuantity));
  }
  function fillsByMarket(strategy) {
    const quantities = new Map();
    for (const fill of strategy.allocations) {
      quantities.set(fill.market, (quantities.get(fill.market) ?? 0) + fill.quantity);
    }
    return quantities;
  }
  function marketFillText(strategy) {
    return Array.from(fillsByMarket(strategy), ([market, quantity]) => market + ' ' + shares(quantity)).join(' · ')
      || '체결 가능한 물량 없음';
  }
  function renderComparisonHours(hours) {
    $('analysis-hours').hidden = !hours;
    if (!hours) return;
    $('analysis-hours-label').textContent = hours.time + ' · 한국시간 · ' + hours.calendar + ' 기준';
    $('analysis-hours-rows').replaceChildren(...hours.markets.map(market => {
      const row = el('tr');
      const name = el('th', '', market.market);
      name.scope = 'row';
      row.append(name);
      cell(row, market.session);
      const limitStatus = market.executesLimit ? '즉시 체결 가능' : market.acceptsLimit ? '접수 후 대기' : '접수 중단';
      cell(row, limitStatus);
      cell(row, market.acceptsMarket ? '접수 가능' : '접수 중단');
      return row;
    }));
  }
  function renderComparison(data) {
    $('analysis-empty').hidden = true; $('analysis-results').hidden = false;
    renderComparisonHours(data.comparisonHours);
    const {commonQuantityCost: common, singleMarket: single, sor: priceFirst, quantityFirst} = data;
    const sell = data.side === 'SELL';
    const amountKind = settlementKind(data.side);
    $('analysis-price-label').textContent=sideText(data.side)+' 지정가(원)';
    $('analysis-quote-price-label').textContent=sell?'매수호가':'매도호가';$('analysis-quote-quantity-label').textContent=sell?'매수 물량':'매도 물량';
    $('price-policy-title').textContent = '가격 우선 ' + amountKind;
    $('quantity-policy-title').textContent = '수량 우선 ' + amountKind;
    $('price-policy-amount').textContent = money(priceFirst.settlementAmount);
    $('quantity-policy-amount').textContent = money(quantityFirst.settlementAmount);
    $('price-policy-fills').textContent = marketFillText(priceFirst);
    $('quantity-policy-fills').textContent = marketFillText(quantityFirst);
    const comparable = priceFirst.filledQuantity > 0 && priceFirst.filledQuantity === quantityFirst.filledQuantity;
    const difference = sell ? priceFirst.settlementAmount - quantityFirst.settlementAmount
      : quantityFirst.settlementAmount - priceFirst.settlementAmount;
    $('policy-difference-title').textContent = sell ? '가격 우선 수령액 증가분' : '가격 우선 결제액 절감분';
    $('policy-difference').textContent = comparable ? money(difference) : '—';
    $('policy-difference-basis').textContent = comparable
      ? '같은 ' + shares(priceFirst.filledQuantity) + ' 체결 기준' : '체결 수량이 같을 때 비교';
    $('baseline-heading').textContent = data.baselineMarket + '만 이용';
    $('baseline-allocation-label').textContent = data.baselineMarket + '만 이용';
    $('analysis-result-label').textContent = symbolName(data.symbol) + ' · ' + sideText(data.side) + ' · ' + money(data.limitPrice)
      + ' · ' + shares(data.requestedQuantity) + ' · ' + sourceLabel(data.source);
    const strategies = [single, priceFirst, quantityFirst];
    const metrics = [
      ['체결 가능 수량', strategy => shares(strategy.filledQuantity)],
      ['미체결 수량', strategy => shares(strategy.remainingQuantity)],
      ['체결 가능 비율', strategy => num(strategy.fillRate) + '%'],
      ['이용 시장 수', strategy => num(fillsByMarket(strategy).size) + '곳'],
      ['예상 평균 체결가', strategy => money(strategy.averagePrice)],
      ['예상 체결금액', strategy => money(strategy.totalAmount)],
      ['예상 수수료', strategy => money(strategy.totalFee)],
      ['예상 ' + amountKind, strategy => money(strategy.settlementAmount)]
    ];
    $('comparison-table').replaceChildren(...metrics.map(([label, format]) => {
      const row = el('tr');
      cell(row, label);
      strategies.forEach((strategy, i) => cell(row, format(strategy), i === 1 ? 'sor-column' : ''));
      return row;
    }));
    for (const [labelId, barId, strategy] of [
      ['baseline-fill-label', 'baseline-bar', single],
      ['sor-fill-label', 'sor-bar', priceFirst],
      ['quantity-fill-label', 'quantity-bar', quantityFirst]
    ]) {
      $(labelId).textContent = shares(strategy.filledQuantity) + ' / ' + shares(data.requestedQuantity);
      allocationBar(barId, strategy, data.requestedQuantity);
    }
    const details = $('analysis-allocation-details');
    details.replaceChildren();
    for (const [label, strategy] of [['가격 우선', priceFirst], ['수량 우선', quantityFirst]]) {
      details.append(el('strong', '', label));
      for (const item of strategy.allocations) {
        details.append(el('p', '', item.market + ' · ' + levelOf(item) + '호가 · ' + money(item.price)
          + ' × ' + shares(item.quantity) + ' = ' + money(item.amount) + ' · 수수료 ' + money(item.fee)));
      }
      if (!strategy.allocations.length) details.append(el('p', '', '체결 가능한 물량 없음'));
    }
    let explanation = common.quantity ? '단일시장과 가격 우선을 같은 ' + shares(common.quantity) + '로 비교하면 '
      + (sell ? '수령액 증가분은 ' : '결제액 절감분은 ') + money(common.savings) + '이에요.'
      : '단일시장과 가격 우선의 공통 체결 수량이 0주예요.';
    if (data.additionalFilledQuantity > 0) explanation += ' 가격 우선으로 ' + shares(data.additionalFilledQuantity) + '를 더 체결할 수 있어요.';
    $('analysis-explanation').textContent = explanation;
    $('analysis-fees').textContent = '시연용 모의 수수료 · ' + marketNames.map(name => name + ' ' + feeRateText(name)).join(' / ');
    $('analysis-quotes').replaceChildren(...data.quotes.map(q => {
      const row = el('tr'); cell(row, '시장 ' + q.market + ' · ' + levelOf(q) + '호가'); cell(row, money(priceOf(q,data.side))); cell(row, shares(quantityOf(q,data.side))); return row;
    }));
  }
  function navigate() {
    const requested = location.hash.slice(1);
    const available = Object.hasOwn(views, requested) && (requested !== 'markets' || state.session?.guest === false);
    const view = available ? requested : 'dashboard';
    state.view = view;
    document.querySelectorAll('[data-view]').forEach(node => { node.hidden = node.dataset.view !== view; });
    document.querySelectorAll('[data-nav]').forEach(node => {
      const selected = node.dataset.nav === view; node.classList.toggle('active', selected);
      if (selected) node.setAttribute('aria-current', 'page'); else node.removeAttribute('aria-current');
    });
    $('breadcrumb-current').textContent = views[view][0]; $('page-title').textContent = views[view][1];
    $('page-description').textContent = views[view][2]; document.title = views[view][0] + ' · SOR Desk';
    if (!state.busy && view === 'analysis' && !state.comparison) run(compare);
    if (!state.busy && view === 'markets' && state.events === null && state.connected) run(loadEvents);
  }
  function switchHistory(showTrades) {
    $('orders-panel').hidden = showTrades; $('trades-panel').hidden = !showTrades;
    for (const [id, selected] of [['tab-orders', !showTrades], ['tab-trades', showTrades]]) {
      $(id).classList.toggle('active', selected); $(id).setAttribute('aria-selected', String(selected));
    }
  }

  initMarketEditor();
  $('symbol-select').addEventListener('change', event => run(() => selectSymbol(event.target.value)));
  $('toggle-auto').addEventListener('click', () => run(() => changeAuto(!(state.auto?.running || state.autoUncertain))));
  $('detail-stop-auto').addEventListener('click', () => run(() => changeAuto(false)));
  $('verification-link').addEventListener('click', event => {
    if (state.auto?.running || state.autoUncertain) {
      event.preventDefault(); notify('안정성 검증 전에는 자동시장을 정지해 주세요.', 'warning');
    }
  });
  $('refresh').addEventListener('click', () => run(async () => { await refresh(); notify('현재 계좌·호가·주문내역을 불러왔어요.'); }));
  $('logout').addEventListener('click', () => run(async () => {
    const response = await sorAuth.request('/logout', {method: 'POST', cache: 'no-store'});
    if (!response.ok) throw new Error('접속을 종료하지 못했어요. 다시 시도해 주세요.');
    location.replace(state.session?.guest === false ? '/presenter.html?logout' : '/login.html?logout');
  }));
  const presenterCodeDialog = $('presenter-code-dialog');
  $('open-presenter-code').addEventListener('click', () => {
    $('presenter-code-account').textContent = '대상 모의계좌 · ' + state.session.accountNumber;
    presenterCodeStatus('');
    presenterCodeDialog.showModal();
  });
  $('issue-presenter-code').addEventListener('click', () => run(issuePresenterCode));
  $('copy-presenter-code').addEventListener('click', async () => {
    presenterCodeStatus(await sorAuth.copyCode($('presenter-code')));
  });
  $('close-presenter-code').addEventListener('click', () => presenterCodeDialog.close());
  presenterCodeDialog.addEventListener('cancel', event => { if (state.busy) event.preventDefault(); });
  presenterCodeDialog.addEventListener('close', () => {
    $('presenter-code').value = '';
    $('presenter-code-result').hidden = true;
    $('issue-presenter-code').hidden = false;
  });
  $('order-form').addEventListener('submit', event => { event.preventDefault(); run(buy); });
  $('order-account-number').addEventListener('input', () => { $('order-rejection').hidden = true; });
  $('use-account-number').addEventListener('click', () => {
    $('order-account-number').value = state.session.accountNumber;
    $('order-rejection').hidden = true;
  });
  $('show-sor-consent').addEventListener('click', openSorConsent);
  $('close-sor-consent').addEventListener('click', () => $('sor-consent-dialog').close());
  $('sor-consent-form').addEventListener('submit', event => { event.preventDefault(); run(acceptSorConsent); });
  $('order-type').addEventListener('change', () => { invalidatePreview(); renderAll(); });
  $('routing-policy').addEventListener('change', () => { invalidatePreview(); renderSide(); controls(); });
  $('preview').addEventListener('click', () => { if ($('order-form').reportValidity()) run(preview); });
  $('retry').addEventListener('click', () => run(performPending));
  $('detail-retry').addEventListener('click', () => run(performPending));
  for (const id of ['limit-price', 'quantity']) $(id).addEventListener('input', () => {
    state.orderSeeded = true; invalidatePreview(); controls();
  });
  $('use-best').addEventListener('click', () => {
    const best = bestQuote(); if (!best) { notify('현재 체결할 상대 물량이 없어요.', 'warning'); return; }
    $('limit-price').value = priceOf(best,state.side); invalidatePreview(); controls();
  });
  document.querySelectorAll('[data-add]').forEach(button => button.addEventListener('click', () => {
    $('quantity').value = Math.min(intMax, Math.max(0, Number($('quantity').value) || 0) + Number(button.dataset.add));
    invalidatePreview(); controls();
  }));
  $('use-max').addEventListener('click', () => {
    if(!state.account)return;
    const price=Number($('limit-price').value),sell=state.side==='SELL';
    if(!safePositive(price)){notify('지정가를 먼저 입력해 주세요.','warning');return;}
    const maxBuy = ((BigInt(state.account.availableCash) + 1n) * feeBasis - 1n)
      / (BigInt(price) * (feeBasis + maxFeeRate()));
    const maximum = sell ? state.account.availableQuantity : Math.min(intMax, Number(maxBuy));
    if(maximum<1){notify(sell?'매도 가능한 주식이 없어요.':'주문 가능한 금액이 부족해요.','warning');return;}
    $('quantity').value=maximum;invalidatePreview();controls();
  });
  document.querySelectorAll('[data-side]').forEach(button=>button.addEventListener('click',()=>{
    state.side=button.dataset.side;state.orderSeeded=true;
    const best=bestQuote();if(best)$('limit-price').value=priceOf(best,state.side);
    invalidatePreview();renderAll();
  }));
  $('order-filter').addEventListener('change', () => { renderOrders(); controls(); });
  $('order-search').addEventListener('input', () => { renderOrders(); controls(); });
  $('tab-orders').addEventListener('click', () => switchHistory(false));
  $('tab-trades').addEventListener('click', () => switchHistory(true));
  $('archived-history').addEventListener('click', () => run(async () => {
    state.archivedHistory = state.archivedHistory ? null : await loadArchivedHistory();
    renderAll();
  }));
  document.addEventListener('click', event => {
    const detail = event.target.closest('[data-detail]'), cancellation = event.target.closest('[data-cancel]');
    const amendment = event.target.closest('[data-amend]');
    if (detail) run(() => showDetail(Number(detail.dataset.detail)));
    else if (amendment && !amendment.disabled) showAmend(Number(amendment.dataset.amend));
    else if (cancellation && !cancellation.disabled) run(() => cancel(Number(cancellation.dataset.cancel)));
  });
  $('close-detail').addEventListener('click', closeDetail); $('close-detail-bottom').addEventListener('click', closeDetail);
  $('order-dialog').addEventListener('close', () => { state.amendDraft = null; });
  $('detail-amend').addEventListener('click', () => { if (state.selectedOrder) showAmend(state.selectedOrder.orderId); });
  $('amend-form').addEventListener('submit', event => { event.preventDefault(); run(amend); });
  $('amend-form').addEventListener('input', controls);
  $('amend-back').addEventListener('click', () => {
    state.amendDraft = null;
    renderDetail(state.selectedOrder);
    controls();
    $('detail-amend').focus();
  });
  $('detail-cancel').addEventListener('click', () => { if (state.selectedOrder) run(() => cancel(state.selectedOrder.orderId)); });
  $('market-clock-form').addEventListener('submit', event => {
    event.preventDefault();
    run(() => applyMarketTime($('market-clock-input').value));
  });
  $('market-clock-real').addEventListener('click', () => run(() => applyMarketTime(null)));
  document.querySelectorAll('[data-market-time]').forEach(button => button.addEventListener('click', () =>
    run(() => applyMarketTime(button.dataset.marketTime))));
  $('market-form').addEventListener('submit', event => { event.preventDefault(); run(applyMarket); });
  document.querySelectorAll('#market-form input').forEach(input => input.addEventListener('input', () => { state.marketDirty = true; marketDraftNote(); }));
  $('load-market').addEventListener('click', () => run(async () => {
    await refresh(); fillMarket(state.book.quotes, state.book.revision); notify('현재 호가를 입력칸에 불러왔어요.');
  }));
  $('example-market').addEventListener('click', () => run(() => loadExample(false)));
  $('load-events').addEventListener('click', () => run(loadEvents));
  $('depth-example-market').addEventListener('click', () => run(() => loadExample(true)));
  async function analysisDefaults() {
    const side = $('analysis-side').value;
    const source = $('analysis-source').value;
    const quotes = source === 'CURRENT' ? state.book?.quotes ?? [] : await exampleQuotes(source === 'DEPTH_EXAMPLE');
    const prices = quotes.filter(quote => quantityOf(quote, side) > 0).map(quote => priceOf(quote, side));
    if (prices.length) $('analysis-price').value = side === 'SELL' ? Math.min(...prices) : Math.max(...prices);
    else if (quotes.length) $('analysis-price').value = priceOf(quotes[0], side);
    if (source !== 'CURRENT') $('analysis-quantity').value = 10;
    $('analysis-price-label').textContent = sideText(side) + ' 지정가(원)';
  }
  $('analysis-source').addEventListener('change', () => run(analysisDefaults));
  $('analysis-side').addEventListener('change', () => run(analysisDefaults));

  $('analysis-form').addEventListener('submit', event => { event.preventDefault(); run(compare); });
  document.querySelectorAll('#analysis-form input, #analysis-form select').forEach(input => input.addEventListener('input', () => {
    state.comparisonStale = true;
    $('analysis-caption').textContent = state.comparison ? '비교 조건이 바뀌었어요. 아래는 이전 결과이며, 결과 비교하기로 다시 계산할 수 있어요.'
      : '비교할 조건을 입력하고 결과 비교하기를 눌러 주세요.';
  }));
  window.addEventListener('hashchange', navigate);
  window.addEventListener('storage', event => { if (event.key === verificationKey) controls(); });
  window.addEventListener('focus', controls);
  window.addEventListener('pageshow', event => { startPolling(); if (event.persisted) { if (state.session) restorePending(); run(refresh); } });
  window.addEventListener('pagehide', () => { clearInterval(pollTimer); pollTimer = null; });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });

  renderAll();
  // Select the initial page without triggering a second concurrent initialization.
  state.busy = true; navigate(); state.busy = false;
  run(async () => {
    await refresh();
    if (state.view === 'analysis') { await analysisDefaults(); await compare(); }
    if (state.view === 'markets') await loadEvents();
  });
  startPolling();
})();
