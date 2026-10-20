# ZynTap

App Android viết bằng Kotlin + Jetpack Compose. Build APK hoàn toàn trên GitHub, không cần cài Android Studio.

## Build APK trên GitHub

1. Tạo repo mới trên GitHub, đưa **toàn bộ** nội dung thư mục này lên (nhớ cả thư mục ẩn `.github`).
2. Vào tab **Actions** → workflow **Build ZynTap APK** → chạy xong (tự chạy mỗi lần đẩy code lên `main`,
   hoặc bấm **Run workflow**).
3. Mở lần chạy đó, kéo xuống mục **Artifacts**, tải `ZynTap-<phiên bản>` (file zip chứa APK).
4. Giải nén, cài file `.apk` lên điện thoại (cho phép "cài từ nguồn không xác định").

## Đổi phiên bản (cập nhật không phải gỡ cài lại)

Phiên bản mặc định: **1.00.25**. Có 3 cách đổi:

- Sửa dòng `appVersion=1.00.17` trong `gradle.properties`, rồi đẩy code lên.
- Vào Actions → **Run workflow** → gõ phiên bản (vd `1.00.02`).
- Tạo tag `v1.00.02` → bản APK sẽ tự đăng ở mục **Releases**.

Để bản mới cài đè được bản cũ, cần đủ 2 điều kiện (đã làm sẵn):

1. **Cùng khoá ký**: `app/zyntap-release.jks` được giữ cố định cho mọi bản build. Đừng xoá/đổi file này,
   nếu đổi thì Android sẽ báo xung đột và bắt gỡ app cũ.
2. **versionCode luôn tăng**: tự tính từ phiên bản (`1.00.01` → 1000001, `1.00.02` → 1000002...).
   Nhớ luôn đặt phiên bản mới **lớn hơn** bản đang cài.

> Khoá ký nằm trong repo cho tiện build. Nếu để repo công khai, người khác có thể ký app giả cùng khoá;
> với app dùng cá nhân thì ổn, còn muốn phát hành rộng thì nên chuyển khoá vào GitHub Secrets.

## Trạng thái hiện tại

Đã có: giao diện, logo/icon, tab hướng dẫn + nhập mã 6 số, chế độ chọn app (thêm/bỏ, lưu lại sau khi tắt app),
phiên bản tuỳ chỉnh.

Ghép nối gỡ lỗi Wi-Fi dùng thư viện libadb-android (SPAKE2 trên TLS 1.3, qua JitPack); khoá ADB lưu
trong thư mục riêng của app (`data/ZynAdbConnectionManager.kt`). Menu nổi có tab 1 tạo nút main và tab 2 liên kết kịch bản điểm nhấn.

## Menu nổi — 3 tab

- **Tạo nút chính**: tạo các nút main tròn (main1, main2, ...). Mỗi nút chỉnh được tên, chế độ (Nhấn / Giữ / Thả),
  độ mờ, kích thước. Ấn thanh nút để mở bảng chỉnh + kéo thả nút tròn trên màn hình (menu không bị đóng nhầm);
  ấn lại thanh để thoát chỉnh sửa. Nút "Tạo nút mới" mở bảng cài đặt riêng.
- **Chỉnh điểm nhấn**: dropdown chọn main; editor đa dòng có cuộn, sửa trực tiếp và tự lưu riêng theo main.
  Chạm X/Y lần đầu để mở ghim nền trong suốt; kéo ghim, nhả rồi chạm ghim để chọn tọa độ tại dấu cộng/mũi ghim.
  Chạm lại X/Y khi ghim đang mở để chuyển sang nhập tay. Chọn ghim xong hiện bàn phím; Enter thêm `x, y` và xuống dòng.
  Ô Delay nhận mili giây; Enter thêm `300ms` và xuống dòng. Có báo lỗi dòng sai/toạ độ ngoài màn hình.
  Dòng Click point ghi `Click x, y` (định dạng cũ `x, y` vẫn chạy). **Multiclick point** nhập y hệt Click point, mỗi lần
  1 điểm, Enter ghi `Multiclick x, y`; các dòng Multiclick nằm liền nhau được click CÙNG MỘT LÚC (tối đa 10 điểm),
  chỉ 1 dòng thì click 1 điểm. Chạm ô X/Y: hiện ghim → chọn xong bàn phím tự nhập → chạm lại đúng ô đó thì lại hiện ghim;
  đổi sang ô khác thì chỉ mở bàn phím.
  **Tốc độ click**: cả kịch bản chạy ngay trong helper (một lệnh ADB duy nhất), giữ = 0ms, nghỉ giữa click = 0ms;
  giữa các click không có delay nào trừ các dòng `300ms` do người dùng tự đặt.
