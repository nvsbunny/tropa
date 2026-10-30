package app.tropa.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tropa.TropaApp
import app.tropa.core.CoreState
import app.tropa.core.TropaVpnService

class MainActivity : ComponentActivity() {

    private val controller get() = (application as TropaApp).controller

    /** Текст из ссылки или «Поделиться», который откроем в окне добавления. */
    private var pendingImport by mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) controller.connect()
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { TropaTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        controller.refreshStale()
        controller.checkUpdates()
        controller.onResumed()
    }

    override fun onStart() {
        super.onStart()
        controller.setVisible(true)
    }

    override fun onStop() {
        controller.setVisible(false)
        super.onStop()
    }

    /** Отсканированный QR-код телевизора — ждёт подтверждения «Отправить?». */
    private var pairTicket by mutableStateOf<app.tropa.core.Pairing.Ticket?>(null)

    /**
     * Код с телевизора читает обычная камера телефона: ссылка tropa://pair откроет Тропу.
     * Своего сканера нет намеренно — сканер Google тянет за собой отправку статистики.
     */
    fun scanTv() {
        controller.say("Наведите камеру на код на телевизоре и откройте ссылку")
        runCatching {
            startActivity(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun onPairCode(text: String) {
        val t = app.tropa.core.Pairing.parse(text)
        if (t == null) controller.say("Это не код Тропы с телевизора") else pairTicket = t
    }

    /** Вкладка, которую попросил открыть виджет. */
    private var openTab by mutableStateOf<Int?>(null)

    private fun handleIntent(intent: Intent?) {
        // QR-код телевизора, отсканированный обычной камерой, открывает Тропу ссылкой tropa://pair?…
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "tropa" && intent.data?.host == "pair") {
            onPairCode(intent.dataString.orEmpty())
            return
        }
        intent?.getIntExtra(EXTRA_TAB, -1)?.takeIf { it >= 0 }?.let { openTab = it }
        // Виджет нажали, а разрешения на VPN ещё нет: спрашиваем его здесь и подключаемся.
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) == true) {
            intent.removeExtra(EXTRA_CONNECT)
            toggle(true)
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) pendingImport = text
    }

    private fun toggle(on: Boolean) {
        if (!on) {
            controller.disconnect()
            return
        }
        val prepare = if (TropaVpnService.needsPermission(this)) VpnService.prepare(this) else null
        if (prepare != null) vpnPermission.launch(prepare) else controller.connect()
    }

    private fun clipboardText(): String =
        getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App() {
        val c = controller
        val status by CoreState.status.collectAsState()
        var tab by rememberSaveable { mutableStateOf(0) }
        var addOpen by remember { mutableStateOf(false) }
        val snack = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { c.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(pendingImport) { if (pendingImport != null) addOpen = true }
        LaunchedEffect(openTab) { openTab?.let { tab = it; openTab = null } }

        Box(Modifier.fillMaxSize().background(Palette.bg)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> HomeScreen(c, status, onOpenServers = { tab = 1 }, onOpenRoutes = { tab = 2 }, onAddSub = { addOpen = true }, onOpenSettings = { tab = 3 })
                        1 -> ServersScreen(c, onAddSub = { addOpen = true })
                        2 -> RoutesScreen(c)
                        else -> SettingsScreen(c, onAddSub = { addOpen = true }, onSendToTv = ::scanTv)
                    }
                }
                if (tab == 0) {
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        val on = status is CoreState.Status.On || status == CoreState.Status.Connecting
                        ConnectBar(
                            on = on,
                            busy = status == CoreState.Status.Connecting,
                            label = when (status) {
                                is CoreState.Status.On -> "Подключено"
                                CoreState.Status.Connecting -> "Подключаемся…"
                                else -> "Нажмите или сдвиньте"
                            },
                            onToggle = ::toggle,
                        )
                    }
                }
                HorizontalDivider(color = Palette.line.copy(alpha = 0.6f), thickness = 1.dp)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
                    TabItem("Главная", Icons.home, tab == 0, Modifier.weight(1f)) { tab = 0 }
                    TabItem("Серверы", Icons.servers, tab == 1, Modifier.weight(1f)) { tab = 1 }
                    TabItem("Маршруты", Icons.fork, tab == 2, Modifier.weight(1f)) { tab = 2 }
                    TabItem("Настройки", Icons.sliders, tab == 3, Modifier.weight(1f)) { tab = 3 }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 150.dp))
        }

        pairTicket?.let { t ->
            val names = c.subsForTv().joinToString(", ") { Names.subTitle(it.title) }
            AlertDialog(
                onDismissRequest = { pairTicket = null },
                containerColor = Palette.s1,
                title = { Text("Отправить на телевизор?", style = Type.heading) },
                text = {
                    Text(
                        "Телевизор ${t.host} получит подписки: ${names.ifBlank { "нет подписок" }}. Передача зашифрована и идёт только по домашней сети.",
                        style = Type.small, color = Palette.muted,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { c.sendToTv(t); pairTicket = null }) { Text("Отправить", color = Palette.vpn, style = Type.bodyStrong) }
                },
                dismissButton = {
                    TextButton(onClick = { pairTicket = null }) { Text("Отмена", color = Palette.text, style = Type.bodyStrong) }
                },
            )
        }

        if (addOpen) {
            ModalBottomSheet(
                onDismissRequest = { addOpen = false; pendingImport = null },
                containerColor = Palette.s1,
                contentWindowInsets = { WindowInsets(0) },
            ) {
                AddSheet(initial = pendingImport.orEmpty(), onPaste = ::clipboardText) { text, done ->
                    if (app.tropa.core.Pairing.parse(text) != null) {
                        done()
                        addOpen = false
                        pendingImport = null
                        onPairCode(text)
                        return@AddSheet
                    }
                    c.addFromText(text) { ok ->
                        done()
                        if (ok) { addOpen = false; pendingImport = null; tab = 0 }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_CONNECT = "connect"
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics { this.selected = selected }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 30.dp).clip(RoundedCornerShape(15.dp))
                .background(if (selected) Palette.s2 else Palette.bg),
            contentAlignment = Alignment.Center,
        ) {
            Ico(icon, tint = if (selected) Palette.text else Palette.muted)
        }
        Text(label, style = Type.tiny, color = if (selected) Palette.text else Palette.muted)
    }
}

@Composable
private fun AddSheet(initial: String, onPaste: () -> String, onAdd: (String, () -> Unit) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
        Text("Добавить подписку", style = Type.heading)
        Text(
            "Ссылка от VPN-сервиса или серверы vless://, vmess://, trojan://, ss://, hysteria2://, tuic://. Ссылки для Happ, INCY, Clash и v2rayNG тоже подойдут.",
            style = Type.caption, color = Palette.muted, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = Type.small.copy(color = Palette.text),
            cursorBrush = SolidColor(Palette.vpn),
            maxLines = 6,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.bg)
                .border(BorderStroke(1.5.dp, Palette.line), RoundedCornerShape(14.dp))
                .semantics { contentDescription = "Ссылка на подписку" }
                .padding(14.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("https://…", style = Type.small, color = Palette.muted)
                inner()
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.s2)
                    .clickable { text = onPaste() }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Ico(Icons.paste, size = 18.dp)
                Text("Вставить", style = Type.bodyStrong)
            }
            PrimaryButton(if (busy) "Загружаем…" else "Добавить", modifier = Modifier.weight(1f)) {
                if (text.isNotBlank() && !busy) {
                    busy = true
                    onAdd(text) { busy = false }
                }
            }
        }
    }
}
