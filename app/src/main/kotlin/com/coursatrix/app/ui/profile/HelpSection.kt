package com.coursatrix.app.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Explanations that used to sit under each control; collapsed by default so the screens stay uncluttered. */
private val topics = listOf(
    "课表操作" to "单指可向任意方向拖动，双指缩放。左下角按钮可缩小、放大和恢复初始大小；点击百分比可把当前大小设为初始大小，或改为适应屏幕宽度。" +
        "点击课程查看详情；同一时段有多门课时会提示「重叠」。周次菜单中的「设置当前周」可按今天是第几周修正第一周日期。",
    "显示选项" to "隐藏周末只影响显示，不会删除周末课程。非本周课程以灰色显示在空位，并标注上课周次。「跟随壁纸」取色需要 Android 12 及以上。",
    "学期与作息" to "第一周周一请按学校校历填写，而不是某门课第一次上课的日期。初始作息只是示例，请改成自己学校的时间。" +
        "修改作息不会改变课程的节次或周次；缩短学期或减少节数前，需要先修改用到这些周次或节次的课程。",
    "课前提醒" to "提醒由本机定时发出，不联网；设置只保存在这台设备上，不包含在备份中。同时开始的课合并为一条通知。" +
        "若系统未允许「闹钟和提醒」，通知可能延迟几分钟。",
    "导入" to "支持 .coursatrix 备份与分享文件、UTF-8 CSV、教务系统导出的 Excel 课表（.xls / .xlsx）和文字版 PDF 课表。" +
        "所有导入都会先预览，确认后才写入；PDF 识别后需要逐条核对。扫描版 PDF 和截图暂不支持。",
    "导出与备份" to "完整备份包含学期、作息、课程、教师、教室、私人备注、计划和外观设置，文件未加密，请妥善保管。" +
        "分享课表不含计划与私人备注，但会保留学校与学期名称。导出的 CSV 包含课程备注。",
    "桌面小组件" to "长按桌面 → 小组件 → Coursatrix「今日课程」。显示今天剩余的课，今天的课结束后显示明天的课。",
    "数据与隐私" to "数据只保存在本机：没有账号、广告 SDK、网络权限或云备份。文件读写通过系统文件选择器，不需要整个存储空间的权限。" +
        "卸载应用会删除本地数据，请定期导出备份。",
)

@Composable
fun HelpSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("使用说明与关于", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开")
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    topics.forEach { (title, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider()
                    Text("Coursatrix ${version.orEmpty()} · 本地离线版。教务系统登录和系统日历导出尚未实现。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