- **Cài cấu hình**: vẫn giữ placeholder, không nằm trong đợt cập nhật này.
- **Debug log** (tab 5): ô in log các lỗi của app — bơm/click, ADB, helper cảm ứng, tâm ảo, dọn dẹp khi tắt, crash.
  Nút **Chỉ lỗi** lọc cảnh báo + lỗi, **Kiểm tra** ghi trạng thái hiện tại (ADB, helper bơm, quyền, tâm ảo) vào log,
  **Sao chép** chép toàn bộ log kèm thông tin máy để dán gửi người sửa lỗi, **Xóa** (chạm 2 lần). Log được lưu ra file
  nên vẫn xem được sau khi app/dịch vụ tắt hoặc crash (`files/zyntap_debug.log`).
  Màn hình chính của app cũng có một ô **Debug log** giống vậy (cuối trang) để xem lại log khi menu nổi đã mất.

### Dùng nút main

Kết nối ADB Wi-Fi trong app, tạo main ở tab 1, chọn main và nhập điểm ở tab 2, sau đó thu menu bằng dấu trừ.
Đặt điểm bên ngoài vùng nút main và tay nắm menu, tránh nút tự kích hoạt hoặc click vào chính cửa sổ ZynTap.

- Nhấn: bắt đầu một lượt khi ngón chạm xuống.
- Giữ: chạy lặp trong khi giữ; nhả hoặc bị hệ thống hủy chạm thì dừng các bước còn lại.
- Thả: chỉ chạy một lượt sau chạm xuống rồi nhả; ACTION_CANCEL không chạy.

Ví dụ:

```text
300ms
111, 111
500ms
112, 112
```

Chờ 300ms, click điểm đầu, chờ 500ms, click điểm sau. Delay không được dồn lên trước điểm đứng trước nó.
Nhiều delay liên tiếp được thực hiện theo thứ tự. Delay cuối danh sách vẫn được giữ, tạo khoảng nghỉ trước vòng tiếp ở mode Giữ.
Kịch bản không có point hoặc có dòng sai sẽ không chạy; không bỏ qua lỗi để tránh click nhầm.
Tọa độ dùng pixel màn hình hiện tại, không tự scale khi xoay màn hình; xoay màn hình sẽ dừng tác vụ, cần kiểm tra/chọn lại điểm.

### Kiểm thử và giới hạn

Bản 1.00.11: build release thành công; 25 unit test parser/gesture pass. Chưa kiểm thử tương tác giao diện,
IME, ADB Wi-Fi và giữ ngón thật trên thiết bị Android vật lý.

Click đi qua helper `shell/InputProxy.java` (ngón ảo chạy song song với ngón thật trong cùng một luồng chạm), không còn lệnh `input tap`
(xem "Thay đổi 1.00.25"). `300ms` là thời gian chờ yêu cầu giữa các bước, không bảo đảm chu kỳ tap chính xác 300ms.
Click lỗi / helper chưa sẵn sàng thì chỉ bỏ click đó, không có đường lui nào khác.
Không dùng text editor làm shell: chỉ tọa độ nguyên đã validate được đưa vào lệnh `TAP x y hold gap` gửi cho helper.


Code: `overlay/MainButton.kt` (dữ liệu + lưu), `MainButtonsTab.kt` (giao diện tab 1), `MainButtonOverlay.kt` (nút tròn nổi),
`FloatingDropdown.kt` (dropdown nổi dùng chung), `TextPromptLayer.kt` (hộp đổi tên).

