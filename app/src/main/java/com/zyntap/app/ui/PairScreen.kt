package com.zyntap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyntap.app.MainViewModel
import com.zyntap.app.data.PairState

@Composable
fun PairScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kết nối gỡ lỗi Wi-Fi", color = Zyn.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Làm lần lượt 3 bước dưới đây để ZynTap kết nối được với máy của bạn.",
                color = Zyn.TextDim,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(2.dp))

            StepCard(
                number = 1,
                title = "Bật chế độ nhà phát triển",
                body = "Vào Cài đặt → Giới thiệu điện thoại (hoặc Thông tin điện thoại). " +
                    "Tìm mục Số bản dựng rồi ấn liên tục 7 lần cho tới khi hiện thông báo đã bật. " +
                    "Nếu máy hỏi mật khẩu hay mã PIN màn hình thì nhập vào.",
            )
            StepCard(
                number = 2,
                title = "Bật gỡ lỗi Wi-Fi",
                body = "Kết nối Wi-Fi trước. Sau đó vào Cài đặt → Tùy chọn nhà phát triển, " +
                    "bật Gỡ lỗi không dây (Wireless debugging) và chọn Cho phép khi máy hỏi.",
            )
            StepCard(
                number = 3,
                title = "Lấy mã ghép nối 6 số",
                body = "Trong mục Gỡ lỗi không dây, ấn Ghép nối thiết bị bằng mã ghép nối. " +
                    "Một bảng nhỏ sẽ hiện mã gồm 6 số. Nhập mã đó vào ô bên dưới rồi ấn Ghép nối.",
            )

            val shape = RoundedCornerShape(14.dp)
            Text(
                text = "Mẹo: mã chỉ hiện khi bảng đó còn mở. Hãy dùng chia đôi màn hình hoặc cửa sổ nổi " +
                    "để vừa xem mã vừa nhập vào ZynTap. Tên các mục có thể khác chút tùy hãng máy.",
                color = Zyn.TextDim,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Zyn.Border, shape)
                    .padding(14.dp),
            )
        }
        PairingPanel(vm)
    }
}

@Composable
private fun StepCard(number: Int, title: String, body: String) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Zyn.Surface, shape)
            .border(1.dp, Zyn.Border, shape)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(Zyn.Cyan.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", color = Zyn.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Zyn.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Zyn.TextDim, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun PairingPanel(vm: MainViewModel) {
    val state = vm.pairState
    val working = state is PairState.Working
    val canSubmit = vm.code.length == 6 && !working && state !is PairState.Success

    // Mở tới là đặt con trỏ vào ô mã và bật bàn phím, để người dùng nhập luôn
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        delay(400) // chờ màn hình trượt xong rồi mới lấy tiêu điểm
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Zyn.Surface, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .border(1.dp, Zyn.Border, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text("Mã ghép nối", color = Zyn.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = vm.code,
            onValueChange = vm::onCode,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            singleLine = true,
            enabled = !working,
            textStyle = TextStyle(
                color = Zyn.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 8.sp,
                textAlign = TextAlign.Center,
            ),
            placeholder = {
                Text(
                    text = "000000",
                    color = Zyn.TextDim.copy(alpha = 0.5f),
                    fontSize = 26.sp,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.submitPair() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Zyn.Cyan,
                unfocusedBorderColor = Zyn.Border,
                disabledBorderColor = Zyn.Border,
                cursorColor = Zyn.Cyan,
                focusedContainerColor = Zyn.SurfaceHi,
                unfocusedContainerColor = Zyn.SurfaceHi,
                disabledContainerColor = Zyn.SurfaceHi,
                disabledTextColor = Zyn.Text,
            ),
        )

        AnimatedVisibility(visible = state is PairState.Failed || state is PairState.Success) {
            val ok = state is PairState.Success
            Text(
                text = when (state) {
                    is PairState.Success -> "Đã ghép nối thành công"
                    is PairState.Failed -> state.message
                    else -> ""
                },
                color = if (ok) Zyn.Ok else Zyn.Danger,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = vm::submitPair,
            enabled = canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Zyn.Blue,
                contentColor = Color.White,
                disabledContainerColor = Zyn.SurfaceHi,
                disabledContentColor = Zyn.TextDim,
            ),
        ) {
            if (working) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text("Ghép nối", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
