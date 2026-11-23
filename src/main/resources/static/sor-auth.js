(() => {
  'use strict';
  let presenter = new URLSearchParams(location.search).get('mode') === 'presenter';

  async function csrfToken(signal) {
    const response = await fetch('/csrf', {cache: 'no-store', signal});
    if (!response.ok) throw new Error('접속 정보를 확인하지 못했어요.');
    return response.json();
  }

  async function request(path, options = {}) {
    const method = (options.method ?? 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
      const csrf = await csrfToken(options.signal);
      const headers = new Headers(options.headers);
      headers.set(csrf.headerName, csrf.token);
      options = {...options, headers};
    }
    const response = await fetch(path, options);
    if (response.status === 401) {
      location.replace(presenter ? '/presenter.html?session' : '/login.html?expired');
      throw new Error('다시 접속해 주세요.');
    }
    return response;
  }

  async function copyCode(input) {
    try {
      await navigator.clipboard.writeText(input.value);
      return '체험 코드를 복사했어요.';
    } catch {
      input.focus();
      input.select();
      return '선택된 체험 코드를 직접 복사해 주세요.';
    }
  }

  function setAccountSession(session) {
    presenter = !session.guest;
  }

  window.sorAuth = {csrfToken, request, copyCode, setAccountSession};
})();
