package top.readaloud.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 字体选择器：**下拉菜单**（Leo 要求的形式）+ 一个导入按钮。
 *
 * ## 菜单里为什么分两段
 *
 * · 上半段是**系统字体**：手机自带，永远可用（这台机器上只有 黑体/宋体/等宽）
 * · 下半段是**我的字体**：用户自己导入的.ttf/.otf
 *
 * ⚠️ 系统字体只有三款是**手机本身只装了 2 款中文字体**决定的（实测荣耀 9X：
 *   NotoSansCJK 黑体、NotoSerifCJK 宋体，无楷体/仿宋）。
 *   与其把不存在的字体列出来让人"点了没变化"，不如给一个导入入口 ——
 *   想用楷体就把楷体文件丢进来，这也是唯一不涉及字体授权的法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontDropdown(
    sysFont: ReadingStyle.SysFont,
    onSysFont: (ReadingStyle.SysFont) -> Unit,
    userFonts: List<String>,
    userFontFile: String,
    onUserFont: (String) -> Unit,
    onImportFont: () -> Unit,
    onDeleteFont: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedUser by remember { mutableStateOf(false) }

    // 当前显示的名字：选了用户字体就显示它，否则显示系统字体名
    val currentLabel = if (userFontFile.isBlank()) {
        sysFont.label
    } else {
        ReadingStyle.userFontLabel(userFontFile)
    }

    Column {
        // ── 系统字体 ──────────────────────────────────────
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = if (userFontFile.isBlank()) currentLabel else "$currentLabel（我的字体）",
                onValueChange = {},
                readOnly = true,
                label = { Text("系统字体") },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ReadingStyle.SysFont.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(f.label) },
                        onClick = {
                            onSysFont(f)
                            // 选系统字体要把"我的字体"选择清掉，否则两处状态打架
                            if (userFontFile.isNotBlank()) onUserFont("")
                            expanded = false
                        },
                        trailingIcon = {
                            if (f == sysFont && userFontFile.isBlank()) {
                                Text("✓", color = Color(0xFF2B5CFF))
                            }
                        }
                    )
                }
            }
        }

        // ── 我的字体（导入的）─────────────────────────────
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ExposedDropdownMenuBox(
                    expanded = expandedUser,
                    onExpandedChange = { if (userFonts.isNotEmpty()) expandedUser = it }
                ) {
                    OutlinedTextField(
                        value = if (userFontFile.isBlank()) "（还没导入字体）"
                        else ReadingStyle.userFontLabel(userFontFile),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("我的字体") },
                        placeholder = { Text("导入 ttf / otf 后可选") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedUser)
                        },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedUser,
                        onDismissRequest = { expandedUser = false }
                    ) {
                        if (userFonts.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("还没有导入的字体", color = Color.Gray) },
                                onClick = { expandedUser = false }
                            )
                        }
                        userFonts.forEach { name ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        ReadingStyle.userFontLabel(name),
                                        fontWeight = if (name == userFontFile) FontWeight.Medium
                                        else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    onUserFont(name)
                                    expandedUser = false
                                },
                                trailingIcon = {
                                    if (name == userFontFile) {
                                        Text("✓", color = Color(0xFF2B5CFF))
                                    } else {
                                        // 长按删除在下面用TextButton，这里给个删除入口
                                        TextButton(onClick = { onDeleteFont(name) }) {
                                            Text("删除", color = Color(0xFFB3261E))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportFont) {
            Text("＋ 导入字体文件（ttf / otf）")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "手机自带字体只有黑体/宋体/等宽。想用楷体、仿宋等，" +
                "把字体文件（.ttf / .otf）从电脑拷到手机，用上面的按钮选它即可。" +
                "字体文件只存在你自己手机里，App 不打包任何字体、也不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}