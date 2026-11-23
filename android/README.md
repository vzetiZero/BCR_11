# BCR 1-1 · Android (Kotlin)

Bot bàn **Baccarat** chạy trên **điện thoại Android**, viết bằng **Kotlin**. App nhúng sảnh
web thật trong `WebView`; người dùng tự đăng nhập, app tự vào bàn, tự đọc cầu và tự đặt
chip theo phương pháp **1-1 xen kẽ + gấp thép 8 bậc**.

> Dự án desktop (Electron + Python) trong `Desktop/BCR` chỉ còn là **bản tham chiếu** cho
> logic. Bản chạy chính thức là app Kotlin này.

## Mục lục tài liệu

| File | Nội dung |
|---|---|
| `README.md` | Tổng quan, cách build/cài, luồng sử dụng |
| `LUAT-PHUONG-PHAP.md` | Luật 1-1, thang gấp thếp, cách tính tiền |
| `LUAT-VAN-HANH.md` | Luồng chạy, điều kiện vào bàn, chống tính tiền oan |
| `LUAT-DOM-SITE.md` | Hợp đồng DOM với site (Evolution r2), cách đọc chip/cầu |
| `GHI-CHU-KY-THUAT.md` | Kiến trúc code, build, kiểm thử, giới hạn đã biết |

## Cấu trúc

```
11BCRAndroid/
├─ app/src/main/java/com/bcr/android/
│  ├─ model/          logic thuần, không I/O
│  │  ├─ Method.kt        1-1: ladder, plan, advance, settle
│  │  ├─ MoneyBook.kt     sổ vốn, lãi/lỗ, chặn thua
│  │  ├─ Session.kt       vòng đời một phiên (vào bàn → đánh → dừng)
│  │  └─ Fmt.kt           định dạng tiền kiểu Việt Nam
│  ├─ web/
│  │  └─ Bridge.kt        cầu nối WebView ↔ Kotlin (đa khung)
│  ├─ core/
│  │  ├─ Runner.kt        vòng đọc → chốt kế hoạch → đặt chip
│  │  └─ SitePrefs.kt     nhớ link site người dùng
│  └─ ui/
│     ├─ MainActivity.kt  WebView + quyền, nhập link site
│     └─ ControlPanel.kt  bảng điều khiển nổi
├─ app/src/main/assets/
│  └─ bcr_core.js     script tiêm vào MỌI khung: đọc cầu, đọc chip, bấm chip
└─ app/src/test/java/com/bcr/android/
   ├─ MethodTest.kt   test logic 1-1
   └─ ParityTest.kt   đối chiếu với bản Python desktop
```

## Build

Yêu cầu: Android Studio (kèm JDK trong `jbr`), Android SDK, `minSdk 26`.

```powershell
cd C:\Users\Administrator\Desktop\11BCRAndroid
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat --no-daemon --console=plain assembleDebug
```

APK ra ở `app/build/outputs/apk/debug/app-debug.apk`.

Bản release (ký bằng khoá debug để cài lên máy khác được):

```powershell
.\gradlew.bat --no-daemon --console=plain assembleRelease
```

> `local.properties` là **bắt buộc**:
> `sdk.dir=C\:\\Users\\Administrator\\AppData\\Local\\Android\\Sdk`
> (`ANDROID_HOME` để trống cũng được, nhưng file này phải có).

## Cài lên điện thoại

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Hoặc copy file `.apk` vào máy rồi mở; cần bật **"Cài từ nguồn không xác định"** cho trình
quản lý file.

## Sử dụng (đúng thứ tự)

1. Mở app. Lần đầu app hỏi **link vào site** — dán link trang chủ (hoặc link sảnh) của site
   bạn chơi rồi bấm **MỞ**. App nhớ link này cho các lần sau.
2. **Tự đăng nhập** trên web trong app. App không lưu, không gửi tài khoản đi đâu.
3. **Tự vào một bàn Baccarat** (bấm tay như bình thường).
4. Bấm **BẮT ĐẦU**. Từ đây app tự làm hết:
   - chờ bàn/cầu tải xong (tối đa ~45 giây),
   - chốt kế hoạch, bấm chip thật vào ô cược,
   - cộng/trừ vốn theo kết quả, tự lên bậc khi thua.

Nếu đang đứng ở **sảnh** mà bấm BẮT ĐẦU, app tự tìm và mở một bàn Baccarat chuẩn.

Đổi site: **giữ lâu** nút **MỞ SẢNH** rồi dán link mới.

## Nút trên bảng điều khiển

| Nút | Việc |
|---|---|
| **BẮT ĐẦU** | Bắt đầu/re tiếp tục tự đọc và tự đặt chip |
| **DỪNG** | Dừng ngay, không đặt thêm |
| **ĐỌC BÀN** | Chỉ đọc thử cầu, không đặt tiền |
| **TỰ ĐẶT: BẬT/TẮT** | Tắt = chỉ xem kế hoạch, không bấm chip |
| **PHIÊN MỚI** | Xoá lịch sử phiên, về bậc 1 với vốn ban đầu |
| **MỞ SẢNH** | Quay lại trang đang dùng (giữ lâu = đổi site) |
| **▾ / ●** | Thu gọn / mở bảng điều khiển |

## Cảnh báo quan trọng: hạn mức bàn

Bàn **phải cho phép mức cược của bậc 8** thì thang gấp thép mới chạy đủ.
Ví dụ bàn "Xclusive Speed Baccarat B" chỉ nhận **tối đa 100.000/cửa**:

| Bậc | Mức cược | Với hạn mức 100.000 |
|---|---|---|
| 1 | 20.000 | đặt được |
| 2 | 40.000 | đặt được |
| 3 | 80.000 | đặt được |
| 4 | 160.000 | **vượt hạn mức** |
| 5–8 | 320.000 – 2.560.000 | **vượt hạn mức** |

App đọc bảng **BET LIMITS** của bàn và **chặn trước khi bấm** nếu mức vượt hạn mức
(`reason: over_table_limit`), thay vì bấm dở dang. Hãy chọn bàn có hạn mức ≥ **2.560.000**
cho mỗi cửa, hoặc giảm thang ở `Method.DEFAULT_LADDER`.

## Trạng thái kiểm chứng

- **Đã chạy thật**: mở được bàn Evolution r2 trên site mới, đọc được cầu (44–46 ô) và đọc
  được khay chip 20/50/250/1000/5000/10000, đọc được hạn mức bàn, đọc được số dư.
- **Đã sửa nhờ kiểm chứng đó**: đọc số tiền sai do ký tự `VN2`; số dư đọc sai role; app
  tưởng "chưa vào bàn" khi bàn còn đang tải.
- **Chưa chạy thật**: một phiên đặt chip hoàn chỉnh trên điện thoại (chưa cài lên máy).
- Xem chi tiết ở `GHI-CHU-KY-THUAT.md`.

## Ghi chú

- **BẮT BUỘC — push GitHub sau mỗi lần sửa.** Commit rồi `git push origin main` lên
  `BCR_11` (`https://github.com/vzetiZero/BCR_11.git`), không phải repo `BCR.git` cũ.
- Không chạy test tự động trừ khi được yêu cầu; nếu chưa chạy thì phải báo rõ.
