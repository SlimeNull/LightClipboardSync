package com.lightclipboardsync.android

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val pageColor = Color(0xFFF6F8FC)
private val actionBlue = Color(0xFF3F70DC)
private val ink = Color(0xFF25324D)
private val muted = Color(0xFF73819A)
private val teal = Color(0xFF22A485)
private val panelShape = RoundedCornerShape(8.dp)

class MainActivity : ComponentActivity() {
    private lateinit var config: SyncConfig
    private lateinit var logPrefs: SharedPreferences
    private var serverDraft by mutableStateOf("")
    private var userDraft by mutableStateOf("")
    private var notificationOn by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var batteryOptimizationIgnored by mutableStateOf(false)
    private var logLines by mutableStateOf(emptyList<String>())
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enableNotification() else toast("通知权限未开启")
    }
    private val logListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SyncLog.KEY) runOnUiThread { logLines = SyncLog.lines(this).asReversed() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModuleBridge.initialize(this)
        ClipboardEventSession.initialize(this)
        config = SyncConfig.load(this)
        serverDraft = savedInstanceState?.getString("serverDraft") ?: config.serverUrl
        userDraft = savedInstanceState?.getString("userDraft") ?: config.userId.toString()
        notificationOn = SyncConfig.notificationEnabled(this)
        overlayGranted = BackgroundClipboard.hasOverlayPermission(this)
        batteryOptimizationIgnored = BackgroundClipboard.isIgnoringBatteryOptimizations(this)
        logPrefs = getSharedPreferences(SyncLog.PREFS, Context.MODE_PRIVATE)
        logLines = SyncLog.lines(this).asReversed()
        val barColor = android.graphics.Color.rgb(246, 248, 252)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(barColor, barColor),
            navigationBarStyle = SystemBarStyle.light(barColor, barColor),
        )
        setContent {
            val localState by ClipboardEventSession.status.collectAsState()
            // The app-owned SSE session is the baseline path. The optional
            // framework module must not replace its status or receiver.
            val connectionState = localState
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = actionBlue,
                    secondary = teal,
                    background = pageColor,
                    surface = Color.White,
                    onSurface = ink,
                ),
                typography = Typography(),
            ) {
                HomeScreen(
                    connectionState = connectionState,
                    server = serverDraft,
                    onServerChange = { serverDraft = it },
                    userId = userDraft,
                    onUserChange = { userDraft = it },
                    notificationOn = notificationOn,
                    onNotificationChange = ::changeNotification,
                    overlayGranted = overlayGranted,
                    onRequestOverlay = ::requestOverlayPermission,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestBatteryOptimization = ::requestBatteryOptimization,
                    logs = logLines,
                    onSync = { if (saveSettings(false)) ManualSync.start(this) },
                    onSave = { saveSettings(true) },
                    onCopyId = ::copyId,
                    onRegenerateId = { userDraft = UUID.randomUUID().toString() },
                    onAddTile = ::requestTile,
                )
            }
        }
        startForegroundService(Intent(this, BackgroundSyncService::class.java))
    }

    private fun copyId() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("同步 UUID", userDraft))
        toast("UUID 已复制")
    }

    private fun saveSettings(showToast: Boolean): Boolean {
        val parsed = SyncConfig.parse(serverDraft, userDraft, config.clientId)
        if (parsed == null) {
            toast("请输入有效的服务器地址和 UUID")
            return false
        }
        val changed = parsed != config
        config = parsed
        config.save(this)
        ModuleBridge.publish(config, changed || showToast)
        if (changed || showToast) ClipboardEventSession.reconnect(this)
        if (changed || showToast) {
            startForegroundService(Intent(this, BackgroundSyncService::class.java))
        }
        if (showToast) toast("设置已保存")
        return true
    }

    private fun changeNotification(enabled: Boolean) {
        if (!enabled) {
            notificationOn = false
            SyncConfig.setNotificationEnabled(this, false)
            // The foreground service is also the baseline sync path when the
            // optional framework module is unavailable, so it remains active.
            startForegroundService(Intent(this, BackgroundSyncService::class.java))
            return
        }
        if (!saveSettings(false)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableNotification()
        }
    }

    private fun enableNotification() {
        notificationOn = true
        SyncConfig.setNotificationEnabled(this, true)
        startForegroundService(Intent(this, BackgroundSyncService::class.java))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("serverDraft", serverDraft)
        outState.putString("userDraft", userDraft)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        overlayGranted = BackgroundClipboard.hasOverlayPermission(this)
        batteryOptimizationIgnored = BackgroundClipboard.isIgnoringBatteryOptimizations(this)
        // ColorOS may destroy the background socket while the activity is
        // stopped without notifying the SSE reader. Start a fresh session
        // whenever the app becomes interactive again.
        ClipboardEventSession.reconnect(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || overlayGranted) return
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")))
    }

    private fun requestBatteryOptimization() {
        if (BackgroundClipboard.requestIgnoreBatteryOptimizations(this)) {
            batteryOptimizationIgnored = BackgroundClipboard.isIgnoringBatteryOptimizations(this)
        }
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("请在控制中心编辑界面添加")
            return
        }
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, SyncTileService::class.java),
            getString(R.string.sync_clipboard),
            Icon.createWithResource(this, R.drawable.ic_clipboard),
            mainExecutor,
        ) { result ->
            toast(if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                "已添加到控制中心" else "可在控制中心编辑界面添加")
        }
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    override fun onStart() {
        super.onStart()
        ClipboardEventSession.setVisible(this, true)
        ModuleBridge.setObserving(true)
        logPrefs.registerOnSharedPreferenceChangeListener(logListener)
        logLines = SyncLog.lines(this).asReversed()
    }

    override fun onStop() {
        ClipboardEventSession.setVisible(this, false)
        ModuleBridge.setObserving(false)
        logPrefs.unregisterOnSharedPreferenceChangeListener(logListener)
        super.onStop()
    }
}

