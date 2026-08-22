/* ============================================================
   관리 콘솔 — 역할 게이팅 탭 (사용자 승인 / 공장 / 구역 / 장치)
   window.Auth(공유 인증) 사용. 프레임워크 없음.
   ============================================================ */
(function () {
  'use strict';

  if (!Auth.requireLogin()) return;

  /* ── 공통 유틸 ── */
  const $ = (sel, root = document) => root.querySelector(sel);
  const el = (tag, cls, html) => {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  };

  function escapeHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  let toastTimer = null;
  function toast(msg, isErr) {
    const t = el('div', 'toast' + (isErr ? ' err' : ''));
    t.textContent = msg || (isErr ? '오류가 발생했습니다.' : '완료되었습니다.');
    document.body.appendChild(t);
    requestAnimationFrame(() => t.classList.add('show'));
    setTimeout(() => {
      t.classList.remove('show');
      setTimeout(() => t.remove(), 250);
    }, 2500);
    if (toastTimer) clearTimeout(toastTimer);
  }

  /* apiFetch 래퍼: 성공 시 파싱된 body, 실패 시 throw(메시지 포함) */
  async function readMsg(res, fallback) {
    try {
      const j = await res.json();
      return j && j.message ? j.message : fallback;
    } catch { return fallback; }
  }
  async function apiGet(url) {
    const res = await Auth.apiFetch(url);
    if (!res) throw new Error('세션이 만료되었습니다.');
    if (!res.ok) throw new Error(await readMsg(res, '데이터를 불러오지 못했습니다.'));
    try { return await res.json(); } catch { return null; }
  }
  /* mutation: 성공 메시지 반환. 실패 시 throw */
  async function apiMutate(url, method, body, okFallback) {
    if (READ_ONLY) throw new Error('열람 전용 계정은 설정을 변경할 수 없습니다.');
    const opts = { method };
    if (body !== undefined) opts.body = JSON.stringify(body);
    const res = await Auth.apiFetch(url, opts);
    if (!res) throw new Error('세션이 만료되었습니다.');
    if (!res.ok) throw new Error(await readMsg(res, '요청을 처리하지 못했습니다.'));
    return await readMsg(res, okFallback);
  }

  function roleTag(role) {
    const rl = Auth.ROLE_LABEL[role] || { text: role || '-', cls: '' };
    return `<span class="tag ${rl.cls}">${escapeHtml(rl.text)}</span>`;
  }

  /* ── 상단바 신원 + 시계 ── */
  (function initTopbar() {
    const role = Auth.getRole();
    const rl = Auth.ROLE_LABEL[role] || { text: role, cls: '' };
    $('#navEmployee').textContent = Auth.getEmployeeId() || '';
    const rt = $('#navRole');
    rt.textContent = rl.text;
    rt.className = 'tag ' + rl.cls;

    const clockEl = $('#clock');
    const tick = () => { clockEl.textContent = new Date().toLocaleTimeString('ko-KR', { hour12: false }); };
    tick();
    setInterval(tick, 1000);
  })();

  /* ── 탭 정의 (역할 게이팅) ── */
  const role = Auth.getRole();
  const READ_ONLY = role === 'SYSTEM_VIEWER' || role === 'VIEWER';
  const TABS = [
    { key: 'users',    label: '사용자',       roles: ['SYSTEM_ADMIN', 'SYSTEM_VIEWER', 'FACTORY_ADMIN'], render: renderUsers },
    { key: 'factories',label: '공장',         roles: ['SYSTEM_ADMIN', 'SYSTEM_VIEWER'], render: renderFactories },
    { key: 'calendars',label: '운영 캘린더',  roles: ['SYSTEM_ADMIN', 'SYSTEM_VIEWER', 'FACTORY_ADMIN'], render: renderCalendars },
    { key: 'zones',    label: '구역',         roles: ['SYSTEM_ADMIN', 'SYSTEM_VIEWER', 'FACTORY_ADMIN'], render: renderZones },
    { key: 'devices',  label: '장치',         roles: ['SYSTEM_ADMIN', 'SYSTEM_VIEWER', 'FACTORY_ADMIN', 'MEMBER'], render: renderDevices },
  ];

  const allowed = TABS.filter(t => t.roles.includes(role));

  const panel = $('#tabPanel');
  const tabbar = $('#tabbar');
  let activeTabKey = null;
  let canLeaveTab = null;

  if (allowed.length === 0) {
    $('#noAccess').classList.remove('hidden');
  } else {
    tabbar.classList.remove('hidden');
    allowed.forEach((t, i) => {
      const btn = el('button', 'tab' + (i === 0 ? ' active' : ''));
      btn.textContent = t.label;
      btn.addEventListener('click', () => activate(t.key));
      btn.dataset.key = t.key;
      tabbar.appendChild(btn);
    });
    activate(allowed[0].key);
  }

  function activate(key) {
    if (activeTabKey && activeTabKey !== key && canLeaveTab && !canLeaveTab()) return;
    Array.from(tabbar.children).forEach(b => b.classList.toggle('active', b.dataset.key === key));
    const tab = allowed.find(t => t.key === key);
    if (!tab) return;
    activeTabKey = key;
    canLeaveTab = null;
    panel.innerHTML = '';
    tab.render(panel);
  }

  /* 리스트 컨테이너에 로딩/빈/에러 표시 헬퍼 */
  function setLoading(node) { node.innerHTML = '<div class="empty">불러오는 중…</div>'; }
  function setEmpty(node, msg) { node.innerHTML = `<div class="empty">${escapeHtml(msg || '표시할 항목이 없습니다.')}</div>`; }
  function setError(node, msg) { node.innerHTML = `<div class="empty">${escapeHtml(msg)}</div>`; }

  /* ==========================================================
     운영 캘린더 — 공장 단위 aggregate 전체 교체
     ========================================================== */
  function renderCalendars(root) {
    const DAYS = [
      ['MONDAY', '월요일'], ['TUESDAY', '화요일'], ['WEDNESDAY', '수요일'],
      ['THURSDAY', '목요일'], ['FRIDAY', '금요일'], ['SATURDAY', '토요일'], ['SUNDAY', '일요일'],
    ];
    let summaries = [];
    let draft = null;
    let selectedFactoryId = null;
    let dirty = false;
    let saving = false;

    root.innerHTML = `<div class="panel panel-pad">
      <div class="flex between items-center gap" style="margin-bottom:1.2rem; flex-wrap:wrap;">
        <div><div class="eyebrow">FACTORY · OPERATING CALENDAR</div><h3 style="margin-top:.35rem;">운영 캘린더</h3></div>
        <div id="calendarFactoryControl" style="min-width:min(320px,100%);"></div>
      </div>
      <div id="calendarEditor"><div class="empty">불러오는 중…</div></div>
    </div>`;
    const editor = $('#calendarEditor', root);
    const factoryControl = $('#calendarFactoryControl', root);

    const guard = () => {
      if (!dirty) return true;
      if (!confirm('저장하지 않은 운영 캘린더 변경이 있습니다. 이동할까요?')) return false;
      dirty = false;
      return true;
    };
    canLeaveTab = guard;
    const beforeUnload = (event) => {
      if (!dirty) return;
      event.preventDefault(); event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);

    function markDirty() { dirty = true; renderStatus(); }

    function renderStatus(message, kind) {
      const node = $('#calendarMessage', root);
      if (!node) return;
      node.textContent = message || (READ_ONLY ? '열람 전용' : (dirty ? '저장하지 않은 변경이 있습니다.' : '저장된 일정입니다.'));
      node.className = `calendar-message${kind ? ` ${kind}` : ''}`;
    }

    function intervalRows(intervals, context, index) {
      if (!intervals.length) return '<span class="dim" style="font-size:.82rem;">운영 구간 없음</span>';
      return intervals.map((interval, intervalIndex) => `<div class="calendar-interval">
        <input class="input mono" data-action="time" data-context="${context}" data-index="${index}" data-interval="${intervalIndex}" data-field="start" value="${escapeHtml(interval.start)}" aria-label="시작 시간"/>
        <span class="dim">–</span>
        <input class="input mono" data-action="time" data-context="${context}" data-index="${index}" data-interval="${intervalIndex}" data-field="end" value="${escapeHtml(interval.end)}" aria-label="종료 시간"/>
        <button class="btn btn-sm btn-danger" type="button" data-action="remove-interval" data-context="${context}" data-index="${index}" data-interval="${intervalIndex}">삭제</button>
      </div>`).join('');
    }

    function renderEditor() {
      if (!draft) { setEmpty(editor, '편집할 공장이 없습니다.'); return; }
      const weeklyByDay = new Map(DAYS.map(([key]) => [key, []]));
      draft.weeklyIntervals.forEach((interval) => weeklyByDay.get(interval.dayOfWeek)?.push(interval));
      editor.innerHTML = `<fieldset id="calendarFieldset" ${saving || READ_ONLY ? 'disabled' : ''} style="border:0; min-width:0;">
        <div class="calendar-settings">
          <div class="field"><label for="calendarTimezone">IANA timezone</label>
            <input class="input mono" id="calendarTimezone" list="timezoneOptions" value="${escapeHtml(draft.timezone)}"/>
            <datalist id="timezoneOptions"><option value="Asia/Seoul"><option value="UTC"><option value="America/New_York"><option value="Europe/Berlin"></datalist>
          </div>
          <div class="field"><label for="calendarGrace">재개 유예 (초)</label><input class="input mono" id="calendarGrace" type="number" min="0" max="86400" value="${draft.resumeGraceSeconds}"/></div>
        </div>
        <div class="detail-section"><div class="detail-head"><div><h3>주간 운영시간</h3><div class="hint">[시작, 종료), 24:00 허용 · 야간 교대는 날짜별 두 구간으로 분할</div></div></div>
          ${DAYS.map(([key, label], dayIndex) => `<div class="calendar-day"><strong>${label}</strong><div class="calendar-intervals">${intervalRows(weeklyByDay.get(key), 'weekly', dayIndex)}</div><button class="btn btn-sm" type="button" data-action="add-weekly" data-day="${key}">구간 추가</button></div>`).join('')}
        </div>
        <div class="detail-section"><div class="detail-head"><div><h3>날짜 예외</h3><div class="hint">휴무는 종일 닫고, 특근은 해당 날짜 주간표를 완전히 대체합니다.</div></div><button class="btn btn-sm" type="button" data-action="add-override">예외 추가</button></div>
          <div class="calendar-overrides">${draft.dateOverrides.length ? draft.dateOverrides.map((override, index) => `<div class="calendar-override">
            <div class="form-grid"><div class="field"><label>날짜</label><input class="input" type="date" data-action="override-field" data-index="${index}" data-field="date" value="${escapeHtml(override.date)}"/></div>
            <div class="field"><label>종류</label><select class="input" data-action="override-field" data-index="${index}" data-field="kind"><option value="CLOSED" ${override.kind === 'CLOSED' ? 'selected' : ''}>휴무</option><option value="OPEN" ${override.kind === 'OPEN' ? 'selected' : ''}>특근</option></select></div></div>
            ${override.kind === 'OPEN' ? `<div class="calendar-intervals">${intervalRows(override.intervals, 'override', index)}<button class="btn btn-sm" type="button" data-action="add-override-interval" data-index="${index}">구간 추가</button></div>` : ''}
            <div style="margin-top:.6rem;"><button class="btn btn-sm btn-danger" type="button" data-action="remove-override" data-index="${index}">예외 삭제</button></div>
          </div>`).join('') : '<div class="empty">등록된 날짜 예외가 없습니다.</div>'}</div>
        </div>
        <div class="detail-section flex between items-center gap" style="flex-wrap:wrap;"><div><span class="tag">revision ${draft.revision}</span><div id="calendarMessage" class="calendar-message"></div></div><div class="flex gap-sm"><button class="btn" type="button" data-action="reload">새로고침</button><button class="btn btn-primary" type="button" data-action="save">저장</button></div></div>
      </fieldset>`;
      renderStatus();
    }

    async function loadDetail(factoryId, skipGuard) {
      if (!skipGuard && !guard()) {
        renderFactoryControl(); return;
      }
      selectedFactoryId = String(factoryId);
      setLoading(editor);
      try {
        draft = await apiGet(`/admin/factory-calendars/${encodeURIComponent(factoryId)}`);
        dirty = false;
        renderFactoryControl(); renderEditor();
      } catch (error) { setError(editor, error.message); }
    }

    function renderFactoryControl() {
      if (role === 'SYSTEM_ADMIN' || role === 'SYSTEM_VIEWER') {
        factoryControl.innerHTML = `<label class="hint" for="calendarFactory">공장</label><select class="input" id="calendarFactory">${summaries.map((summary) => `<option value="${summary.factoryId}" ${String(summary.factoryId) === String(selectedFactoryId) ? 'selected' : ''}>${escapeHtml(summary.factoryName)}</option>`).join('')}</select>`;
        $('#calendarFactory', root)?.addEventListener('change', (event) => loadDetail(event.target.value, false));
      } else {
        const summary = summaries[0];
        factoryControl.innerHTML = summary ? `<div class="hint">내 공장</div><strong>${escapeHtml(summary.factoryName)}</strong>` : '';
      }
    }

    function payload() {
      return {
        timezone: String(draft.timezone || '').trim(),
        resumeGraceSeconds: Number(draft.resumeGraceSeconds),
        revision: Number(draft.revision),
        weeklyIntervals: draft.weeklyIntervals,
        dateOverrides: draft.dateOverrides,
      };
    }

    async function save() {
      if (READ_ONLY) return;
      const request = payload();
      const validation = CalendarValidation.validate(request);
      if (!validation.valid) { renderStatus(validation.errors.join('\n'), 'error'); return; }
      saving = true; renderEditor();
      try {
        const res = await Auth.apiFetch(`/admin/factory-calendars/${encodeURIComponent(selectedFactoryId)}`, { method: 'PUT', body: JSON.stringify(request) });
        if (!res) throw new Error('세션이 만료되었습니다.');
        if (res.status === 409) {
          saving = false; renderEditor(); renderStatus('다른 관리자가 먼저 수정했습니다. 새로고침 후 변경을 다시 적용해 주세요.', 'conflict'); return;
        }
        if (!res.ok) throw new Error(await readMsg(res, '운영 캘린더를 저장하지 못했습니다.'));
        draft = await res.json(); dirty = false; saving = false; renderEditor(); toast('운영 캘린더를 저장했습니다.');
      } catch (error) { saving = false; renderEditor(); renderStatus(error.message, 'error'); }
    }

    editor.addEventListener('input', (event) => {
      if (READ_ONLY) return;
      if (!draft) return;
      if (event.target.id === 'calendarTimezone') draft.timezone = event.target.value;
      else if (event.target.id === 'calendarGrace') draft.resumeGraceSeconds = Number(event.target.value);
      else if (event.target.dataset.action === 'time') {
        const target = event.target.dataset.context === 'weekly'
          ? draft.weeklyIntervals.filter((item) => item.dayOfWeek === DAYS[Number(event.target.dataset.index)][0])[Number(event.target.dataset.interval)]
          : draft.dateOverrides[Number(event.target.dataset.index)].intervals[Number(event.target.dataset.interval)];
        if (target) target[event.target.dataset.field] = event.target.value;
      } else return;
      markDirty();
    });
    editor.addEventListener('change', (event) => {
      if (READ_ONLY) return;
      if (event.target.dataset.action !== 'override-field') return;
      const override = draft.dateOverrides[Number(event.target.dataset.index)];
      override[event.target.dataset.field] = event.target.value;
      if (event.target.dataset.field === 'kind') override.intervals = event.target.value === 'OPEN' ? [{ start: '08:00', end: '18:00' }] : [];
      markDirty(); renderEditor();
    });
    editor.addEventListener('click', (event) => {
      if (READ_ONLY) return;
      const button = event.target.closest('[data-action]'); if (!button || !draft) return;
      const action = button.dataset.action;
      if (action === 'save') { save(); return; }
      if (action === 'reload') { loadDetail(selectedFactoryId, false); return; }
      if (action === 'add-weekly') draft.weeklyIntervals.push({ dayOfWeek: button.dataset.day, start: '08:00', end: '18:00' });
      if (action === 'add-override') draft.dateOverrides.push({ date: new Date().toISOString().slice(0, 10), kind: 'CLOSED', intervals: [] });
      if (action === 'remove-override') draft.dateOverrides.splice(Number(button.dataset.index), 1);
      if (action === 'add-override-interval') draft.dateOverrides[Number(button.dataset.index)].intervals.push({ start: '08:00', end: '18:00' });
      if (action === 'remove-interval') {
        if (button.dataset.context === 'override') draft.dateOverrides[Number(button.dataset.index)].intervals.splice(Number(button.dataset.interval), 1);
        else {
          const day = DAYS[Number(button.dataset.index)][0];
          const match = draft.weeklyIntervals.filter((item) => item.dayOfWeek === day)[Number(button.dataset.interval)];
          draft.weeklyIntervals.splice(draft.weeklyIntervals.indexOf(match), 1);
        }
      }
      if (!['add-weekly', 'add-override', 'remove-override', 'add-override-interval', 'remove-interval'].includes(action)) return;
      markDirty(); renderEditor();
    });

    (async () => {
      try {
        summaries = await apiGet('/admin/factory-calendars') || [];
        if (!summaries.length) { renderFactoryControl(); setEmpty(editor, '접근 가능한 공장이 없습니다.'); return; }
        await loadDetail(summaries[0].factoryId, true);
      } catch (error) { setError(editor, error.message); }
    })();
  }

  /* ==========================================================
     탭 1) 사용자 승인
     ========================================================== */
  function renderUsers(root) {
    root.innerHTML = `
      <div class="panel">
        <div class="panel-head">
          <div class="flex items-center gap-sm"><span class="lamp ok"></span><h3>사용자</h3></div>
          <label class="flex items-center gap-sm" style="font-size:.82rem; color:var(--text-dim); cursor:pointer;">
            <input type="checkbox" id="pendingOnly"/> 대기만 보기
          </label>
        </div>
        <div class="table-wrap" id="usersList"><div class="empty">불러오는 중…</div></div>
      </div>`;
    const listNode = $('#usersList', root);
    const pendingOnly = $('#pendingOnly', root);

    async function load() {
      setLoading(listNode);
      try {
        const url = pendingOnly.checked ? '/admin/users/pending' : '/admin/users';
        const users = await apiGet(url);
        if (!users || users.length === 0) { setEmpty(listNode, '해당하는 사용자가 없습니다.'); return; }
        renderTable(users);
      } catch (e) { setError(listNode, e.message); }
    }

    const STATUS_TAG = {
      PENDING: 'tag-brand', ACTIVE: 'tag-signal', REJECTED: 'tag-alarm',
    };

    function renderTable(users) {
      const rows = users.map(u => {
        const statusCls = STATUS_TAG[u.status] || '';
        const actions = !READ_ONLY && u.status === 'PENDING'
          ? `<div class="actions-cell">
               <button class="btn btn-sm btn-primary" data-act="approve" data-id="${u.id}">승인</button>
               <button class="btn btn-sm btn-danger" data-act="reject" data-id="${u.id}">반려</button>
             </div>`
          : '<span class="faint">—</span>';
        return `<tr>
          <td class="num">${escapeHtml(u.employeeId)}</td>
          <td>${escapeHtml(u.name)}</td>
          <td class="dim">${u.factoryName ? escapeHtml(u.factoryName) : '<span class="faint">공장 미지정</span>'}</td>
          <td>${roleTag(u.role)}</td>
          <td><span class="tag ${statusCls}">${escapeHtml(u.status)}</span></td>
          <td>${actions}</td>
        </tr>`;
      }).join('');
      listNode.innerHTML = `<table class="table">
        <thead><tr>
          <th>사번</th><th>이름</th><th>공장</th><th>역할</th><th>상태</th><th>액션</th>
        </tr></thead>
        <tbody>${rows}</tbody></table>`;

      listNode.querySelectorAll('button[data-act]').forEach(btn => {
        const id = btn.dataset.id;
        btn.addEventListener('click', () => {
          if (btn.dataset.act === 'approve') {
            openApproveModal(users.find(u => String(u.id) === String(id)));
          } else {
            reject(id);
          }
        });
      });
    }

    async function reject(id) {
      try {
        const msg = await apiMutate(`/admin/users/${id}/reject`, 'PATCH', undefined, '반려 처리되었습니다.');
        toast(msg);
        load();
      } catch (e) { toast(e.message, true); }
    }

    /* 승인 = 역할·공장 부여 + 선택 공장 구역 배정. 백엔드도 같은 범위를 검증한다. */
    async function openApproveModal(user) {
      if (!user) return;
      const isSystemAdmin = Auth.getRole() === 'SYSTEM_ADMIN';
      const grantable = isSystemAdmin
        ? ['VIEWER', 'MEMBER', 'FACTORY_ADMIN']
        : ['VIEWER', 'MEMBER'];

      let zones = [];
      let factories = [];
      try {
        zones = await apiGet('/admin/zones');
        if (isSystemAdmin) {
          // apiFetch의 401 refresh가 중복되지 않도록 인증 요청은 순차 처리한다.
          factories = await apiGet('/admin/factories');
        }
        zones = Array.isArray(zones) ? zones : [];
        factories = Array.isArray(factories) ? factories : [];
      }
      catch (e) { toast(e.message, true); return; }

      const roleOpts = grantable
        .map(r => `<option value="${r}">${(Auth.ROLE_LABEL[r] || {}).text || r}</option>`).join('');
      const validFactories = factories.filter(factory => FactorySelection.parsePositiveId(factory && factory.id) != null);
      const factoryIds = new Set(validFactories.map(factory => FactorySelection.parsePositiveId(factory.id)));
      const targetFactoryId = FactorySelection.parsePositiveId(user.factoryId);
      const initialFactoryId = isSystemAdmin
        ? (FactorySelection.isAllowedId(targetFactoryId, factoryIds) ? targetFactoryId : null)
        : targetFactoryId;
      const factoryField = isSystemAdmin
        ? `<select class="input" id="apFactory" aria-describedby="apFactoryHint">
             <option value="">공장을 선택해주세요</option>
             ${validFactories.map(factory => `<option value="${FactorySelection.parsePositiveId(factory.id)}"${initialFactoryId === FactorySelection.parsePositiveId(factory.id) ? ' selected' : ''}>${escapeHtml(factory.name)}</option>`).join('')}
           </select>
           <p id="apFactoryHint" class="faint" style="font-size:.72rem; margin-top:.35rem;">가입 신청 공장을 확인하고 필요하면 교정할 수 있습니다.</p>`
        : `<input class="input" id="apFactory" type="text" value="${escapeHtml(user.factoryName || (targetFactoryId == null ? '공장 미지정' : '공장 #' + targetFactoryId))}" readonly/>
           <p class="faint" style="font-size:.72rem; margin-top:.35rem;">공장 관리자는 대상의 소속 공장으로만 승인할 수 있습니다.</p>`;

      const overlay = el('div', 'modal-overlay');
      const box = el('div', 'modal-box');
      box.innerHTML = `
        <h3>승인 — ${escapeHtml(user.name)} <span class="faint mono">${escapeHtml(user.employeeId)}</span></h3>
        <div class="field">
          <label for="apFactory">소속 공장</label>
          ${factoryField}
        </div>
        <div class="field">
          <label for="apRole">부여 역할</label>
          <select class="input" id="apRole">${roleOpts}</select>
        </div>
        <div class="field">
          <label>구역 배정 <span class="faint">(복수 선택 가능)</span></label>
          <div class="zone-picker" id="apZones"></div>
        </div>
        <div id="apError" class="hidden" role="alert" aria-live="assertive" style="color:var(--alarm); font-size:.82rem; margin-top:.8rem;"></div>
        <div class="modal-actions">
          <button class="btn btn-ghost btn-sm" id="apCancel">취소</button>
          <button class="btn btn-primary btn-sm" id="apConfirm">승인 확정</button>
        </div>`;
      overlay.appendChild(box);
      document.body.appendChild(overlay);

      const factoryControl = $('#apFactory', box);
      const roleControl = $('#apRole', box);
      const zonePicker = $('#apZones', box);
      const errorNode = $('#apError', box);
      const cancelButton = $('#apCancel', box);
      const confirmButton = $('#apConfirm', box);
      let submitting = false;

      const selectedFactoryId = () => isSystemAdmin
        ? (FactorySelection.isAllowedId(factoryControl.value, factoryIds)
          ? FactorySelection.parsePositiveId(factoryControl.value) : null)
        : initialFactoryId;

      function showModalError(message) {
        errorNode.textContent = message;
        errorNode.classList.remove('hidden');
      }

      function clearModalError() {
        errorNode.textContent = '';
        errorNode.classList.add('hidden');
      }

      function renderZoneOptions() {
        const factoryId = selectedFactoryId();
        if (factoryId == null) {
          zonePicker.innerHTML = '<div class="faint" style="font-size:.85rem;">공장을 먼저 선택해주세요.</div>';
          return;
        }
        const availableZones = FactorySelection.filterZones(zones, factoryId)
          .filter(zone => FactorySelection.parsePositiveId(zone && zone.id) != null);
        zonePicker.innerHTML = availableZones.length
          ? availableZones.map(zone => `<label><input type="checkbox" value="${FactorySelection.parsePositiveId(zone.id)}"/> ${escapeHtml(zone.name)}</label>`).join('')
          : '<div class="faint" style="font-size:.85rem;">이 공장에는 배정 가능한 구역이 없습니다. 공장과 역할만 부여합니다.</div>';
      }

      function updateConfirmAvailability() {
        confirmButton.disabled = submitting || selectedFactoryId() == null;
      }

      function setSubmitting(next) {
        submitting = next;
        roleControl.disabled = next;
        if (isSystemAdmin) factoryControl.disabled = next;
        zonePicker.querySelectorAll('input').forEach(input => { input.disabled = next; });
        cancelButton.disabled = next;
        updateConfirmAvailability();
      }

      const close = (force = false) => {
        if (submitting && !force) return;
        overlay.remove();
      };
      overlay.addEventListener('click', e => { if (e.target === overlay) close(); });
      cancelButton.addEventListener('click', () => close());
      if (isSystemAdmin) {
        factoryControl.addEventListener('change', () => {
          factoryControl.removeAttribute('aria-invalid');
          clearModalError();
          // 공장 변경 때 이전 체크 DOM 자체를 교체해 교차 공장 선택이 남지 않게 한다.
          renderZoneOptions();
          updateConfirmAvailability();
        });
      }
      confirmButton.addEventListener('click', async () => {
        if (submitting) return;
        clearModalError();
        const factoryId = selectedFactoryId();
        if (factoryId == null) {
          factoryControl.setAttribute('aria-invalid', 'true');
          showModalError('승인할 공장을 선택해주세요.');
          updateConfirmAvailability();
          return;
        }
        const payload = FactorySelection.buildApprovalPayload(
          roleControl.value,
          factoryId,
          Array.from(zonePicker.querySelectorAll('input:checked')).map(input => input.value),
        );
        if (!payload) {
          showModalError('공장과 구역 선택을 다시 확인해주세요.');
          return;
        }

        setSubmitting(true);
        try {
          const msg = await apiMutate(`/admin/users/${user.id}/approve`, 'PATCH', payload, '승인 처리되었습니다.');
          toast(msg);
          close(true);
          load();
        } catch (e) {
          showModalError(e.message);
          setSubmitting(false);
        }
      });

      renderZoneOptions();
      if (!isSystemAdmin && initialFactoryId == null) {
        showModalError('대상 사용자의 소속 공장을 확인할 수 없습니다.');
      } else if (isSystemAdmin && validFactories.length === 0) {
        showModalError('승인에 사용할 공장이 없습니다. 공장을 먼저 등록해주세요.');
      }
      updateConfirmAvailability();
    }

    pendingOnly.addEventListener('change', load);
    load();
  }

  /* ==========================================================
     탭 2) 공장 (SYSTEM_ADMIN)
     ========================================================== */
  function renderFactories(root) {
    root.innerHTML = `
      ${READ_ONLY ? '' : `<div class="panel panel-pad">
        <div class="eyebrow" style="margin-bottom:.9rem;">CREATE · 공장 등록</div>
        <div class="form-grid">
          <div class="field"><label for="fName">이름</label><input id="fName" class="input" type="text" placeholder="예: 1공장"/></div>
          <div class="field"><label for="fDesc">설명</label><input id="fDesc" class="input" type="text" placeholder="설명(선택)"/></div>
          <div class="field"><button id="fCreate" class="btn btn-primary">등록</button></div>
        </div>
      </div>`}
      <div class="panel">
        <div class="panel-head"><div class="flex items-center gap-sm"><span class="lamp ok"></span><h3>공장 목록</h3></div></div>
        <div class="table-wrap" id="facList"><div class="empty">불러오는 중…</div></div>
      </div>`;
    const listNode = $('#facList', root);

    async function load() {
      setLoading(listNode);
      try {
        const items = await apiGet('/admin/factories');
        if (!items || items.length === 0) { setEmpty(listNode, '등록된 공장이 없습니다.'); return; }
        listNode.innerHTML = `<table class="table">
          <thead><tr><th>ID</th><th>이름</th><th>설명</th>${READ_ONLY ? '' : '<th>액션</th>'}</tr></thead>
          <tbody>${items.map(f => `<tr>
            <td class="num dim">${f.id}</td>
            <td>${escapeHtml(f.name)}</td>
            <td class="dim">${escapeHtml(f.description) || '—'}</td>
            ${READ_ONLY ? '' : `<td><div class="actions-cell">
              <button class="btn btn-sm" data-act="edit" data-id="${f.id}"
                data-name="${escapeHtml(f.name)}" data-desc="${escapeHtml(f.description || '')}">수정</button>
              <button class="btn btn-sm btn-danger" data-act="del" data-id="${f.id}"
                data-name="${escapeHtml(f.name)}">삭제</button>
            </div></td>`}
          </tr>`).join('')}</tbody></table>`;
        if (!READ_ONLY) bind();
      } catch (e) { setError(listNode, e.message); }
    }

    function bind() {
      listNode.querySelectorAll('button[data-act="edit"]').forEach(b => b.addEventListener('click', () => {
        const name = prompt('공장 이름', b.dataset.name);
        if (name == null) return;
        if (!name.trim()) { toast('이름은 필수입니다.', true); return; }
        const desc = prompt('설명', b.dataset.desc);
        if (desc == null) return;
        update(b.dataset.id, name.trim(), desc.trim());
      }));
      listNode.querySelectorAll('button[data-act="del"]').forEach(b => b.addEventListener('click', () => {
        if (!confirm(`공장 "${b.dataset.name}"을(를) 삭제할까요?`)) return;
        remove(b.dataset.id);
      }));
    }

    async function create() {
      const name = $('#fName', root).value.trim();
      const desc = $('#fDesc', root).value.trim();
      if (!name) { toast('이름은 필수입니다.', true); return; }
      try {
        const msg = await apiMutate('/admin/factories', 'POST', { name, description: desc }, '공장이 등록되었습니다.');
        toast(msg);
        $('#fName', root).value = ''; $('#fDesc', root).value = '';
        load();
      } catch (e) { toast(e.message, true); }
    }
    async function update(id, name, description) {
      try {
        const msg = await apiMutate(`/admin/factories/${id}`, 'PUT', { name, description }, '수정되었습니다.');
        toast(msg); load();
      } catch (e) { toast(e.message, true); }
    }
    async function remove(id) {
      try {
        const msg = await apiMutate(`/admin/factories/${id}`, 'DELETE', undefined, '삭제되었습니다.');
        toast(msg); load();
      } catch (e) { toast(e.message, true); }
    }

    if (!READ_ONLY) $('#fCreate', root).addEventListener('click', create);
    load();
  }

  /* ==========================================================
     탭 3) 구역 (SYSTEM_ADMIN, FACTORY_ADMIN)
     ========================================================== */
  function renderZones(root) {
    root.innerHTML = `
      ${READ_ONLY ? '' : `<div class="panel panel-pad">
        <div class="eyebrow" style="margin-bottom:.9rem;">CREATE · 구역 등록</div>
        <div class="form-grid">
          <div class="field"><label for="zFactory">공장</label>
            <select id="zFactory" class="input"></select>
          </div>
          <div class="field hidden" id="zFactoryIdWrap"><label for="zFactoryId">공장 ID</label>
            <input id="zFactoryId" class="input" type="number" placeholder="공장 ID"/>
          </div>
          <div class="field"><label for="zName">이름</label><input id="zName" class="input" type="text" placeholder="예: A라인"/></div>
          <div class="field"><label for="zDesc">설명</label><input id="zDesc" class="input" type="text" placeholder="설명(선택)"/></div>
          <div class="field"><button id="zCreate" class="btn btn-primary">등록</button></div>
        </div>
      </div>`}
      <div class="panel">
        <div class="panel-head"><div class="flex items-center gap-sm"><span class="lamp ok"></span><h3>구역 목록</h3></div></div>
        <div class="table-wrap" id="zoneList"><div class="empty">불러오는 중…</div></div>
      </div>`;
    const listNode = $('#zoneList', root);
    const facSelect = $('#zFactory', root);
    const facIdWrap = $('#zFactoryIdWrap', root);
    const facIdInput = $('#zFactoryId', root);
    const isSysAdmin = Auth.hasRole('SYSTEM_ADMIN');

    /* 공장 옵션: SYSTEM_ADMIN은 /admin/factories, FACTORY_ADMIN은 구역 결과에서 distinct 유도 */
    function fillFactoryOptions(list) {
      if (READ_ONLY) return;
      if (list && list.length) {
        facSelect.innerHTML = list.map(f => `<option value="${f.id}">${escapeHtml(f.name)}</option>`).join('');
        facSelect.parentElement.classList.remove('hidden');
        facIdWrap.classList.add('hidden');
      } else {
        // 옵션 없음 → 숫자 입력 폴백
        facSelect.parentElement.classList.add('hidden');
        facIdWrap.classList.remove('hidden');
      }
    }

    async function loadFactoryOptions(zones) {
      if (READ_ONLY) return;
      if (isSysAdmin) {
        try {
          const facs = await apiGet('/admin/factories');
          fillFactoryOptions(facs);
          return;
        } catch { /* 실패 시 구역에서 유도로 폴백 */ }
      }
      // FACTORY_ADMIN(또는 실패): 구역 결과에서 distinct factory 유도
      const seen = new Map();
      (zones || []).forEach(z => { if (z.factoryId != null && !seen.has(z.factoryId)) seen.set(z.factoryId, z.factoryName); });
      fillFactoryOptions(Array.from(seen, ([id, name]) => ({ id, name: name || ('공장 #' + id) })));
    }

    async function load() {
      setLoading(listNode);
      try {
        const zones = await apiGet('/admin/zones');
        await loadFactoryOptions(zones);
        if (!zones || zones.length === 0) { setEmpty(listNode, '등록된 구역이 없습니다.'); return; }
        listNode.innerHTML = `<table class="table">
          <thead><tr><th>공장</th><th>이름</th><th>설명</th>${READ_ONLY ? '' : '<th>액션</th><th>구역 사용자</th>'}</tr></thead>
          <tbody>${zones.map(z => `<tr>
            <td class="dim">${escapeHtml(z.factoryName) || '#' + z.factoryId}</td>
            <td>${escapeHtml(z.name)}</td>
            <td class="dim">${escapeHtml(z.description) || '—'}</td>
            ${READ_ONLY ? '' : `<td><div class="actions-cell">
              <button class="btn btn-sm" data-act="edit" data-id="${z.id}" data-fid="${z.factoryId}"
                data-name="${escapeHtml(z.name)}" data-desc="${escapeHtml(z.description || '')}">수정</button>
              <button class="btn btn-sm btn-danger" data-act="del" data-id="${z.id}" data-name="${escapeHtml(z.name)}">삭제</button>
            </div></td>
            <td><div class="actions-cell">
              <button class="btn btn-sm btn-ghost" data-act="uadd" data-id="${z.id}">사용자 추가</button>
              <button class="btn btn-sm btn-ghost" data-act="udel" data-id="${z.id}">사용자 제거</button>
              <div class="hint" style="flex-basis:100%;">user id 입력</div>
            </div></td>`}
          </tr>`).join('')}</tbody></table>`;
        if (!READ_ONLY) bind();
      } catch (e) { setError(listNode, e.message); }
    }

    function bind() {
      listNode.querySelectorAll('button[data-act="edit"]').forEach(b => b.addEventListener('click', () => {
        const name = prompt('구역 이름', b.dataset.name);
        if (name == null) return;
        if (!name.trim()) { toast('이름은 필수입니다.', true); return; }
        const desc = prompt('설명', b.dataset.desc);
        if (desc == null) return;
        update(b.dataset.id, Number(b.dataset.fid), name.trim(), desc.trim());
      }));
      listNode.querySelectorAll('button[data-act="del"]').forEach(b => b.addEventListener('click', () => {
        if (!confirm(`구역 "${b.dataset.name}"을(를) 삭제할까요?`)) return;
        remove(b.dataset.id);
      }));
      listNode.querySelectorAll('button[data-act="uadd"]').forEach(b => b.addEventListener('click', () => {
        const uid = prompt('추가할 사용자 ID (숫자)');
        if (uid == null) return;
        if (!uid.trim() || isNaN(Number(uid))) { toast('유효한 사용자 ID가 필요합니다.', true); return; }
        userAdd(b.dataset.id, Number(uid));
      }));
      listNode.querySelectorAll('button[data-act="udel"]').forEach(b => b.addEventListener('click', () => {
        const uid = prompt('제거할 사용자 ID (숫자)');
        if (uid == null) return;
        if (!uid.trim() || isNaN(Number(uid))) { toast('유효한 사용자 ID가 필요합니다.', true); return; }
        userDel(b.dataset.id, Number(uid));
      }));
    }

    function currentFactoryId() {
      if (!facIdWrap.classList.contains('hidden')) {
        const v = facIdInput.value.trim();
        return v && !isNaN(Number(v)) ? Number(v) : null;
      }
      const v = facSelect.value;
      return v ? Number(v) : null;
    }

    async function create() {
      const factoryId = currentFactoryId();
      const name = $('#zName', root).value.trim();
      const desc = $('#zDesc', root).value.trim();
      if (factoryId == null) { toast('공장을 선택하거나 공장 ID를 입력하세요.', true); return; }
      if (!name) { toast('이름은 필수입니다.', true); return; }
      try {
        const msg = await apiMutate('/admin/zones', 'POST', { factoryId, name, description: desc }, '구역이 등록되었습니다.');
        toast(msg);
        $('#zName', root).value = ''; $('#zDesc', root).value = '';
        load();
      } catch (e) { toast(e.message, true); }
    }
    async function update(id, factoryId, name, description) {
      try {
        const msg = await apiMutate(`/admin/zones/${id}`, 'PUT', { factoryId, name, description }, '수정되었습니다.');
        toast(msg); load();
      } catch (e) { toast(e.message, true); }
    }
    async function remove(id) {
      try {
        const msg = await apiMutate(`/admin/zones/${id}`, 'DELETE', undefined, '삭제되었습니다.');
        toast(msg); load();
      } catch (e) { toast(e.message, true); }
    }
    async function userAdd(zoneId, userId) {
      try {
        const msg = await apiMutate(`/admin/zones/${zoneId}/users`, 'POST', { userId }, '사용자가 추가되었습니다.');
        toast(msg);
      } catch (e) { toast(e.message, true); }
    }
    async function userDel(zoneId, userId) {
      try {
        const msg = await apiMutate(`/admin/zones/${zoneId}/users/${userId}`, 'DELETE', undefined, '사용자가 제거되었습니다.');
        toast(msg);
      } catch (e) { toast(e.message, true); }
    }

    if (!READ_ONLY) $('#zCreate', root).addEventListener('click', create);
    load();
  }

  /* ==========================================================
     탭 4) 장치 master-detail + child 채널
     ========================================================== */
  const THRESHOLD_DIRECTIONS = ['ABOVE', 'BELOW', 'ABS_ABOVE'];

  function renderDevices(root) {
    root.innerHTML = `
      <div class="panel panel-pad device-toolbar">
        <div class="field">
          <label for="deviceZoneFilter">구역 필터</label>
          <select id="deviceZoneFilter" class="input"><option value="">전체 구역</option></select>
        </div>
        ${READ_ONLY ? '' : '<button id="deviceCreate" class="btn btn-primary">장치 등록</button>'}
      </div>
      <div class="device-master-detail">
        <section class="panel">
          <div class="panel-head"><div class="flex items-center gap-sm"><span class="lamp ok"></span><h3>장치 목록</h3></div></div>
          <div class="table-wrap" id="devList"><div class="empty">불러오는 중…</div></div>
        </section>
        <section class="panel panel-pad" id="deviceDetail">
          <div class="empty">장치를 선택하면 상세 정보가 표시됩니다.</div>
        </section>
      </div>`;
    const listNode = $('#devList', root);
    const detailNode = $('#deviceDetail', root);
    const zoneFilter = $('#deviceZoneFilter', root);
    let devices = [];
    let zones = [];
    let selectedDeviceId = null;
    let channelLoadToken = 0;

    function zoneLabel(zone) {
      const factory = zone.factoryName ? `${zone.factoryName} · ` : '';
      return factory + (zone.name || `구역 #${zone.id}`);
    }

    function openFormModal(title, fieldsHtml, submitLabel, onSubmit) {
      const overlay = el('div', 'modal-overlay');
      const box = el('div', 'modal-box');
      box.innerHTML = `<form>
        <h3>${escapeHtml(title)}</h3>
        <div class="form-grid">${fieldsHtml}</div>
        <div class="modal-actions">
          <button type="button" class="btn btn-ghost btn-sm" data-modal-cancel>취소</button>
          <button type="submit" class="btn btn-primary btn-sm">${escapeHtml(submitLabel)}</button>
        </div>
      </form>`;
      overlay.appendChild(box);
      document.body.appendChild(overlay);
      const close = () => overlay.remove();
      overlay.addEventListener('click', e => { if (e.target === overlay) close(); });
      $('[data-modal-cancel]', box).addEventListener('click', close);
      $('form', box).addEventListener('submit', async e => {
        e.preventDefault();
        const submit = $('button[type="submit"]', box);
        submit.disabled = true;
        try {
          await onSubmit(box);
          close();
        } catch (err) {
          toast(err.message, true);
          submit.disabled = false;
        }
      });
      const first = $('input:not([disabled]), select:not([disabled])', box);
      if (first) first.focus();
      return box;
    }

    function parseOptionalNumber(input, label) {
      const raw = input.value.trim();
      if (raw === '') return null;
      const value = Number(raw);
      if (!Number.isFinite(value)) throw new Error(`${label}은(는) 숫자여야 합니다.`);
      return value;
    }

    function openDeviceModal(device) {
      const creating = !device;
      const defaultZoneId = creating
        ? (zoneFilter.value || (zones.length === 1 ? String(zones[0].id) : ''))
        : String(device.zoneId);
      const zoneOptions = zones.map(z =>
        `<option value="${z.id}" ${defaultZoneId === String(z.id) ? 'selected' : ''}>${escapeHtml(zoneLabel(z))}</option>`
      ).join('');
      const fields = `
        <div class="field"><label for="modalDeviceCode">코드</label>
          <input id="modalDeviceCode" class="input" type="text" value="${escapeHtml(device ? device.code : '')}" ${creating ? '' : 'disabled'} required/></div>
        <div class="field"><label for="modalDeviceName">이름</label>
          <input id="modalDeviceName" class="input" type="text" value="${escapeHtml(device ? device.name : '')}" required/></div>
        <div class="field"><label for="modalDeviceLocation">위치</label>
          <input id="modalDeviceLocation" class="input" type="text" value="${escapeHtml(device ? device.location || '' : '')}"/></div>
        <div class="field"><label for="modalDeviceInterval">기대 주기(초)</label>
          <input id="modalDeviceInterval" class="input" type="number" min="0" value="${device && device.expectedIntervalSeconds != null ? device.expectedIntervalSeconds : ''}"/></div>
        <div class="field"><label for="modalDeviceZone">구역</label>
          <select id="modalDeviceZone" class="input" ${creating ? '' : 'disabled'} required>
            <option value="">구역을 선택하세요</option>${zoneOptions}
          </select></div>`;
      openFormModal(creating ? '장치 등록' : '장치 수정', fields, creating ? '등록' : '저장', async box => {
        const name = $('#modalDeviceName', box).value.trim();
        if (!name) throw new Error('이름은 필수입니다.');
        const body = {
          name,
          location: $('#modalDeviceLocation', box).value.trim(),
          expectedIntervalSeconds: parseOptionalNumber($('#modalDeviceInterval', box), '기대 주기'),
        };
        let msg;
        if (creating) {
          const code = $('#modalDeviceCode', box).value.trim();
          const zoneId = Number($('#modalDeviceZone', box).value);
          if (!code) throw new Error('코드는 필수입니다.');
          if (!zoneId) throw new Error('구역을 선택하세요.');
          msg = await apiMutate('/devices', 'POST', { code, zoneId, ...body }, '장치가 등록되었습니다.');
        } else {
          msg = await apiMutate(`/devices/${device.id}`, 'PUT', body, '장치가 수정되었습니다.');
        }
        toast(msg);
        await load();
      });
    }

    function openChannelModal(channel) {
      const device = devices.find(d => String(d.id) === String(selectedDeviceId));
      if (!device) return;
      const creating = !channel;
      const hasThreshold = channel && channel.thresholdValue != null;
      const directionOptions = '<option value="">없음</option>' + THRESHOLD_DIRECTIONS.map(direction =>
        `<option value="${direction}" ${channel && channel.thresholdDirection === direction ? 'selected' : ''}>${direction}</option>`
      ).join('');
      const fields = `
        <div class="field"><label for="modalChannelCode">코드</label>
          <input id="modalChannelCode" class="input" type="text" value="${escapeHtml(channel ? channel.code : '')}" ${creating ? '' : 'disabled'} required/></div>
        <div class="field"><label for="modalChannelUnit">단위</label>
          <input id="modalChannelUnit" class="input" type="text" value="${escapeHtml(channel ? channel.unit || '' : '')}"/></div>
        <div class="field"><label for="modalChannelKind">측정 종류</label>
          <input id="modalChannelKind" class="input" type="text" value="${escapeHtml(channel ? channel.quantityKind || '' : '')}"/></div>
        <div class="field"><label for="modalChannelThreshold">임계값</label>
          <input id="modalChannelThreshold" class="input" type="number" step="any" value="${channel && channel.thresholdValue != null ? escapeHtml(String(channel.thresholdValue)) : ''}"/></div>
        <div class="field"><label for="modalChannelDirection">방향</label>
          <select id="modalChannelDirection" class="input" ${hasThreshold ? '' : 'disabled'}>${directionOptions}</select></div>`;
      const modalBox = openFormModal(creating ? `${device.name} · 채널 등록` : `${device.name} · 채널 수정`, fields,
        creating ? '등록' : '저장', async box => {
          const thresholdValue = parseOptionalNumber($('#modalChannelThreshold', box), '임계값');
          const thresholdDirection = $('#modalChannelDirection', box).value;
          if (thresholdValue != null && !thresholdDirection) {
            throw new Error('임계값 방향을 선택하세요.');
          }
          const body = {
            unit: $('#modalChannelUnit', box).value.trim(),
            quantityKind: $('#modalChannelKind', box).value.trim(),
            thresholdValue,
            thresholdDirection: thresholdValue == null ? null : thresholdDirection,
          };
          let msg;
          if (creating) {
            const code = $('#modalChannelCode', box).value.trim();
            if (!code) throw new Error('코드는 필수입니다.');
            msg = await apiMutate(`/devices/${device.id}/channels`, 'POST', { code, ...body }, '채널이 등록되었습니다.');
          } else {
            msg = await apiMutate(`/channels/${channel.id}`, 'PUT', body, '채널이 수정되었습니다.');
          }
          toast(msg);
          await loadChannels(device.id);
        });
      const thresholdInput = $('#modalChannelThreshold', modalBox);
      const directionSelect = $('#modalChannelDirection', modalBox);
      const syncDirection = () => {
        const enabled = thresholdInput.value.trim() !== '';
        directionSelect.disabled = !enabled;
        directionSelect.options[0].disabled = enabled;
        if (!enabled) directionSelect.value = '';
        else if (!directionSelect.value) directionSelect.value = 'ABOVE';
      };
      thresholdInput.addEventListener('input', syncDirection);
      syncDirection();
    }

    function filteredDevices() {
      const zoneId = zoneFilter.value;
      return zoneId ? devices.filter(d => String(d.zoneId) === zoneId) : devices;
    }

    function renderDeviceList() {
      const visible = filteredDevices();
      if (visible.length === 0) {
        setEmpty(listNode, devices.length ? '선택한 구역에 장치가 없습니다.' : '등록된 장치가 없습니다.');
        return;
      }
      listNode.innerHTML = `<table class="table">
        <thead><tr><th>코드</th><th>이름</th><th>구역</th></tr></thead>
        <tbody>${visible.map(d => `<tr class="device-row ${String(d.id) === String(selectedDeviceId) ? 'selected' : ''}"
          data-device-id="${d.id}" tabindex="0" role="button" aria-selected="${String(d.id) === String(selectedDeviceId)}">
          <td class="mono">${escapeHtml(d.code)}</td>
          <td>${escapeHtml(d.name)}</td>
          <td class="dim">${escapeHtml(d.zoneName) || `#${d.zoneId}`}</td>
        </tr>`).join('')}</tbody></table>`;
      listNode.querySelectorAll('[data-device-id]').forEach(row => {
        const select = () => selectDevice(row.dataset.deviceId);
        row.addEventListener('click', select);
        row.addEventListener('keydown', e => {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); select(); }
        });
      });
    }

    function renderDeviceDetail(device) {
      if (!device) {
        detailNode.innerHTML = '<div class="empty">장치를 선택하면 상세 정보가 표시됩니다.</div>';
        return;
      }
      detailNode.innerHTML = `
        <div class="detail-head">
          <div><div class="eyebrow">DEVICE · ${escapeHtml(device.code)}</div><h3 style="margin-top:.3rem;">${escapeHtml(device.name)}</h3></div>
          ${READ_ONLY ? '' : `<div class="actions-cell">
            <button class="btn btn-sm" data-device-edit>수정</button>
            <button class="btn btn-sm btn-danger" data-device-delete>삭제</button>
          </div>`}
        </div>
        <dl class="detail-summary">
          <div class="detail-item"><dt>구역</dt><dd>${escapeHtml(device.zoneName) || `#${device.zoneId}`}</dd></div>
          <div class="detail-item"><dt>위치</dt><dd>${escapeHtml(device.location) || '—'}</dd></div>
          <div class="detail-item"><dt>기대 주기</dt><dd>${device.expectedIntervalSeconds != null ? `${device.expectedIntervalSeconds}초` : '—'}</dd></div>
          <div class="detail-item"><dt>장치 ID</dt><dd class="mono">${device.id}</dd></div>
        </dl>
        <div class="detail-section">
          <div class="detail-head">
            <div><div class="eyebrow">CHILD · CHANNELS</div><h3 style="margin-top:.3rem;">채널</h3></div>
            ${READ_ONLY ? '' : '<button class="btn btn-sm btn-primary" data-channel-create>채널 등록</button>'}
          </div>
          <div class="table-wrap" data-channel-list><div class="empty">불러오는 중…</div></div>
        </div>`;
      if (!READ_ONLY) {
        $('[data-device-edit]', detailNode).addEventListener('click', () => openDeviceModal(device));
        $('[data-device-delete]', detailNode).addEventListener('click', () => removeDevice(device));
        $('[data-channel-create]', detailNode).addEventListener('click', () => openChannelModal(null));
      }
    }

    async function selectDevice(id) {
      selectedDeviceId = id == null ? null : String(id);
      renderDeviceList();
      const device = devices.find(d => String(d.id) === selectedDeviceId);
      renderDeviceDetail(device);
      if (device) await loadChannels(device.id);
    }

    async function loadChannels(deviceId) {
      const token = ++channelLoadToken;
      const channelList = $('[data-channel-list]', detailNode);
      if (!channelList) return;
      setLoading(channelList);
      try {
        const channels = (await apiGet(`/channels?deviceId=${encodeURIComponent(deviceId)}`)) || [];
        if (token !== channelLoadToken || String(deviceId) !== String(selectedDeviceId)) return;
        if (channels.length === 0) { setEmpty(channelList, '등록된 채널이 없습니다.'); return; }
        channelList.innerHTML = `<table class="table">
          <thead><tr><th>코드</th><th>단위</th><th>측정 종류</th><th>임계값</th><th>방향</th>${READ_ONLY ? '' : '<th>액션</th>'}</tr></thead>
          <tbody>${channels.map(c => `<tr>
            <td class="mono">${escapeHtml(c.code)}</td>
            <td class="dim">${escapeHtml(c.unit) || '—'}</td>
            <td class="dim">${escapeHtml(c.quantityKind) || '—'}</td>
            <td class="num">${c.thresholdValue != null ? escapeHtml(String(c.thresholdValue)) : '—'}</td>
            <td class="dim">${escapeHtml(c.thresholdDirection) || '—'}</td>
            ${READ_ONLY ? '' : `<td><div class="actions-cell"><button class="btn btn-sm" data-channel-id="${c.id}">수정</button></div></td>`}
          </tr>`).join('')}</tbody></table>`;
        if (!READ_ONLY) {
          channelList.querySelectorAll('[data-channel-id]').forEach(button => {
            const channel = channels.find(c => String(c.id) === button.dataset.channelId);
            button.addEventListener('click', () => openChannelModal(channel));
          });
        }
      } catch (e) {
        if (token === channelLoadToken) setError(channelList, e.message);
      }
    }

    async function removeDevice(device) {
      if (!confirm(`장치 "${device.name}"을(를) 삭제할까요?`)) return;
      try {
        const msg = await apiMutate(`/devices/${device.id}`, 'DELETE', undefined, '장치가 삭제되었습니다.');
        toast(msg);
        selectedDeviceId = null;
        await load();
      } catch (e) { toast(e.message, true); }
    }

    function fillZoneFilter() {
      const current = zoneFilter.value;
      zoneFilter.innerHTML = '<option value="">전체 구역</option>' + zones.map(z =>
        `<option value="${z.id}">${escapeHtml(zoneLabel(z))}</option>`
      ).join('');
      if (zones.some(z => String(z.id) === current)) zoneFilter.value = current;
    }

    async function load() {
      setLoading(listNode);
      try {
        [zones, devices] = await Promise.all([apiGet('/zones'), apiGet('/devices')]);
        zones = zones || [];
        devices = devices || [];
        fillZoneFilter();
        const visible = filteredDevices();
        if (!visible.some(d => String(d.id) === String(selectedDeviceId))) {
          selectedDeviceId = visible.length ? String(visible[0].id) : null;
        }
        renderDeviceList();
        const selected = devices.find(d => String(d.id) === String(selectedDeviceId));
        renderDeviceDetail(selected);
        if (selected) await loadChannels(selected.id);
      } catch (e) {
        setError(listNode, e.message);
        detailNode.innerHTML = `<div class="empty">${escapeHtml(e.message)}</div>`;
      }
    }

    zoneFilter.addEventListener('change', async () => {
      const visible = filteredDevices();
      selectedDeviceId = visible.length ? String(visible[0].id) : null;
      renderDeviceList();
      const selected = visible[0];
      renderDeviceDetail(selected);
      if (selected) await loadChannels(selected.id);
    });
    if (!READ_ONLY) {
      $('#deviceCreate', root).addEventListener('click', () => {
        if (zones.length === 0) { toast('장치를 등록할 수 있는 구역이 없습니다.', true); return; }
        openDeviceModal(null);
      });
    }
    load();
  }

})();
