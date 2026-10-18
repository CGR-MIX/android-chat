package com.ddzhilian.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** 密码验证对话框：只在连点「版本」7 次后出现，不提供任何常驻入口。 */
@Composable
internal fun AdminGateDialog(state: ChatState, vm: ChatViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!state.adminBusy) vm.adminCancelGate() },
        title = { Text("管理员验证") },
        text = {
            Column {
                Text(
                    "请输入主站管理员账号。会话只保存在本机内存，退出应用后需要重新验证。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    email,
                    { email = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("管理员邮箱") },
                    singleLine = true,
                    enabled = !state.adminBusy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("密码") },
                    singleLine = true,
                    enabled = !state.adminBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.adminError?.let {
                    Text(it, Modifier.padding(top = 10.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.adminLogin(email, password) }, enabled = !state.adminBusy) {
                Text(if (state.adminBusy) "验证中…" else "进入")
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.adminCancelGate() }, enabled = !state.adminBusy) { Text("取消") }
        },
    )
}

/** 管理员面板：AI 策略、供应商、历史暂存文件、助手头像。 */
@Composable
internal fun AdminScreen(state: ChatState, vm: ChatViewModel) {
    val dashboard = (state.admin as? AdminMode.Open)?.dashboard ?: return
    val ai = dashboard.ai
    val editable = dashboard.isSuperAdmin
    val values = remember(dashboard) { mutableStateMapOf<String, String>().apply { putAll(initialAdminValues(dashboard.ai)) } }
    val modelToggles = remember(dashboard) { mutableStateMapOf<String, Boolean>().apply { putAll(readModelToggles(dashboard.ai)) } }
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<AdminHistoryFile?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.adminUploadAvatar(uri)
    }

    LaunchedEffect(state.adminError, state.adminNotice) {
        val text = state.adminError ?: state.adminNotice
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.adminConsumeNotice()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 28.dp),
        ) {
            AdminHeader(state, dashboard, editable, vm, onLogout = { confirmLogout = true })
            Spacer(Modifier.height(18.dp))

            AdminSection("AI 助手策略", "与主站后台同一份配置：名称、唤醒词、记忆轮数与 System Prompt。") {
                AdminField("AI 名称", values["policy.name"].orEmpty(), editable) { values["policy.name"] = it }
                AdminField("唤醒词（逗号分隔，最多 8 个）", values["policy.wakeWords"].orEmpty(), editable) { values["policy.wakeWords"] = it }
                AdminField("历史记忆轮数（0 关闭）", values["policy.memoryTurns"].orEmpty(), editable, numeric = true) { values["policy.memoryTurns"] = it }
                AdminMultilineField("System Prompt", values["policy.systemPrompt"].orEmpty(), editable) { values["policy.systemPrompt"] = it }
                SaveRow(state, editable) { payload ->
                    val wakeWords = JSONArray(
                        values["policy.wakeWords"].orEmpty().split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .take(8),
                    )
                    vm.adminSaveAiConfig(
                        JSONObject()
                            .put("slice", "policy")
                            .put("aiAssistantName", values["policy.name"].orEmpty().trim())
                            .put("aiWakeWords", wakeWords)
                            .put("aiMemoryTurns", values["policy.memoryTurns"].orEmpty().trim().toIntOrNull()?.coerceIn(0, 30) ?: 0)
                            .put("systemPrompt", values["policy.systemPrompt"].orEmpty()),
                        "AI 助手策略已保存",
                    )
                }
            }

            AdminSection(
                "供应商",
                if (editable) "编辑后与主站后台同步；API Key 默认掩码显示。" else "当前账号不是超级管理员，供应商配置只读。",
            ) {
                val cloudflare = ai.optJSONObject("cloudflare") ?: JSONObject()
                Text("Cloudflare", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                AdminField("Account ID", values["cloudflare.accountId"].orEmpty(), editable) { values["cloudflare.accountId"] = it }
                AdminField("API Token", values["cloudflare.apiToken"].orEmpty(), editable, secret = true) { values["cloudflare.apiToken"] = it }
                AdminField("默认模型", values["cloudflare.model"].orEmpty(), editable) { values["cloudflare.model"] = it }
                AdminField("每日神经元预算", values["cloudflare.dailyNeuronBudget"].orEmpty(), editable, numeric = true) { values["cloudflare.dailyNeuronBudget"] = it }
                AdminField("提示词上限（字符）", values["cloudflare.maxPromptChars"].orEmpty(), editable, numeric = true) { values["cloudflare.maxPromptChars"] = it }
                AdminField("输出上限（token）", values["cloudflare.maxOutputTokens"].orEmpty(), editable, numeric = true) { values["cloudflare.maxOutputTokens"] = it }
                ModelToggleRow("cloudflare", cloudflare.optJSONArray("models"), modelToggles, editable)

                val openai = ai.optJSONArray("openai") ?: JSONArray()
                for (index in 0 until openai.length()) {
                    val slot = openai.optJSONObject(index) ?: continue
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "OpenAI 兼容 · " + slot.optString("displayName", "#" + (index + 1)),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    AdminField("显示名称", values["openai.$index.displayName"].orEmpty(), editable) { values["openai.$index.displayName"] = it }
                    AdminField("Base URL", values["openai.$index.baseUrl"].orEmpty(), editable) { values["openai.$index.baseUrl"] = it }
                    AdminField("API Key", values["openai.$index.apiKey"].orEmpty(), editable, secret = true) { values["openai.$index.apiKey"] = it }
                    AdminField("默认模型", values["openai.$index.model"].orEmpty(), editable) { values["openai.$index.model"] = it }
                    AdminField("提示词上限（字符）", values["openai.$index.maxPromptChars"].orEmpty(), editable, numeric = true) { values["openai.$index.maxPromptChars"] = it }
                    AdminField("输出上限（token）", values["openai.$index.maxOutputTokens"].orEmpty(), editable, numeric = true) { values["openai.$index.maxOutputTokens"] = it }
                    ModelToggleRow("openai.$index", slot.optJSONArray("models"), modelToggles, editable)
                }

                val anthropic = ai.optJSONArray("anthropic") ?: JSONArray()
                for (index in 0 until anthropic.length()) {
                    val slot = anthropic.optJSONObject(index) ?: continue
                    Spacer(Modifier.height(10.dp))
                    Text("Anthropic · #" + (index + 1), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    AdminField("Base URL", values["anthropic.$index.baseUrl"].orEmpty(), editable) { values["anthropic.$index.baseUrl"] = it }
                    AdminField("Auth Token", values["anthropic.$index.authToken"].orEmpty(), editable, secret = true) { values["anthropic.$index.authToken"] = it }
                    AdminField("默认模型", values["anthropic.$index.model"].orEmpty(), editable) { values["anthropic.$index.model"] = it }
                    AdminField("Sonnet 默认模型", values["anthropic.$index.defaultSonnetModel"].orEmpty(), editable) { values["anthropic.$index.defaultSonnetModel"] = it }
                    AdminField("Opus 默认模型", values["anthropic.$index.defaultOpusModel"].orEmpty(), editable) { values["anthropic.$index.defaultOpusModel"] = it }
                    AdminField("Haiku 默认模型", values["anthropic.$index.defaultHaikuModel"].orEmpty(), editable) { values["anthropic.$index.defaultHaikuModel"] = it }
                    AdminField("提示词上限（字符）", values["anthropic.$index.maxPromptChars"].orEmpty(), editable, numeric = true) { values["anthropic.$index.maxPromptChars"] = it }
                    AdminField("输出上限（token）", values["anthropic.$index.maxOutputTokens"].orEmpty(), editable, numeric = true) { values["anthropic.$index.maxOutputTokens"] = it }
                    ModelToggleRow("anthropic.$index", slot.optJSONArray("models"), modelToggles, editable)
                }

                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = { vm.adminSaveAiConfig(buildProvidersPayload(ai, values), "供应商设置已保存") },
                        enabled = editable && !state.adminBusy,
                    ) { Text("保存供应商") }
                    TextButton(
                        onClick = { vm.adminSaveAiConfig(buildModelsPayload(ai, modelToggles), "模型开关已保存") },
                        enabled = editable && !state.adminBusy,
                    ) { Text("保存模型开关") }
                }
            }

            AdminSection("历史暂存文件", "房间历史附件按时间倒序；删除会在服务端写撤回墓碑，在线客户端同步消失。") {
                HistorySummary(dashboard.history)
                state.adminHistoryFiles.forEach { file ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.fileName, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                adminSize(file.size) + " · " + file.senderName + " · " + adminTime(file.createdAt),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { pendingDelete = file }, enabled = !state.adminBusy) { Text("删除", fontSize = 12.sp) }
                    }
                }
                if (state.adminHistoryFiles.isEmpty() && !state.adminHistoryLoading) {
                    Text("没有历史附件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { vm.loadAdminHistory(reset = true) }, enabled = !state.adminHistoryLoading) { Text("刷新列表") }
                    if (state.adminHistoryCursor != null) {
                        TextButton(onClick = { vm.loadAdminHistory() }, enabled = !state.adminHistoryLoading) { Text("加载更多") }
                    }
                    TextButton(onClick = { confirmClear = true }, enabled = !state.adminBusy) { Text("清空全站历史", fontSize = 12.sp) }
                    if (state.adminHistoryLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            AdminSection("AI 助手头像", "PNG / JPEG / WebP，最大 2 MB；与主站后台、网页聊天共用同一张。") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val bitmap = remember(state.aiAvatarRevision, state.ai.avatarUrl) { vm.aiAvatar() }
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(bitmap.asImageBitmap(), "AI 助手头像", Modifier.fillMaxSize())
                        } else {
                            Text("无头像", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        TextButton(
                            onClick = {
                                avatarPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            enabled = editable && !state.adminBusy,
                        ) { Text("选择图片") }
                        if (state.ai.avatarUrl != null) {
                            TextButton(onClick = { vm.adminDeleteAvatar() }, enabled = editable && !state.adminBusy) {
                                Text("移除头像", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "所有改动写在同一份服务端配置，后台、网页与 App 立即一致。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除历史附件") },
            text = { Text("确认删除「" + file.fileName + "」？该操作会同步撤回在线客户端里的这条消息。") },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.adminDeleteHistory(file) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全站历史") },
            text = { Text("这会删除所有房间的文字与附件历史，且无法恢复。确认继续？") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.adminClearHistory() }) {
                    Text("确认清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出管理员模式") },
            text = { Text("退出后需要重新连点版本行并输入密码。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; vm.adminLogout() }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun AdminHeader(
    state: ChatState,
    dashboard: AdminDashboard,
    editable: Boolean,
    vm: ChatViewModel,
    onLogout: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("管理员模式", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                dashboard.email + if (editable) " · 超级管理员" else " · 只读权限",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.adminBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        TextButton(onClick = { vm.adminRefresh() }, enabled = !state.adminBusy) { Text("刷新", fontSize = 12.sp) }
        TextButton(onClick = onLogout, enabled = !state.adminBusy) { Text("退出", fontSize = 12.sp) }
        TextButton(onClick = { vm.adminClose() }, enabled = !state.adminBusy) { Text("关闭", fontSize = 12.sp) }
    }
}

@Composable
private fun AdminSection(title: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) { content() }
        }
    }
}

@Composable
private fun AdminField(
    label: String,
    value: String,
    enabled: Boolean,
    secret: Boolean = false,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    var reveal by remember { mutableStateOf(false) }
    // 显式标注可组合类型：Compose 需要 (@Composable () -> Unit)?，直接写 if/else 推断不出来。
    val trailing: (@Composable () -> Unit)? = if (secret) {
        {
            TextButton(onClick = { reveal = !reveal }) {
                Text(if (reveal) "隐藏" else "显示", fontSize = 11.sp)
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        trailingIcon = trailing,
    )
}

@Composable
private fun AdminMultilineField(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        label = { Text(label, fontSize = 12.sp) },
        enabled = enabled,
        minLines = 5,
        maxLines = 12,
    )
}

@Composable
private fun ModelToggleRow(
    prefix: String,
    models: JSONArray?,
    toggles: MutableMap<String, Boolean>,
    editable: Boolean,
) {
    if (models == null || models.length() == 0) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("可用模型", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val id = model.optString("id")
            if (id.isEmpty()) continue
            val key = prefix + "." + id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = toggles[key] ?: model.optBoolean("enabled", true),
                    onCheckedChange = { checked -> toggles[key] = checked },
                    enabled = editable,
                )
                Text(model.optString("label", id), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SaveRow(state: ChatState, editable: Boolean, onSave: (JSONObject) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        TextButton(onClick = { onSave(JSONObject()) }, enabled = editable && !state.adminBusy) { Text("保存策略") }
    }
}

@Composable
private fun HistorySummary(history: JSONObject) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("附件 " + history.optInt("fileCount"), fontSize = 12.sp)
        Text("文字 " + history.optInt("textCount"), fontSize = 12.sp)
        Text(adminSize(history.optLong("totalBytes")), fontSize = 12.sp)
        Text("房间 " + history.optInt("roomCount"), fontSize = 12.sp)
    }
}

private fun adminSize(size: Long): String = when {
    size <= 0 -> "0 B"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}

private fun adminTime(raw: String): String = runCatching {
    raw.take(16).replace('T', ' ')
}.getOrDefault(raw)

private fun initialAdminValues(ai: JSONObject): Map<String, String> {
    val values = mutableMapOf<String, String>()
    values["policy.name"] = ai.optString("aiAssistantName")
    values["policy.wakeWords"] = ai.optJSONArray("aiWakeWords")?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }.joinToString(", ")
    }.orEmpty()
    values["policy.memoryTurns"] = ai.optInt("aiMemoryTurns", 0).toString()
    values["policy.systemPrompt"] = ai.optString("systemPrompt")

    val cloudflare = ai.optJSONObject("cloudflare") ?: JSONObject()
    listOf("accountId", "apiToken", "model", "dailyNeuronBudget", "maxPromptChars", "maxOutputTokens").forEach { key ->
        values["cloudflare.$key"] = cloudflare.opt(key)?.toString().orEmpty()
    }

    val openai = ai.optJSONArray("openai") ?: JSONArray()
    for (index in 0 until openai.length()) {
        val slot = openai.optJSONObject(index) ?: continue
        listOf("displayName", "baseUrl", "apiKey", "model", "maxPromptChars", "maxOutputTokens").forEach { key ->
            values["openai.$index.$key"] = slot.opt(key)?.toString().orEmpty()
        }
    }

    val anthropic = ai.optJSONArray("anthropic") ?: JSONArray()
    for (index in 0 until anthropic.length()) {
        val slot = anthropic.optJSONObject(index) ?: continue
        listOf(
            "baseUrl", "authToken", "model", "defaultSonnetModel", "defaultOpusModel",
            "defaultHaikuModel", "maxPromptChars", "maxOutputTokens",
        ).forEach { key ->
            values["anthropic.$index.$key"] = slot.opt(key)?.toString().orEmpty()
        }
    }

    return values
}

private fun readModelToggles(ai: JSONObject): Map<String, Boolean> {
    val toggles = mutableMapOf<String, Boolean>()
    fun read(prefix: String, models: JSONArray?) {
        for (index in 0 until (models?.length() ?: 0)) {
            val model = models?.optJSONObject(index) ?: continue
            val id = model.optString("id")
            if (id.isNotEmpty()) toggles["$prefix.$id"] = model.optBoolean("enabled", true)
        }
    }

    read("cloudflare", ai.optJSONObject("cloudflare")?.optJSONArray("models"))
    val openai = ai.optJSONArray("openai")
    for (index in 0 until (openai?.length() ?: 0)) {
        read("openai.$index", openai?.optJSONObject(index)?.optJSONArray("models"))
    }
    val anthropic = ai.optJSONArray("anthropic")
    for (index in 0 until (anthropic?.length() ?: 0)) {
        read("anthropic.$index", anthropic?.optJSONObject(index)?.optJSONArray("models"))
    }
    return toggles
}

private fun buildProvidersPayload(ai: JSONObject, values: Map<String, String>): JSONObject {
    fun intOf(key: String, fallback: Int): Int = values[key]?.trim()?.toIntOrNull() ?: fallback

    val cloudflareSource = ai.optJSONObject("cloudflare") ?: JSONObject()
    val cloudflare = JSONObject(cloudflareSource.toString()).apply {
        put("accountId", values["cloudflare.accountId"].orEmpty())
        put("apiToken", values["cloudflare.apiToken"].orEmpty())
        put("model", values["cloudflare.model"].orEmpty())
        put("dailyNeuronBudget", intOf("cloudflare.dailyNeuronBudget", cloudflareSource.optInt("dailyNeuronBudget", 0)))
        put("maxPromptChars", intOf("cloudflare.maxPromptChars", cloudflareSource.optInt("maxPromptChars", 8000)))
        put("maxOutputTokens", intOf("cloudflare.maxOutputTokens", cloudflareSource.optInt("maxOutputTokens", 1000)))
    }

    val openaiSource = ai.optJSONArray("openai") ?: JSONArray()
    val openai = JSONArray()
    for (index in 0 until openaiSource.length()) {
        val source = openaiSource.optJSONObject(index) ?: continue
        openai.put(JSONObject(source.toString()).apply {
            put("displayName", values["openai.$index.displayName"].orEmpty())
            put("baseUrl", values["openai.$index.baseUrl"].orEmpty())
            put("apiKey", values["openai.$index.apiKey"].orEmpty())
            put("model", values["openai.$index.model"].orEmpty())
            put("maxPromptChars", intOf("openai.$index.maxPromptChars", source.optInt("maxPromptChars", 8000)))
            put("maxOutputTokens", intOf("openai.$index.maxOutputTokens", source.optInt("maxOutputTokens", 1000)))
        })
    }

    val anthropicSource = ai.optJSONArray("anthropic") ?: JSONArray()
    val anthropic = JSONArray()
    for (index in 0 until anthropicSource.length()) {
        val source = anthropicSource.optJSONObject(index) ?: continue
        anthropic.put(JSONObject(source.toString()).apply {
            put("baseUrl", values["anthropic.$index.baseUrl"].orEmpty())
            put("authToken", values["anthropic.$index.authToken"].orEmpty())
            put("model", values["anthropic.$index.model"].orEmpty())
            put("defaultSonnetModel", values["anthropic.$index.defaultSonnetModel"].orEmpty())
            put("defaultOpusModel", values["anthropic.$index.defaultOpusModel"].orEmpty())
            put("defaultHaikuModel", values["anthropic.$index.defaultHaikuModel"].orEmpty())
            put("maxPromptChars", intOf("anthropic.$index.maxPromptChars", source.optInt("maxPromptChars", 8000)))
            put("maxOutputTokens", intOf("anthropic.$index.maxOutputTokens", source.optInt("maxOutputTokens", 1000)))
        })
    }

    return JSONObject()
        .put("slice", "providers")
        .put("cloudflare", cloudflare)
        .put("openai", openai)
        .put("anthropic", anthropic)
}

private fun buildModelsPayload(ai: JSONObject, toggles: Map<String, Boolean>): JSONObject {
    fun modelsOf(prefix: String, models: JSONArray?): JSONArray {
        val out = JSONArray()
        for (index in 0 until (models?.length() ?: 0)) {
            val model = models?.optJSONObject(index) ?: continue
            val id = model.optString("id")
            if (id.isEmpty()) continue
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("label", model.optString("label", id))
                    .put("enabled", toggles["$prefix.$id"] ?: model.optBoolean("enabled", true)),
            )
        }
        return out
    }

    val cloudflare = ai.optJSONObject("cloudflare") ?: JSONObject()
    val openaiSource = ai.optJSONArray("openai") ?: JSONArray()
    val openai = JSONArray()
    for (index in 0 until openaiSource.length()) {
        val slot = openaiSource.optJSONObject(index) ?: continue
        openai.put(
            JSONObject()
                .put("model", slot.optString("model"))
                .put("models", modelsOf("openai.$index", slot.optJSONArray("models"))),
        )
    }

    val anthropicSource = ai.optJSONArray("anthropic") ?: JSONArray()
    val anthropic = JSONArray()
    for (index in 0 until anthropicSource.length()) {
        val slot = anthropicSource.optJSONObject(index) ?: continue
        anthropic.put(
            JSONObject()
                .put("model", slot.optString("model"))
                .put("defaultSonnetModel", slot.optString("defaultSonnetModel"))
                .put("defaultOpusModel", slot.optString("defaultOpusModel"))
                .put("defaultHaikuModel", slot.optString("defaultHaikuModel"))
                .put("models", modelsOf("anthropic.$index", slot.optJSONArray("models"))),
        )
    }

    return JSONObject()
        .put("slice", "models")
        .put("openai", openai)
        .put("anthropic", anthropic)
        .put(
            "cloudflare",
            JSONObject()
                .put("model", cloudflare.optString("model"))
                .put("models", modelsOf("cloudflare", cloudflare.optJSONArray("models"))),
        )
}
