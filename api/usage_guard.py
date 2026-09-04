"""AI呼び出しの総量を1日単位で止める見張り。

**なぜ要るか（2026-09-05）**

Basic認証を外して公開したことで、`/analyze` は誰でも叩ける状態になった。
nginx 側にIP単位のレート制限を入れてあるが、あれは**1つのIPが速く叩くこと**しか止められない。
IPを変えられれば回り込めるので、**その日に何回AIを呼んだかの総量**は別に数える必要がある。

止めたいのは「AIの無限使用」であって「利用者を困らせること」ではないので、
上限に達したら 429 と**いつ再開するか**を返す。

状態はファイル1つに持つ。DBを増やさないのは、
この見張りが落ちたときにアプリまで落ちるのを避けたいため
（数えられなくなったら通す、ではなく、数えられなくなったら**止める**側に倒している。
 課金が絡む以上、疑わしいときは止めるほうが安全）。
"""
from __future__ import annotations

import json
import os
import threading
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

# 1日に許すAI呼び出しの回数。0 以下にすると無効（無制限）になる。
DAILY_LIMIT = int(os.environ.get("DAILY_ANALYZE_LIMIT", "50"))

# 数えた結果の置き場所。書ける場所であればどこでもよい。
STATE_PATH = Path(os.environ.get("USAGE_STATE_PATH", "/var/lib/keiri-ai/usage.json"))

# 集計の単位は日本時間の1日。UTCで数えると、日本の朝9時に日付が変わってしまう。
JST = timezone(timedelta(hours=9))

_lock = threading.Lock()


class DailyLimitExceeded(RuntimeError):
    """その日の上限に達した。"""

    def __init__(self, used: int, limit: int, resets_at: str) -> None:
        super().__init__(f"本日の利用上限({limit}回)に達しました")
        self.used = used
        self.limit = limit
        self.resets_at = resets_at


def _today() -> str:
    return datetime.now(JST).date().isoformat()


def _resets_at() -> str:
    tomorrow = datetime.now(JST).date() + timedelta(days=1)
    return f"{tomorrow.isoformat()} 00:00 (JST)"


def _read() -> dict:
    try:
        with STATE_PATH.open(encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {"date": _today(), "count": 0}
    if not isinstance(data, dict) or data.get("date") != _today():
        # 日付が変わっていれば0から数え直す
        return {"date": _today(), "count": 0}
    return {"date": data["date"], "count": int(data.get("count", 0))}


def _write(state: dict) -> None:
    STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_PATH.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(state, f)
    tmp.replace(STATE_PATH)  # 書きかけを読ませないため置き換えで反映する


def consume() -> dict:
    """1回ぶん使う。上限に達していれば DailyLimitExceeded を投げる。

    **数える前に呼ぶこと。** AIを呼んでから数えると、失敗した回が数から漏れる。
    漏れる方向の誤差は「思ったより多く使う」ことになるので、先に数える。
    """
    if DAILY_LIMIT <= 0:
        return {"used": 0, "limit": 0, "remaining": -1}

    with _lock:
        state = _read()
        if state["count"] >= DAILY_LIMIT:
            raise DailyLimitExceeded(state["count"], DAILY_LIMIT, _resets_at())
        state["count"] += 1
        try:
            _write(state)
        except OSError as exc:
            # 数えられないなら止める。課金が絡むので、疑わしいときは通さない。
            raise DailyLimitExceeded(-1, DAILY_LIMIT, _resets_at()) from exc
        return {
            "used": state["count"],
            "limit": DAILY_LIMIT,
            "remaining": DAILY_LIMIT - state["count"],
        }


def status() -> dict:
    """いまの消費状況。監視・確認用。"""
    if DAILY_LIMIT <= 0:
        return {"enabled": False, "used": 0, "limit": 0, "remaining": -1}
    state = _read()
    return {
        "enabled": True,
        "date": state["date"],
        "used": state["count"],
        "limit": DAILY_LIMIT,
        "remaining": max(0, DAILY_LIMIT - state["count"]),
        "resets_at": _resets_at(),
    }
