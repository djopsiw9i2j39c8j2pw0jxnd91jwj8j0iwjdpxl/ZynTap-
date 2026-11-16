# ZynTap · 1.00.36

Ứng dụng Android Kotlin + Jetpack Compose. `applicationId` giữ nguyên **com.zyntap.app**.

## Thay đổi ở bản này

Màn hình chính có **một công tắc UI nổi**. Bật: dịch vụ foreground dựng tay nắm,
nút main, tâm ảo và thanh thông số theo cấu hình đã lưu. Tắt: gỡ toàn bộ cửa sổ,
hủy callback phục hồi, dừng click/script và dịch vụ. Trạng thái được lưu trong
`zyntap_overlay/wanted`, không tạo thêm cờ bật/tắt song song.

Đã gỡ mã chọn/ghim/mở app, nút Quick Settings mở app đã chọn, worker kiểm tra
app tiền cảnh và cơ chế tự đóng khi rời app. Manifest không còn yêu cầu
`PACKAGE_USAGE_STATS` hay liệt kê app launcher. Bản này không cần quyền
“Truy cập dữ liệu sử dụng”. Chuyển app/Home không tắt UI nổi.
Nút X trong menu nổi chỉ thu menu; tắt toàn bộ bằng công tắc trong app chính.
Thông báo foreground mở lại app, không tạo thêm công tắc tắt riêng.

Menu ba gạch nằm cạnh logo, dùng màu nhấn xanh/cyan. Chạm mở drawer dạng khung
trượt, kéo hoặc chạm nền tối/Back để đóng; không còn AlertDialog cài đặt.
Khung có lối vào ghép nối ADB và công tắc nhật ký gỡ lỗi. Công tắc UI nổi dùng
logo có sẵn, cùng bảng màu và hình khối với ứng dụng; không thêm ảnh/font mới.

## Phục hồi UI và chi phí xử lý

`OverlayWindowManager` quản lý duy nhất các cửa sổ mà dịch vụ muốn giữ.
Detach ngoài ý muốn kích hoạt phục hồi theo sự kiện, có grace period/backoff;
watchdog mỗi 5 giây chỉ kiểm tra bù. Không remove/add cửa sổ khỏe liên tục,
không ép vẽ mỗi khung hình, không tạo timer mới mỗi lần bật.

Phục hồi nhường thao tác kéo/chạm nút, thao tác menu, chọn điểm, nhập liệu và
animation. Cửa sổ được chủ động tháo khỏi registry sẽ không tự sinh lại.
Tắt toggle dọn registry/callback trước khi tháo View, ngăn tự bật lại.
START_STICKY, mở app và nhận MY_PACKAGE_REPLACED tận dụng cùng trạng thái đã lưu
để phục hồi khi Android cho phép. Không chạy luồng polling ứng dụng tiền cảnh.

**Giới hạn Android:** force-stop, thu hồi quyền vẽ đè, chế độ pin/ROM chặn dịch vụ,
màn hình hệ thống chặn overlay hoặc thiết bị tắt nguồn không thể bị “vượt qua”
bằng code UI. Mở lại app/cấp lại quyền khi hệ điều hành yêu cầu. Không cam kết
UI luôn nằm trên mọi cửa sổ bảo mật, hay không lag trên mọi thiết bị.

## Tọa độ ngang/dọc

Mỗi nút lưu kịch bản gốc kèm `pointWidth`, `pointHeight`, `pointRotation`.
`PointCoordinates` dùng chung phép đổi khung hình cho chạy click, editor và
kiểm tra vùng điểm nhấn/pass-through. Có hỗ trợ 0/90/180/270 độ, đổi kích thước,
điểm sát mép và multiclick; delay và nội dung gốc không bị ghi lại mỗi lần xoay.
Editor hiển thị tọa độ trong chiều màn hình hiện tại và chỉ lưu khung mới khi sửa.

DisplayListener và Configuration dùng chung đường relayout debounce. Xoay máy
hủy press/script đang chạy để tránh bơm tọa độ cũ; nhấn lại sau khi màn hình ổn định.
Helper nhận ROT cùng RUN; ROT không thay đổi là idempotent, không hủy những script
khác đang chạy. Tọa độ hình học không thể tự hiểu vị trí nút bên trong game nếu
UI game tự bố trí lại khác nhau giữa ngang/dọc — khi đó cần chỉnh lại điểm.

Kịch bản cũ không có metadata được gắn với khung màn hình tại lần bật UI đầu tiên.
Vì không thể suy ra chiều màn hình đã dùng khi tạo dữ liệu cũ, hãy bật lần đầu
ở chiều từng đặt điểm hoặc mở tab Điểm nhấn để kiểm tra và lưu lại.
Cấu hình JSON mới có kèm metadata tọa độ.

