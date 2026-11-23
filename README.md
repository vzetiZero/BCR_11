# BCR Desktop

Bot bàn **Baccarat** cho Windows: đánh **xen kẽ 1 tay Banker · 1 tay Player (B-P-B-P…)**
kèm **gấp thếp 8 bậc**. Giao diện **Electron**, lõi Python chạy headless.

```
Electron (View)  ──IPC──▶  engine.py (Transport)  ──▶  Controller  ──▶  Adapter / Model
      ▲                                                                        │
      └──────────────── sự kiện "state" / "log" (JSON mỗi dòng) ◀──────────────┘
```

## Cấu trúc

```
BCR/
├─ ui/                       View — Electron
│  ├─ main.js                cửa sổ, IPC, spawn engine Python
│  ├─ preload.js             contextBridge (không nodeIntegration)
│  └─ renderer/              index.html · app.js · styles.css
├─ bcr/
│  ├─ config.py              hằng số thời gian/ngưỡng dùng chung
│  ├─ models/                Model — logic thuần, không I/O
│  │  ├─ engine.py           hằng số Banker/Player/Tie, tỉ lệ trả thưởng
│  │  ├─ method.py           phương pháp 1-1: check_table, plan, advance, settle
│  │  ├─ money.py            MoneyBook: vốn, lãi/lỗ, chốt lời, chặn thua
│  │  ├─ chips.py            chia tiền → mệnh giá chip
│  │  └─ live_sim.py         LiveSimulation: vòng đời một phiên bàn
│  ├─ adapters/              Adapter — Chrome/Selenium/CDP
│  │  ├─ browser.py          mở/gắn Chrome, quét DOM, đọc số dư
│  │  ├─ lobby_stream.py     bắt WebSocket sảnh (lịch sử bàn), mở/rời bàn
│  │  ├─ table_bets.py       đọc chip, đặt chip vào ô cược
│  │  ├─ human_mouse.py · ghost_mouse.py · mouse_chromedriver.py   chuột người thật
│  │  └─ reader.js           script đọc DOM (nạp qua CDP)
│  ├─ controllers/           Controller — composition root, headless
│  │  └─ baccarat.py         BaccaratController
│  ├─ service/engine.py      Transport — JSON-RPC stdin/stdout
│  └─ __main__.py            `python -m bcr --game baccarat`
├─ tests/                    unittest + kiểm thử JS của reader
├─ tools/                    backtest + probe site (không cần cho vận hành)
├─ install-toan-bo.bat       cài Python deps + Electron binary
├─ Start.bat                 mở ứng dụng (Electron)
└─ setup-electron.ps1        tải/giải nén Electron (tự phục hồi khi lỗi)
```

Quy ước tầng (được khoá bằng `tests/test_architecture.py`):

| Tầng | Được import | Không được import |
|---|---|---|
| models | `bcr.config`, `bcr.models` | tkinter, selenium, websocket, adapters, controllers, service |
| adapters | `bcr.config`, `bcr.models` | controllers, service |
| controllers | models, adapters, config | service |
| service | controllers, adapters, config | (không chứa nghiệp vụ) |

## Chạy

```
install-toan-bo.bat     # 1 lần: pip install + npm install + Electron binary
Start.bat               # mở ứng dụng
```

Yêu cầu: Python 3.11+ (đã có `Add to PATH`) và Node.js LTS.

Chạy engine bằng tay (không cần Electron):

```
python -m bcr --game baccarat
{"id":1,"method":"state"}          # gõ JSON mỗi dòng, Enter
```

Đọc 1 file lệnh: `python -m bcr --game baccarat < lenh.jsonl`

## Giao thức engine

| Loại | Dạng |
|---|---|
| Yêu cầu | `{"id":1,"method":"tool.start","params":{}}` |
| Trả lời | `{"id":1,"ok":true,"result":{...}}` hoặc `{"id":1,"ok":false,"error":"..."}` |
| Sự kiện | `{"event":"state","data":{...}}`, `{"event":"log","data":{...}}` |

Một tiến trình engine, một profile Chrome và một file phiên:

| Trò | Cổng CDP | Profile | File phiên |
|---|---|---|---|
| Baccarat | 9222 | `chrome_profile/` | `bcr_state_baccarat.json` |

Lệnh: `chrome.open|attach|close|window`, `lobby.enter|sweep|back`,
`tables`, `table.enterValid|cancelEnter|open|select|leave`, `tool.start|stop`, `scan`,
`chips.read`, `config.get|set`, `session.new|reset|recover`.
UI chỉ gọi `table.enterValid` (nút duy nhất); `table.open`/`table.select` giữ lại cho script.

## Phương pháp 1-1 (xen kẽ B/P)

