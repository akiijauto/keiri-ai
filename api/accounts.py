"""勘定科目・税区分マスタ（freee準拠）。

全科目は網羅せず、デモで頻出する経費系を中心に構築する。
拡張はこのファイルにエントリを追加するだけでよい。

※ 実運用のfreeeでは事業所ごとに科目がカスタマイズされるため、
   受託案件では必ず先方の科目一覧を取得して差し替えること。
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True)
class Account:
    """勘定科目1件。"""

    name: str
    category: str          # 費用 / 資産 / 負債 など
    default_tax: str       # 既定の税区分キー
    keywords: tuple[str, ...] = field(default_factory=tuple)
    note: str = ""


# --- 税区分（freeeの表記に寄せる） ---
TAX_CODES: dict[str, str] = {
    "課対仕入10%": "課税仕入 10%",
    "課対仕入8%(軽)": "課税仕入 軽減税率8%",
    "非課仕入": "非課税仕入",
    "対象外": "不課税・対象外",
    "課対仕入10%(区分記載)": "課税仕入 10%（インボイス登録なし・経過措置対象）",
}

# --- 勘定科目マスタ ---
ACCOUNTS: list[Account] = [
    Account("消耗品費", "費用", "課対仕入10%",
            ("コピー用紙", "文具", "事務用品", "トナー", "カートリッジ", "ファイル")),
    Account("事務用品費", "費用", "課対仕入10%", ("事務用品", "ステーショナリー")),
    Account("会議費", "費用", "課対仕入8%(軽)",
            ("弁当", "会議", "打合せ", "茶菓"), note="持ち帰り飲食物は軽減税率8%"),
    Account("接待交際費", "費用", "課対仕入10%",
            ("懇親会", "接待", "会食", "贈答")),
    Account("地代家賃", "費用", "非課仕入",
            ("賃料", "家賃", "テナント", "事務所"), note="事業用建物の賃料は原則課税。契約内容を要確認"),
    Account("支払手数料", "費用", "課対仕入10%",
            ("手数料", "振込手数料", "仲介料")),
    Account("保険料", "費用", "非課仕入",
            ("保険", "火災保険", "損害保険"), note="保険料は非課税"),
    Account("荷造運賃", "費用", "課対仕入10%",
            ("配送", "運送", "送料", "宅配", "運賃")),
    Account("外注費", "費用", "課対仕入10%",
            ("制作", "デザイン", "外注", "委託", "開発")),
    Account("広告宣伝費", "費用", "課対仕入10%",
            ("広告", "宣伝", "バナー", "チラシ", "出稿")),
    Account("通信費", "費用", "課対仕入10%",
            ("電話", "インターネット", "回線", "通信")),
    Account("水道光熱費", "費用", "課対仕入10%",
            ("電気", "ガス", "水道", "光熱")),
    Account("旅費交通費", "費用", "課対仕入10%",
            ("交通費", "旅費", "宿泊", "出張")),
    Account("修繕費", "費用", "課対仕入10%", ("修理", "修繕", "メンテナンス")),
    Account("諸会費", "費用", "対象外", ("会費", "年会費", "組合費")),
    Account("租税公課", "費用", "対象外", ("印紙", "税", "収入印紙")),
    Account("買掛金", "負債", "対象外", ("仕入",)),
    Account("未払金", "負債", "対象外", ()),
]

ACCOUNT_NAMES: list[str] = [a.name for a in ACCOUNTS]


def as_prompt_table() -> str:
    """プロンプトに埋め込む科目一覧を生成する。"""
    lines = ["| 勘定科目 | 既定の税区分 | 判断キーワード | 備考 |",
             "|---|---|---|---|"]
    for a in ACCOUNTS:
        kw = "、".join(a.keywords) if a.keywords else "-"
        lines.append(f"| {a.name} | {a.default_tax} | {kw} | {a.note or '-'} |")
    return "\n".join(lines)


def tax_code_list() -> str:
    """プロンプトに埋め込む税区分一覧を生成する。"""
    return "\n".join(f"- {k}: {v}" for k, v in TAX_CODES.items())


def is_known_account(name: str) -> bool:
    return name in ACCOUNT_NAMES


def is_known_tax_code(code: str) -> bool:
    return code in TAX_CODES
