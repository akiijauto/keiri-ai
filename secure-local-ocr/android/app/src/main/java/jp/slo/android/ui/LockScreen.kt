package jp.slo.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.slo.android.security.DeviceIntegrity

/**
 * 起動時の認証画面（企画書 15）。
 *
 * 端末ロックが設定されていない端末、Root化が疑われる端末では業務利用させない。
 */
@Composable
fun LockScreen(
    integrity: DeviceIntegrity.Result,
    lockAvailable: Boolean,
    message: String?,
    onUnlock: () -> Unit,
    onVerify: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Secure Local OCR", style = MaterialTheme.typography.headlineSmall)
        Text(
            "個人情報は端末内だけで処理します",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        if (!integrity.trustworthy) {
            Card(modifier = Modifier.padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("この端末は業務利用できません", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Root化またはカスタムビルドの疑いがあります。管理された業務用端末をご利用ください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (!lockAvailable) {
            Card(modifier = Modifier.padding(bottom = 16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("画面ロックが未設定です", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "端末の設定で画面ロック（PIN・パターン・生体認証）を有効にしてください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Button(onClick = onUnlock, enabled = integrity.trustworthy && lockAvailable) {
            Text("認証して開始")
        }

        // 認証前でも、このビルドの素性（とくに通信権限の有無）は確認できるようにしておく。
        // 個人情報を扱う前に「何を渡すビルドなのか」を見られることに意味がある。
        TextButton(onClick = onVerify, modifier = Modifier.padding(top = 8.dp)) {
            Text("ビルド情報と検証設定")
        }

        if (message != null) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