Vào bàn là tool tự đánh **1 tay Banker, 1 tay Player, 1 tay Banker…** tới hết phiên —
**không** xét bên mạnh, **không** xét cầu thực tế, **không** có khối 3-3 / 3-2:

| Tình huống | Bậc gấp thếp | Cửa tay kế tiếp |
|---|---|---|
| Thắng | về bậc 1 | đảo cửa (B ↔ P) |
| Thua | lên bậc kế tiếp | đảo cửa (B ↔ P) |
| Hoà (Tie) | giữ nguyên bậc | giữ nguyên cửa |

Nguồn duy nhất: `bcr/models/method.py` (`METHOD_NAME = "1-1"`, `DEFAULT_LADDER`,
`plan()`, `advance()`, `settle()`). Model, controller và UI đều đọc từ đây.

### Thang gấp thếp mặc định (8 bậc)

Đơn vị hiển thị trên sàn là **VN2** (1 VN2 = 1.000đ):

| Bậc | Vốn (VN2) | Tiền |
|---|---|---|
| 1 | 20 | 20.000đ |
| 2 | 40 | 40.000đ |
| 3 | 80 | 80.000đ |
| 4 | 160 | 160.000đ |
| 5 | 320 | 320.000đ |
| 6 | 640 | 640.000đ |
| 7 | 1.280 | 1.280.000đ |
| 8 | 2.560 | 2.560.000đ |
| **Tổng** | **5.100** | **5.100.000đ** |

Thua hết bậc 8 → `plan()` trả `action="halt"` và tool dừng, không tự tăng thêm bậc.

### Chế độ thang cược (tab Cấu hình)

| `ladder_mode` | Ý nghĩa |
|---|---|
| `manual` | Điền từng bậc bằng tay rồi **Lưu phương pháp & thang** |
| `multiply` | Nhập **bậc 1**, tool tự sinh 8 bậc gấp đôi (`multiply_ladder`) |
| `fixed` | Cùng một mức cược cho mọi tay (`flat_stake`) |

## Điều kiện vào bàn

`bcr/models/method.py::check_table` chỉ kiểm **số tay đã đi** trong khoảng
`min_hands`–`max_hands` (mặc định 10–50). Phương pháp 1-1 không phụ thuộc cầu nên không
còn điều kiện bên mạnh / chênh lệch B-P / bệt khi chọn bàn.

Trước khi tính cầu, danh sách bàn còn phải qua cổng kỹ thuật: cầu WebSocket khớp
cầu DOM (`road_match`/`screen_stable ≥ 2`), dữ liệu ≤ 30s, không xung đột, đúng
`game_type = baccarat`, có mã bàn, và bỏ qua bàn Siêu Tốc Độ.

**Thoát bàn khi bệt**: đang chơi mà vệt hiện tại chạm `live_exit_streak` (mặc định 7 tay)
thì tool rời bàn và quét bàn khác — đây là ngưỡng thoát, không phải điều kiện chọn bàn.

## Một nút duy nhất: VÀO BÀN HỢP LỆ

Tab **Bàn chơi** chỉ còn một nút thao tác bàn: **VÀO BÀN HỢP LỆ** (`table.enterValid`).
Các nút cũ (Gắn Chrome / Nhận tab sảnh / Quét sảnh / Đọc lại bàn / Rời bàn / Đóng Chrome)
và nút chọn bàn theo dòng đã bị bỏ để không bấm nhầm. Danh sách bàn bên dưới chỉ còn
để **xem** (đạt/loại + lý do), không bấm được.

Một lần bấm chạy đúng chuỗi:

1. Gắn Chrome CDP 9222 (chưa mở thì mở Chrome `chrome_profile` rồi yêu cầu đăng nhập
   + mở sảnh, bấm lại nút).
2. Nhận tab sảnh → quét toàn bộ danh sách (cuộn hết, gom mọi ô bàn đã render).
3. Chấm từng bàn bằng điều kiện số tay + các cổng kỹ thuật ở trên, bỏ bàn Siêu Tốc Độ và
   bàn vừa rời; chọn bàn "giữa khung" (`|số tay − 30|` nhỏ nhất).
4. Bấm đúng ô bàn bằng pointer event thật, chờ URL có `table_id`.
5. Xác nhận cầu trong bàn: 2 lần đọc giống nhau, `source=evo-bead`, cùng shoe với cầu
   đã lọc ở sảnh.
6. **Dừng ở đây**: bàn đã vào & đã xác nhận nhưng **chưa đặt cược** (`sim.running = false`,
   `pending = null`, không arm). Dòng trạng thái ghi `Đã vào bàn hợp lệ … CHƯA đặt cược`.

