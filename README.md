# ZynTap

App Android viết bằng Kotlin + Jetpack Compose. Build APK hoàn toàn trên GitHub, không cần cài Android Studio.

## Build APK trên GitHub

1. Tạo repo mới trên GitHub, đưa **toàn bộ** nội dung thư mục này lên (nhớ cả thư mục ẩn `.github`).
2. Vào tab **Actions** → workflow **Build ZynTap APK** → chạy xong (tự chạy mỗi lần đẩy code lên `main`,
   hoặc bấm **Run workflow**).
3. Mở lần chạy đó, kéo xuống mục **Artifacts**, tải `ZynTap-<phiên bản>` (file zip chứa APK).
4. Giải nén, cài file `.apk` lên điện thoại (cho phép "cài từ nguồn không xác định").

## Đổi phiên bản (cập nhật không phải gỡ cài lại)

Phiên bản mặc định: **1.00.11**. Có 3 cách đổi:

- Sửa dòng `appVersion=1.00.11` trong `gradle.properties`, rồi đẩy code lên.
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
- **Cài cấu hình**: vẫn giữ placeholder, không nằm trong đợt cập nhật này.

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

Backend dùng `ZynAdbConnectionManager` có sẵn và lệnh `input tap` của Android, không yêu cầu Accessibility/root mới.
Mỗi tap có chi phí xử lý ADB/khởi động lệnh; `300ms` là thời gian chờ yêu cầu giữa các bước, không bảo đảm chu kỳ tap chính xác 300ms.
Một lệnh tap đã gửi tới Android có thể hoàn tất dù vừa nhả ngón; các bước chưa gửi sẽ bị hủy.
Khả năng giữ ngón vật lý đồng thời phát tap phần mềm phụ thuộc InputDispatcher/ROM. Nếu hệ thống phát ACTION_CANCEL,
app dừng an toàn, không giả vờ tiếp tục giữ. Vì vậy chưa thể bảo đảm đa chạm thật hoạt động trên mọi máy chỉ với `input tap`.
Không dùng text editor làm shell: chỉ tọa độ nguyên đã validate được đưa vào lệnh click.


Code: `overlay/MainButton.kt` (dữ liệu + lưu), `MainButtonsTab.kt` (giao diện tab 1), `MainButtonOverlay.kt` (nút tròn nổi),
`FloatingDropdown.kt` (dropdown nổi dùng chung), `TextPromptLayer.kt` (hộp đổi tên).

## Cấu trúc

```
app/src/main/java/com/zyntap/app/
  MainActivity.kt, MainViewModel.kt
  data/   AppRepository.kt (danh sách app), AdbPairing.kt (ghép nối)
  ui/     ZynTapApp.kt (khung + chuyển tab), HomeScreen.kt, PairScreen.kt,
          AppPickerSheet.kt, Components.kt, LucideIcons.kt, Theme.kt
app/src/main/res/   icon launcher, logo trong suốt (drawable-nodpi/logo_transparent.png)
```
