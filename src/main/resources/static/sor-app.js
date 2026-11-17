/* SOR Desk: existing Spring Boot API client. No local account or simulated fills. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const pendingKey = 'stock.sor.pendingAction.v4';
  const verificationKey = 'stock.sor.check.active.v1';
  const intMax = 2147483647;
  const levelOf = quote => quote.level ?? 1;
  const sourceLabel = source => source === 'EXAMPLE' ? '기본 예시' : source === 'DEPTH_EXAMPLE' ? '3단계 예시' : '조회한 현재 시장';
  const depthExample = () => [
    {market:'A',level:1,askPrice:1000,askQuantity:2,bidPrice:970,bidQuantity:2},{market:'A',level:2,askPrice:1020,askQuantity:4,bidPrice:950,bidQuantity:4},{market:'A',level:3,askPrice:1040,askQuantity:4,bidPrice:930,bidQuantity:4},
    {market:'B',level:1,askPrice:990,askQuantity:3,bidPrice:980,bidQuantity:3},{market:'B',level:2,askPrice:1010,askQuantity:3,bidPrice:960,bidQuantity:3},{market:'B',level:3,askPrice:1030,askQuantity:4,bidPrice:940,bidQuantity:4}];
  const sideText = side => side === 'SELL' ? '매도' : '매수';
  const priceOf = (q,side) => side === 'SELL' ? q.bidPrice : q.askPrice;
  const quantityOf = (q,side) => side === 'SELL' ? q.bidQuantity : q.askQuantity;
  const reservedAsset = order => order.side === 'SELL' ? '예약 수량 '+shares(order.reservedQuantity) : '예약금 '+money(order.reservedCash);
  const views = {
    dashboard: ['대시보드', '계좌와 시장을 한눈에', '두 시장의 호가를 살펴보고, 더 나은 조건으로 주문하세요.'],
    trade: ['주문하기', '두 시장을 연결하는 매수·매도 주문', '매수는 낮은 가격부터, 매도는 높은 가격부터 SOR이 배분해요.'],
    orders: ['주문내역', '접수부터 체결, 취소까지', '주문 상태와 시장별 체결 결과를 확인하세요.'],
    analysis: ['SOR 분석', '한 시장과 두 시장의 차이', '같은 시작 호가에서 가격과 체결 가능 수량을 비교하세요.'],
    markets: ['시장관리', '시장 상황을 직접 바꿔 보세요', '두 모의시장의 가격과 잔량을 바꾸고 대기 주문에 반영하세요.']
  };
  const state = {side:'BUY', busy: false, connected: false, account: null, book: null, orders: [], open: [], trades: [],
    events: null, view: 'dashboard', pending: null, storageAvailable: true, orderSeeded: false,
    draftRevision: null, marketDirty: false, preview: null, comparison: null, comparisonStale: false,
    selectedOrder: null, detailTrigger: null, auto: null, autoKnown: false, autoUncertain: false, polling: false, operationVersion: 0};
  let pollTimer = null;

  function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }
  const num = value => typeof value === 'number' && Number.isFinite(value)
    ? value.toLocaleString('ko-KR', {maximumFractionDigits: 2}) : '—';
  const money = value => value == null ? '—' : num(value) + '원';
  const shares = value => value == null ? '—' : num(value) + '주';
  const idValid = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
  // getRandomValues also works when the app is opened over HTTP from a LAN address.
  const requestId = () => Array.from(crypto.getRandomValues(new Uint8Array(16)),
    byte => byte.toString(16).padStart(2, '0')).join('');
  const safePositive = value => Number.isSafeInteger(value) && value > 0;
  function quantityValid(value, allowZero = false) {
    return Number.isInteger(value) && value >= (allowZero ? 0 : 1) && value <= intMax;
  }
  function validBuy(body) {
    return body && idValid(body.clientOrderId) && safePositive(body.limitPrice)
      && quantityValid(body.quantity) && Number.isSafeInteger(body.limitPrice * body.quantity)
      && ['BUY','SELL'].includes(body.side ?? 'BUY');
  }
  function validQuotes(body) {
    return body && idValid(body.clientEventId) && Number.isSafeInteger(body.expectedRevision)
      && body.expectedRevision >= 0 && validQuoteRows(body.quotes);
  }
  function validQuoteRows(quotes) {
    if (!Array.isArray(quotes) || ![2, 6].includes(quotes.length) || quotes.some(q => !q)) return false;
    const depth = quotes.length / 2;
    const bids = quotes.filter(q => (q.bidPrice ?? 0) > 0).length;
    if (bids !== 0 && bids !== quotes.length) return false;
    return ['A', 'B'].every(market => {
      const rows = quotes.filter(q => q.market === market).sort((a,b) => levelOf(a) - levelOf(b));
      return rows.length === depth && rows.every((q,i) => levelOf(q) === i+1 && safePositive(q.askPrice)
        && quantityValid(q.askQuantity, true) && (!i || q.askPrice >= rows[i-1].askPrice)
        && Number.isSafeInteger(q.bidPrice ?? 0) && (q.bidPrice ?? 0) >= 0 && quantityValid(q.bidQuantity ?? 0,true)
        && ((q.bidPrice ?? 0) > 0 || (q.bidQuantity ?? 0) === 0)
        && (!i || (q.bidPrice ?? 0) <= (rows[i-1].bidPrice ?? 0)));
    });
  }
  function validPending(value) {
    return value && ((['BUY','SELL'].includes(value.kind) && validBuy(value.body) && (value.body.side ?? 'BUY') === value.kind)
      || (value.kind === 'QUOTES' && validQuotes(value.body))
      || (value.kind === 'CANCEL' && safePositive(value.orderId)));
  }
  function verificationActive() {
    try { return Boolean(localStorage.getItem(verificationKey)); } catch { return false; }
  }
  function restorePending() {
    try {
      const raw = sessionStorage.getItem(pendingKey);
      if (raw) {
        const saved = JSON.parse(raw);
        if (!validPending(saved)) throw new Error('보관한 요청을 읽지 못했어요. 기존 주문 화면에서 이전 요청을 확인해 주세요.');
        state.pending = saved;
      } else {
        const old = JSON.parse(sessionStorage.getItem('stock.sor.pendingBuy'));
        if (validBuy(old)) {
          state.pending = {kind: 'BUY', body: old};
          sessionStorage.setItem(pendingKey, JSON.stringify(state.pending));
          sessionStorage.removeItem('stock.sor.pendingBuy');
        } else state.pending = null;
      }
      if (['BUY','SELL'].includes(state.pending?.kind)) {
        state.side = state.pending.body.side ?? 'BUY';
        $('limit-price').value = state.pending.body.limitPrice;
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
    try { sessionStorage.setItem(pendingKey, JSON.stringify(value)); }
    catch { throw new Error('요청 ID를 보관할 수 없어 주문을 전송하지 않았어요. 브라우저 저장 설정을 확인해 주세요.'); }
    state.pending = value;
    controls();
  }
  function clearPending() {
    state.pending = null;
    try { sessionStorage.removeItem(pendingKey); }
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
    document.querySelectorAll('[data-request]').forEach(node => { node.disabled = state.busy; });
    document.querySelectorAll('[data-edit], [data-mutate]').forEach(node => {
      node.disabled = state.busy || Boolean(state.pending) || checking || !state.storageAvailable || !state.connected;
    });
    document.querySelectorAll('#analysis-form input, #analysis-form select').forEach(node => { node.disabled = state.busy; });
    $('pending-banner').hidden = !state.pending;
    $('check-banner').hidden = !checking;
    $('retry').disabled = state.busy || checking || !state.pending || !state.storageAvailable;
    $('detail-retry').hidden = !state.pending;
    $('detail-retry').disabled = $('retry').disabled;
    if (state.pending) {
      $('pending-description').textContent = state.pending.kind === 'BUY' ? '접수한 매수 주문을 원래 요청 ID로 확인해요.'
        : state.pending.kind === 'SELL' ? '접수한 매도 주문을 원래 요청 ID로 확인해요.'
        : state.pending.kind === 'CANCEL' ? '주문 ' + state.pending.orderId + '번의 취소 결과를 확인해요.'
        : '호가를 다시 덮어쓰지 않도록, 처음 보낸 요청으로 반영 결과를 확인해요.';
    }
    const autoRunning = state.auto?.running || state.autoUncertain;
    if (autoRunning) {
      document.querySelectorAll('#market-form [data-edit], #market-form [data-mutate]').forEach(node => { node.disabled = true; });
    }
    $('toggle-auto').disabled = state.busy || !state.autoKnown || state.auto === null
      || (!autoRunning && (Boolean(state.pending) || checking || !state.connected || !state.storageAvailable));
    $('toggle-auto').textContent = state.autoUncertain ? '자동시장 정지 · 상태 확인' : state.auto?.running ? '자동시장 정지' : '자동시장 시작';
    $('toggle-auto').className = 'button ' + (autoRunning ? 'button-outline' : 'button-primary');
    $('detail-stop-auto').hidden = !autoRunning;
    $('detail-stop-auto').disabled = state.busy;
    updateCommitment();
  }
  async function request(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(path, {...options, cache: 'no-store', signal: controller.signal});
      let data;
      try { data = await response.json(); }
      catch { throw new Error('서버 응답을 읽지 못했어요. 연결 상태를 확인해 주세요.'); }
      if (!response.ok) {
        const error = new Error(data.message || data.detail || '요청을 처리하지 못했어요. (' + response.status + ')');
        error.status = response.status; throw error;
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
    $('auto-indicator').className = 'auto-indicator' + (auto?.running ? ' running' : '');
    $('auto-title').textContent = state.autoUncertain ? '자동시장 상태 확인 필요' : auto?.running ? '자동 모의시장 실행 중' : '자동 모의시장 정지';
    $('auto-description').textContent = state.autoUncertain ? '실행 상태를 확인하지 못했어요. 정지 버튼으로 상태를 다시 확인해 주세요.'
      : !state.autoKnown ? '서버 상태를 확인하고 있어요.'
      : !auto ? '자동시장 기능에 연결할 수 없어요. 서버 업데이트를 확인해 주세요.'
      : auto.message || (auto.running ? '약 3초마다 시장별 3단계 모의호가를 바꾸고 대기 주문을 검사해요.'
        : '시작하면 기존 대기 주문이 체결될 수 있어요. 브라우저를 닫아도 서버에서 실행돼요.');
    $('auto-metrics').textContent = auto ? '이번 실행 ' + num(auto.updates) + '회 갱신 · 체결 ' + shares(auto.filledQuantity) : '';
  }
  async function refresh({includeEvents = state.events !== null, background = false, version = state.operationVersion} = {}) {
    try {
      const selectedId = $('order-dialog').open ? state.selectedOrder?.orderId : null;
      const [account, book, orders, open, trades, events, auto, selected] = await Promise.all([
        request('/api/sor/account'), request('/api/sor/book'), request('/api/sor/orders'),
        request('/api/sor/orders/open'), request('/api/trades'),
        includeEvents ? request('/api/sor/quote-events') : Promise.resolve(null), autoStatus(),
        selectedId ? request('/api/sor/orders/' + selectedId) : Promise.resolve(null)
      ]);
      if (background && (state.busy || state.operationVersion !== version)) return false;
      validateSnapshot(account, book);
      const changed = state.book && state.book.revision !== book.revision;
      const previousAuto = state.auto;
      Object.assign(state, {account, book, orders, open, trades, connected: true, auto, autoKnown: true, autoUncertain: false});
      if (selected && state.selectedOrder?.orderId === selectedId) state.selectedOrder = selected;
      if (events !== null) state.events = events;
      if (!state.orderSeeded) {
        const best = bestQuote();
        $('limit-price').value = best ? priceOf(best,state.side) : priceOf(book.quotes[0],state.side);
        state.orderSeeded = true;
      }
      if (!state.marketDirty && state.pending?.kind !== 'QUOTES') fillMarket(book.quotes, book.revision);
      if (state.preview && changed) invalidatePreview('호가가 바뀌었어요. 배분 미리보기를 다시 눌러 주세요.');
      if (state.comparison?.source === 'CURRENT' && state.comparison.bookRevision !== book.revision) {
        state.comparisonStale = true;
        $('analysis-caption').textContent = '현재 호가가 바뀌었어요. 아래는 이전 비교 결과이며, 결과 비교하기로 다시 계산할 수 있어요.';
      }
      $('last-updated').textContent = '최근 조회 ' + new Date().toLocaleTimeString('ko-KR', {hour12: false});
      connection(); renderAll();
      if (background && !state.pending && auto && previousAuto && auto.startedAt === previousAuto.startedAt
          && auto.filledQuantity > previousAuto.filledQuantity) {
        notify('자동시장에서 대기 주문 ' + shares(auto.filledQuantity - previousAuto.filledQuantity) + '가 추가 체결됐어요.');
      }
      return true;
    } catch (error) {
      if (background && (state.busy || state.operationVersion !== version)) return false;
      state.connected = false; connection();
      $('last-updated').textContent = '이전 조회 값이 표시될 수 있어요.';
      controls();
      throw error;
    }
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
    notify(status.running ? '자동시장을 시작했어요. 호가 변경과 대기 주문 체결이 서버에서 자동으로 진행돼요.'
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
  function bestQuote(side = state.side) {
    return state.book?.quotes.filter(q => quantityOf(q,side) > 0).sort((a,b) =>
      (side === 'SELL' ? priceOf(b,side)-priceOf(a,side) : priceOf(a,side)-priceOf(b,side))
      || a.market.localeCompare(b.market) || levelOf(a)-levelOf(b))[0] ?? null;
  }
  function marketSymbol(market) { return el('span', 'market-symbol ' + (market === 'A' ? 'market-a' : 'market-b'), market); }
  function renderMarketCards(target) {
    const best = bestQuote();
    const cards = ['A', 'B'].map(market => {
      const rows = (state.book?.quotes ?? []).filter(q => q.market === market).sort((a,b) => levelOf(a)-levelOf(b));
      const first = rows.find(q => quantityOf(q,state.side) > 0);
      const isBest = Boolean(first && market === best?.market);
      const card = el('article', 'market-card' + (isBest ? ' best' : ''));
      const heading = el('div', 'market-card-heading'), identity = el('div', 'market-identity');
      identity.append(marketSymbol(market), el('span', '', '시장 ' + market)); heading.append(identity);
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
      card.append(heading, prices, book, volume); return card;
    });
    $(target).replaceChildren(...cards);
  }
  function statusLabel(order) {
    if (order.status === 'FILLED') return '체결 완료';
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
    document.querySelectorAll('[data-side]').forEach(node => node.setAttribute('aria-pressed',String(node.dataset.side===state.side)));
    $('order-title').textContent=name+' 주문';$('order-pill').textContent=name+' 지정가';$('order-pill').classList.toggle('sell',sell);
    $('limit-price-label').textContent=name+' 지정가';$('buy').textContent=name+' 주문하기';$('buy').classList.toggle('button-sell',sell);
    $('use-best').textContent=sell?'최고 매수호가':'최저 매도호가';$('use-max').textContent=sell?'매도 가능 전량':'최대 가능';
    $('trade-available-label').textContent=sell?'매도 가능 수량':'주문 가능 금액';$('commitment-label').textContent=sell?'접수에 예약할 수량':'접수에 필요한 금액';
    $('routing-hint').textContent=sell?'매도는 지정가 이상의 매수호가를 높은 가격부터 선택해요.':'매수는 지정가 이하의 매도호가를 낮은 가격부터 선택해요.';
    $('order-form-note').textContent=sell?'접수 시 전체 수량을 예약해요. 체결된 주식만 보유수량에서 차감하고 대금을 더해요. 남은 예약 수량은 취소할 때 해제돼요.':'접수 시 지정가 × 전체 수량을 확보해요. 실제 체결대금을 차감하고 남은 수량의 금액을 예약해요.';
  }
  function cell(row, value, className = '') {
    const node = el('td', className);
    node.append(value instanceof Node ? value : document.createTextNode(String(value))); row.append(node); return node;
  }
  function emptyTable(id, columns, text) {
    const row = el('tr'), td = el('td', 'empty-cell', text); td.colSpan = columns; row.append(td); $(id).replaceChildren(row);
  }
  function detailButton(orderId) {
    const button = el('button', 'table-action', '상세'); button.type = 'button'; button.dataset.detail = String(orderId);
    button.setAttribute('data-request', '');
    button.setAttribute('aria-label', '주문 ' + orderId + ' 상세 보기'); return button;
  }
  function cancelButton(orderId) {
    const button = el('button', 'table-action cancel-action', '취소'); button.type = 'button';
    button.dataset.cancel = String(orderId); button.setAttribute('data-mutate', '');
    button.setAttribute('aria-label', '주문 ' + orderId + ' 잔여 수량 취소'); return button;
  }
  function remaining(order) { return order.cancellable ? order.remainingQuantity : 0; }
  function renderDashboardOrders() {
    if (!state.orders.length) { emptyTable('dashboard-orders', 6, state.connected ? '아직 접수한 주문이 없어요.' : '서버 연결 후 주문을 확인할 수 있어요.'); return; }
    $('dashboard-orders').replaceChildren(...state.orders.slice(0, 5).map(order => {
      const row = el('tr'); cell(row, '#' + order.orderId, 'row-id'); cell(row, sideBadge(order.side)); cell(row, money(order.limitPrice));
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
        el('p', '', '체결 ' + shares(order.filledQuantity) + ' / ' + reservedAsset(order)));
      const actions = el('div', 'waiting-actions'); actions.append(detailButton(order.orderId), cancelButton(order.orderId));
      item.append(content, actions); return item;
    }));
  }
  function renderOrders() {
    const filter = $('order-filter').value, search = $('order-search').value.trim().toLowerCase();
    const orders = state.orders.filter(order => {
      const matches = filter === 'ALL' || (filter === 'WORKING' && order.cancellable)
        || order.status === filter || (filter === 'CLOSED' && !order.cancellable && !['FILLED', 'CANCELLED'].includes(order.status));
      return matches && (!search || String(order.orderId).includes(search) || order.clientOrderId.toLowerCase().includes(search));
    });
    $('order-count').textContent = num(orders.length) + '건';
    if (!orders.length) { emptyTable('orders-table', 9, search || filter !== 'ALL' ? '조건에 맞는 주문이 없어요.' : '아직 접수한 주문이 없어요.'); return; }
    $('orders-table').replaceChildren(...orders.map(order => {
      const row = el('tr'); cell(row, '#' + order.orderId, 'row-id'); cell(row, sideBadge(order.side)); cell(row, money(order.limitPrice));
      cell(row, shares(order.requestedQuantity)); cell(row, shares(order.filledQuantity)); cell(row, shares(remaining(order)));
      cell(row, shares(order.cancelledQuantity)); cell(row, statusBadge(order));
      const actions = el('div', 'table-actions'); actions.append(detailButton(order.orderId));
      if (order.cancellable) actions.append(cancelButton(order.orderId)); cell(row, actions); return row;
    }));
  }
  function displayTime(value) {
    if (!value) return '—';
    const parsed = new Date(String(value).replace(' ', 'T'));
    return Number.isNaN(parsed.getTime()) ? String(value).replace('T', ' ').slice(0, 19)
      : parsed.toLocaleString('ko-KR', {month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false});
  }
  function renderTrades() {
    $('trade-count').textContent = num(state.trades.length) + '건';
    if (!state.trades.length) { emptyTable('trades-table', 6, '아직 체결된 거래가 없어요.'); return; }
    $('trades-table').replaceChildren(...state.trades.map(trade => {
      const row = el('tr'); cell(row, '#' + trade.id, 'row-id');
      cell(row, el('span', 'side-label' + (trade.side === 'SELL' ? ' sell' : ''), trade.side === 'SELL' ? '매도' : '매수'));
      cell(row, money(trade.price)); cell(row, shares(trade.quantity));
      cell(row, money(trade.price * trade.quantity)); cell(row, displayTime(trade.created_at)); return row;
    }));
  }
  function renderEvents() {
    if (state.events === null) { emptyTable('events-table', 6, '내역 불러오기를 누르면 최근 호가 반영 기록을 볼 수 있어요.'); return; }
    if (!state.events.length) { emptyTable('events-table', 6, '아직 호가를 반영한 기록이 없어요.'); return; }
    $('events-table').replaceChildren(...state.events.map(event => {
      const row = el('tr');
      cell(row, '#' + event.eventId, 'row-id');
      for (const market of ['A','B']) {
        const levels = el('div','event-levels');
        for (const q of event.submittedQuotes.filter(q => q.market === market)) levels.append(el('span','',levelOf(q)+'호가 · 매도 '+money(q.askPrice)+'/'+shares(q.askQuantity)+' · 매수 '+(q.bidPrice>0?money(q.bidPrice)+'/'+shares(q.bidQuantity):'변경 없음')));
        cell(row,levels);
      }
      cell(row, shares(event.filledQuantity)); cell(row, money(event.totalAmount)); cell(row, displayTime(event.createdAt)); return row;
    }));
  }
  function renderAll() {
    renderSide();
    if (state.account) {
      $('cash').textContent = money(state.account.cash); $('reserved').textContent = money(state.account.reservedCash);
      $('available').textContent = money(state.account.availableCash); $('holdings').textContent = shares(state.account.quantity);
      $('trade-available').textContent = state.side==='SELL'?shares(state.account.availableQuantity):money(state.account.availableCash);
      $('holdings-note').textContent = '매도 예약 '+shares(state.account.reservedQuantity)+' · 가능 '+shares(state.account.availableQuantity);
    }
    renderMarketCards('dashboard-markets'); renderMarketCards('trade-markets');
    const best = bestQuote(); $('best-market-label').textContent = best
      ? sideText(state.side)+'는 시장 ' + best.market + '의 ' + levelOf(best) + '호가부터 배분할 수 있어요.' : state.book ? '현재 '+(state.side==='SELL'?'매수':'매도')+' 물량이 없어요.' : '시장 상태를 조회하고 있어요.';
    $('open-summary').textContent = '총 ' + num(state.open.length) + '건 · 예약금 ' + money(state.account?.reservedCash)+' · 예약 주식 '+shares(state.account?.reservedQuantity);
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
  function orderInputs() {
    const limitPrice = Number($('limit-price').value), quantity = Number($('quantity').value);
    if (!safePositive(limitPrice) || !quantityValid(quantity) || !Number.isSafeInteger(limitPrice * quantity)) {
      throw new Error('지정가와 수량을 확인해 주세요. 정확히 표시할 수 있는 양의 정수가 필요해요.');
    }
    return {side:state.side,limitPrice, quantity};
  }
  function updateCommitment() {
    let input=null;
    try { input=orderInputs(); } catch {}
    const sell=state.side==='SELL', needed=input?(sell?input.quantity:input.limitPrice*input.quantity):null;
    $('commitment').textContent=sell?shares(needed):money(needed);
    const available=state.account?(sell?state.account.availableQuantity:state.account.availableCash):null;
    const insufficient=needed!==null&&available!==null&&needed>available;
    $('funds-hint').className='funds-hint'+(insufficient?' insufficient':'');
    $('funds-hint').textContent=insufficient?(sell?'매도 가능 수량이 '+shares(needed-available)+' 부족해요.':'주문 가능 금액이 '+money(needed-available)+' 부족해요.')
      :(sell?'전체 주문 수량만큼 매도 가능한 주식이 필요해요.':'지정가 × 전체 수량만큼의 금액이 필요해요.');
    if(insufficient||input===null)$('buy').disabled=true;
  }
  function invalidatePreview(caption = '주문 조건이 바뀌었어요. 배분 미리보기를 다시 눌러 주세요.') {
    state.preview = null; $('preview-caption').textContent = caption;
    const empty = el('div', 'empty-state'); empty.append(el('span', 'empty-symbol', '↗'),
      el('strong', '', '예상 배분을 확인해 보세요.'), el('p', '', '주문 접수 시 시장 상황에 따라 결과가 달라질 수 있어요.'));
    $('preview-content').replaceChildren(empty);
  }
  async function preview() {
    const body = orderInputs();
    const plan = await request('/api/sor/preview?' + new URLSearchParams(body));
    state.preview = plan;
    const content = $('preview-content'), stats = el('div', 'preview-stats');
    for (const [label, value] of [['체결 가능', shares(plan.allocatedQuantity)], ['미체결', shares(plan.unallocatedQuantity)], ['예상 대금', money(plan.estimatedAmount)]]) {
      const item = el('div'); item.append(el('span', '', label), el('strong', '', value)); stats.append(item);
    }
    content.replaceChildren(stats);
    for (const [index, allocation] of plan.allocations.entries()) {
      const row = el('div', 'preview-line'), name = el('div'); name.append(marketSymbol(allocation.market),
        el('span', '', (index+1) + '. ' + levelOf(allocation) + '호가 · ' + money(allocation.price) + ' × ' + shares(allocation.quantity)));
      row.append(name, el('strong', '', money(allocation.amount))); content.append(row);
    }
    content.append(el('p','routing-note',state.side==='SELL'?'지정가 이상의 매수호가를 높은 가격부터 배분해요. 같은 가격이면 A시장이 먼저예요.':'지정가 이하의 매도호가를 낮은 가격부터 배분해요. 같은 가격이면 A시장이 먼저예요.'));
    $('preview-caption').textContent = sideText(body.side)+' 지정가 ' + money(body.limitPrice) + ' · ' + shares(body.quantity) + '의 조회 시점 예상 배분';
    notify('예상 배분을 계산했어요. 잔액과 시장 물량은 바뀌지 않았어요.');
  }
  async function performPending() {
    if (verificationActive()) throw new Error('안정성 검증을 먼저 마쳐 주세요.');
    const action = state.pending;
    if (!action) return;
    let result;
    try {
      result = action.kind === 'CANCEL' ? await post('/api/sor/orders/' + action.orderId + '/cancel')
        : await post(['BUY','SELL'].includes(action.kind) ? '/api/sor/orders' : '/api/sor/quotes', action.body);
    } catch (error) {
      if (error.status >= 400 && error.status < 500) {
        clearPending();
        if (action.kind === 'QUOTES') {
          state.marketDirty = false;
          try { await refresh(); } catch { /* Preserve the rejection message. */ }
        } else { try { await refresh(); } catch { /* Preserve the rejection message. */ } }
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
      state.selectedOrder = result;
      $('detail-message').hidden = true;
      notify('주문 #' + result.orderId + ' · ' + statusLabel(result) + ' · 체결 ' + shares(result.filledQuantity)
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
    if (body.side==='BUY'&&body.limitPrice*body.quantity>state.account.availableCash) throw new Error('주문 가능 금액이 부족해요.');
    if (body.side==='SELL'&&body.quantity>state.account.availableQuantity) throw new Error('매도 가능 수량이 부족해요.');
    rememberPending({kind:body.side,body:{clientOrderId:requestId(),...body}});
    await performPending();
  }
  async function cancel(orderId) {
    requireWritable();
    rememberPending({kind: 'CANCEL', orderId}); await performPending();
  }
  async function showDetail(orderId) {
    const order = await request('/api/sor/orders/' + orderId);
    state.selectedOrder = order; state.detailTrigger = document.activeElement;
    $('detail-message').hidden = true;
    renderDetail(order); if (!$('order-dialog').open) $('order-dialog').showModal();
  }
  function renderDetail(order) {
    $('detail-title').textContent = '주문 #' + order.orderId;
    const root = $('detail-body'), meta = el('div', 'detail-meta');
    meta.append(el('span', '', '가상종목 01 · '+sideText(order.side)+' 지정가'), statusBadge(order));
    const grid = el('div', 'detail-grid');
    for (const [label, value] of [['지정가', money(order.limitPrice)], ['요청 수량', shares(order.requestedQuantity)],
      ['체결 수량', shares(order.filledQuantity)], ['대기 수량', shares(remaining(order))],
      ['취소 수량', shares(order.cancelledQuantity)], [order.side==='SELL'?'예약 수량':'예약금', order.side==='SELL'?shares(order.reservedQuantity):money(order.reservedCash)]]) {
      const item = el('div'); item.append(el('span', '', label), el('strong', '', value)); grid.append(item);
    }
    root.replaceChildren(meta, grid, el('h3', 'detail-section-title', '시장별 '+sideText(order.side)+' 체결 · 총 ' + money(order.totalAmount)));
    if (!order.executions.length) root.append(el('p', 'form-note', '아직 체결된 물량이 없어요.'));
    for (const execution of order.executions) {
      const row = el('div', 'detail-execution'), name = el('div');
      name.append(marketSymbol(execution.market), el('strong', '', levelOf(execution) + '호가 · ' + money(execution.price) + ' × ' + shares(execution.quantity)));
      row.append(name, el('span', '', money(execution.amount))); root.append(row);
    }
    const times = el('div', 'detail-times');
    times.append(el('p', '', '접수 ' + displayTime(order.createdAt)), el('p', '', '최근 변경 ' + displayTime(order.updatedAt)));
    if (order.status === 'CANCELLED' && order.filledQuantity > 0) times.append(el('p', '', '체결된 수량은 유지하고, 남은 수량만 취소한 주문이에요.'));
    if (!order.cancellable && order.remainingQuantity > 0) times.append(el('p', '', '이전 단계에서 종료한 주문 기록이에요. 추가로 대기하는 주문은 없어요.'));
    root.append(times); $('detail-cancel').hidden = !order.cancellable;
  }
  function closeDetail() { $('order-dialog').close(); state.detailTrigger?.focus?.(); }
  function editorId(market, level, kind) { return 'market-' + market.toLowerCase() + (level === 1 ? '' : '-'+level) + '-'+kind; }
  function initMarketEditor() {
    const body = $('market-inputs');
    for (const market of ['A','B']) for (let level=1;level<=3;level++) {
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
    for (const market of ['A','B']) for (let level=1;level<=3;level++) {
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
      : state.auto?.running || state.autoUncertain ? '자동시장이 호가를 관리하고 있어요. 직접 입력하려면 위의 자동시장 정지를 눌러 주세요.'
      : stale ? '입력 이후 시장 상태가 바뀌었어요. 현재 호가 불러오기로 확인한 뒤 다시 반영해 주세요.'
      : state.marketDirty ? '입력한 전체 잔량으로 시장이 변경되고, 기존 대기 주문이 체결될 수 있어요.'
      : '현재 호가를 불러왔어요. 수량은 기존 잔량에 더하는 값이 아니라 새 전체 잔량이에요.';
  }
  async function applyMarket() {
    requireWritable();
    if (state.auto?.running || state.autoUncertain) throw new Error('직접 호가를 반영하려면 자동시장을 먼저 정지해 주세요.');
    const body = {clientEventId: requestId(), expectedRevision: state.draftRevision,
      quotes: ['A', 'B'].flatMap(market => [1,2,3].map(level => ({market, level,
        askPrice:Number($(editorId(market,level,'price')).value),askQuantity:Number($(editorId(market,level,'quantity')).value),
        bidPrice:Number($(editorId(market,level,'bid-price')).value),bidQuantity:Number($(editorId(market,level,'bid-quantity')).value)})))};
    if (!validQuotes(body)||body.quotes.some(q=>!safePositive(q.bidPrice))) throw new Error('가격은 양의 정수, 잔량은 0 이상의 정수로 입력해 주세요. 1→3 매도호가는 오름차순, 매수호가는 내림차순이어야 해요.');
    if (body.expectedRevision !== state.book.revision) throw new Error('입력 이후 호가가 바뀌었어요. 현재 호가를 다시 불러와 주세요.');
    rememberPending({kind: 'QUOTES', body}); await performPending();
  }
  async function loadEvents() { state.events = await request('/api/sor/quote-events'); renderEvents(); }
  function comparisonInputs() {
    const input = {side:$('analysis-side').value,source: $('analysis-source').value, baselineMarket: $('analysis-market').value,
      limitPrice: Number($('analysis-price').value), quantity: Number($('analysis-quantity').value)};
    if (!['BUY','SELL'].includes(input.side)||!['EXAMPLE', 'DEPTH_EXAMPLE', 'CURRENT'].includes(input.source) || !['A', 'B'].includes(input.baselineMarket)
        || !safePositive(input.limitPrice) || !quantityValid(input.quantity) || !Number.isSafeInteger(input.limitPrice * input.quantity)) {
      throw new Error('비교할 지정가·수량과 기준 시장을 확인해 주세요.');
    }
    return input;
  }
  async function compare() {
    const input = comparisonInputs();
    try {
      const result = await request('/api/sor/compare?' + new URLSearchParams(input));
      state.comparison = result; state.comparisonStale = false; renderComparison(result);
      $('analysis-caption').textContent = sourceLabel(result.source)
        + '의 호가로 비교했어요. 계좌·주문·시장 물량은 바뀌지 않았어요.';
    } catch (error) {
      state.comparisonStale = true;
      $('analysis-caption').textContent = state.comparison ? '아래는 이전 비교 결과예요. 새로운 비교 결과를 불러오지 못했어요.' : '비교 결과를 불러오지 못했어요.';
      throw error;
    }
  }
  function allocationBar(target, strategy, requested) {
    const root = $(target); root.replaceChildren();
    for (const item of strategy.allocations) {
      const node = el('span', item.market === 'A' ? 'segment-a' : 'segment-b', item.market + ' ' + shares(item.quantity));
      node.style.width = (item.quantity / requested * 100) + '%';
      node.title = '시장 ' + item.market + ' · ' + levelOf(item) + '호가 · ' + shares(item.quantity) + ' · ' + money(item.amount); root.append(node);
    }
    if (strategy.remainingQuantity > 0) {
      const node = el('span', 'segment-empty', shares(strategy.remainingQuantity));
      node.style.width = (strategy.remainingQuantity / requested * 100) + '%'; root.append(node);
    }
    root.setAttribute('aria-label', '체결 가능 ' + shares(strategy.filledQuantity) + ', 미체결 ' + shares(strategy.remainingQuantity));
  }
  function renderComparison(data) {
    $('analysis-empty').hidden = true; $('analysis-results').hidden = false;
    const common = data.commonQuantityCost, single = data.singleMarket, sor = data.sor, sell=data.side==='SELL';
    $('analysis-price-label').textContent=sideText(data.side)+' 지정가(원)';
    $('analysis-quote-price-label').textContent=sell?'매수호가':'매도호가';$('analysis-quote-quantity-label').textContent=sell?'매수 물량':'매도 물량';
    $('savings-label').textContent = common.quantity ? '같은 ' + shares(common.quantity) + (sell?' 기준 매도 증가액':' 기준 매수 절감액') : '같은 수량 기준 금액 차이';
    $('savings').textContent = common.quantity ? money(common.savings) : '비교 불가';
    $('savings-basis').textContent = common.quantity ? '단일시장 ' + money(common.singleMarketAmount) + ' / SOR ' + money(common.sorAmount)
      : '양쪽 모두 체결할 수 있는 수량이 없어요.';
    $('extra-filled').textContent = shares(data.additionalFilledQuantity);
    $('extra-basis').textContent = '단일시장 ' + shares(single.filledQuantity) + ' / SOR ' + shares(sor.filledQuantity);
    $('common-filled').textContent = shares(common.quantity);
    $('baseline-heading').textContent = data.baselineMarket + '시장만 이용';
    $('baseline-allocation-label').textContent = data.baselineMarket + '시장만 이용';
    $('analysis-result-label').textContent = sideText(data.side)+' · '+money(data.limitPrice) + ' · ' + shares(data.requestedQuantity) + ' · ' + sourceLabel(data.source);
    const rows = [['체결 가능 수량', shares(single.filledQuantity), shares(sor.filledQuantity)],
      ['미체결 수량', shares(single.remainingQuantity), shares(sor.remainingQuantity)],
      ['체결 가능 비율', num(single.fillRate) + '%', num(sor.fillRate) + '%'],
      ['예상 평균 체결가', money(single.averagePrice), money(sor.averagePrice)], [sell?'예상 매도대금':'예상 매수대금', money(single.totalAmount), money(sor.totalAmount)]];
    $('comparison-table').replaceChildren(...rows.map(values => {
      const row = el('tr'); values.forEach((value, i) => cell(row, value, i === 2 ? 'sor-column' : '')); return row;
    }));
    $('baseline-fill-label').textContent = shares(single.filledQuantity) + ' / ' + shares(data.requestedQuantity);
    $('sor-fill-label').textContent = shares(sor.filledQuantity) + ' / ' + shares(data.requestedQuantity);
    allocationBar('baseline-bar', single, data.requestedQuantity); allocationBar('sor-bar', sor, data.requestedQuantity);
    $('analysis-allocation-details').replaceChildren(...(sor.allocations.length ? sor.allocations.map((item,index) =>
      el('p', '', (index+1)+'. 시장 ' + item.market + ' · ' + levelOf(item) + '호가 · ' + money(item.price) + ' × ' + shares(item.quantity) + ' = ' + money(item.amount)))
      : [el('p', '', 'SOR로 체결할 수 있는 물량이 없어요.')]));
    let explanation = common.quantity ? '같은 ' + shares(common.quantity) + '를 각각 주문하면 '+(sell?'예상 매도대금 증가액은 ':'예상 매수대금 절감액은 ') + money(common.savings) + '이에요.'
      : '양쪽 모두 체결할 수 있는 공통 수량이 없어 체결대금을 비교할 수 없어요.';
    if (data.additionalFilledQuantity > 0) explanation += ' SOR로 ' + shares(data.additionalFilledQuantity) + '를 더 체결할 수 있어요.';
    if (single.filledQuantity !== sor.filledQuantity) explanation += ' 전체 체결대금은 서로 다른 체결 수량의 금액이에요. 금액 차이는 같은 수량을 별도로 계산했어요.';
    $('analysis-explanation').textContent = explanation;
    $('analysis-quotes').replaceChildren(...data.quotes.map(q => {
      const row = el('tr'); cell(row, '시장 ' + q.market + ' · ' + levelOf(q) + '호가'); cell(row, money(priceOf(q,data.side))); cell(row, shares(quantityOf(q,data.side))); return row;
    }));
  }
  function navigate() {
    const requested = location.hash.slice(1), view = Object.hasOwn(views, requested) ? requested : 'dashboard';
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
  $('toggle-auto').addEventListener('click', () => run(() => changeAuto(!(state.auto?.running || state.autoUncertain))));
  $('detail-stop-auto').addEventListener('click', () => run(() => changeAuto(false)));
  $('verification-link').addEventListener('click', event => {
    if (state.auto?.running || state.autoUncertain) {
      event.preventDefault(); notify('안정성 검증 전에는 자동시장을 정지해 주세요.', 'warning');
    }
  });
  $('refresh').addEventListener('click', () => run(async () => { await refresh(); notify('현재 계좌·호가·주문내역을 불러왔어요.'); }));
  $('order-form').addEventListener('submit', event => { event.preventDefault(); run(buy); });
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
    const maximum=sell?state.account.availableQuantity:Math.min(intMax,Math.floor(state.account.availableCash/price));
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
  document.addEventListener('click', event => {
    const detail = event.target.closest('[data-detail]'), cancellation = event.target.closest('[data-cancel]');
    if (detail) run(() => showDetail(Number(detail.dataset.detail)));
    else if (cancellation && !cancellation.disabled) run(() => cancel(Number(cancellation.dataset.cancel)));
  });
  $('close-detail').addEventListener('click', closeDetail); $('close-detail-bottom').addEventListener('click', closeDetail);
  $('detail-cancel').addEventListener('click', () => { if (state.selectedOrder) run(() => cancel(state.selectedOrder.orderId)); });
  $('market-form').addEventListener('submit', event => { event.preventDefault(); run(applyMarket); });
  document.querySelectorAll('#market-form input').forEach(input => input.addEventListener('input', () => { state.marketDirty = true; marketDraftNote(); }));
  $('load-market').addEventListener('click', () => run(async () => {
    await refresh(); fillMarket(state.book.quotes, state.book.revision); notify('현재 호가를 입력칸에 불러왔어요.');
  }));
  $('example-market').addEventListener('click', () => {
    if (!state.book) return;
    fillMarket(['A','B'].flatMap(market=>[1,2,3].map(level=>({market,level,askPrice:market==='A'?70000:69900,askQuantity:level===1?(market==='A'?10:4):0,bidPrice:market==='A'?69800:69850,bidQuantity:level===1?(market==='A'?10:4):0}))),state.book.revision,true);
    notify('기본 예시를 입력칸에 채웠어요. 호가 반영 버튼을 눌러야 시장이 변경돼요.');
  });
  $('load-events').addEventListener('click', () => run(loadEvents));
  $('depth-example-market').addEventListener('click', () => {
    if (!state.book) return;
    fillMarket(depthExample(),state.book.revision,true);
    notify('3단계 예시를 채웠어요. 반영하면 기존 대기 주문도 검사해요. 매수 1,040원 또는 매도 930원 · 10주로 배분을 확인할 수 있어요.');
  });
  function analysisDefaults() {
    const sell=$('analysis-side').value==='SELL',source=$('analysis-source').value;
    if(source==='DEPTH_EXAMPLE'){$('analysis-price').value=sell?930:1040;$('analysis-quantity').value=10;}
    if(source==='EXAMPLE'){$('analysis-price').value=sell?69800:70000;$('analysis-quantity').value=10;}
    $('analysis-price-label').textContent=(sell?'매도':'매수')+' 지정가(원)';
  }
  $('analysis-source').addEventListener('change',analysisDefaults);
  $('analysis-side').addEventListener('change',analysisDefaults);

  $('analysis-form').addEventListener('submit', event => { event.preventDefault(); run(compare); });
  document.querySelectorAll('#analysis-form input, #analysis-form select').forEach(input => input.addEventListener('input', () => {
    state.comparisonStale = true;
    $('analysis-caption').textContent = state.comparison ? '비교 조건이 바뀌었어요. 아래는 이전 결과이며, 결과 비교하기로 다시 계산할 수 있어요.'
      : '비교할 조건을 입력하고 결과 비교하기를 눌러 주세요.';
  }));
  window.addEventListener('hashchange', navigate);
  window.addEventListener('storage', event => { if (event.key === verificationKey) controls(); });
  window.addEventListener('focus', controls);
  window.addEventListener('pageshow', event => { startPolling(); if (event.persisted) { restorePending(); run(refresh); } });
  window.addEventListener('pagehide', () => { clearInterval(pollTimer); pollTimer = null; });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) poll(); });

  restorePending(); renderAll();
  // Select the initial page without triggering a second concurrent initialization.
  state.busy = true; navigate(); state.busy = false;
  run(async () => {
    await refresh();
    if (state.view === 'analysis') await compare();
    if (state.view === 'markets') await loadEvents();
  });
  startPolling();
})();