## Cấu trúc

```
app/src/main/java/com/zyntap/app/
  MainActivity.kt, MainViewModel.kt
  data/   AppRepository.kt (danh sách app), AdbPairing.kt (ghép nối)
  shell/  InputProxy.java (helper chạy bằng app_process: grab cảm ứng + gộp luồng chạm)
  ui/     ZynTapApp.kt (khung + chuyển tab), HomeScreen.kt, PairScreen.kt,
          AppPickerSheet.kt, Components.kt, LucideIcons.kt, Theme.kt
app/src/main/res/   icon launcher, logo trong suốt (drawable-nodpi/logo_transparent.png)
```

## Thay đổi 1.00.12

- **Tab nhớ vị trí**: đóng menu nổi rồi mở lại vẫn ở tab đang chọn (trước đây menu bị dựng lại nên luôn về tab 1). Lưu trong `OverlayService` (`active_tab`).
- **Kết nối ADB**: thêm `data/AdbSession.kt`. Nút main tự nối ADB khi chưa có kết nối thay vì báo lỗi; kết nối được giữ sống;
  menu nổi tự nối lại ở nền (sau khi cập nhật app, bật lại gỡ lỗi Wi-Fi, đổi mạng). Lỗi nối thoáng qua không còn làm app
  "quên" ghép nối: chỉ khi máy từ chối khoá ở cả 3 lần thử mới yêu cầu ghép nối lại.

## Thay đổi 1.00.13

- **Nút nổi hiện tên**: nút tròn trên màn hình giờ hiện đúng tên nút (đổi tên trong menu là nút đổi chữ ngay). Chữ tự thu nhỏ / ngắt tối đa 3 dòng cho vừa lòng nút, nút càng nhỏ chữ càng nhỏ (`MainButtonView.fitLabel`).
- **Chạm xuyên nút** (công tắc trong bảng chỉnh của từng nút, cả bảng "Tạo nút mới"): bật thì chạm vào nút main vừa kích hoạt nút (Nhấn/Giữ/Thả như thường) vừa lọt xuống app/màn hình bên dưới.
  - Cửa sổ nút được đặt `FLAG_NOT_TOUCHABLE` nên ngón chạm đi thẳng xuống app dưới; app đọc vị trí ngón qua ADB quyền shell: `exec:cat /dev/input/eventN` rồi giải mã thẳng `struct input_event` nhị phân (file `TouchWatcher.kt`, tự nhận 24/16 byte/bản ghi; `getevent -lp` chỉ chạy 1 lần để dò node + khoảng toạ độ) để biết ngón có đặt lên nút không. Vì vậy cần ADB Wi-Fi đang kết nối.
  - Android 12+ chặn chạm xuyên qua cửa sổ phủ đục hơn 0.8 nên nút chạm xuyên bị đặt độ đục cửa sổ 0.8 (nhìn mờ hơn một chút).
  - Lúc đang chỉnh nút (kéo vị trí) nút vẫn nhận chạm bình thường; chỉnh xong mới xuyên lại.
  - Chưa kiểm thử trên máy thật: bộ giải mã input_event nhị phân + ánh xạ toạ độ/xoay màn hình, luồng ADB + cửa sổ phủ cần thử trên thiết bị.

## Thay đổi 1.00.29 — tab "Cài đặt" + thanh thông số nổi

- **Tab 6 "Cài đặt"** (`overlay/StatsBar.kt`): 1 dropdown chọn mục (FPS / Pin / Giờ / Dữ liệu mạng) + 1 công tắc bật/tắt mục đó. Ô xem trước ở trên cùng là thanh thật với số liệu thật.
- **Thanh thông số**: viên thuốc tối bán trong suốt, chỉ chứa các mục đang bật; số FPS đổi màu (xanh ≥55, vàng ≥30, đỏ), pin có biểu tượng tự vẽ (xanh dương khi sạc, vàng/đỏ khi yếu), mạng hiện ↓/↑ theo thời gian thực.
  - Menu thu/đóng: cửa sổ `FLAG_NOT_TOUCHABLE` (chạm xuyên hoàn toàn, nhỏ gọn, không che thao tác).
  - Menu mở: thanh có viền sáng + tay nắm, kéo được (menu mờ đi trong lúc kéo như kéo nút main); vị trí lưu theo tỉ lệ màn hình, xoay màn hình vẫn nằm đúng chỗ.
