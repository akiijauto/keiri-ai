package jp.slo.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.slo.core.Extractor

/**
 * OCRが読み取った行をそのまま並べる画面（デバッグビルド専用）。
 *
 * 抽出がうまくいかないとき、原因は2つに分かれる。
 *   1. そもそも文字が読めていない  → 撮影条件・画像処理の問題
 *   2. 読めているが項目に結びつかない → 抽出規則の問題
 * 確認画面だけを見ていると、この2つを区別できない。
 *
 * ここに出るのは読み取った文字そのものなので、
 * 架空データでの検証時にだけ使うこと。保存も送信もしない。
 */
@Composable
fun OcrDebugScreen(
    lines: List<Extractor.Line>,
    elapsedMillis: Long,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("読み取った行", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${lines.size}行 / ${elapsedMillis}ms。抽出規則ではなくOCRの生の出力です。",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "架空データの検証専用。保存も送信もしていません。",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (lines.isEmpty()) {
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("1行も読み取れていません", fontWeight = FontWeight.Bold)
                    Text(
                        "撮影条件の問題です。明るさ・角度・距離を変えて撮り直してください。" +
                            "画面を撮影している場合は、画面の明るさを上げ、映り込みを避けてください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(lines) { index, line ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp)) {
                        Text(
                            "%02d".format(index + 1),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 10.dp)
                        )
                        Column {
                            Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "信頼度 %.2f".format(line.confidence) +
                                    (line.box?.let { " / 位置 x${it.left}-${it.right} y${it.top}-${it.bottom}" }
                                        ?: " / 位置なし（並び順で対応づけ）"),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) {
            Text("確認画面へ戻る")
        }
    }
}