@Composable
private fun HomeScreen(
    connectionState: ConnectionState,
    server: String,
    onServerChange: (String) -> Unit,
    userId: String,
    onUserChange: (String) -> Unit,
    notificationOn: Boolean,
    onNotificationChange: (Boolean) -> Unit,
    overlayGranted: Boolean,
    onRequestOverlay: () -> Unit,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    logs: List<String>,
    onSync: () -> Unit,
    onSave: () -> Unit,
    onCopyId: () -> Unit,
    onRegenerateId: () -> Unit,
    onAddTile: () -> Unit,
) {
    Scaffold(
        containerColor = pageColor,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { safePadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(safePadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("剪贴板同步", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = ink)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val indicatorColor = if (connectionState == ConnectionState.CONNECTED) teal else actionBlue
                            Box(Modifier.size(7.dp).background(indicatorColor, CircleShape))
                            Text(if (connectionState == ConnectionState.CONNECTED) "已连接" else "正在连接",
                                modifier = Modifier.padding(start = 8.dp), fontSize = 13.sp, color = muted)
                        }
                    }
                    Image(painterResource(R.drawable.app_icon), null, Modifier.size(48.dp))
                }
            }
            item {
                Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = panelShape,
                    colors = ButtonDefaults.buttonColors(containerColor = actionBlue),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null, Modifier.size(23.dp))
                    Spacer(Modifier.size(10.dp))
                    Text("同步剪切板", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item { SectionTitle("连接设置") }
            item {
                OutlinedTextField(
                    value = server,
                    onValueChange = onServerChange,
                    label = { Text("服务器地址") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = panelShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = userId,
                    onValueChange = onUserChange,
                    label = { Text("同步 UUID") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = onCopyId) {
                            Icon(Icons.Outlined.ContentCopy, "复制 UUID", tint = actionBlue)
                        }
                    },
                    shape = panelShape,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRegenerateId, shape = panelShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = actionBlue)) {
                        Icon(Icons.Outlined.Refresh, "重新生成 UUID", Modifier.size(20.dp))
                    }
                    Button(onClick = onSave, shape = panelShape, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Save, null, Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("保存设置")
                    }
                }
            }
            item { SectionTitle("快捷入口") }
            item {
                Surface(shape = panelShape, color = Color.White,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onAddTile)) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 17.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(Color(0xFFE7EEFF), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, null, tint = actionBlue)
                        }
                        Text("添加到控制中心", modifier = Modifier.weight(1f).padding(start = 14.dp),
                            fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ink)
                        Icon(Icons.Outlined.ChevronRight, null, tint = muted)
                    }
                }
            }
            item {
                Surface(shape = panelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("常驻通知", modifier = Modifier.weight(1f), fontSize = 16.sp,
                            fontWeight = FontWeight.Medium, color = ink)
                        Switch(checked = notificationOn, onCheckedChange = onNotificationChange,
                            colors = SwitchDefaults.colors(checkedTrackColor = actionBlue))
                    }
                }
            }
            item {
                Surface(shape = panelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("后台读取剪贴板", fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, color = ink)
                            Text(if (overlayGranted) "已允许悬浮窗读取" else "需要悬浮窗权限",
                                fontSize = 12.sp, color = muted)
                        }
                        if (!overlayGranted) {
                            Button(onClick = onRequestOverlay, shape = panelShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Text("去开启")
                            }
                        }
                    }
                }
            }
            item {
                Surface(shape = panelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("电池优化", fontSize = 16.sp,
                                fontWeight = FontWeight.Medium, color = ink)
                            Text(if (batteryOptimizationIgnored) "已忽略电池优化" else "可能被系统限制后台网络",
                                fontSize = 12.sp, color = muted)
                        }
                        if (!batteryOptimizationIgnored) {
                            Button(onClick = onRequestBatteryOptimization, shape = panelShape,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Text("去开启")
                            }
                        }
                    }
                }
            }
            item { SectionTitle("活动日志") }
            item {
                Surface(shape = panelShape, color = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.heightIn(min = 170.dp, max = 240.dp)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (logs.isEmpty()) {
                            Text("暂无记录", fontSize = 13.sp, color = muted)
                        } else {
                            logs.forEach { line ->
                                Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                    color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
}
