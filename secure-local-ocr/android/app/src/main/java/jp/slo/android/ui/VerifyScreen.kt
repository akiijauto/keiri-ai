package jp.slo.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 実機検証のための画面。
 *
 * 目的は2つ。
 *  1. このビルドの素性（とくに通信権限の有無）を端末上で確認できるようにする
 *  2. 検証に必要な最小限の設定（登録先URL・スクリーンショット許可）を変更できるようにする
 *
 * 設定の変更はデバッグビルドでのみ有効。リリースビルドでは表示のみ。
 */
@Composable
fun VerifyScreen(
    info: List<Pair<String, String>>,
    hasInternetPermission: Boolean,
    isDebugBuild: Boolean,
    targetUrl: String,
    allowScreenshots: Boolean,
    auditLogTail: List<String>,
    onTargetUrlChange: (String) -> Unit,
    onAllowScreenshotsChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    var urlInput by remember { mutableStateOf(targetUrl) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("検証", style = MaterialTheme.typography.headlineSmall)

        // 通信権限の有無は、この仕組みの根拠そのものなので最初に大きく出す
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (hasInternetPermission) "このビルドは通信権限を持っています"
                    else "このビルドは通信権限を持っていません",
                    fontWeight = FontWeight.Bold,
                    color = if (hasInternetPermission) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
                Text(
                    if (hasInternetPermission) {
                        "Web入力支援を行うため INTERNET 権限を持ちます。" +
                            "通信先は下の許可オリジンに限定されます。"
                    } else {
                        "OSがソケットを開かせないため、OCR中に外部へ送信することが仕組みとして起こり得ません。" +
                            "端末の「設定 → アプリ → 権限」でも同じことを確認できます。"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("ビルド情報", fontWeight = FontWeight.Bold)
                for ((label, value) in info) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (isDebugBuild) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("検証用の設定（デバッグビルドのみ）", fontWeight = FontWeight.Bold)
                    Text(
                        "架空データでの検証にのみ使用してください。リリースビルドではこの欄は機能しません。",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("登録先URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    Text(
                        "例: http://192.168.1.10:8787/registration/（同じWi-Fi上のPCで参照実装を動かす場合）",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Button(
                        onClick = { onTargetUrlChange(urlInput) },
                        modifier = Modifier.padding(top = 8.dp)
                    ) { Text("登録先URLを保存") }

                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Switch(checked = allowScreenshots, onCheckedChange = onAllowScreenshotsChange)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("スクリーンショットを許可", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "検証結果を記録するための一時的な措置です。切り替えるとアプリを再起動します。",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("監査ログ（直近・個人情報を含みません）", fontWeight = FontWeight.Bold)
                if (auditLogTail.isEmpty()) {
                    Text("まだ記録がありません。", style = MaterialTheme.typography.bodySmall)
                } else {
                    for (line in auditLogTail) {
                        Text(line, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        OutlinedButton(onClick = onBack) { Text("戻る") }
    }
}