- **Nguồn số liệu**: pin = `ACTION_BATTERY_CHANGED`; mạng = `TrafficStats` (không cần quyền); giờ = giờ máy;
  **FPS = `dumpsys SurfaceFlinger --latency <layer game>` qua ADB Wi-Fi** (đếm khung thực sự lên màn hình trong 1s cuối; layer tìm tự động theo app mở từ ZynTap). Cần ADB đang nối, không thì hiện `--`.
- Cột tab bên trái tự co chiều cao tab khi card thấp để đủ 6 tab. Chưa chạy thử trên máy thật; Kotlin chưa biên dịch ở môi trường này — build trên GitHub Actions để xác nhận.

## Thay đổi 1.00.28 — sửa "hệ thống từ chối sự kiện" trên Android 14–16

Log: mọi `injectInputEvent` (kể cả DOWN 1 ngón) trả về `false` -> helper tự thoát, click bị bỏ.
Nguyên nhân: từ Android 14, system_server (InputDispatcher/InputVerifier) kiểm tra luồng chạm của sự kiện bơm không kèm targetUid, trạng thái khoá theo `deviceId`:
1. `ACTION_CANCEL` không có `FLAG_CANCELED` (0x20) -> bị từ chối.
2. Bơm bằng `deviceId` THẬT của tấm cảm ứng: chỉ cần một lần CANCEL/UP bị từ chối hoặc helper chết giữa chừng là deviceId đó kẹt "đang chạm"
   và mọi DOWN sau đó đều bị từ chối ("already down") tới khi khởi động lại máy.
Sửa trong `shell/InputProxy.java`: CANCEL có `FLAG_CANCELED`; sự kiện bơm dùng mã thiết bị ảo riêng (`injDev`), đổi mới mỗi lần helper chạy và mỗi lần
dựng lại luồng (bỏ hàng đợi cũ, CANCEL luồng cũ ở chế độ "soft" không tính lỗi); log in rõ `dev=` của sự kiện bị từ chối.
Chưa thử trên máy thật — cần build lại + chạy thử; nếu vẫn lỗi hãy gửi lại log.

## Thay đổi 1.00.25 — click chung một luồng, bỏ hẳn `input tap`

Các mục cũ bên dưới (1.00.14, 1.00.20) nhắc tới `input tap`, id 16..23, clone ngón gốc, nhường ngón: **đã bị thay bởi mục này**.

- **Bỏ id số cho click**: lệnh helper giờ là `TAP x y holdMs [gapMs]` (không còn số thứ tự, không còn `OK id` / `ERR id` trả về).
  Mã pointer không còn chia sẵn (ngón thật 0..15 / click 16..23) mà **helper tự cấp id nhỏ nhất đang trống** (0, 1, 2...) cho mọi ngón, thật hay ảo,
  giữ nguyên tới khi nhấc — đúng cách InputReader của Android làm. Game/engine chỉ nhận pointer id < 10 giờ thấy được click.
- **Không ép ngón thật lên đầu, không nhường nữa**: ngón nào chạm xuống trước thì đứng trước, ngón nào sau thì đứng sau.
  Xoá `yieldSynthToReal`, `DELIVERED_HOLD_MS`, trạng thái "nhấc tạm / chạm lại" của click. Click ảo và ngón thật chạy **song song trong cùng một luồng**
  (cùng danh sách `shown`, cùng `MotionEvent`); thứ tự phát đúng kiểu InputReader: UP -> MOVE -> DOWN.