Nếu không có bàn đạt, tool tự quét lại (lượt đầu ngay, sau đó mỗi 15 giây) tới khi hết
`ENTER_VALID_TIMEOUT = 240` giây thì báo lỗi và dừng chế độ. Nút **Dừng tự quét** trên
băng trạng thái huỷ ngay (`table.cancelEnter`).

Bật **Chạy tool** khi đang đứng ở bàn đã xác nhận thì chuyển thẳng sang theo dõi, vẫn
chờ hết tay hiện tại rồi mới chốt kế hoạch và (nếu bật `auto_place`) mới bấm chip.
Bật tool khi chưa có bàn thì tool tự chạy chuỗi "vào bàn hợp lệ" rồi theo dõi.

Kiểm tra luồng này ở mức chọn bàn:

```
python -m unittest tests.test_enter_valid_flow -v     # harness: sảnh giả + Chrome giả,
                                                      # chạy đúng pipeline thật
python tools/kiem_tra_chon_ban.py                     # THỰC TẾ: đọc sảnh Chrome của bạn,
                                                      # in bảng đạt/loại (không bấm gì)
python tools/kiem_tra_chon_ban.py --vao-ban           # bấm ô bàn đầu tiên đạt + xác nhận
                                                      # cầu, KHÔNG đặt cược
```

## Chống tính tiền oan khi đặt chip

- **Chờ ván mới**: vào bàn xong tool chờ kết quả ổn định (2 lần đọc giống nhau) của tay
  hiện tại rồi mới chốt kế hoạch cho tay kế tiếp.
- **Xác nhận đã đặt chip**: chỉ tính tay khi web xác nhận lượt bấm chip thành công; lượt
  bấm trượt (khay chip chưa hiện, cửa sổ cược đóng, xác nhận muộn hơn lúc có kết quả) thì
  tay đó **không** vào sổ vốn, không đổi bậc, không đổi cửa (`_skip_unplaced_hand`).
- **Giới hạn bỏ tay**: bỏ liên tiếp `PLACE_SKIP_LIMIT` tay thì tool tự dừng để kiểm tra.
- **Cầu nhảy tay**: cầu không nối tiếp (đổi shoe, nhảy tay) thì tool dừng, không tính bù.

## Luật 1-1 và tiền

- Banker thắng ăn 0.95 (`E.PAYOUT`), Player ăn 1.00, Tie 8.00; tool không đánh Tie.
- Hoà là **hoàn tiền** (`settle` trả `delta = 0`, `hold`): vốn giữ nguyên, bậc giữ nguyên.
- Thuật toán chốt kế hoạch nằm ở `bcr/models/method.py::plan`; vòng đời phiên ở
  `bcr/models/live_sim.py`.

## Kiểm thử

```
python -m unittest discover -s tests -t . -q        # toàn bộ (không cần Chrome)
node tests/test_reader.cjs                          # script đọc DOM
```

## Ghi chú

- **BẮT BUỘC — push GitHub sau mỗi lần cập nhật/sửa đổi.** Mọi thay đổi (code, cấu hình
  mặc định, tài liệu) phải được commit và đẩy lên `origin/main` ngay trong cùng lần làm
  việc, không để tồn đọng. Cách nhanh:

  ```
  push_update.bat                          # commit tự động "Update <thời gian>" rồi push
  push_update.bat "mô tả thay đổi"         # commit kèm mô tả
  powershell -ExecutionPolicy Bypass -File .\push_update.ps1 "mô tả"
  ```

  Script đã `git add -A` (tôn trọng `.gitignore`: `chrome_profile*`, `bcr_state*.json`,
  `drivers`, `__pycache__`…), commit rồi `git push origin main`. Không chạy test tự động
  trừ khi người dùng yêu cầu; hãy báo rõ nếu chưa chạy. Nếu push lỗi (mạng, xác thực hoặc
  xung đột nhánh), báo chính xác trạng thái và không nói là đã push thành công.
- **Đóng Chrome bằng tay**: app dò cổng CDP (mỗi 2 giây, có cache 3 giây) nên phát hiện
  ngay, đổi nút thành **“Mở lại Chrome”**, tạm dừng phiên và xoá danh sách bàn cũ. Bấm
  lại là Chrome mở lại bình thường — không cần khởi động lại app.
- `TỰ ĐẶT CHIP THẬT` mặc định **bật** (giữ hành vi bản Tk cũ); tắt switch này nếu
  chỉ muốn xem kế hoạch — khi tắt, tay không được ghi sổ (chỉ tính tay web đã xác nhận).
- Baccarat cần Chrome có CDP: bấm **Mở Chrome** trong app rồi đăng nhập/vào sảnh, sau đó
  bấm **VÀO BÀN HỢP LỆ** (tool tự gắn tab sảnh đang mở).
- Vanguard/anti-cheat không liên quan: ứng dụng chỉ điều khiển Chrome qua CDP.
