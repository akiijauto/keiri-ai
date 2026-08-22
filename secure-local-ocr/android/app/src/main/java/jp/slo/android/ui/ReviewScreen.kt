package jp.slo.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.slo.core.Profile

/**
 * OCR結果の確認・修正画面（企画書 Step 5 / 9 Human-in-the-loop）。
 *
 * ここを通らずにWeb入力へ進む経路は存在しない。
 * 各項目の「確認」を人間が押して初めて、その値が引き渡し対象になる（INV-1）。
 */
@Composable
fun ReviewScreen(
    vm: SessionViewModel,
    onProceed: () -> Unit,
    onRetake: () -> Unit,
    onDiscard: () -> Unit
) {
    val missing = vm.missingRequired()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("OCR結果の確認", style = MaterialTheme.typography.headlineSmall)
        Text(
            "読み取り結果は候補です。1項目ずつ内容を確かめて「確認」を付けてください。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        Text(
            "認識 ${vm.ocrLineCount}行 / ${vm.lastOcrElapsedMillis}ms" +
                    if (vm.offlineCapture) " / 機内モードで取込済み" else "",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (missing.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("未確認の必須項目があります", fontWeight = FontWeight.Bold)
                    Text(
                        missing.joinToString("、") { Profile.label(it) },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(vm.fields) { index, field ->
                FieldRow(
                    field = field,
                    onValueChange = { vm.edit(index, it) },
                    onConfirmChange = { vm.setConfirmed(index, it) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onProceed, enabled = vm.canHandoff()) { Text("Web入力へ進む") }
            OutlinedButton(onClick = onRetake) { Text("撮り直す") }
            OutlinedButton(onClick = onDiscard) { Text("破棄") }
        }
    }
}

@Composable
private fun FieldRow(
    field: SessionViewModel.EditableField,
    onValueChange: (String) -> Unit,
    onConfirmChange: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, fontWeight = FontWeight.Bold)
                Text(
                    confidenceLabel(field),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (field.needsReview) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            OutlinedTextField(
                value = field.input,
                onValueChange = onValueChange,
                singleLine = true,
                isError = !field.valid,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            if (!field.valid && field.error != null) {
                Text(
                    errorMessage(field.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (field.raw.isNotBlank() && field.raw != field.input) {
                Text(
                    "読み取り: ${field.raw}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = field.confirmed,
                    onCheckedChange = onConfirmChange,
                    enabled = field.valid && field.input.isNotBlank()
                )
                Text("この内容で確認しました")
            }
        }
    }
}

private fun confidenceLabel(field: SessionViewModel.EditableField): String = when {
    field.origin == "manual" -> "手入力"
    !field.valid -> "要修正"
    field.confidence >= 0.85 -> "信頼度 高"
    field.confidence >= 0.70 -> "信頼度 中 — 要確認"
    else -> "信頼度 低 — 要確認"
}

private fun errorMessage(code: String): String = when (code) {
    "E_VALIDATION" -> "形式が正しくありません。内容を確認してください。"
    "E_PARSE" -> "読み取れませんでした。手入力してください。"
    "E_MISSING" -> "必須項目です。入力してください。"
    else -> "確認が必要です（$code）"
}
