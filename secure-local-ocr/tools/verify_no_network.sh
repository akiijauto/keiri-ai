#!/usr/bin/env bash
#
# OCRフェーズで通信が発生しないことの検証（企画書 20 / 原則8）。
#
# 「コード上では通信していない」を合格としないため、成果物そのものを検査する。
#   静的検査 : APKの権限とネットワークセキュリティ設定を実測する（このスクリプト）
#   動的検査 : 実端末で通信を捕捉し tools/analyze_pcap.py で判定する（手順を末尾に表示）
#
# 使い方:
#   tools/verify_no_network.sh                       # offlineビルドのAPKをすべて検査
#   tools/verify_no_network.sh path/to/app.apk ...   # 任意のAPKを検査
#
# 終了コード: 0=合格 / 1=不合格 / 2=前提不足

set -u

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OFFLINE_APK_DIR="$REPO_ROOT/android/app/build/outputs/apk/offline/debug"

# APKはABIごとに分割されるため、名前を決め打ちせず存在するものをすべて検査する。
# 1つでも通信権限を持っていたら不合格。
APKS=()
if [ "$#" -ge 1 ]; then
  APKS=("$@")
else
  while IFS= read -r found; do
    APKS+=("$found")
  done < <(find "$OFFLINE_APK_DIR" -maxdepth 1 -name '*.apk' 2>/dev/null | sort)
fi

FORBIDDEN_PERMISSIONS=(
  "android.permission.INTERNET"
  "android.permission.ACCESS_NETWORK_STATE"
)

fail=0

say()  { printf '%s\n' "$*"; }
ok()   { printf '  OK   %s\n' "$*"; }
ng()   { printf '  NG   %s\n' "$*"; fail=1; }
info() { printf '  --   %s\n' "$*"; }

find_aapt2() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$sdk" ] || return 1
  local latest
  latest="$(ls -1 "$sdk/build-tools" 2>/dev/null | sort -V | tail -1)" || return 1
  [ -n "$latest" ] || return 1
  printf '%s' "$sdk/build-tools/$latest/aapt2"
}

say "=============================================="
say " OCRフェーズ 通信検証（静的検査）"
say "=============================================="

if [ "${#APKS[@]}" -eq 0 ]; then
  say "APKが見つかりません。先にビルドしてください:"
  say "  cd android && ANDROID_HOME=... ./gradlew :app:assembleOfflineDebug"
  say "  （探索先: $OFFLINE_APK_DIR）"
  exit 2
fi

AAPT2="$(find_aapt2)" || {
  say "aapt2 が見つかりません。ANDROID_HOME を設定してください。"
  exit 2
}

say "対象APK: ${#APKS[@]}件"
for apk in "${APKS[@]}"; do
  say "  - $(basename "$apk")"
done
say ""

for APK in "${APKS[@]}"; do
  if [ ! -f "$APK" ]; then
    ng "$(basename "$APK") が存在しない"
    continue
  fi

  say "--- $(basename "$APK") ---"

  say "[1] 権限の実測"
  PERMISSIONS="$("$AAPT2" dump permissions "$APK")"
  for perm in "${FORBIDDEN_PERMISSIONS[@]}"; do
    if printf '%s' "$PERMISSIONS" | grep -q "$perm"; then
      ng "$perm を保持している（OCR専用ビルドでは保持してはならない）"
    else
      ok "$perm を保持していない"
    fi
  done
  say ""
  say "  APKが宣言する全権限:"
  printf '%s\n' "$PERMISSIONS" | grep "uses-permission" | sed 's/^/    /'
  say ""

  say "[2] 平文通信の禁止設定"
  if "$AAPT2" dump xmltree "$APK" --file res/xml/network_security_config.xml >/dev/null 2>&1; then
    NSC="$("$AAPT2" dump xmltree "$APK" --file res/xml/network_security_config.xml 2>/dev/null)"
    if printf '%s' "$NSC" | grep -q 'cleartextTrafficPermitted.*0xffffffff'; then
      info "一部ドメインで平文通信が許可されている（社内検証用ループバックの想定。運用ビルドでは外すこと）"
    fi
    ok "ネットワークセキュリティ設定が同梱されている"
  else
    info "ネットワークセキュリティ設定が見つからない（webフレーバーでは必須）"
  fi
  say ""
done

say "[3] 判定"
if [ "$fail" -eq 0 ]; then
  say "  静的検査: 合格"
else
  say "  静的検査: 不合格"
fi

cat <<'PROCEDURE'

=============================================
 動的検査（実端末での通信監視）の手順
=============================================
静的検査だけでは「権限を持っていない」ことしか言えない。
実際に通信が出ていないことは、実端末で捕捉して確認する。

A. 端末側で完結させる方法（推奨・追加機材不要）
   1. 端末を機内モードにする
   2. アプリで 撮影 → OCR → 項目抽出 → 確認 まで一通り操作する
   3. すべて完了できることを確認する
      → 通信が必要なら、この時点で失敗するはず

B. 経路上で捕捉する方法（合格条件の証跡を残す）
   1. 検証用PCでWi-Fiアクセスポイントを立て、端末をそこへ接続する
   2. PC側で捕捉を開始する
        sudo tcpdump -i <interface> -w ocr_phase.pcap host <端末のIP>
   3. アプリで 撮影 → OCR → 確認 まで操作する（Web入力へは進まない）
   4. 捕捉を止め、解析する
        python3 tools/analyze_pcap.py ocr_phase.pcap --phase ocr
      → 「合格」かつ宛先0件であること

C. Web登録フェーズの確認（許可リストの検証）
   1. 同じ手順で捕捉しながら、Web入力まで進める
   2. 解析する
        python3 tools/analyze_pcap.py web_phase.pcap --phase web \
            --allow <業務サイトのIP>
      → 業務サイト以外の宛先が0件であること

D. 記録
   合否・日時・端末・ビルド番号・pcapのハッシュを docs/test-plan.html の
   様式に従って残す。pcapそのものは個人情報を含み得るため、
   リポジトリへコミットしない。
PROCEDURE

exit "$fail"
