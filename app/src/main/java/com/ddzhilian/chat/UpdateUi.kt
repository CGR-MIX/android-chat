package com.ddzhilian.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ddzhilian.chat.liquid.LiquidButton
import com.kyant.backdrop.Backdrop

/*
 * 设置页的版本更新区块：版本来源就是 git 仓库的版本 tag，检查、下载、安装都在这里完成。
 * 按钮沿用页面上的液态玻璃控件，玻璃表面同样绘制在它自己采样的层之外（backdrop 由设置页现画壁纸）。
 */

@Composable
fun UpdateSetting(s: ChatState, vm: ChatViewModel, backdrop: Backdrop, reduce: Boolean) {
    val context = LocalContext.current
    val update = s.update
    val detail = when (update) {
        is UpdateState.Idle -> "版本来源是 git 仓库的版本 tag，不经过第三方更新服务。"
        is UpdateState.Checking -> "正在读取 git 仓库的版本 tag…"
        is UpdateState.Current -> "已是最新版本，通道里最新的 tag 是 ${update.tag}。"
        is UpdateState.Available -> "发现新版本 ${update.release.version}，当前为 ${BuildConfig.VERSION_NAME}。"
        is UpdateState.Downloading -> "正在下载 ${update.release.tag}…"
        is UpdateState.Ready -> "${update.release.tag} 已下载，交给系统安装器完成覆盖安装。"
        is UpdateState.Failed -> update.message
    }
    val label = when (update) {
        is UpdateState.Idle -> "检查更新"
        is UpdateState.Checking -> "正在检查…"
        is UpdateState.Current -> "已是最新 · 重新检查"
        is UpdateState.Available -> "下载 ${update.release.tag}"
        is UpdateState.Downloading -> "下载中 " + downloadPercent(update) + "%"
        is UpdateState.Ready -> "安装 ${update.release.tag}"
        is UpdateState.Failed -> "重新检查"
    }
    val busy = update is UpdateState.Checking || update is UpdateState.Downloading
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Text("版本更新", fontWeight = FontWeight.Medium)
        Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (update is UpdateState.Downloading) {
            LinearProgressIndicator(
                progress = { downloadFraction(update) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        LiquidButton(
            onClick = {
                when (update) {
                    is UpdateState.Available -> vm.downloadUpdate()
                    is UpdateState.Ready -> when (val request = requestInstall(context, update.file)) {
                        is InstallRequest.Started -> vm.showNotice("已交给系统安装器，请按提示完成覆盖安装")
                        is InstallRequest.NeedsPermission -> context.startActivity(unknownAppSourcesIntent(context))
                        is InstallRequest.Failed -> vm.showError(request.message)
                    }
                    // 检查与下载进行中：按钮只做状态展示，重复点击不排队。
                    is UpdateState.Checking, is UpdateState.Downloading -> Unit
                    else -> vm.checkForUpdate()
                }
            },
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            isInteractive = !busy,
            reduce = reduce,
        ) { Text(label, fontWeight = FontWeight.Medium) }
    }
}

private fun downloadPercent(state: UpdateState.Downloading): Int =
    if (state.total <= 0L) 0 else (state.received * 100 / state.total).toInt().coerceIn(0, 100)

private fun downloadFraction(state: UpdateState.Downloading): Float =
    if (state.total <= 0L) 0f else (state.received.toFloat() / state.total).coerceIn(0f, 1f)
