// Renderer: một khối state, chỉ vẽ lại phần thay đổi.
// BACCARAT: bàn chơi / cấu hình / lịch sử / nhật ký.
const api = window.bcr;
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

// Gom lỗi runtime để kiểm tra nhanh khi chạy thử (không ảnh hưởng vận hành).
window.__errors = [];
window.addEventListener('error', (event) => window.__errors.push(String(event.message || event.error)));
window.addEventListener('unhandledrejection', (event) => window.__errors.push(String(event.reason)));

const state = {
  game: 'baccarat',
  baccarat: null,
  logs: { baccarat: [] },
  sub: { baccarat: 'play' },
  tableSearch: '',
  onlyEligible: false,
  huntTick: 0,
};

/* ------------------------------- định dạng ------------------------------- */
const fmtMoney = (value) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—';
  return `${Number(value).toLocaleString('de-DE', { maximumFractionDigits: 2 })} VN2`;
};
const fmtUnits = (value, digits = 0) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—';
  return Number(value).toLocaleString('de-DE', { maximumFractionDigits: digits });
};
const fmtSigned = (value) => {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '—';
  const number = Number(value);
  return `${number > 0 ? '+' : ''}${number.toLocaleString('de-DE', { maximumFractionDigits: 2 })}`;
};
const pct = (value) => `${Number(value || 0).toFixed(2)}%`;
const SIDE_NAME = { B: 'Banker', P: 'Player', T: 'Hoà' };

function toast(message) {
  const box = $('#toast');
  if (!box) return;
  box.textContent = message;
  box.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => box.classList.remove('show'), 2800);
}

/* --------------------------------- gọi API -------------------------------- */
async function call(game, method, params = {}) {
  const reply = await api.call(game, method, params);
  const payloadError = reply && reply.result && reply.result.ok === false
    ? reply.result.error || reply.result.note || 'engine từ chối lệnh'
    : null;
  if (!reply || reply.ok === false || payloadError) {
    const text = (reply && reply.error) || payloadError || 'lỗi không rõ';
    appendLog(game, 'error', `${method}: ${text}`);
    toast(text);
    return null;
  }
  return reply.result;
}

/* ---------------------------------- log ---------------------------------- */
function appendLog(game, level, text) {
  const list = state.logs[game];
  list.push({ at: new Date().toLocaleTimeString('vi-VN', { hour12: false }), level, text });
  if (list.length > 400) list.shift();
  renderLog(game);
}