## Thanh thông số

Pin, giờ và tốc độ mạng giữ nguyên. FPS theo app cũ phụ thuộc package đã chọn;
luồng lấy mẫu này đã bỏ cùng cơ chế chọn/theo dõi app. Nếu cấu hình cũ bật ô FPS,
ô đó hiện **--**, không dùng FPS giả từ animation UI. Không còn worker/ADB dumpsys
định kỳ để dò FPS theo package. Cấu hình ô cũ vẫn đọc được để không hỏng JSON.

## Khóa ký và cập nhật cài đè

Muốn cài đè phải giữ **cùng applicationId, cùng chứng thư khóa ký**, đồng thời
tăng `appVersion`/versionCode. Không phải thêm một chuỗi “key nhận diện” vào code.
`versionCodeFrom` vẫn lấy số tăng dần từ phiên bản, ví dụ 1.00.36 → 1000036.

Bộ khóa riêng tạo cho bản bàn giao nằm trong **ZIP riêng tư**, không có trong repo
hay ZIP source. Giữ ít nhất hai bản sao an toàn của keystore và mật khẩu. Không
commit `.jks`, `.keystore`, base64 keystore hoặc token lên GitHub.

Nếu còn keystore của bản đang cài, **ưu tiên dùng chính khóa đó**. Khóa mới không
ký thay được bản cũ; thiếu khóa cũ không thể bảo đảm cập nhật cài đè lần chuyển này.
Xuất cấu hình trước khi chuyển bản nếu chứng thư không khớp.

GitHub → Settings → Secrets and variables → Actions, đặt đủ:

| Secret | Giá trị |
|---|---|
| `ZYNTAP_KEYSTORE_BASE64` | Nội dung `keystore-base64.txt` trong bộ khóa riêng, hoặc base64 của khóa cũ |
| `ZYNTAP_STORE_PASSWORD` | Mật khẩu keystore tương ứng |
| `ZYNTAP_KEY_PASSWORD` | Mật khẩu alias tương ứng |
| `ZYNTAP_KEY_ALIAS` | Alias tương ứng, khóa mới là `zyntap` |

Workflow dùng các secrets này để ký. **Không tự ký release bằng debug key tạm nữa.**
Nếu không có khóa, CI vẫn kiểm thử/build nhưng artifact có hậu tố `UNSIGNED`, không
phải APK cài được. Nếu cấu hình khóa thiếu một phần, build báo lỗi thay vì âm thầm
đổi chữ ký. Chỉ bản đã ký mới được đăng Releases khi đẩy tag v*.
Token bàn giao không có quyền quản lý Actions secrets nên cần chủ repo tự đặt các
secret ở trên. Không đưa bộ khóa riêng vào issue, commit hoặc artifact công khai.

Build local: Java 17, Android SDK 34; đặt `ZYNTAP_KEYSTORE_PATH` tới keystore,
`ZYNTAP_STORE_PASSWORD`, `ZYNTAP_KEY_PASSWORD`, `ZYNTAP_KEY_ALIAS`, rồi chạy:

```sh
./gradlew testReleaseUnitTest assembleRelease --no-daemon
```

Có thể đặt keystore tại `app/zyntap-release.jks` (đã gitignore) thay cho biến đường dẫn.
Muốn bản tiếp theo, tăng `appVersion` trong `gradle.properties`, không đổi applicationId
và không tạo lại khóa. Cùng versionCode có thể bị trình cài đặt/OEM từ chối cập nhật.

## Kiểm chứng và thử máy thật

Unit test bao gồm hình học 4 chiều, đổi kích thước, round-trip không trôi điểm,
multiclick/delay, bảo toàn comment/lỗi editor, tương thích kịch bản cũ, cử chỉ menu,
nhật ký phục hồi pointer và phép tính FPS cũ. Build/test JVM không thay thế thiết bị thật.

Trước khi phát hành rộng, thử trên máy đích: bật/tắt liên tiếp; Home/chuyển app;
kéo/giữ/thả main và tay nắm; nhập/chọn điểm; xoay cả 4 chiều; nhiều script cùng lúc;
mất rồi nối lại ADB; màn hình khóa; thu hồi quyền; update APK có cùng chữ ký.
Chưa có benchmark A/B CPU/RAM/frame-time hoặc thử ADB input trên điện thoại trong
môi trường bàn giao này. Không tuyên bố “không lag” chỉ dựa vào build thành công.
