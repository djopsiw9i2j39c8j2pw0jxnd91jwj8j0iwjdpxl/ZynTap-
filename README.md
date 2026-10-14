# ZynTap

App Android viết bằng Kotlin + Jetpack Compose. Build APK hoàn toàn trên GitHub, không cần cài Android Studio.

## Build APK trên GitHub

1. Tạo repo mới trên GitHub, đưa **toàn bộ** nội dung thư mục này lên (nhớ cả thư mục ẩn `.github`).
2. Vào tab **Actions** → workflow **Build ZynTap APK** → chạy xong (tự chạy mỗi lần đẩy code lên `main`,
   hoặc bấm **Run workflow**).
3. Mở lần chạy đó, kéo xuống mục **Artifacts**, tải `ZynTap-<phiên bản>` (file zip chứa APK).
4. Giải nén, cài file `.apk` lên điện thoại (cho phép "cài từ nguồn không xác định").

## Đổi phiên bản (cập nhật không phải gỡ cài lại)

Phiên bản mặc định: **1.00.02**. Có 3 cách đổi:

- Sửa dòng `appVersion=1.00.02` trong `gradle.properties`, rồi đẩy code lên.
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
trong thư mục riêng của app (`data/ZynAdbConnectionManager.kt`). Nút menu 3 gạch chưa gán chức năng.

## Cấu trúc

```
app/src/main/java/com/zyntap/app/
  MainActivity.kt, MainViewModel.kt
  data/   AppRepository.kt (danh sách app), AdbPairing.kt (ghép nối)
  ui/     ZynTapApp.kt (khung + chuyển tab), HomeScreen.kt, PairScreen.kt,
          AppPickerSheet.kt, Components.kt, LucideIcons.kt, Theme.kt
app/src/main/res/   icon launcher, logo trong suốt (drawable-nodpi/logo_transparent.png)
```