function renderLog(game) {
  const box = $('#ba-log');
  if (!box) return;
  const list = state.logs[game];
  if (!list.length) {
    box.innerHTML = '<div class="log-line info"><span class="m">Chưa có dòng nào.</span></div>';
    return;
  }
  box.innerHTML = list
    .map((entry) => `<div class="log-line ${entry.level}"><span class="t">${entry.at}</span><span class="m">${escapeHtml(entry.text)}</span></div>`)
    .join('');
  box.scrollTop = box.scrollHeight;
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

// Nút "Mở Chrome" phải luôn bấm lại được: người dùng có thể đóng cửa sổ Chrome
// bất cứ lúc nào, khi đó chỉ đổi nhãn thành "Mở lại Chrome".
const chromeSeen = { baccarat: false };

function syncOpenChromeButton(selector, game, snapshot) {
  const button = $(selector);
  if (!button) return;
  if (snapshot.chrome_connected) chromeSeen[game] = true;
  button.disabled = false;
  button.textContent = (!snapshot.chrome_connected && chromeSeen[game])
    ? 'Mở lại Chrome' : 'Mở Chrome';
  button.title = snapshot.chrome_connected
    ? 'Chrome đang kết nối — nếu bạn đã đóng cửa sổ, bấm để mở lại'
    : (chromeSeen[game] ? 'Chrome đã đóng — bấm để mở lại' : 'Chrome chưa kết nối — bấm để mở');
}

/* ------------------------------- điều hướng ------------------------------- */
function switchGame(game) {
  state.game = game;
  $$('.game').forEach((button) => button.classList.toggle('active', button.dataset.game === game));
  $$('.view').forEach((view) => view.classList.toggle('hidden', view.id !== `view-${game}`));
  switchSub(game, state.sub[game] || 'play');
}

function switchSub(game, sub) {
  state.sub[game] = sub;
  const nav = $(`[data-subtabs="${game}"]`);
  if (nav) {
    $$('button', nav).forEach((button) => button.classList.toggle('active', button.dataset.sub === sub));
  }
  $$(`.subview[data-game="${game}"]`).forEach((view) => view.classList.toggle('hidden', view.dataset.sub !== sub));
}

const CHIP_STATUS = {
  match: ['KHỚP', 'ok'],
  no_bet: ['CHƯA ĐẶT', 'warn'],
  wrong_side: ['SAI CỬA', 'bad'],
  wrong_amount: ['SAI TIỀN', 'bad'],
  extra_bets: ['ĐẶT THÊM', 'bad'],
  ungroupable: ['KHÔNG GHÉP ĐƯỢC', 'bad'],
  unknown: ['CHƯA ĐỌC', 'warn'],
};

function setText(selector, text) {
  const node = $(selector);
  if (node) node.textContent = text;
}

function setValue(selector, value) {
  const node = $(selector);
  if (node && document.activeElement !== node) node.value = value ?? '';
}

function renderRoad(letters) {
  const box = $('#ba-road');
  if (!box) return;
  const text = String(letters || '');
  if (!text) {
    box.innerHTML = '<span class="hint">Chưa có cầu.</span>';
    return;
  }
  box.innerHTML = text
    .split('')
    .slice(-60)
    .map((ch) => `<span class="road-cell ${ch.toLowerCase()}">${ch}</span>`)
    .join('');
}

// Dải thang gấp thếp: bậc đang dùng được tô sáng, mỗi ô ghi số tiền của bậc đó.
function renderLadderStrip(table, plan) {
  const box = $('#ba-plan-strip');
  if (!box) return;
  const ladder = (table.ladder || []).map(Number);
  if (!ladder.length) { box.innerHTML = ''; return; }
  const current = Number(plan.ladder_step || 0);
  box.innerHTML = ladder
    .map((value, index) => {
      const active = index === current && (plan.action === 'bet' || plan.action === 'start');
      const done = index < current;
      const cls = ['ps-cell', done ? 'side-done' : '', active ? 'now' : ''].join(' ').trim();
      return `<div class="${cls}"><b>${fmtUnits(value)}</b><small>bậc ${index + 1}</small></div>`;
    })
    .join('');
}

function renderChecks(plan) {
  const box = $('#ba-checks');
  if (!box) return;
  const checks = plan.checks || [];
  if (!checks.length) { box.innerHTML = ''; return; }
  box.innerHTML = checks
    .map((check) => `<div class="check-line ${check.ok ? 'ok' : 'bad'}">
      <span class="check-mark">${check.ok ? '✓' : '•'}</span>
      <span>${escapeHtml(check.ok ? check.name : check.detail)}</span></div>`)
    .join('');
}

function renderChips(chips, plan) {
  const statusBox = $('#ba-chip-status');
  const detailBox = $('#ba-chip-detail');
  if (!statusBox || !detailBox) return;
  const check = chips && chips.check;
  const live = chips && chips.live;
  if (!check) {
    statusBox.textContent = 'Chưa đọc chip.';
    statusBox.dataset.state = 'warn';
    detailBox.textContent = 'Vào bàn rồi chip sẽ được đọc tự động.';
    return;
  }
  const [label, level] = CHIP_STATUS[check.status] || [check.status, 'warn'];
  statusBox.textContent = `${label} · ${check.note || ''}`;
  statusBox.dataset.state = level;

  const lines = [];
  if (check.suggested_chips && check.suggested_chips.length && plan.side) {
    lines.push(`Cần bấm: ${check.suggested_chips.join(' + ')} (${check.suggested_chips.length} chip) = ${fmtMoney(check.expected)}`);
  } else if (check.expected) {
    lines.push(`Kế hoạch: ${fmtMoney(check.expected)}`);
  }
  if (live && live.ok) {
    const parts = [];
    ['B', 'P', 'T'].forEach((side) => {
      const spot = (live.spots || {})[side] || {};
      if (spot.amount_vnd) {
        const chipText = (spot.chips || []).join(' + ');
        parts.push(`${SIDE_NAME[side]} ${fmtMoney(spot.amount_vnd)}${chipText ? ` [${chipText}]` : ''}`);
      }
    });
    lines.push(`Trên bàn: ${parts.length ? parts.join(' · ') : 'chưa có chip'}`);
    if (live.total !== null && live.total !== undefined) {
      lines.push(`Tổng đặt: ${fmtMoney(live.total)}`);
    }
  } else if (live && live.error) {
    lines.push(`Chưa đọc được chip (${live.error}).`);
  }
  detailBox.innerHTML = lines.map((line) => `<div>${escapeHtml(line)}</div>`).join('');
}

function renderTables(rows) {
  const box = $('#ba-tables');
  const title = $('#ba-table-title');
  if (!box) return;
  const search = state.tableSearch.trim().toLowerCase();
  const list = (rows || []).filter((row) => {
    if (state.onlyEligible && !row.eligible) return false;
    return !search || String(row.label || '').toLowerCase().includes(search);
  });
  if (title) title.textContent = `Bàn đọc được · ${list.length}`;
  if (!list.length) {
    box.innerHTML = '<p class="hint">Không có bàn nào khớp.</p>';
    return;
  }
  box.innerHTML = list
    .map((row) => {
      const tagClass = row.read_error ? 'bad' : row.ready ? 'ok' : row.eligible ? 'warn' : 'muted';
      const counts = row.banker === undefined || row.banker === null
        ? ''
        : ` · B ${row.banker} / P ${row.player}`;
      return `<div class="tbl-row ${row.current ? 'current' : ''}">
        <div class="tbl-main">
          <b>${escapeHtml(row.label || row.key)}</b>
          <small>${row.hands} tay${counts}${row.current ? ' · đang theo dõi' : ''}</small>
        </div>
        <div class="tbl-meta">
          <span class="tag ${tagClass}">${escapeHtml(row.verdict || '')}</span>
          <small>${escapeHtml(row.status || '')}</small>
        </div>
      </div>`;
    })
    .join('');
}

function renderHistory(history, money) {
  const body = $('#ba-history-body');
  const note = $('#ba-history-note');
  if (!body) return;
  const rows = (history || []).slice().reverse();
  if (note) {
    note.textContent = `${money.hands || 0} tay · ${money.wins || 0} thắng / ${money.losses || 0} thua / `
      + `${money.pushes || 0} hoà · lãi/lỗ ${fmtSigned(money.profit)} · sụt vốn lớn nhất ${fmtMoney(money.drawdown)}`;
  }
  if (!rows.length) {
    body.innerHTML = '<tr><td colspan="8" class="empty">Chưa có tay nào được ghi.</td></tr>';
    return;
  }
  body.innerHTML = rows
    .map((row) => {
      const cls = row.outcome === 'win' ? 'win' : row.outcome === 'loss' ? 'loss' : row.outcome === 'not_placed' ? 'warn' : '';
      const result = row.result ? `${row.result}` : '—';
      const bet = row.bet ? SIDE_NAME[row.bet] || row.bet : '—';
      return `<tr class="${cls}">
        <td>${escapeHtml(row.at || '')}</td>
        <td>${escapeHtml(row.table || '')}</td>
        <td>${row.hand || ''}</td>
        <td>${escapeHtml(bet)}</td>
        <td>${escapeHtml(result)}</td>
        <td>${row.stake ? fmtMoney(row.stake) : '—'}</td>
        <td>${row.delta ? fmtSigned(row.delta) : '—'}</td>
        <td>${fmtMoney(row.balance_after)}</td>
      </tr>`;
    })
    .join('');
}

function renderBaLadder(config) {
  const box = $('#ba-ladder-edit');
  if (!box) return;
  const ladder = config.ladder || [];
  const signature = `${config.ladder_mode}|${ladder.join(',')}`;
  if (box.dataset.signature === signature) return;
  box.dataset.signature = signature;
  const readonly = config.ladder_mode === 'multiply' ? 'readonly' : '';
  box.innerHTML = ladder
    .map((value, index) => `<label>Bậc ${index + 1}<input data-ba-ladder="${index}" type="number" min="1" step="10" value="${value}" ${readonly} /></label>`)
    .join('');
}

function renderBaConfig(config) {
  if (!config) return;
  setValue('#ba-capital-input', config.capital);
  setValue('#ba-tp-input', config.take_profit_pct);
  setValue('#ba-min-hands', config.min_hands);
  setValue('#ba-max-hands', config.max_hands);
  setValue('#ba-live-exit', config.live_exit_streak);
  setValue('#ba-tables-target', config.tables_to_play);
  setValue('#ba-ladder-base', config.ladder_base);
  setValue('#ba-flat-stake', config.flat_stake);
  setValue('#ba-start-url', config.start_url);

  $$('input[name="ba-ladder-mode"]').forEach((input) => { input.checked = input.value === config.ladder_mode; });
  syncBaLadderModeControls(config.ladder_mode);

  const flags = {
    '#ba-auto-cycle': config.auto_cycle,
    '#ba-auto-hunt': config.auto_hunt,
    '#ba-auto-recover': config.auto_recover,
    '#ba-favorites-only': config.favorites_only,
    '#ba-human-mouse': config.human_mouse,
    '#ba-auto-place': config.auto_place,
  };
  Object.entries(flags).forEach(([selector, value]) => {
    const node = $(selector);
    if (node) node.checked = !!value;
  });

  const window = config.chrome_window || {};
  const full = $('#ba-fullscreen');
  if (full) full.checked = !!window.fullscreen;
  setValue('#ba-win-w', window.width);
  setValue('#ba-win-h', window.height);
  setValue('#ba-win-scale', window.percent);

  renderBaLadder(config);
}

function syncBaLadderModeControls(mode) {
  const fixed = mode === 'fixed';
  const flatField = $('#ba-flat-stake-field');
  const baseField = $('#ba-ladder-base-field');
  const ladderEdit = $('#ba-ladder-edit');
  const saveButton = $('#ba-save-ladder');
  if (flatField) flatField.hidden = !fixed;
  if (baseField) baseField.hidden = fixed;
  if (ladderEdit) ladderEdit.hidden = fixed;
  if (saveButton) saveButton.textContent = fixed ? 'Lưu mức cược cố định' : 'Lưu phương pháp & thang';
}

function renderBaccarat(snapshot) {
  if (!snapshot) return;
  state.baccarat = snapshot;
  const money = snapshot.money || {};
  const table = snapshot.table || {};
  const plan = snapshot.plan || {};
  const config = snapshot.config || {};

  /* --- KPI --- */
  const capitalBox = $('#ba-capital');
  if (capitalBox) {
    capitalBox.textContent = fmtMoney(money.capital);
    capitalBox.className = 'kpi-value';
  }

  const profitBox = $('#ba-profit');
  if (profitBox) {
    profitBox.textContent = fmtSigned(money.profit);
    profitBox.className = 'kpi-value ' + (money.profit > 0 ? 'green' : money.profit < 0 ? 'red' : '');
  }

  const roiBox = $('#ba-roi');
  if (roiBox) {
    roiBox.textContent = `${money.roi > 0 ? '+' : ''}${pct(money.roi)}`;
    roiBox.className = 'kpi-value ' + (money.roi > 0 ? 'green' : money.roi < 0 ? 'red' : '');
  }
  setText('#ba-roi-note', `sụt vốn ${fmtMoney(money.drawdown)}`);

  setText('#ba-winrate', `${Math.round(money.win_rate || 0)}% / ${Math.round(100 - (money.win_rate || 0))}%`);
  setText('#ba-winrate-note', money.blocked ? String(money.blocked[1] || 'đã chặn') : 'không tính hoà');

  /* --- thanh trạng thái --- */
  const chromeChip = $('#ba-chrome-chip');
  if (chromeChip) {
    chromeChip.textContent = snapshot.chrome_connected ? 'Chrome: đã kết nối' : 'Chrome: chưa kết nối';
    chromeChip.dataset.state = snapshot.chrome_connected ? 'on' : 'off';
  }
  const toolChip = $('#ba-tool-chip');
  if (toolChip) {
    toolChip.textContent = snapshot.tool_running ? 'Tool: đang chạy' : 'Tool: đang dừng';
    toolChip.dataset.state = snapshot.tool_running ? 'on' : 'warn';
  }
  const toolButton = $('#ba-tool');
  if (toolButton) {
    toolButton.textContent = snapshot.tool_running ? 'Dừng tool' : 'Chạy tool';
    toolButton.classList.toggle('danger-btn', !!snapshot.tool_running);
  }
  if (snapshot.chrome_connected) chromeSeen.baccarat = true;
  syncOpenChromeButton('#ba-open-chrome', 'baccarat', snapshot);
  const led = $('[data-led="baccarat"]');
  if (led) led.classList.toggle('on', !!snapshot.tool_running || !!snapshot.chrome_connected);

  const enter = snapshot.enter_valid || {};

  /* --- banner trạng thái vào bàn hợp lệ / tự săn bàn --- */
  const hunt = $('#ba-hunt');
  const cycle = snapshot.cycle || {};
  if (hunt) {
    if (enter.active) {
      hunt.classList.remove('hidden');
      state.huntTick += 1;
      const spin = $('#ba-hunt-spin');
      if (spin) spin.textContent = state.huntTick % 2 ? '●' : '○';
      const remain = Math.max(0, Math.round(enter.remain || 0));
      setText('#ba-hunt-text', `${enter.note || 'Đang tìm bàn hợp lệ…'} · còn ${remain}s`);
      const cancel = $('#ba-hunt-cancel');
      if (cancel) cancel.textContent = 'Huỷ vào bàn';
    } else if (cycle.waiting) {
      hunt.classList.remove('hidden');
      state.huntTick += 1;
      const spin = $('#ba-hunt-spin');
      if (spin) spin.textContent = state.huntTick % 2 ? '●' : '○';
      const remain = Math.max(0, Math.round(cycle.retry_in || 0));
      setText('#ba-hunt-text', remain > 0
        ? `Đang tự săn bàn… nghỉ ${remain}s rồi quét lại (đã thử ${cycle.attempts || 0} lượt).`
        : `Đang tự quét tìm bàn đạt chuẩn… (đã thử ${cycle.attempts || 0} lượt)`);
      const cancel = $('#ba-hunt-cancel');
      if (cancel) cancel.textContent = 'Dừng tự quét';
    } else {
      hunt.classList.add('hidden');
    }
  }

  /* --- kế hoạch tay kế tiếp --- */
  const planState = $('#ba-plan-state');
  const planBox = $('#ba-plan');
  const planReason = $('#ba-plan-reason');
  if (planState && planBox) {
    if (!table.key) {
      planState.textContent = 'CHƯA CÓ BÀN';
      planState.className = 'hero-state';
      planBox.textContent = 'Bấm “Chạy tool” để tự tìm bàn hợp lệ.';
    } else if (money.blocked) {
      planState.textContent = 'ĐÃ CHẶN';
      planState.className = 'hero-state stop';
      planBox.textContent = String(money.blocked[1] || 'Phiên đã chạm ngưỡng dừng.');
    } else if (plan.action === 'bet') {
      planState.textContent = plan.armed ? 'ĐÃ CHỐT' : 'ĐẶT';
      planState.className = 'hero-state bet';
      planBox.textContent = `${plan.side_name || SIDE_NAME[plan.side] || '—'} ${fmtMoney(plan.stake)}`
        + (config.ladder_mode === 'fixed'
          ? ' · mức cố định'
          : ` · bậc ${(plan.ladder_step || 0) + 1}/${(table.ladder || []).length}`);
    } else if (plan.action === 'halt') {
      planState.textContent = 'DỪNG';
      planState.className = 'hero-state stop';
      planBox.textContent = plan.reason || 'Đã thua hết thang.';
    } else if (plan.action === 'start') {
      planState.textContent = 'BẮT ĐẦU';
      planState.className = 'hero-state bet';
      planBox.textContent = `Bắt đầu 1-1 · ${plan.side_name || 'Banker'} ${fmtMoney(plan.stake)}`;
    } else {
      planState.textContent = 'CHỜ';
      planState.className = 'hero-state';
      planBox.textContent = table.running ? 'Chờ tay mới để chốt kế hoạch.' : 'Chưa theo dõi bàn.';
    }
    if (planReason) planReason.textContent = plan.reason || '';
  }
  renderLadderStrip(table, plan);
  renderChecks(plan);

  /* --- bàn đang theo dõi --- */
  setText('#ba-table-name', table.key ? `${table.name || table.key} · ${table.hands || 0} tay` : 'Chưa chọn bàn');
  renderRoad(table.letters);
  renderChips(snapshot.chips, plan);
  const place = snapshot.place || {};
  const placeBox = $('#ba-place-status');
  if (placeBox) {
    placeBox.textContent = place.note || '';
    placeBox.dataset.state = place.ok === true ? 'on' : place.ok === false ? 'err' : '';
  }

  renderTables(snapshot.tables || []);
  renderBaConfig(config);
  renderHistory(snapshot.history || [], money);
}

/* ============================== sự kiện chung ============================== */
api.onEngineEvent(({ game, event, data }) => {
  if (event === 'state') {
    renderBaccarat(data);
    return;
  }
  if (event === 'log') {
    appendLog(game, data.level || 'info', data.text || '');
    return;
  }
  if (event === 'ready') {
    appendLog(game, 'info', `engine ${data.game} sẵn sàng · CDP ${data.cdp_port}`);
  }
});

document.addEventListener('click', (event) => {
  const winButton = event.target.closest('[data-win]');
  if (winButton) api.windowControl(winButton.dataset.win);
});

$$('.game').forEach((button) => button.addEventListener('click', () => switchGame(button.dataset.game)));

$$('[data-subtabs]').forEach((nav) => {
  const game = nav.dataset.subtabs;
  $$('button', nav).forEach((button) => button.addEventListener('click', () => switchSub(game, button.dataset.sub)));
});

/* ------------------------------ BACCARAT actions ------------------------------ */
$('#ba-open-chrome').addEventListener('click', async () => {
  const reply = await call('baccarat', 'chrome.open');
  if (reply) toast(reply.note || 'Đang mở Chrome cho Baccarat.');
});

$('#ba-tool').addEventListener('click', async () => {
  const running = state.baccarat && state.baccarat.tool_running;
  const reply = await call('baccarat', running ? 'tool.stop' : 'tool.start');
  if (reply) toast(reply.note || (running ? 'Đã dừng tool.' : 'Tool đang chạy.'));
});

$('#ba-hunt-cancel').addEventListener('click', async () => {
  if (state.baccarat && (state.baccarat.enter_valid || {}).active) {
    const reply = await call('baccarat', 'table.cancelEnter');
    if (reply) toast(reply.note || 'Đã huỷ vào bàn.');
    return;
  }
  await call('baccarat', 'config.set', { auto_hunt: false });
  toast('Đã dừng tự săn bàn.');
});

$('#ba-search').addEventListener('input', (event) => {
  state.tableSearch = event.target.value || '';
  renderTables((state.baccarat && state.baccarat.tables) || []);
});

$('#ba-only-eligible').addEventListener('change', (event) => {
  state.onlyEligible = !!event.target.checked;
  renderTables((state.baccarat && state.baccarat.tables) || []);
});

$('#ba-save-money').addEventListener('click', async () => {
  const reply = await call('baccarat', 'config.set', {
    capital: Number($('#ba-capital-input').value),
    take_profit_pct: Number($('#ba-tp-input').value),
  });
  if (reply) toast(reply.note || 'Đã lưu vốn.');
});

$('#ba-new-session').addEventListener('click', async () => {
  const reply = await call('baccarat', 'session.new', {
    capital: Number($('#ba-capital-input').value),
    take_profit_pct: Number($('#ba-tp-input').value),
  });
  if (reply) toast(reply.note || 'Đã tạo phiên mới.');
});

$('#ba-reset-session').addEventListener('click', async () => {
  const reply = await call('baccarat', 'session.reset');
  if (reply) toast(reply.note || 'Đã reset phiên.');
});

$$('input[name="ba-ladder-mode"]').forEach((input) => input.addEventListener('change', () => {
  syncBaLadderModeControls(input.value);
}));

$('#ba-save-ladder').addEventListener('click', async () => {
  const ladderMode = ($('input[name="ba-ladder-mode"]:checked') || {}).value || 'manual';
  const ladder = $$('#ba-ladder-edit input').map((input) => Number(input.value)).filter((value) => value > 0);
  const params = {
    ladder_mode: ladderMode,
    ladder_base: Number($('#ba-ladder-base').value),
    flat_stake: Number($('#ba-flat-stake').value),
  };
  if (ladderMode === 'manual' && ladder.length) params.ladder = ladder;
  const reply = await call('baccarat', 'config.set', params);
  if (reply) toast(reply.note || 'Đã lưu phương pháp & thang.');
});

$('#ba-save-filter').addEventListener('click', async () => {
  const reply = await call('baccarat', 'config.set', {
    min_hands: Number($('#ba-min-hands').value),
    max_hands: Number($('#ba-max-hands').value),
    live_exit_streak: Number($('#ba-live-exit').value),
  });
  if (reply) toast(reply.note || 'Đã lưu điều kiện vào bàn.');
});

$('#ba-save-options').addEventListener('click', async () => {
  const reply = await call('baccarat', 'config.set', {
    auto_cycle: $('#ba-auto-cycle').checked,
    auto_hunt: $('#ba-auto-hunt').checked,
    auto_recover: $('#ba-auto-recover').checked,
    favorites_only: $('#ba-favorites-only').checked,
    human_mouse: $('#ba-human-mouse').checked,
    auto_place: $('#ba-auto-place').checked,
    tables_to_play: Number($('#ba-tables-target').value),
  });
  if (reply) toast(reply.note || 'Đã lưu tùy chọn.');
});

$('#ba-save-window').addEventListener('click', async () => {
  const reply = await call('baccarat', 'chrome.window', {
    width: Number($('#ba-win-w').value),
    height: Number($('#ba-win-h').value),
    percent: Number($('#ba-win-scale').value),
    fullscreen: $('#ba-fullscreen').checked,
  });
  if (reply) toast(reply.note || 'Đã lưu cửa sổ Chrome.');
});

$('#ba-save-url').addEventListener('click', async () => {
  const reply = await call('baccarat', 'config.set', { start_url: $('#ba-start-url').value.trim() });
  if (reply) toast(reply.note || 'Đã lưu link.');
});

$('#ba-export').addEventListener('click', async () => {
  const snap = state.baccarat;
  const rows = (snap && snap.history) || [];
  if (!rows.length) {
    toast('Chưa có dữ liệu để xuất.');
    return;
  }
  const head = ['time', 'table', 'hand', 'bet', 'result', 'stake', 'delta', 'outcome', 'balance_after'];
  const csv = [head.join(',')]
    .concat(rows.map((row) => head.map((key) => {
      const value = row[key === 'time' ? 'at' : key];
      const text = value === null || value === undefined ? '' : String(value);
      return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
    }).join(',')))
    .join('\n');
  const name = `bcr-baccarat-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')}.csv`;
  const saved = await api.saveText(name, csv);
  if (saved && saved.ok) toast(`Đã lưu: ${saved.path}`);
  else if (saved && saved.error) toast(saved.error);
});

$('#ba-clear-log').addEventListener('click', () => {
  state.logs.baccarat = [];
  renderLog('baccarat');
});

/* ---------------------------------- khởi động ---------------------------------- */
(async function boot() {
  renderLog('baccarat');
  const info = await api.info();
  $('#app-ver').textContent = `v${info.version}`;
  switchGame('baccarat');
  const ba = await call('baccarat', 'state');
  if (ba) renderBaccarat(ba);
})();