- **Bỏ clone ngón gốc**: xoá `lastReal`; click dùng thông số cố định (`tapPointer`). `TouchDevice` vẫn giữ max áp lực/độ lớn chỉ để phát lại ngón THẬT cho đúng.
- **Xử lý chung cùng lúc**: không còn luồng riêng + `sleep` cho từng click. Một luồng `zyn-pump` giữ giờ DOWN/UP của click và nhịp trợ vuốt,
  mọi thay đổi đi qua cùng `sync()` dưới cùng khoá với ngón thật. Click xếp hàng FIFO (tối đa 16), mỗi lúc chỉ một ngón ảo, cách nhau >= 10ms.
- **Loại hẳn `input tap`**: xoá nhánh `shell:input tap` trong `PointRunner`, `inputTapAllowed()`, `inCatMode`, cơ chế click lại / retry (ở cả app lẫn helper).
  Click lỗi (helper chưa READY, thụ động, bộ phát từ chối, hết chỗ pointer, hàng đợi đầy, ADB gửi lỗi) thì **chỉ bỏ click đó**, ghi Debug log (giãn nhịp),
  và ép bơm lại helper nếu helper chưa sẵn sàng. Nếu helper không chạy được 3 lần liền vẫn lùi về đọc thô `cat` để nút main nhận ngón, nhưng lúc đó không click được.
- **Sửa lỗi kẹt/đơ khi vuốt sau khi click (EVIOCGRAB)** — các nguyên nhân tìm được trong code:
  1. Luồng đọc cảm ứng phải chờ `injectInputEvent` (cuộc gọi binder đồng bộ, có thể chậm cả chục ms khi game nặng) vì nó chạy NGAY trong lúc giữ khoá.
     Click đang chạy thì mỗi click lại chen vào khoá -> bộ đệm kernel tràn (`SYN_DROPPED`) -> ngón thật giật/đơ. Giờ sự kiện được dựng nhanh dưới khoá rồi
     xếp vào hàng đợi, luồng `zyn-inject` phát lần lượt đúng thứ tự; các `MOVE` liên tiếp được gộp lại. Khi thoát, hàng đợi được phát cạn trước khi nhả grab.
  2. Mất gói (`SYN_DROPPED`) có thể làm mất lệnh nhấc ngón -> ngón "ma" dính hệ thống tới khi nhấc tay. Giờ `BTN_TOUCH=0` dọn hết ngón kẹt, và slot đổi
     tracking id mà không thấy nhả thì nhả ngón cũ rồi chạm lại.
  3. Lệnh `ROT` (xoay màn hình) giờ áp dụng ngay (`sync()`), không chờ sự kiện cảm ứng kế tiếp.
- **Kiểm thử**: logic helper được chạy trong bộ giả lập (bộ kiểm tra chuỗi sự kiện theo luật của InputDispatcher) với 10 kịch bản: ngón thật + click chen vào,
  click trước ngón thật sau, cấp id nhỏ nhất, 2s vuốt 240Hz + click lặp, bộ phát chậm 30ms, hệ thống từ chối sự kiện -> dựng lại luồng, giãn cách click,
  chạm xuyên + gap, dọn ngón kẹt, nhiều ngón. **Chưa chạy trên máy Android thật**: `EVIOCGRAB`, hàm inject theo từng bản Android, và việc game cụ thể phản ứng ra sao
  với thứ tự pointer "chạm trước đứng trước" cần thử trên thiết bị. Phần Kotlin chưa được biên dịch ở môi trường này (không có Android SDK) — build trên GitHub Actions để xác nhận.

## Thay đổi 1.00.20 — bơm lại helper tự động

- Helper bơm lỗi / chưa READY -> tự **ép bơm lại, lặp** (giãn dần 8s -> 120s nếu vẫn lỗi, về 8s khi bơm được).
- Helper không báo READY sau 10s -> đóng và chạy lại, không đứng im.
- Lùi về `cat` chỉ tạm 30s rồi tự thử bơm lại (trước đây bỏ hẳn sau 3 lần lỗi).
- Debug log ghi từng bước vòng helper (chờ ADB, dò cảm ứng, chạy helper, READY...) và dòng "Vòng helper" trong báo cáo.

## Thay đổi 1.00.17

