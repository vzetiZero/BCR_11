/* BCR Android — script tiêm vào MỌI khung, chạy trước khi trang tải (document-start).
 *
 * Thay cho phần CDP của bản desktop:
 *   - Đọc bảng cầu / khay chip / ô cược ngay trong khung chứa bàn.
 *   - Bấm chip + ô cược bằng sự kiện chuột tổng hợp; trả thêm toạ độ tuyệt đối
 *     để native bấm bằng cảm ứng thật khi site chặn sự kiện tổng hợp.
 *
 * Cầu nối: native cài `BcrNative.postMessage(String)` (WebViewCompat
 * addWebMessageListener, cho phép mọi origin nên khung bàn khác origin vẫn nói
 * chuyện được). Mỗi khung tự gửi/nhận JSON; native gửi lệnh ngược lại bằng
 * `replyProxy.postMessage(json)`, khung nhận qua sự kiện `message`.
 *
 * Chỉ đọc DOM của chính trang đang mở. Không đọc cookie/token, không gửi dữ liệu
 * ra ngoài thiết bị.
 */
(function () {
  'use strict';
  if (window.__BCR_INSTALLED__) return;
  window.__BCR_INSTALLED__ = true;

  var FRAME_ID = 'f' + Math.random().toString(36).slice(2, 10) + '_' + (Date.now() % 100000);
  var MIN_TEXT_GROUP = 6;
  var MIN_SVG_GROUP = 12;
  var SPECIAL_IDS = [
    'LightningBac0001', 'XXXtremeLB000001', 'NoCommBac0000001',
    'gwbaccarat000001', 'Empgwbaccarat001', 'PTBaccarat000001', 'InsuranceBac0001'
  ];
  var outbox = [];

  function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

  /* ============================== nguồn kết quả ==============================
   *
   * Script này KHÔNG đụng vào bất kỳ API mạng nào của trang (không thay `WebSocket`,
   * không `fetch`, không `XMLHttpRequest`). Bản v1.8 từng thay `window.WebSocket` để
   * nghe khung mạng — chính chỗ đó làm đứt prototype chain và khiến nhà cái đá phiên
   * ngay khi ván bắt đầu, nên đã gỡ sạch.
   *
   * Kết quả tay giờ đọc từ BẢNG CẦU của bàn: mỗi ván mới là một ô `roadItem` mới. Đó
   * là dữ liệu trang vốn đã công khai cho người chơi, nên không có gì để site phát hiện.
   */

  /* ============================== cầu nối native ============================== */

  function post(payload) {
    payload.frameId = FRAME_ID;
    payload.url = location.href;
    outbox.push(JSON.stringify(payload));
    if (outbox.length > 60) outbox.shift();
    flush();
  }

  function flush() {
    if (!outbox.length) return;
    var api = window.BcrNative;
    if (!api || typeof api.postMessage !== 'function') return;
    while (outbox.length) {
      var text = outbox[0];
      try {
        api.postMessage(text);
        outbox.shift();
      } catch (e) {
        return;
      }
    }
  }

  function reply(id, cmd, ok, result, error) {
    post({ __bcr: 'res', id: id, cmd: cmd, ok: ok, result: result || null, error: error || '' });
  }

  /* Chuyển lệnh xuống khung con. postMessage đi qua được cả khung KHÁC origin — đây là
   * lý do bản Android không cần CDP như desktop. Mỗi khung chỉ xử lý một lệnh một lần. */
  var handled = {};
  function relay(msg) {
    var depth = Number(msg.depth || 0);
    if (depth >= 5) return;
    var count = 0;
    try { count = window.frames.length; } catch (e) { return; }
    for (var i = 0; i < count; i++) {
      var copy = {};
      for (var key in msg) {
        if (Object.prototype.hasOwnProperty.call(msg, key)) copy[key] = msg[key];
      }
      copy.depth = depth + 1;
      try { window.frames[i].postMessage(JSON.stringify(copy), '*'); } catch (e) { /* bỏ qua */ }
    }
  }

  window.addEventListener('message', function (evt) {
    var raw = evt && evt.data;
    if (typeof raw !== 'string') return;
    var msg;
    try { msg = JSON.parse(raw); } catch (e) { return; }
    onCommand(msg);
  }, false);

  /* Kênh chính thức của WebViewCompat.addWebMessageListener: object BcrNative vừa có
   * postMessage() vừa phát 'message'. Object này có thể xuất hiện sau document-start nên
   * phải thử lại vài nhịp. */
  function bindNativeChannel() {
    var api = window.BcrNative;
    if (!api || typeof api.addEventListener !== 'function') return false;
    api.addEventListener('message', function (evt) {
      var raw = evt && evt.data;
      if (typeof raw !== 'string') return;
      var msg;
      try { msg = JSON.parse(raw); } catch (e) { return; }
      onCommand(msg);
    });
    flush();
    return true;
  }

  var nativeReady = bindNativeChannel();
  var bindTries = 0;
  var bindTimer = setInterval(function () {
    bindTries += 1;
    if (nativeReady || bindTries > 100) {
      clearInterval(bindTimer);
      return;
    }
    nativeReady = bindNativeChannel();
    if (nativeReady) clearInterval(bindTimer);
  }, 100);

  function onCommand(msg) {
    if (!msg || msg.__bcr !== 'cmd') return;
    if (handled[msg.id]) return;
    handled[msg.id] = Date.now();
    var cutoff = Date.now() - 120000;
    for (var id in handled) {
      if (handled[id] < cutoff) delete handled[id];
    }
    relay(msg);
    handle(msg);
  }

  /* Đường dự phòng: native gọi trực tiếp trong khung gốc (WebView cũ không có
   * WebMessageListener). */
  window.__BCR__ = {
    dispatch: function (json) {
      var msg;
      try { msg = JSON.parse(json); } catch (e) { return false; }
      onCommand(msg);
      return true;
    },
    version: '2'
  };

  function handle(msg) {
    var id = msg.id, cmd = msg.cmd, args = msg.args || {};
    var out;
    try {
      out = Promise.resolve(run(cmd, args));
    } catch (err) {
      reply(id, cmd, false, null, String((err && err.message) || err));
      return;
    }
    out.then(function (result) {
      reply(id, cmd, true, result, '');
    }, function (err) {
      reply(id, cmd, false, null, String((err && err.message) || err));
    });
  }

  /* Báo hiện diện một lần khi khung sẵn sàng (native biết khung mới xuất hiện). */
  function hello() {
    post({ __bcr: 'hello', ready: true, top: window === window.top, href: location.href });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', hello, { once: true });
  }
  setTimeout(hello, 400);

  /* ================================ tiện ích DOM ================================ */

  function txt(el) {
    return el ? String(el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }

  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width <= 4 || r.height <= 4) return false;
    var st = getComputedStyle(el);
    return st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
  }

  function digits(s) {
    if (s === null || s === undefined) return null;
    // Bỏ ký tự điều khiển unicode mà site chèn quanh số tiền (U+2066..U+2069), rồi bỏ
    // tiền tố tiền tệ "VN2"/"VND"/"USD" — nếu không, chữ số 2 trong "VN2" bị đọc thành số.
    // Không dùng \b vì site viết liền "BANKERVN2 20" (chữ cái dính liền mã tiền tệ).
    var text = String(s)
      .replace(/[\u2066\u2067\u2068\u2069\u200e\u200f]/g, '')
      .replace(/(?:VN2|VND|USDT|USD|CNY|THB|KRW|JPY|EUR)/gi, ' ');
    var m = text.match(/\d[\d.,]*/);
    if (!m) return null;
    return parseInt(m[0].replace(/[^\d]/g, ''), 10);
  }

  function hueOf(color) {
    var m = /rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)/.exec(color || '');
    if (!m) return null;
    var r = +m[1] / 255, g = +m[2] / 255, b = +m[3] / 255;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
    if (d < 0.15) return null;
    var h;
    if (mx === r) h = ((g - b) / d) % 6;
    else if (mx === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    return (h * 60 + 360) % 360;
  }

  function sideOf(color) {
    var h = hueOf(color);
    if (h === null) return null;
    if (h >= 335 || h <= 22) return 'B';
    if (h >= 190 && h <= 275) return 'P';
    if (h >= 75 && h <= 170) return 'T';
    return null;
  }

  function ancestorAt(el, levels) {
    var node = el;
    for (var i = 0; i < levels && node.parentElement; i++) node = node.parentElement;
    return node;
  }

  function bestGrouping(nodes, from, to, minSize) {
    var best = null;
    for (var levels = from; levels <= to; levels++) {
      var map = new Map();
      for (var i = 0; i < nodes.length; i++) {
        var key = ancestorAt(nodes[i], levels);
        var arr = map.get(key);
        if (!arr) map.set(key, (arr = []));
        arr.push(nodes[i]);
      }
      var groups = [];
      map.forEach(function (g) { if (g.length >= minSize) groups.push(g); });
      if (!groups.length) continue;
      var score = groups.length * 100000;
      for (var j = 0; j < groups.length; j++) score += groups[j].length;
      if (!best || score > best.score) best = { score: score, groups: groups };
    }
    return best ? best.groups : [];
  }

  function gridOrder(items) {
    function uniq(arr) {
      return arr.slice().sort(function (a, b) { return a - b; })
        .filter(function (v, i, a) { return i === 0 || v - a[i - 1] > 6; });
    }
    var xLines = uniq(items.map(function (i) { return i.x; }));
    var yLines = uniq(items.map(function (i) { return i.y; }));
    function near(v, lines) {
      var bi = 0, bd = Infinity;
      for (var i = 0; i < lines.length; i++) {
        var d = Math.abs(lines[i] - v);
        if (d < bd) { bd = d; bi = i; }
      }
      return bi;
    }
    var pos = items.map(function (it, idx) {
      return { idx: idx, c: near(it.x, xLines), r: near(it.y, yLines) };
    });
    var geo = pos.slice().sort(function (a, b) { return (a.c - b.c) || (a.r - b.r); })
      .map(function (p) { return p.idx; });
    return { geo: geo, dom: pos.map(function (p) { return p.idx; }), cols: xLines.length, rows: yLines.length };
  }

  function orderedLetters(items) {
    var g = gridOrder(items);
    var useGeo = g.cols >= 2 && g.rows >= 2;
    var order = useGeo ? g.geo : g.dom;
    return {
      letters: order.map(function (i) { return items[i].side; }).join(''),
      order: useGeo ? 'geo' : 'dom', cols: g.cols, rows: g.rows
    };
  }

  function nameFromText(t) {
    var words = (t || '').split(' ');
    var acc = '';
    for (var i = 0; i < words.length && i < 5; i++) {
      acc = acc ? acc + ' ' + words[i] : words[i];
      if (/[A-Za-z][\w'-]*\d{1,3}$/.test(words[i])) break;
    }
    return /^[A-Za-z][A-Za-z0-9 .&'-]{0,30}\d{1,3}$/.test(acc) ? acc : '';
  }

  function labelFor(container) {
    var node = container;
    for (var up = 0; up < 9 && node; up++) {
      var attrs = node.attributes || [];
      for (var i = 0; i < attrs.length; i++) {
        var a = attrs[i];
        if (/^(data-|aria-|title)/i.test(a.name) && /\d/.test(a.value) && a.value.length <= 40) {
          return a.value.trim();
        }
      }
      var t = txt(node);
      var nm = nameFromText(t);
      if (nm) return nm;
      var short = /([A-Za-z]\d{1,3}(?![\d]))/.exec(t);
      if (short) return short[1];
      node = node.parentElement;
    }
    return '';
  }

  function boxOf(items) {
    var xs = items.map(function (i) { return i.x; });
    var ys = items.map(function (i) { return i.y; });
    return {
      w: Math.max.apply(null, xs) - Math.min.apply(null, xs),
      h: Math.max.apply(null, ys) - Math.min.apply(null, ys),
      x: Math.round(Math.min.apply(null, xs)), y: Math.round(Math.min.apply(null, ys))
    };
  }

  function dedupe(roads) {
    var seen = new Map();
    for (var i = 0; i < roads.length; i++) {
      var r = roads[i];
      var key = (r.label || '?') + '|' + r.letters;
      if (!seen.has(key)) seen.set(key, r);
    }
    var out = [];
    seen.forEach(function (v) { out.push(v); });
    return out;
  }

  /* ============================ các nguồn đọc cầu ============================ */

  function textRoads() {
    var leaves = [];
    document.querySelectorAll('*').forEach(function (el) {
      if (el.childElementCount !== 0) return;
      var t = String(el.textContent || '').trim().toUpperCase();
      if (t !== 'B' && t !== 'P' && t !== 'T') return;
      var r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) {
        leaves.push({ el: el, x: r.x + r.width / 2, y: r.y + r.height / 2, side: t });
      }
    });
    if (leaves.length < MIN_TEXT_GROUP) return { roads: [], scanned: leaves.length };
    var groups = bestGrouping(leaves.map(function (l) { return l.el; }), 1, 9, MIN_TEXT_GROUP);
    var byEl = new Map();
    leaves.forEach(function (l) { byEl.set(l.el, l); });
    var roads = groups.map(function (group) {
      var items = group.map(function (el) { return byEl.get(el); });
      var res = orderedLetters(items);
      return {
        letters: res.letters, label: labelFor(group[0]),
        source: 'text-' + res.order, box: boxOf(items)
      };
    }).filter(function (r) { return r.letters.length >= MIN_TEXT_GROUP && r.box.w > 40; });
    return { roads: dedupe(roads), scanned: leaves.length };
  }

  function svgRoads() {
    var shapes = [];
    document.querySelectorAll('svg circle, svg ellipse, svg rect, svg path, svg use').forEach(function (el) {
      var st = getComputedStyle(el);
      var side = sideOf(st.fill) || sideOf(st.stroke);
      if (!side) return;
      var r = el.getBoundingClientRect();
      if (r.width < 5 || r.height < 5 || r.width > 70 || r.height > 70) return;
      if (el.closest('button,[role=button]')) return;
      shapes.push({ el: el, x: r.x + r.width / 2, y: r.y + r.height / 2, side: side });
    });
    if (shapes.length < MIN_SVG_GROUP) return { roads: [], scanned: shapes.length };
    var groups = bestGrouping(shapes.map(function (s) { return s.el; }), 1, 9, MIN_SVG_GROUP);
    var byEl = new Map();
    shapes.forEach(function (s) { byEl.set(s.el, s); });
    var roads = groups.map(function (group) {
      var items = group.map(function (el) { return byEl.get(el); });
      var res = orderedLetters(items);
      var box = boxOf(items);
      var sides = new Set(res.letters.replace(/T/g, '').split(''));
      return {
        letters: res.letters, label: labelFor(group[0]), source: 'svg-' + res.order, box: box,
        ok: res.cols >= 3 && res.rows >= 2 && box.w > 60 && sides.size >= 1
          && box.h / Math.max(1, res.rows) < 60
      };
    }).filter(function (r) { return r.ok; });
    return { roads: dedupe(roads), scanned: shapes.length };
  }

  /* Big Road: letters = mỗi tay một ký tự, hoà là 'T' nối sau ký tự quyết định. */
  function historyLetters(history) {
    var letters = '';
    for (var i = 0; i < history.length; i++) {
      var item = history[i] || {};
      var side = item.c === 'R' ? 'B' : (item.c === 'B' ? 'P' : null);
      var ties = (item.ties === undefined || item.ties === null) ? 0 : item.ties;
      var posOk = Array.isArray(item.pos) && item.pos.length === 2 && item.pos.every(function (p) {
        return typeof p === 'number' && isFinite(p) && p >= 0 && Math.floor(p) === p;
      });
      if (!side || typeof ties !== 'number' || ties < 0 || ties > 50 || !posOk) {
        return { letters: '', readError: 'Dữ liệu Big Road không đúng định dạng; không suy đoán kết quả.' };
      }
      letters += side;
      for (var t = 0; t < ties; t++) letters += 'T';
      if (letters.length > 400) {
        return { letters: '', readError: 'Lịch sử vượt giới hạn 400 tay.' };
      }
    }
    var readError = '';
    if (history.length && (history[0].pos[0] !== 0 || history[0].pos[1] !== 0)) {
      readError = 'Big Road bị cắt đầu; không đủ lịch sử để chọn bàn.';
    } else if (history[0] && history[0].ties) {
      readError = 'Ô đầu có hòa: Big Road không cho biết hòa trước hay sau tay đầu.';
    }
    return { letters: letters, readError: readError };
  }

  /* Đi NGƯỢC từ fiber của canvas lên props.history/props.table (rẻ hơn duyệt cả cây). */
  function canvasRoads() {
    var canvases = Array.prototype.slice.call(
      document.querySelectorAll('[data-role="history-roads"] canvas'));
    var roads = [];
    for (var i = 0; i < canvases.length; i++) {
      var canvas = canvases[i];
      var rect = canvas.getBoundingClientRect();
      if (!rect.width || !rect.height) continue;
      var key = Object.keys(canvas).filter(function (n) {
        return n.indexOf('__reactFiber$') === 0;
      })[0];
      var fiber = key ? canvas[key] : null;
      var history = null, table = null, gameType = '';
      for (var step = 0; fiber && step < 200; step++, fiber = fiber.return) {
        var props = fiber.memoizedProps || {};
        if (!history && props.type === 'BaccaratHistoryRoadTypeBig' && Array.isArray(props.history)) {
          history = props.history;
        }
        if (!gameType && typeof props.gameType === 'string') gameType = props.gameType;
        if (!table && props.table && typeof props.table.id === 'string') table = props.table;
        if (history && table) break;
      }
      if (!table || !/^[A-Za-z0-9_-]{1,80}$/.test(table.id)) continue;
      if (['baccarat', 'luckybaccarat'].indexOf(gameType) < 0) continue;
      var read = history
        ? historyLetters(history)
        : { letters: '', readError: 'Chưa đọc được lịch sử Canvas của bàn.' };
      var details = table.gameDetails || {};
      if ((details.gameConfig || {}).lightning || SPECIAL_IDS.indexOf(table.id) >= 0) {
        read.readError = 'Bàn có luật trả thưởng đặc biệt; chỉ xem cầu, không giả lập theo bàn chuẩn.';
      }
      roads.push({
        letters: read.letters,
        label: typeof table.title === 'string' ? table.title : table.id,
        table_id: table.id, game_type: gameType, read_error: read.readError,
        source: 'evo-canvas-big',
        box: {
          x: Math.round(rect.x), y: Math.round(rect.y),
          w: Math.round(rect.width), h: Math.round(rect.height)
        }
      });
    }
    return { roads: roads, scanned: canvases.length };
  }

  function beadRoads() {
    var roads = [];
    var names = { Banker: 'B', Player: 'P', Tie: 'T' };
    document.querySelectorAll('[data-role="Bead-road"]').forEach(function (bead) {
      var items = bead.querySelectorAll('[data-role="roadItem"][name]');
      if (!items.length) return;
      var letters = '', readError = '', skipped = 0;
      for (var i = 0; i < items.length; i++) {
        // Ô cầu có thể ghi "Banker Player" (ván có cả hai) — lấy ký tự ĐẦU làm cửa quyết định.
        var raw = (items[i].getAttribute('name') || '').trim().split(/\s+/)[0];
        var side = names[raw];
        if (!side) {
          // Không xoá cả cầu vì một ô lạ: bỏ qua ô đó và ghi lại số ô đã bỏ. Xoá sạch
          // khiến app không đọc được cầu nào dù bàn hoàn toàn bình thường.
          skipped += 1;
          continue;
        }
        letters += side;
        if (letters.length > 400) {
          letters = letters.slice(-400);
          readError = 'Cầu dài quá 400 tay, chỉ giữ 400 tay gần nhất.';
        }
      }
      if (skipped > 0 && !readError) {
        readError = 'Bỏ qua ' + skipped + ' ô cầu có ký hiệu lạ.';
      }
      if (!letters.length) return;
      var r = bead.getBoundingClientRect();
      roads.push({
        letters: letters, label: '', read_error: readError, source: 'evo-bead',
        box: {
          x: Math.round(r.x), y: Math.round(r.y),
          w: Math.round(r.width), h: Math.round(r.height)
        }
      });
    });
    return { roads: roads, scanned: roads.length };
  }

  /**
   * Đọc cầu NHẸ cho vòng lặp: chỉ lấy chuỗi ký tự của bảng cầu, không quét sảnh,
   * không đọc khay chip. Trả về cầu DÀI NHẤT tìm được (bàn thật có thể có nhiều bảng).
   *
   * Đây là nguồn kết quả của app: mỗi ván mới = một ô `roadItem` mới ở cuối cầu, nên
   * native so độ dài để biết tay mình vừa thắng hay thua. Không đụng API mạng của trang.
   */
  function roadOnly() {
    var best = '';
    var source = '';
    var tid = tableIdFromUrl();

    var bead = beadRoads();
    for (var i = 0; i < bead.roads.length; i++) {
      if (bead.roads[i].letters.length > best.length) {
        best = bead.roads[i].letters;
        source = 'bead';
      }
    }

    // Bàn mới render bằng canvas: đọc thêm nếu cầu DOM chưa có.
    if (!best.length) {
      var evo = canvasRoads();
      for (var j = 0; j < evo.roads.length; j++) {
        if (evo.roads[j].letters.length > best.length) {
          best = evo.roads[j].letters;
          source = 'canvas';
        }
      }
    }

    return {
      ok: true,
      in_table: !!tid,
      table_id: tid,
      letters: best,
      hands: best.length,
      source: source,
      road_items: document.querySelectorAll('[data-role="roadItem"][name]').length
    };
  }

  /* ================================= quét trang ================================= */

  function tableIdFromUrl() {
    var m = /[#?&]table_id=([^&]+)/.exec(location.href);
    return m ? decodeURIComponent(m[1]) : '';
  }

  function walletText() {
    // Site mới (Evolution r2) đặt số dư trong [data-role="balance-label-value"]; bản cũ dùng
    // header-balance/footer-balance. Đọc lần lượt để chạy được ở cả hai.
    var el = document.querySelector('[data-role="balance-label-value"]')
      || document.querySelector('[data-role="header-balance"]')
      || document.querySelector('[data-role="footer-balance"]');
    return el ? txt(el) : '';
  }

  /**
   * Hạn mức cược của từng cửa chính, đọc từ bảng "BET LIMITS" của bàn.
   * Bàn thật (Evolution r2) ghi dạng "BANKER⁦VN2 20⁩ – 100,000⁩0.95:1" → lấy số ĐẦU = min,
   * số SAU dấu gạch = max. Trả {} khi bàn không hiện bảng này.
   */
  function betLimits() {
    var out = {};
    var rows = [
      ['B', 'bet-limits_banker'],
      ['P', 'bet-limits_player'],
      ['T', 'bet-limits_tie']
    ];
    for (var i = 0; i < rows.length; i++) {
      var side = rows[i][0];
      var el = document.querySelector('[data-role="' + rows[i][1] + '"]');
      if (!el) continue;
      var text = String(txt(el))
        .replace(/[\u2066\u2067\u2068\u2069\u200e\u200f]/g, '')
        .replace(/(?:VN2|VND|USDT|USD)/gi, ' ')
        .replace(/:\s*\d+(?:\.\d+)?/g, ' ');   // bỏ tỉ lệ trả thưởng "0.95:1", "8:1"
      var nums = text.match(/\d{1,3}(?:[.,]\d{3})*|\d+/g);
      if (!nums || !nums.length) continue;
      var parse = function (s) { return parseInt(String(s).replace(/[^\d]/g, ''), 10); };
      out[side] = { min: parse(nums[0]), max: nums.length > 1 ? parse(nums[1]) : null };
    }
    return out;
  }

  function tableTileOf(tile) {
    var key = Object.keys(tile).filter(function (n) {
      return n.indexOf('__reactFiber$') === 0;
    })[0];
    var f = key ? tile[key] : null;
    for (var i = 0; f && i < 60; i++) {
      if (f.memoizedProps && f.memoizedProps.table && f.memoizedProps.table.gameDetails) {
        return f.memoizedProps.table;
      }
      f = f.return;
    }
    return null;
  }

  function lobbyTiles() {
    var out = [];
    document.querySelectorAll('[data-role="table-tile"]').forEach(function (tile) {
      var table = tableTileOf(tile);
      if (!table || typeof table.id !== 'string' || !table.id) return;
      var details = table.gameDetails || {};
      out.push({
        id: table.id,
        title: typeof table.title === 'string' ? table.title : table.id,
        game_type: details.gameType || '',
        lightning: !!((details.gameConfig || {}).lightning),
        special: SPECIAL_IDS.indexOf(table.id) >= 0
      });
    });
    return out;
  }

  function scanPage() {
    var tiles = lobbyTiles();
    var inLobby = tiles.length > 0;
    var tid = tableIdFromUrl();
    var out = null, kind = '';

    if (!inLobby && tid) {
      var evo = canvasRoads();
      if (evo.roads.length) { out = evo; kind = 'evo-canvas'; }
      else {
        var bead = beadRoads();
        if (bead.roads.length) { out = bead; kind = 'evo-bead'; }
        else { out = evo; kind = 'evo-canvas'; }
      }
    } else if (!inLobby) {
      var bodyLen = document.body ? String(document.body.innerText || '').length : 0;
      if (bodyLen > 60) {
        var text = textRoads();
        out = text;
        kind = 'text';
        if (!text.roads.length) {
          var svg = svgRoads();
          out = { roads: svg.roads, scanned: Math.max(text.scanned, svg.scanned) };
          kind = 'svg';
        }
      } else {
        out = { roads: [], scanned: 0 };
        kind = 'empty';
      }
    } else {
      out = { roads: [], scanned: 0 };
      kind = 'lobby';
    }

    var spots = null;
    try { spots = readChips(); } catch (e) { spots = null; }

    return {
      page: {
        url: location.origin + location.pathname,
        table_id: tid,
        title: (document.title || '').slice(0, 80),
        wallet: walletText(),
        limits: betLimits()
      },
      in_table: !inLobby && !!tid,
      in_lobby: inLobby,
      tables: out.roads.map(function (r) {
        return {
          label: r.label || (out.roads.length === 1 && tid ? tid : 'ban ' + r.box.w),
          letters: r.letters, source: r.source, table_id: r.table_id || '',
          read_error: r.read_error || ''
        };
      }),
      lobby: { tiles: tiles },
      chips: spots,
      diag: { kind: kind, scanned: out.scanned, roads: out.roads.length }
    };
  }

  /* ============================== khay chip / ô cược ============================== */

  function trayOf() {
    var named = document.querySelector('[data-role="chip-stack"]');
    if (named) return named;
    var chips = Array.prototype.slice.call(document.querySelectorAll('[data-role="chip"][data-value]'))
      .filter(function (el) {
        return visible(el) && !el.closest('[data-role^="bet-spot-"]')
          && (parseInt(el.getAttribute('data-value'), 10) || 0) > 0;
      });
    if (!chips.length) return null;
    var node = chips[0];
    for (var i = 0; i < 8 && node && node !== document.body; i++) {
      var count = Array.prototype.slice.call(node.querySelectorAll('[data-role="chip"][data-value]'))
        .filter(function (el) {
          return visible(el) && !el.closest('[data-role^="bet-spot-"]');
        }).length;
      if (count >= chips.length) return node;
      node = node.parentElement;
    }
    return chips[0].parentElement;
  }

  function spotOf(side) {
    var role = { B: 'Banker', P: 'Player', T: 'Tie' }[side];
    return role ? document.querySelector('[data-role="bet-spot-' + role + '"]') : null;
  }

  function spotAmount(side) {
    var spot = spotOf(side);
    if (!spot) return null;
    var total = 0;
    var els = spot.querySelectorAll('[data-role="chip"]');
    for (var i = 0; i < els.length; i++) {
      if (!visible(els[i])) continue;
      var v = parseInt(els[i].getAttribute('data-value'), 10);
      if (isFinite(v) && v > 0) total += v;
    }
    return total;
  }

  function selectedChip() {
    var tray = document.querySelector('[data-role="chip-stack"]') || document;
    var marked = Array.prototype.slice.call(
      tray.querySelectorAll('[data-role="chip"][data-selected="true"]'))
      .map(function (el) { return parseInt(el.getAttribute('data-value'), 10); })
      .filter(function (v) { return isFinite(v) && v > 0; });
    if (marked.length === 1) return marked[0];
    var wrap = document.querySelector('[data-role="selected-chip"]');
    if (!wrap) return null;
    var inner = wrap.querySelector('[data-role="chip"][data-value]');
    if (inner) {
      var v = parseInt(inner.getAttribute('data-value'), 10);
      if (isFinite(v)) return v;
    }
    return digits(txt(wrap));
  }

  function readChips() {
    var tray = trayOf();
    var hasTable = !!(document.querySelector('[data-role="Bead-road"]') || (tray && spotOf('B')));
    var spots = {};
    ['B', 'P', 'T'].forEach(function (side) {
      var spot = spotOf(side);
      if (!spot) { spots[side] = null; return; }
      var chips = [];
      var els = spot.querySelectorAll('[data-role="chip"]');
      for (var i = 0; i < els.length; i++) {
        if (!visible(els[i])) continue;
        var v = parseInt(els[i].getAttribute('data-value'), 10);
        if (isFinite(v) && v > 0) chips.push(v);
      }
      spots[side] = {
        amount: chips.reduce(function (s, v) { return s + v; }, 0),
        chips: chips
      };
    });
    var denominations = [];
    if (tray) {
      var seen = {};
      var cells = tray.querySelectorAll('[data-role="chip"]');
      for (var i = 0; i < cells.length; i++) {
        if (!visible(cells[i])) continue;
        var v = parseInt(cells[i].getAttribute('data-value'), 10);
        if (isFinite(v) && v > 0 && !seen[v]) { seen[v] = 1; denominations.push(v); }
      }
      denominations.sort(function (a, b) { return a - b; });
    }
    var total = 0;
    ['B', 'P', 'T'].forEach(function (s) { total += (spots[s] && spots[s].amount) || 0; });
    var labelEl = document.querySelector('[data-role="total-bet-label"]');
    return {
      has_table: hasTable,
      total: total,
      selected_chip: selectedChip(),
      spots: spots,
      denominations: denominations,
      limits: betLimits(),
      total_label: labelEl ? digits(txt(labelEl)) : null
    };
  }

  /* Điểm đo trong khung -> toạ độ khung GỐC. Chỉ tính được khi chuỗi iframe cùng
   * origin; khác origin thì trả ok=false và native dùng sự kiện tổng hợp thay thế. */
  function toTop(x, y) {
    var px = x, py = y, w = window, depth = 0;
    while (w !== w.top) {
      var fe = null;
      try { fe = w.frameElement; } catch (e) { return { x: 0, y: 0, ok: false, depth: depth }; }
      if (!fe) return { x: 0, y: 0, ok: false, depth: depth };
      var r = fe.getBoundingClientRect();
      var st = w.parent.getComputedStyle(fe);
      var num = function (v) { var n = parseFloat(v); return isFinite(n) ? n : 0; };
      px += r.x + num(st.borderLeftWidth) + num(st.paddingLeft);
      py += r.y + num(st.borderTopWidth) + num(st.paddingTop);
      w = w.parent;
      depth += 1;
      if (depth > 8) return { x: 0, y: 0, ok: false, depth: depth };
    }
    return { x: px, y: py, ok: true, depth: depth };
  }

  function pointOf(el, opts) {
    if (!el) return null;
    var r = el.getBoundingClientRect();
    if (r.width <= 2 || r.height <= 2) return null;
    // Cho phép lệch tâm (ô bàn ở sảnh có nút yêu thích ở góc trên phải).
    var fx = (opts && opts.fx !== undefined) ? opts.fx : 0.5;
    var fy = (opts && opts.fy !== undefined) ? opts.fy : 0.5;
    var x = r.x + r.width * fx, y = r.y + r.height * fy;
    return { x: x, y: y, abs: toTop(x, y) };
  }

  function targetsFor(side) {
    var role = { B: 'Banker', P: 'Player', T: 'Tie' }[side];
    var spot = spotOf(side);
    if (!role || !spot) return null;
    var tray = trayOf();
    var chips = {};
    if (tray) {
      var els = tray.querySelectorAll('[data-role="chip"][data-value]');
      for (var i = 0; i < els.length; i++) {
        var el = els[i];
        if (!visible(el)) continue;
        var v = parseInt(el.getAttribute('data-value'), 10);
        if (!isFinite(v) || v <= 0) continue;
        var r = el.getBoundingClientRect();
        var area = r.width * r.height;
        if (chips[v] && chips[v].area >= area) continue;
        var p = pointOf(el);
        if (!p) continue;
        // Giữ THAM CHIẾU element: trên mobile các chip xếp chồng nhau nên nếu chỉ
        // dùng toạ độ rồi elementFromPoint sẽ bấm nhầm sang chip nằm trên.
        chips[v] = { el: el, x: p.x, y: p.y, abs: p.abs, area: area };
      }
    }
    var sp = pointOf(spot);
    if (!sp) return null;
    return { spot: { el: spot, x: sp.x, y: sp.y, abs: sp.abs }, chips: chips };
  }

  function fire(el, type, x, y) {
    var init = {
      bubbles: true, cancelable: true, composed: true, view: window,
      clientX: x, clientY: y, screenX: x, screenY: y,
      button: 0, buttons: 0, pointerId: 1, pointerType: 'mouse', isPrimary: true
    };
    if (type.indexOf('pointer') === 0) {
      init.buttons = type === 'pointerdown' ? 1 : 0;
      try { el.dispatchEvent(new PointerEvent(type, init)); return; } catch (e) { /* fallback */ }
    }
    if (typeof MouseEvent === 'function') {
      try { el.dispatchEvent(new MouseEvent(type, init)); return; } catch (e) { /* fallback */ }
    }
    var ev = document.createEvent('MouseEvents');
    ev.initMouseEvent(type, true, true, window, 0, x, y, x, y, false, false, false, false, 0, null);
    el.dispatchEvent(ev);
  }

  /* Bấm vào ĐÚNG element được chỉ định (không tra lại theo toạ độ).
   * Trên desktop mỗi chip là một ô riêng nên tra toạ độ vẫn đúng; trên điện thoại các chip
   * trong khay xếp chồng lên nhau nên elementFromPoint sẽ trả về chip NẰM TRÊN, khiến app
   * bấm sai mệnh giá. Truyền thẳng element lấy từ DOM là cách duy nhất chắc chắn đúng. */
  function synthTap(target) {
    if (!target) return false;
    var el = null, x = 0, y = 0;
    if (target.el) {
      el = target.el;
      x = target.x || 0;
      y = target.y || 0;
    } else {
      // Tương thích chỗ gọi cũ chỉ truyền toạ độ.
      x = target.x || 0;
      y = target.y || 0;
      el = document.elementFromPoint(x, y);
    }
    if (!el) return false;
    // Element có thể đã bị React thay khỏi DOM giữa lúc đo và lúc bấm.
    if (!document.contains(el)) return false;
    fire(el, 'pointerover', x, y);
    fire(el, 'pointerenter', x, y);
    fire(el, 'mouseover', x, y);
    fire(el, 'pointerdown', x, y);
    fire(el, 'mousedown', x, y);
    fire(el, 'pointerup', x, y);
    fire(el, 'mouseup', x, y);
    fire(el, 'click', x, y);
    return true;
  }

  /* Chia số tiền thành ít chip nhất (port của chips.decompose bản desktop). */
  function decompose(amount, denominations) {
    var denoms = [], seen = {};
    (denominations || []).forEach(function (d) {
      var v = parseInt(d, 10);
      if (isFinite(v) && v > 0 && !seen[v]) { seen[v] = 1; denoms.push(v); }
    });
    denoms.sort(function (a, b) { return a - b; });
    var target = Math.round(Number(amount) || 0);
    if (target <= 0) return { exact: true, chips: [] };
    if (!denoms.length) return { exact: false, chips: [] };
    var best = [[]];
    for (var v = 1; v <= target; v++) {
      var choice = null;
      for (var i = 0; i < denoms.length; i++) {
        var d = denoms[i];
        if (d > v) break;
        var prev = best[v - d];
        if (!prev) continue;
        if (choice === null || prev.length + 1 < choice.length) choice = prev.concat([d]);
      }
      if (choice) best[v] = choice;
    }
    if (best[target]) {
      return { exact: true, chips: best[target].slice().sort(function (a, b) { return b - a; }) };
    }
    var remaining = target, picked = [];
    denoms.slice().reverse().forEach(function (d) {
      var count = Math.floor(remaining / d);
      remaining -= count * d;
      for (var k = 0; k < count; k++) picked.push(d);
    });
    // Số tiền nhỏ hơn mệnh giá nhỏ nhất (hoặc lẻ không chia hết) thì đặt ĐÚNG MỘT chip
    // nhỏ nhất — làm tròn LÊN, không bỏ tay. Thà cược hơi cao hơn bậc mong muốn còn hơn
    // dừng cả phiên chỉ vì người dùng nhập bậc 1 thấp hơn min bet của bàn.
    if (!picked.length && denoms.length) {
      return { exact: false, chips: [denoms[0]], roundedUp: true, wanted: target };
    }
    return { exact: false, chips: picked };
  }

  /* args: {side, amount, dryRun, clickDelayMs, waitMs, waitClearMs, requireClear} */
  async function placeBet(args) {
    var side = String(args.side || '').toUpperCase();
    var want = Math.round(Number(args.amount) || 0);
    var role = { B: 'Banker', P: 'Player', T: 'Tie' }[side];
    if (!role) return { ok: false, reason: 'bad_side', note: 'Cửa không hợp lệ: ' + side };
    if (want <= 0) return { ok: false, reason: 'bad_amount', note: 'Số tiền không hợp lệ.' };

    var waitUntil = Date.now() + (Number(args.waitMs) || 9000);
    var first = null;
    while (Date.now() < waitUntil) {
      first = readChips();
      if (first.has_table) break;
      first = null;
      await sleep(150);
    }
    if (!first) {
      return { ok: false, reason: 'no_table_view', note: 'Khung này không có khay chip/ô cược.' };
    }

    // Bàn phải SẠCH chip ván trước rồi mới bấm: bấm chồng lên chip cũ là đặt gấp đôi.
    if (args.requireClear !== false && !args.dryRun) {
      var clearUntil = Date.now() + (Number(args.waitClearMs) || 22000);
      var cleared = false;
      while (Date.now() < clearUntil) {
        var live = readChips();
        if (live && live.has_table && (live.total || 0) <= 0) { cleared = true; break; }
        await sleep(220);
      }
      if (!cleared) {
        return {
          ok: false, reason: 'table_not_clear',
          note: 'Bàn vẫn còn chip ván trước nên không bấm (tránh đặt chồng).'
        };
      }
    }

    // Hạn mức của cửa: vượt max là site từ chối, nên chặn TRƯỚC khi bấm để không đặt dở dang.
    var lim = (first.limits || {})[side];
    if (lim && lim.max && want > lim.max) {
      return {
        ok: false, reason: 'over_table_limit',
        note: 'Cửa ' + role + ' chỉ nhận tối đa ' + lim.max + ' — mức ' + want +
          ' vượt hạn mức bàn. Bậc gấp thếp này không đặt được ở bàn này.',
        limit: lim, want: want
      };
    }

    var denoms = (first.denominations && first.denominations.length)
      ? first.denominations : [20, 50, 250, 1000, 5000, 10000];
    var split = decompose(want, denoms);
    if (!split.chips.length) {
      return {
        ok: false, reason: 'ungroupable',
        note: want + ' không ghép được bằng mệnh giá ' + denoms.join('/') + '.'
      };
    }
    // Số tiền THẬT sẽ đặt sau khi ghép/làm tròn chip — dùng để đối chiếu với bàn.
    var actual = split.chips.reduce(function (s, v) { return s + v; }, 0);

    var targets = null;
    while (Date.now() < waitUntil && !targets) {
      targets = targetsFor(side);
      if (!targets) await sleep(120);
    }
    if (!targets) {
      return { ok: false, reason: 'no_targets', note: 'Chưa thấy ô cược/khay chip.' };
    }

    var delay = Math.max(40, Math.min(400, Number(args.clickDelayMs) || 90));
    var startAmount = spotAmount(side);
    var steps = [];
    var placedChips = [];

    for (var i = 0; i < split.chips.length; i++) {
      var denom = split.chips[i];
      var fresh = targetsFor(side);
      if (!fresh) {
        return { ok: false, reason: 'no_targets', note: 'Khay chip biến mất giữa chừng.', steps: steps };
      }
      var chip = fresh.chips[denom];
      if (!chip) {
        return {
          ok: false, reason: 'denom_missing',
          note: 'Khay không có mệnh giá ' + denom + ' (' + Object.keys(fresh.chips).join('/') + ').',
          steps: steps
        };
      }
      steps.push({ kind: 'chip', denom: denom, x: chip.x, y: chip.y, abs: chip.abs });
      if (!args.dryRun) {
        synthTap(chip);
        await sleep(delay);
      }
      var afterChip = targetsFor(side);
      var sp = afterChip ? afterChip.spot : fresh.spot;
      steps.push({ kind: 'spot', denom: denom, x: sp.x, y: sp.y, abs: sp.abs });
      if (!args.dryRun) {
        synthTap(sp);
        await sleep(delay);
      }
      placedChips.push(denom);
    }

    if (args.dryRun) {
      return {
        ok: true, dry_run: true, note: 'Chỉ tính toạ độ.',
        want: want, actual: actual, rounded: !!split.roundedUp,
        steps: steps, chips: split.chips, denominations: denoms
      };
    }

    var verifyUntil = Date.now() + 2600;
    var got = spotAmount(side);
    while (Date.now() < verifyUntil) {
      got = spotAmount(side);
      if (got !== null && startAmount !== null && got - startAmount === actual) break;
      await sleep(120);
    }
    var delta = (got === null || startAmount === null) ? null : got - startAmount;
    if (delta === actual) {
      var note = 'Đã đặt ' + actual + ' vào ' + role + '.';
      if (actual !== want) {
        note += ' (bậc muốn đặt ' + want + ' thấp hơn mệnh giá nhỏ nhất nên đặt ' +
          actual + '.)';
      }
      return {
        ok: true, note: note, want: want, actual: actual, rounded: !!split.roundedUp,
        placed: placedChips, spot_after: got, steps: steps
      };
    }
    return {
      ok: false, reason: 'verify_failed',
      needs_real_touch: true,
      missing: (delta === null ? actual : Math.max(0, actual - delta)),
      note: 'Sau khi bấm, ô ' + role + ' còn ' + (got === null ? '?' : got)
        + ' (cần +' + actual + ').',
      want: want, actual: actual,
      placed: placedChips, spot_after: got, steps: steps
    };
  }

  /* Mở ô bàn ở sảnh: tìm ô theo table_id, cuộn vào giữa, trả toạ độ tâm. */
  function openTile(args) {
    var id = String(args.id || '');
    var found = [];
    document.querySelectorAll('[data-role="table-tile"]').forEach(function (tile) {
      var table = tableTileOf(tile);
      if (!table || table.id !== id) return;
      found.push(tile);
    });
    if (!found.length) return { ok: false, reason: 'tile_not_found', note: 'Không thấy ô bàn ' + id + '.' };
    var tile = found.filter(function (t) { return t.getAttribute('data-open') === 'true'; })[0] || found[0];
    try { tile.scrollIntoView({ block: 'center' }); } catch (e) { /* bỏ qua */ }
    var rect = tile.getBoundingClientRect();
    if (rect.width < 20 || rect.height < 20) {
      return { ok: false, reason: 'tile_hidden', note: 'Ô bàn đang ẩn.' };
    }
    // Tránh góc trên phải: ở đó là nút yêu thích có onclick riêng.
    var p = pointOf(tile, { fx: 0.42, fy: 0.62 });
    if (!p) return { ok: false, reason: 'tile_hidden', note: 'Không đo được ô bàn.' };
    var clicked = false;
    if (!args.dryRun) clicked = synthTap({ el: tile, x: p.x, y: p.y });
    return {
      ok: true, x: p.x, y: p.y, abs: p.abs, clicked: clicked,
      title: tableTileOf(tile) ? (tableTileOf(tile).title || id) : id
    };
  }

  /* ============================== chẩn đoán DOM bàn ============================== */

  /**
   * Dump cấu trúc bàn đang mở để map selector đúng với layout MOBILE.
   *
   * Bàn Evolution trên PC và trên điện thoại dùng chung link nhưng render khác nhau:
   * khay chip có thể xếp chồng, ô cược đổi data-role. Hàm này trả về mọi thứ cần biết
   * để sửa selector: các element có data-role, khay chip, ô cược, nút ×2/gấp đôi.
   */
  function probeTable() {
    var out = { url: location.href, roles: [], chips: [], spots: [], buttons: [], diag: {} };

    // 1) Mọi data-role đang có trên trang, kèm số lượng.
    var roleCount = {};
    document.querySelectorAll('[data-role]').forEach(function (el) {
      var role = el.getAttribute('data-role');
      if (!role) return;
      roleCount[role] = (roleCount[role] || 0) + 1;
    });
    out.roles = Object.keys(roleCount).sort().map(function (r) {
      return { role: r, count: roleCount[r] };
    });

    // 2) Mọi element mang data-value (chip) — kèm vị trí + kích thước để biết cái nào thật.
    document.querySelectorAll('[data-value]').forEach(function (el) {
      var v = parseInt(el.getAttribute('data-value'), 10);
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      out.chips.push({
        value: isFinite(v) ? v : el.getAttribute('data-value'),
        role: el.getAttribute('data-role') || '',
        x: Math.round(r.x), y: Math.round(r.y),
        w: Math.round(r.width), h: Math.round(r.height),
        text: txt(el).slice(0, 24),
        cls: String(el.className || '').slice(0, 60),
        inTray: !!el.closest('[data-role="chip-stack"]')
      });
    });

    // 3) Ô cược: tìm theo data-role bet-spot-* và cả các ô lớn ở khu cược.
    document.querySelectorAll('[data-role^="bet-spot-"]').forEach(function (el) {
      var r = el.getBoundingClientRect();
      out.spots.push({
        role: el.getAttribute('data-role'),
        x: Math.round(r.x), y: Math.round(r.y),
        w: Math.round(r.width), h: Math.round(r.height),
        text: txt(el).slice(0, 40)
      });
    });

    // 4) Nút bấm đáng chú ý: ×2, gấp đôi, hoàn tác, xoá.
    var PAT = /(x\s*2|×\s*2|double|gấp đôi|gap doi|hoàn tác|hoan tac|undo|xóa|clear)/i;
    document.querySelectorAll('button,[role="button"],[data-role]').forEach(function (el) {
      var label = (txt(el) || el.getAttribute('aria-label') || '').trim();
      if (!label || label.length > 40) return;
      if (!PAT.test(label) && !PAT.test(el.getAttribute('data-role') || '')) return;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) return;
      out.buttons.push({
        text: label.slice(0, 30),
        role: el.getAttribute('data-role') || '',
        cls: String(el.className || '').slice(0, 60),
        x: Math.round(r.x), y: Math.round(r.y),
        w: Math.round(r.width), h: Math.round(r.height)
      });
    });

    // 5) Chẩn đoán cách đọc hiện tại thấy gì.
    out.diag = {
      bead_roads: document.querySelectorAll('[data-role="Bead-road"]').length,
      road_items: document.querySelectorAll('[data-role="roadItem"][name]').length,
      tray: !!trayOf(),
      spots_found: ['B', 'P', 'T'].filter(function (s) { return !!spotOf(s); }).length,
      read_chips: (function () {
        try {
          var c = readChips();
          return { has_table: c.has_table, total: c.total, denoms: c.denominations };
        } catch (e) { return { error: String(e) }; }
      })()
    };

    // 6) Mẫu HTML của khay chip và mỗi ô cược (để biết cấu trúc thật).
    var tray = trayOf();
    out.tray_html = tray ? String(tray.outerHTML || '').slice(0, 1200) : '';
    out.spot_html = {};
    ['B', 'P', 'T'].forEach(function (s) {
      var el = spotOf(s);
      out.spot_html[s] = el ? String(el.outerHTML || '').slice(0, 700) : '';
    });

    return out;
  }

  /* ================================ điều phối lệnh ================================ */

  function run(cmd, args) {
    switch (cmd) {
      case 'ping':
        return {
          ok: true, top: window === window.top, frames: (function () {
            try { return window.frames.length; } catch (e) { return 0; }
          })(), href: location.href
        };
      case 'scan':
        return scanPage();
      case 'page_url':
        // Chỉ trả URL của chính khung này: dùng để biết "đang ở trong bàn" dù iframe
        // bàn chưa dựng xong cầu (URL khung gốc có table_id, khung rỗng thì không).
        return { ok: true, url: location.href, has_table_id: /[#?&]table_id=/.test(location.href) };
      case 'chips':
        return readChips();
      case 'road':
        // Đọc cầu nhẹ: chỉ đếm ô cầu và lấy chuỗi ký tự, KHÔNG quét sảnh/khay chip.
        // Dùng mỗi nhịp của vòng lặp nên phải rẻ (trước đây gọi `scan` tốn tới 3,2s).
        return roadOnly();
      case 'round':
        // ĐÃ BỎ. Kết quả ván giờ đọc từ bảng cầu qua lệnh `road` — không cần WebSocket,
        // nên không có gì can thiệp vào kết nối mạng của trang.
        return { ok: false, reason: 'removed', note: 'Dùng lệnh road.' };
      case 'probe':
        return probeTable();
      case 'place':
        return placeBet(args);
      case 'open_tile':
        return openTile(args);
      case 'back':
        try { history.back(); return { ok: true }; } catch (e) { return { ok: false }; }
      case 'goto':
        try {
          location.href = String(args.url || '');
          return { ok: true };
        } catch (e) {
          return { ok: false, note: String(e) };
        }
      default:
        return { ok: false, reason: 'unknown_cmd', note: cmd };
    }
  }
})();
