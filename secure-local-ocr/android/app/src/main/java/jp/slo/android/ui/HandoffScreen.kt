package jp.slo.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import jp.slo.android.handoff.SloWebViewBridge

/**
 * Web入力支援画面（企画書 10）。
 *
 * Level 1（表示）・Level 2（コピー）・Level 3/4（WebViewへの充填）を1画面で扱う。
 * どのレベルでも、最終的な送信ボタンを押すのは人間である（INV-4）。
 */
@Composable
fun HandoffScreen(
    vm: SessionViewModel,
    webHandoffEnabled: Boolean,
    targetUrl: String,
    bridge: SloWebViewBridge?,
    onDeliver: () -> Unit,
    onFinish: () -> Unit,
    onCopied: (String) -> Unit,
    statusMessage: String?
) {
    val context = LocalContext.current
    val confirmed = vm.fields.filter { it.confirmed && it.valid }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Web入力", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (webHandoffEnabled) {
                "確認済みの${confirmed.size}項目を、登録先の入力欄へ充填します。送信は画面上のボタンをご自身で押してください。"
            } else {
                "このビルドは通信を行いません。値を確認しながら手入力するか、項目ごとにコピーしてください。"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        if (statusMessage != null) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (webHandoffEnabled && bridge != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onDeliver, enabled = vm.canHandoff()) { Text("この内容を入力する") }
                OutlinedButton(onClick = onFinish) { Text("終了して破棄") }
            }
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).also { view ->
                        bridge.attach(view)
                        view.loadUrl(targetUrl)
                    }
                }
            )
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(confirmed) { field ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(field.label, fontWeight = FontWeight.Bold)
                                Text(field.input, style = MaterialTheme.typography.bodyMedium)
                            }
                            OutlinedButton(onClick = {
                                copySensitive(context, field.label, field.input)
                                onCopied(field.key)
                            }) { Text("コピー") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onFinish, modifier = Modifier.padding(top = 8.dp)) {
                Text("終了して破棄")
            }
        }
    }
}

/**
 * クリップボードへのコピー（Level 2）。
 *
 * Android 13以降はクリップボードの内容がプレビュー表示されるため、
 * 機微情報である印を付けてプレビューを抑止する（企画書 15「クリップボード利用制限」）。
 * 実運用では業務専用端末のMDMでクリップボード共有そのものを制限することが望ましい。
 */
private fun copySensitive(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, value)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)
}