- **Tiêu đề tab Điểm nhấn / Cấu hình đứng yên khi vuốt**: trước đây tiêu đề nằm TRONG vùng cuộn nên trôi mất khi vuốt nội dung. Giờ `PointsTab.build()` / `ConfigTab.build()` trả về khung dọc: tiêu đề cố định ở trên, chỉ phần nội dung bên dưới là `ScrollView` (giống tab Nút chính).

## Thay đổi 1.00.16

- **Khung tiêu đề chung cho 3 tab**: file mới `overlay/TabHeader.kt` (`addTabHeader`) dựng phần đầu giống tab 1 (tên tab đậm + dòng phụ nhỏ, chừa chỗ cho nút mắt / - / X).
  Cả "Nút chính", "Điểm nhấn", "Cấu hình" đều dùng nó nên nội dung các tab bắt đầu cùng độ cao, không còn đụng nhóm nút ở góc.
- **Độ mờ nút main xuống được 0%** (`MainButton.MIN_ALPHA = 0`). Ở 0% và menu đóng: nút vô hình hoàn toàn (vẫn nhận chạm bình thường).
  Khi menu mở, nút luôn được vẽ tối thiểu 20% (`MainButton.MENU_MIN_ALPHA`) để còn thấy mà kéo/chỉnh; menu thu lại là nút biến mất.
  Bản xem trước trong bảng "Tạo nút mới" cũng luôn thấy được. Cơ chế: `MainButtonView.minPct` + `MainButtonOverlay.setMenuOpen()`, gọi từ `OverlayService`.

## Thay đổi 1.00.14 — click không làm mất ngón thật

Vấn đề cũ: click của app đi bằng `input tap` (một "thiết bị" riêng) trong khi ngón thật là thiết bị cảm ứng,
nên hai luồng chạm đụng nhau và ngón thật bị hệ thống bỏ / huỷ. Shell ADB cũng không có quyền ghi `/dev/input` hay `/dev/uinput`
nên không tự "bơm" sự kiện vào node cảm ứng được.

Cách làm mới (không cần ghi vào `/dev/input`, không cần root):

- **File mới `shell/InputProxy.java`**: helper chạy bằng `app_process` với quyền shell (`CLASSPATH=<apk> app_process ... InputProxy`),
  dùng luôn dex trong APK nên không phải đẩy file nào lên máy.
  1. Mở tấm cảm ứng thật, **`EVIOCGRAB`** (qua `libcore.io.Os.ioctlInt`) để hệ thống không nhận trực tiếp sự kiện ngón thật.
  2. Tự đọc `input_event` của ngón thật rồi phát lại vào hệ thống thành `MotionEvent` (cùng deviceId + source cảm ứng của máy, tính xoay màn hình).
  3. Click của app là **một pointer thêm** (id 16..23) trong CHÍNH luồng đó, nên chỉ còn một luồng chạm; áp lực / độ lớn vùng chạm **clone từ ngón thật gần nhất**
     (chưa có ngón thật thì dùng giá trị mặc định theo trục máy báo).
  4. An toàn: grab gắn với file descriptor nên tiến trình chết là kernel tự nhả; helper tự thoát khi mất nhịp (PING 250ms, quá 5s) hoặc ADB đứt;
     lúc thoát chờ ngón thật nhả (tối đa 1.5s) rồi mới nhả grab, kèm ACTION_CANCEL cho pointer còn treo.
  5. Nếu máy/ROM không cho grab hoặc không tìm được hàm inject -> **chế độ thụ động**: không grab, ngón thật chạy như bình thường, helper chỉ báo ngón; click tự lùi về `input tap`.
- **`overlay/TouchWatcher.kt`** (sửa): tự dò đường dẫn cảm ứng (`getevent -lp`, lùi về `/proc/bus/input/devices`) và **clone thông số**
  (tên thiết bị, max X/Y, max áp lực, max độ lớn) vào `TouchDevice`; chạy helper, nhận sự kiện ngón (chạm xuyên) + gửi lệnh click.
  Nếu helper không khởi động được 3 lần liền thì dùng lại luồng `cat` cũ. Thêm `TouchWatcher.tap()` và `TouchWatcher.current`.
