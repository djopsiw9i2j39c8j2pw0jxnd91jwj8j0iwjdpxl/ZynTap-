package com.zyntap.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyntap.app.MainViewModel
import com.zyntap.app.data.AppEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerSheet(vm: MainViewModel) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val visible = remember(vm.allApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) vm.allApps else vm.allApps.filter { it.label.lowercase().contains(q) }
    }

    ModalBottomSheet(
        onDismissRequest = vm::closePicker,
        sheetState = sheetState,
        containerColor = Zyn.Surface,
        contentColor = Zyn.Text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Thêm ứng dụng",
                color = Zyn.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = "Ấn vào app để thêm hoặc bỏ khỏi danh sách",
                color = Zyn.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Tìm ứng dụng", color = Zyn.TextDim) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Zyn.Cyan,
                    unfocusedBorderColor = Zyn.Border,
                    cursorColor = Zyn.Cyan,
                    focusedTextColor = Zyn.Text,
                    unfocusedTextColor = Zyn.Text,
                    focusedContainerColor = Zyn.SurfaceHi,
                    unfocusedContainerColor = Zyn.SurfaceHi,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )

            if (vm.loadingApps && vm.allApps.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Zyn.Cyan)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        PickerRow(app = app, added = vm.isPinned(app.packageName), onClick = { vm.toggle(app) })
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(app: AppEntry, added: Boolean, onClick: () -> Unit) {
    val tickBg by animateColorAsState(if (added) Zyn.Cyan else Color.Transparent, label = "tick-bg")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = app.label,
            color = Zyn.Text,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(tickBg, CircleShape)
                .border(1.5.dp, if (added) Zyn.Cyan else Zyn.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (added) {
                Icon(Lucide.Check, contentDescription = "Đã thêm", tint = Color(0xFF00141C), modifier = Modifier.size(15.dp))
            }
        }
    }
}
