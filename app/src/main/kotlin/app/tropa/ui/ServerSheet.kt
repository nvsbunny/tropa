package app.tropa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Выбор сервера прямо с главной: шторка снизу вместо перехода на вкладку.
 * Сверху автоматика (Тропа или группа провайдера), ниже страны и серверы — по отклику
 * или как у провайдера. Выбрали — шторка закрылась, осталось сдвинуть кнопку.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(c: Controller, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val d = rememberServerList(c, filter = null)
    LaunchedEffect(Unit) { c.testAll() }
    val picked: () -> Unit = { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = state,
        containerColor = Palette.bg,
        contentColor = Palette.text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Palette.line) },
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Сервер", style = Type.title)
                        Text("Выберите и сдвиньте кнопку на главной", style = Type.caption, color = Palette.muted)
                    }
                }
                SortChips(d.settings.providerOrder, c)
            }
            serverList(d, c, picked)
            item { Spacer(24.dp) }
        }
    }
}