- **`overlay/PointRunner.kt`** (sửa): click gọi `TouchWatcher.current.tap()` trước, chỉ khi proxy chưa sẵn sàng mới dùng `input tap`.
- **`overlay/MainButtonOverlay.kt`** (sửa): proxy bật khi có ít nhất một nút main (trước đây chỉ khi có nút chạm xuyên), vì phải grab TRƯỚC khi ngón đặt lên nút.

Lưu ý:
- Khi proxy đang chạy, MỌI chạm trên màn hình đi qua helper (thêm vài ms trễ). Tắt menu nổi / xoá hết nút / ngắt ADB là trả cảm ứng về như cũ.
- Đừng khởi động lại proxy lúc đang đặt ngón lên nút Giữ: grab giữa chừng một ngón đang chạm có thể để lại ngón "ma" cho tới lần chạm kế.
- Chưa kiểm thử trên máy thật (môi trường dựng không có Android SDK): `EVIOCGRAB` qua `ioctlInt`, các tên hàm `injectInputEvent` theo từng bản Android,
  ánh xạ toạ độ/xoay và clone áp lực cần thử trên thiết bị. Log của helper có dạng `LOG ...` (chỉ đọc được khi chạy tay qua `adb shell`).

## Thay đổi giao diện nổi (6.6+)

- **Ghim chọn điểm nhỏ lại**: cửa sổ ghim 72dp → 44dp, ghim vẽ 2x → 1.25x (`PointPickerOverlay`: `PIN_WIN_DP`, `PIN_SCALE`, `TIP_X_DP/TIP_Y_DP`). Mũi ghim vẫn chính là toạ độ chọn.
- **Animation ẩn/hiện menu khi kéo ghim / nút main**: trước đây card mờ 90ms còn nền tối biến mất ngay. Giờ card mờ dần + co nhẹ 6% và nền tối nhạt đi cùng một animator (ẩn 220ms, hiện lại 280ms) — `setPanelGhost` / `applyGhost` trong `OverlayService`.
- **UI nổi tự co giãn theo máy/DPI**: thêm `overlay/UiScale.kt`. Mọi chuyển đổi dp→px và cỡ chữ của lớp phủ (menu, nút main, ghim, dropdown, hộp thoại, tay nắm) đều nhân thêm hệ số = cạnh ngắn màn hình (dp) / 420, kẹp 0.75–1.0 (chỉ thu nhỏ, không phóng to). Menu tối đa 94% rộng và 80% cao màn hình, cao gốc 440dp. Dùng cạnh ngắn nên xoay màn hình không làm cỡ đổi. Đổi DPI / "Kích thước hiển thị" thì tay nắm + nút main được dựng lại theo cỡ mới (`onConfigurationChanged`). Muốn chỉnh độ to chung, sửa `REF_DP`, `MIN`, `MAX`.

## Tab 3 — Cấu hình (ConfigTab.kt)

- **Lưu macro**: ô nhập tên + dropdown + 4 nút. Một cấu hình = ảnh chụp toàn bộ nút main (tab 1) kèm kịch bản điểm nhấn (tab 2), lưu trong SharedPreferences `zyntap_configs` (`ConfigStore`).
  - **Tạo**: cần nhập tên; tên đã có (không phân biệt hoa/thường) thì KHÔNG tạo, báo dùng Cập nhật.
  - **Load**: nạp cấu hình đang chọn trong dropdown, thay toàn bộ nút main hiện tại, dựng lại nút nổi + menu.
  - **Cập nhật**: ghi đè cấu hình đang chọn bằng trạng thái hiện tại.
  - **Xóa**: bấm 2 lần trong 3 giây (lần 1 đổi thành "Chắc?") để khỏi xóa nhầm.
- **Kích thước menu**: hai ô Rộng × Cao (dp), Áp dụng / Mặc định. Tự kẹp trong khoảng cho phép (tối thiểu 360×260, tối đa theo màn hình). Lưu `panel_w_dp` / `panel_h_dp`; 0 = mặc định.
- `MainButtonRepo` thêm `exportJson()` / `replaceFromJson()`; `OverlayService.rebuildPanel()` dựng lại menu giữ nguyên tab.
