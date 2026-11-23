# LUẬT VẬN HÀNH — luồng chạy trên Android

Nguồn sự thật: `core/Runner.kt`, `model/Session.kt`, `web/Bridge.kt`.

---

## 1. Luồng người dùng (đúng thứ tự)

```
Mở app
  └─ app mở sẵn https://onbet.com/en/live (hoặc link bạn đã lưu)
       └─ BẠN tự đăng nhập
            └─ BẠN tự bấm vào một bàn Baccarat
                 └─ BẠN bấm BẮT ĐẦU  ← chỉ một lần
                      └─ app tự làm phần còn lại
```

Sau khi bấm **BẮT ĐẦU**, app tự:

1. Chờ bàn và cầu tải xong (**tối đa 45 giây**).
2. Xác định cầu đã ổn định (2 lần đọc giống nhau).
3. Chốt kế hoạch tay kế tiếp (cửa + bậc + số tiền).
4. Chờ ván hiện tại kết thúc, bàn **sạch chip**.
5. Bấm chip vào ô cược **bằng cảm ứng thật** trên WebView.
6. Xác nhận chip đã vào ô (đọc lại DOM), rồi ghi sổ vốn.
7. Lặp lại từ bước 2.

## 2. Tự vào bàn từ sảnh

Nếu bấm BẮT ĐẦU khi đang đứng ở **sảnh**:

1. Quét `[data-role="table-tile"]`, lấy thông tin bàn từ React fiber.
2. **Bỏ** bàn Lightning / bàn luật đặc biệt (bảng trả thưởng khác làm sai phép cân tiền).
3. Chỉ nhận bàn có `game_type` chứa `baccarat` (hoặc để trống).
4. Bấm vào ô bàn, chờ `table_id` xuất hiện trong URL.
5. Đọc lại cầu; nếu chưa có cầu thì chờ tiếp trong hạn 45 giây.

Nếu sảnh **chỉ** có bàn đặc biệt → app **không tự vào**, báo rõ để bạn tự chọn bàn.

## 3. Phân biệt "sảnh" và "trong bàn"

Đây là chỗ dễ sai nhất trên site mới:

| Dấu hiệu | Ý nghĩa |
|---|---|
| Có `[data-role="table-tile"]` | đang ở **sảnh** |
| URL có `table_id=` | đang ở **trong bàn** |
| Có `[data-role="Bead-road"]` | bàn đã dựng xong cầu |

**Quan trọng**: bàn nằm trong **iframe**, và khung trên cùng cũng có `table_id` trong URL
dù nó **rỗng**. Vì vậy app chỉ chấp nhận trả lời từ khung **đọc được ít nhất 1 dãy cầu** —
nếu không sẽ "thấy bàn" giả rồi báo sai.

## 4. Chống tính tiền oan (bất biến)

| Tình huống | Xử lý |
|---|---|
| Bấm chip trượt / không xác nhận được | **Không** ghi sổ, **không** đổi bậc, **không** đổi cửa |
| Bàn còn chip ván trước | Chờ tới 22 giây cho sạch chip rồi mới bấm (tránh đặt chồng) |
| Cầu nhảy tay / đổi shoe | **Dừng**, không tính bù |
| Bỏ liên tiếp 3 tay (`PLACE_SKIP_LIMIT`) | Tự dừng để kiểm tra |
| Vượt hạn mức bàn | **Chặn trước khi bấm** (`over_table_limit`) |
| Cầu chưa ổn định | Chờ, không chốt kế hoạch sớm |

## 5. Đặt chip: cơ chế thật

1. Chọn mệnh giá trong khay: `[data-role="chip-stack"] [data-role="chip"][data-value]`.
2. Bấm vào ô cược: `[data-role="bet-spot-Banker|Player|Tie"]`.
3. Mỗi chip là **một cặp bấm** (chọn mệnh giá → bấm ô).
4. Chia số tiền thành các mệnh giá **có thật trên khay** (thuật toán ít chip nhất).
5. Xác minh: tổng chip trong ô tăng đúng bằng số tiền dự định.

Ví dụ với khay thật `20 / 50 / 250 / 1000 / 5000 / 10000`:

