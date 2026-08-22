/* ============================================================
   공유 인증/세션 유틸 — window.Auth
   - 역할 라벨 단일 소스
   - apiFetch: 401 시 /auth/refresh 1회 자동 시도 후 재요청
   ============================================================ */
(function () {
  const TOKEN = 'jwt_token';
  const REFRESH = 'refresh_token';

  const ROLE_LABEL = {
    SYSTEM_ADMIN: { text: 'SYSTEM ADMIN', cls: 'tag-alarm' },
    SYSTEM_VIEWER:{ text: 'SYSTEM VIEWER',cls: 'tag-brand' },
    FACTORY_ADMIN: { text: 'FACTORY ADMIN', cls: 'tag-brand' },
    MEMBER:       { text: 'MEMBER',        cls: 'tag-signal' },
    VIEWER:       { text: 'VIEWER',        cls: '' },
  };

  function decodeJwt(token) {
    try {
      const p = token.split('.')[1];
      return JSON.parse(atob(p.replace(/-/g, '+').replace(/_/g, '/')));
    } catch { return null; }
  }

  const getToken   = () => localStorage.getItem(TOKEN);
  const getPayload = () => { const t = getToken(); return t ? decodeJwt(t) : null; };
  const getRole    = () => (getPayload() || {}).role || null;
  const getEmployeeId = () => (getPayload() || {}).sub || null;
  const isLoggedIn = () => !!getPayload();

  function setTokens(access, refresh) {
    localStorage.setItem(TOKEN, access);
    if (refresh) localStorage.setItem(REFRESH, refresh);
  }
  function clearTokens() {
    localStorage.removeItem(TOKEN);
    localStorage.removeItem(REFRESH);
  }
  function toLogin() { location.href = '/index.html'; }

  async function login(employeeId, password) {
    const res = await fetch('/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ employeeId, password }),
    });
    if (!res.ok) {
      let msg = '로그인에 실패했습니다.';
      try { msg = (await res.json()).message || msg; } catch {}
      throw new Error(msg);
    }
    const { accessToken, refreshToken } = await res.json();
    setTokens(accessToken, refreshToken);
  }

  async function register(request) {
    const res = await fetch('/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
    let body = {};
    try { body = await res.json(); } catch {}
    if (!res.ok) throw new Error(body.message || '가입 요청을 처리하지 못했습니다.');
    return body.message || '가입 신청이 완료됐어요. 관리자 승인 후 로그인 가능해요';
  }

  async function logout() {
    const t = getToken();
    try {
      if (t) await fetch('/auth/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + t } });
    } catch {} finally { clearTokens(); toLogin(); }
  }

  async function tryRefresh() {
    const rt = localStorage.getItem(REFRESH);
    if (!rt) return false;
    try {
      const res = await fetch('/auth/refresh', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: rt }),
      });
      if (!res.ok) return false;
      const { accessToken, refreshToken } = await res.json();
      setTokens(accessToken, refreshToken);
      return true;
    } catch { return false; }
  }

  /* 반환: Response(성공/4xx) | null(토큰없음·refresh실패·네트워크오류).
     403은 Response 그대로 반환하니 호출부가 처리. */
  async function apiFetch(url, opts = {}, _retried = false) {
    const token = getToken();
    if (!token) { toLogin(); return null; }
    let res;
    try {
      res = await fetch(url, {
        ...opts,
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token, ...(opts.headers || {}) },
      });
    } catch { return null; }

    if (res.status === 401 && !_retried) {
      if (await tryRefresh()) return apiFetch(url, opts, true);
      clearTokens(); toLogin(); return null;
    }
    return res;
  }

  /* 페이지 가드: 미로그인→로그인, 역할 불일치→false 반환(호출부가 안내) */
  function requireLogin() {
    if (!isLoggedIn()) { toLogin(); return false; }
    return true;
  }
  function hasRole(...roles) { return roles.includes(getRole()); }

  window.Auth = {
    ROLE_LABEL, decodeJwt, getToken, getPayload, getRole, getEmployeeId,
    isLoggedIn, login, register, logout, apiFetch, refreshAccessToken: tryRefresh,
    requireLogin, hasRole, toLogin,
  };
})();
