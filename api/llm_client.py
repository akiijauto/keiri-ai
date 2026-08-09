"""
LLM抽象化レイヤー

アプリ本体はこのモジュールだけを呼び出す。
モデル名やプロバイダ固有のSDKをアプリ側に漏らさないことで、
将来クライアント案件でモデル指定が変わっても差し替えるだけで済む設計にする。

既定プロバイダ: gemini（コスト抑制）
切替: 環境変数 LLM_PROVIDER=gemini | claude
"""

from __future__ import annotations

import json
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any

from dotenv import load_dotenv

load_dotenv()


@dataclass
class LLMResponse:
    """プロバイダに依存しない共通のレスポンス型。"""

    text: str
    raw: Any = None

    def as_json(self) -> dict:
        """JSON文字列として返された応答をdictに変換する。

        モデルがコードフェンス（```json ... ```）を付ける場合があるため除去する。
        """
        cleaned = self.text.strip()
        if cleaned.startswith("```"):
            lines = [ln for ln in cleaned.splitlines() if not ln.strip().startswith("```")]
            cleaned = "\n".join(lines).strip()
        return json.loads(cleaned)


class BaseLLMClient(ABC):
    """全プロバイダ共通のインターフェース。"""

    @abstractmethod
    def generate(self, prompt: str, *, system: str | None = None) -> LLMResponse:
        """テキストプロンプトから応答を生成する。"""

    @abstractmethod
    def generate_from_image(
        self, prompt: str, image_bytes: bytes, *, mime_type: str = "image/png",
        system: str | None = None,
    ) -> LLMResponse:
        """画像＋プロンプトから応答を生成する（請求書の読取に使用）。"""


class GeminiClient(BaseLLMClient):
    """Gemini実装（既定）。"""

    def __init__(self, model: str = "gemini-2.0-flash") -> None:
        import google.generativeai as genai

        api_key = os.environ.get("GEMINI_API_KEY")
        if not api_key:
            raise RuntimeError("GEMINI_API_KEY が設定されていません（.env を確認してください）")
        genai.configure(api_key=api_key)
        self._genai = genai
        self._model_name = model

    def _model(self, system: str | None):
        return self._genai.GenerativeModel(
            self._model_name, system_instruction=system
        )

    def generate(self, prompt: str, *, system: str | None = None) -> LLMResponse:
        result = self._model(system).generate_content(prompt)
        return LLMResponse(text=result.text, raw=result)

    def generate_from_image(
        self, prompt: str, image_bytes: bytes, *, mime_type: str = "image/png",
        system: str | None = None,
    ) -> LLMResponse:
        result = self._model(system).generate_content(
            [{"mime_type": mime_type, "data": image_bytes}, prompt]
        )
        return LLMResponse(text=result.text, raw=result)


class ClaudeClient(BaseLLMClient):
    """Claude実装（任意。クライアント案件でモデル指定がある場合に使用）。"""

    def __init__(self, model: str = "claude-sonnet-4-6") -> None:
        raise NotImplementedError(
            "Claudeプロバイダは未実装です。必要になった時点で実装してください。"
        )

    def generate(self, prompt: str, *, system: str | None = None) -> LLMResponse:
        raise NotImplementedError

    def generate_from_image(
        self, prompt: str, image_bytes: bytes, *, mime_type: str = "image/png",
        system: str | None = None,
    ) -> LLMResponse:
        raise NotImplementedError


_PROVIDERS: dict[str, type[BaseLLMClient]] = {
    "gemini": GeminiClient,
    "claude": ClaudeClient,
}


def get_client() -> BaseLLMClient:
    """環境変数 LLM_PROVIDER に応じたクライアントを返す。

    アプリ本体はこの関数だけを呼ぶ。
    """
    provider = os.environ.get("LLM_PROVIDER", "gemini").lower()
    if provider not in _PROVIDERS:
        raise ValueError(
            f"未対応のプロバイダです: {provider}（対応: {', '.join(_PROVIDERS)}）"
        )
    return _PROVIDERS[provider]()