| Cần đặt | Chia thành |
|---|---|
| 20 | 20 |
| 40 | 20 + 20 |
| 80 | 20 ×4 |
| 160 | 50 + 50 + 20 + 20 + 20 |
| 320 | 250 + 50 + 20 |
| 640 | 250 + 250 + 50 + 50 + 20 + 20 |
| 1.280 | 1000 + 50×4 + 20×4 |
| 2.560 | 1000 + 1000 + 250 + 250 + 20×3 |

## 6. Cơ chế bấm (hai lớp)

| Lớp | Khi nào dùng |
|---|---|
| **Sự kiện tổng hợp** (`pointerdown/up`, `mousedown/up`, `click`) | mặc định, nhẹ và nhanh |
| **Cảm ứng thật** (`MotionEvent` trên WebView) | khi site bỏ qua sự kiện tổng hợp |

Script tự báo `needs_real_touch` → app chuyển sang bấm cảm ứng thật tại đúng toạ độ CSS
đã quy đổi sang toạ độ khung gốc.

## 7. Cấu hình trên giao diện

Hai ô nhập + nút **ÁP DỤNG**:

| Ô | Ý nghĩa | Ví dụ |
|---|---|---|
| **Vốn** | Vốn ban đầu, theo **đơn vị VN2** (1 VN2 = 1.000đ) | `6000` = 6.000.000đ |
| **Số tay** | Số bậc gấp thếp, hay số tay của thang | `10` → 10 bậc 20,40,…,10.240 |

Quy tắc:

- Bậc 1 luôn = **20 VN2** (20.000đ) — mệnh giá chip nhỏ nhất của bàn.
- Số tay `n` → bậc cuối = `20 × 2^(n-1)`.
- Số tay hợp lệ: **1 … 20**. Nhập 0 hoặc để trống = mặc định **8**.
- Đổi cấu hình **chỉ khi đã DỪNG** (đang chạy mà đổi thì app từ chối, tránh lệch sổ vốn).

Ví dụ: vốn `10`, số tay `10` → thang `10 · 20 · 40 · … · 5.120`, tổng `10.230`.
App sẽ cảnh báo nếu vốn nhỏ hơn tổng thang (không đủ chạy hết thang).

## 8. Các nút

| Nút | Việc |
|---|---|
| **BẮT ĐẦU** | Bắt đầu tự đọc + tự đặt |
| **DỪNG** | Dừng ngay |
| **ĐỌC BÀN** | Chỉ đọc cầu, không đặt tiền |
| **TỰ ĐẶT** | TẮT = chỉ xem kế hoạch |
| **PHIÊN MỚI** | Xoá sổ, về bậc 1 |
| **MỞ SẢNH** | Về trang đang dùng (giữ lâu = đổi site) |
| **MỞ LINK** | Mở link đang dán trong ô "Link site" của panel |
| **✕ / BCR** | Đóng / mở panel |

## 9. Panel không được che bàn

- App **mở ở dạng thu gọn**: chỉ một bong bóng tròn `BCR` ở **đáy phải**.
- **Chạm** bong bóng để mở panel; bong bóng đổi thành **✕** đỏ, chạm lần nữa để đóng.
- **Giữ và kéo** bong bóng để dời tới chỗ trống (hoạt động như nút trợ năng).
- Panel neo ở **TRÊN** màn hình, giới hạn **62% chiều cao** và cuộn được, nên không che bàn
  và **không bị bàn phím che** khi đang nhập liệu (app tự co panel + cuộn ô đang nhập).

## 10. Đổi site / sửa lỗi "trang web không khả dụng"

Trong panel cấu hình có **ô "Link site"** + nút **MỞ LINK**:

1. Dán link site đang dùng được (hoặc tên miền trần — app tự thêm `https://`).
2. Bấm **MỞ LINK** → app mở ngay và **lưu làm site mặc định** cho các lần sau.

Khi trang không tải được, app hiện thông báo lỗi kèm hướng dẫn dán link mới vào ô này.
Cách khác: giữ lâu nút **MỞ SẢNH** → hộp thoại nhập link. App cũng nhớ **trang cuối** đã mở
để "MỞ SẢNH" quay lại đúng chỗ.
