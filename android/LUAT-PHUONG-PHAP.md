# LUẬT PHƯƠNG PHÁP — 1-1 xen kẽ + gấp thép 8 bậc

Nguồn sự thật duy nhất trong code: `app/src/main/java/com/bcr/android/model/Method.kt`
(`NAME = "1-1"`, `DEFAULT_LADDER`, `plan()`, `advance()`, `settle()`).

Bản desktop tương đương: `Desktop/BCR/bcr/models/method.py`. Hai bên được khoá bằng
`ParityTest.kt`.

---

## 1. Luật cửa (đánh xen kẽ)

Vào bàn là đánh được ngay. **Không** đọc cầu để quyết định cửa.

| Tay | Cửa đánh |
|---|---|
| 1 | **Banker** |
| 2 | **Player** |
| 3 | **Banker** |
| 4 | **Player** |
| … | xen kẽ mãi |

- **Không** xét bên mạnh, **không** xét chênh lệch B–P.
- **Không** có khối 3-3 / 3-2 (đã xoá khỏi cả hai dự án).
- **Không** xét bệt để chọn cửa.
- **Không** đánh **Tie** (Hoà) bao giờ.

## 2. Luật bậc (gấp thếp)

| Kết quả tay vừa rồi | Bậc tay kế tiếp | Cửa tay kế tiếp |
|---|---|---|
| **Thắng** | về **bậc 1** | **đảo** cửa (B ↔ P) |
| **Thua** | **lên bậc kế** | **đảo** cửa (B ↔ P) |
| **Hoà (Tie)** | **giữ nguyên bậc** | **giữ nguyên cửa** |

Ghi chú:

- Hoà là **hoàn tiền**: vốn không đổi, bậc không đổi, cửa không đổi → tay hoà chỉ làm
  chậm lại, không phá thang.
- Vì cửa luôn đảo sau mỗi tay **có kết quả**, dù thắng hay thua thì cửa vẫn xen kẽ
  B-P-B-P. Chỉ có **bậc** phụ thuộc thắng/thua.

## 3. Thang gấp thếp mặc định (8 bậc)

Đơn vị trên sàn là **VN2** (1 VN2 = 1.000đ). `Method.DEFAULT_LADDER`:

| Bậc | VN2 | Tiền | Luỹ kế đã thua |
|---|---|---|---|
| 1 | 20 | 20.000đ | 20.000đ |
| 2 | 40 | 40.000đ | 60.000đ |
| 3 | 80 | 80.000đ | 140.000đ |
| 4 | 160 | 160.000đ | 300.000đ |
| 5 | 320 | 320.000đ | 620.000đ |
| 6 | 640 | 640.000đ | 1.260.000đ |
| 7 | 1.280 | 1.280.000đ | 2.540.000đ |
| 8 | 2.560 | 2.560.000đ | **5.100.000đ** |

- Tổng vốn cần cho trọn thang: **5.100.000đ**.
- Thua hết bậc 8 → `plan()` trả `action = "halt"`, app **dừng**, **không** tự thêm bậc.

## 4. Tính tiền

| Cửa | Tỉ lệ ăn | Ghi chú |
|---|---|---|
| **Banker** | **0,95** | ăn 95% tiền cược |
| **Player** | **1,00** | ăn đủ |
| **Tie** | 8,00 | app **không** đánh |

Công thức trong `MoneyBook.settle()`:

```
Thắng Banker:  lãi = +cược × 0,95
Thắng Player:  lãi = +cược × 1,00
Thua:          lãi = −cược
Hoà:           lãi = 0        (hoàn tiền)
```

Cộng dồn vào `balance`; `profit = balance − startCapital`.

## 5. Chế độ thang cược

| Chế độ | Ý nghĩa | Cờ trong code |
|---|---|---|
| **Gấp thép** (mặc định) | Dùng `DEFAULT_LADDER` | `fixedStake = 0` |
| **Cược đều** | Mỗi tay đúng một mức, không lên bậc | `fixedStake > 0` |
| **Tự sinh thang** | Nhập bậc 1, tự nhân đôi 8 bậc | `multiplyLadder(base)` |

`multiplyLadder(base)` = `base × 1, 2, 4, 8, 16, 32, 64, 128` — luôn đúng 8 bậc.

## 6. Ràng buộc bảng vốn

`Method.ladderStakes()` kiểm tra:

- Số bậc trong khoảng `LADDER_LOWER = 1` … `LADDER_UPPER = 20`.
- Mỗi bậc là số hữu hạn và **> 0**.
- Sai → ném lỗi, không chạy với bảng vốn hỏng.

## 7. Điều kiện vào bàn

`Method.checkTable(hands, minHands, maxHands)` **chỉ** xét số tay đã đi.

| Hằng số | Giá trị | Lý do |
|---|---|---|
| `MIN_HANDS` | **0** | Người dùng tự mở bàn → vào là đánh được |
| `MAX_HANDS` | 50 | Chỉ là mốc tham khảo, **không** chặn |
| `DEFAULT_EXIT_STREAK` | **0** = tắt | Bệt dài không được cắt ngang thang |

Khác bản desktop (10–50 tay, thoát khi bệt 7): desktop **tự chọn bàn** nên cần cửa sổ
này; app Android do người dùng tự mở bàn nên nếu chặn sẽ không vào được bàn chạy lâu.

## 8. Bất biến (không được phá)

1. Tay chỉ được ghi sổ khi **web đã xác nhận** chip vào ô cược.
2. Bấm trượt → **không** tính tay, **không** đổi bậc, **không** đổi cửa.
3. Cầu không nối tiếp (đổi shoe, nhảy tay) → **dừng**, không tính bù.
4. Bỏ liên tiếp `PLACE_SKIP_LIMIT = 3` tay → tự dừng để kiểm tra.
5. Không đặt chồng chip ván trước: chờ bàn sạch chip rồi mới bấm.
