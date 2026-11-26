"""Shared client wrapper for Google's Gemini Decisions API (`gemini-decision-1`).

The Decisions API evaluates up to 1,000 closed-ended questions (`noul`, `choice`,
`score`) against a shared multimodal/text input in a single prefill forward pass,
returning calibrated outcome probabilities and confidence scores with zero output
tokens.

Requires `google-genai >= 3.2` and `api_version = "v1beta"`.
"""

from __future__ import annotations

import os
import re
from typing import Any, Callable, Literal, Optional, Union

from pydantic import BaseModel, Field

DECISION_MODEL_ID = "gemini-decision-1"
DECISIONS_API_VERSION = "v1beta"
MAX_QUESTIONS_PER_REQUEST = 1000
MAX_CHOICE_OPTIONS = 255
MAX_SCORE_LEVELS = 255
MAX_QUESTION_KEY_LEN = 64
MAX_CHOICE_KEY_LEN = 128
MAX_CHOICE_KEY_BYTES = 512
DECISION_INPUT_COST_PER_M_TOKENS = 0.10

_INVALID_KEY_CHARS_RE = re.compile(r"[^A-Za-z0-9_-]+")


def sanitize_question_key(raw: str, prefix: str = "q", index: int = 0) -> str:
    """Sanitize a key to match Decisions API rule: `[A-Za-z0-9_-]+`, 1..64 ASCII chars."""
    cleaned = _INVALID_KEY_CHARS_RE.sub("_", (raw or "").strip()).strip("_")
    if not cleaned:
        cleaned = f"{prefix}_{index}"
    elif not cleaned[0].isalnum():
        cleaned = f"{prefix}_{cleaned}"
    if len(cleaned) > MAX_QUESTION_KEY_LEN:
        cleaned = cleaned[:MAX_QUESTION_KEY_LEN]
    return cleaned


def make_text_blocks(blocks: list[str]) -> list[dict[str, str]]:
    """Build Decisions API text content blocks with explicit newline separation.

    The Decisions API concatenates adjacent text blocks without adding delimiters,
    so callers must supply separators explicitly.
    """
    non_empty = [b for b in blocks if b]
    if not non_empty:
        return []
    out: list[dict[str, str]] = []
    for i, text in enumerate(non_empty):
        suffix = "\n\n" if i < len(non_empty) - 1 and not text.endswith("\n") else ""
        out.append({"type": "text", "text": text + suffix})
    return out


def noul_question(
    instructions: str,
    *,
    true_description: str = "",
    false_description: str = "",
) -> dict[str, Any]:
    """Construct a yes/no (`noul`) question payload."""
    instructions_clean = (instructions or "").strip()
    if not instructions_clean:
        raise ValueError("Noul question instructions must be non-empty.")
    q: dict[str, Any] = {
        "type": "noul",
        "instructions": instructions_clean,
    }
    if true_description or false_description:
        criteria: dict[str, str] = {}
        if true_description:
            criteria["true_description"] = true_description.strip()
        if false_description:
            criteria["false_description"] = false_description.strip()
        q["criteria"] = criteria
    return q


def choice_question(instructions: str, criteria: dict[str, str]) -> dict[str, Any]:
    """Construct a single-select (`choice`) question payload (2..255 options)."""
    instructions_clean = (instructions or "").strip()
    if not instructions_clean:
        raise ValueError("Choice question instructions must be non-empty.")
    if not (2 <= len(criteria) <= MAX_CHOICE_OPTIONS):
        raise ValueError(
            f"Choice question requires 2 to {MAX_CHOICE_OPTIONS} options, got {len(criteria)}."
        )
    cleaned_criteria: dict[str, str] = {}
    for k, v in criteria.items():
        raw_k = str(k)
        stripped_k = raw_k.strip()
        if raw_k != stripped_k:
            raise ValueError(
                f"Choice option key must not contain leading/trailing whitespace: {raw_k!r}"
            )
        if not (1 <= len(stripped_k) <= MAX_CHOICE_KEY_LEN):
            raise ValueError(
                f"Choice option key must be 1..{MAX_CHOICE_KEY_LEN} chars: {stripped_k!r}"
            )
        if len(stripped_k.encode("utf-8")) > MAX_CHOICE_KEY_BYTES:
            raise ValueError(
                f"Choice option key exceeds {MAX_CHOICE_KEY_BYTES} UTF-8 bytes: {stripped_k!r}"
            )
        if any(ord(c) < 32 or ord(c) == 127 for c in stripped_k):
            raise ValueError(
                f"Choice option key must not contain control characters: {stripped_k!r}"
            )
        cleaned_criteria[stripped_k] = str(v).strip()
    return {
        "type": "choice",
        "instructions": instructions_clean,
        "criteria": cleaned_criteria,
    }


def score_question(instructions: str, criteria: list[str]) -> dict[str, Any]:
    """Construct an ordered rubric (`score`) question payload (2..255 non-empty levels)."""
    instructions_clean = (instructions or "").strip()
    if not instructions_clean:
        raise ValueError("Score question instructions must be non-empty.")
    levels = [str(level).strip() for level in criteria if str(level).strip()]
    if not (2 <= len(levels) <= MAX_SCORE_LEVELS):
        raise ValueError(
            f"Score question requires 2 to {MAX_SCORE_LEVELS} non-empty levels, got {len(levels)}."
        )
    return {
        "type": "score",
        "instructions": instructions_clean,
        "criteria": levels,
    }


# ── Normalized Answer & Usage Models ─────────────────────────────────────────


class DecisionNoulAnswer(BaseModel):
    type: Literal["noul"] = "noul"
    noul: float
    confidence: float

    @property
    def p_false(self) -> float:
        return max(0.0, min(1.0, 1.0 - self.noul))

    def is_true(self, threshold: float = 0.5) -> bool:
        return self.noul >= threshold


class DecisionChoiceAnswer(BaseModel):
    type: Literal["choice"] = "choice"
    choice: str
    probabilities: dict[str, float] = Field(default_factory=dict)
    confidence: float


class DecisionScoreAnswer(BaseModel):
    type: Literal["score"] = "score"
    score: float
    probabilities: dict[str, float] = Field(default_factory=dict)
    legend: dict[str, str] = Field(default_factory=dict)
    confidence: float

    def normalized_score(self) -> float:
        """Normalize expected score onto [0, 1] by dividing by highest level index."""
        max_idx = max(1, len(self.legend) - 1) if self.legend else max(1, len(self.probabilities) - 1)
        return max(0.0, min(1.0, self.score / max_idx))

    def top_level(self) -> str:
        """Return the string level index with the highest calibrated probability."""
        if not self.probabilities:
            return "0"
        return max(self.probabilities, key=lambda k: self.probabilities[k])


DecisionAnswer = Union[DecisionNoulAnswer, DecisionChoiceAnswer, DecisionScoreAnswer]


class DecisionUsage(BaseModel):
    total_input_tokens: int = 0
    total_output_tokens: int = 0
    total_tokens: int = 0


class DecisionBatchResult(BaseModel):
    answers: dict[str, DecisionAnswer] = Field(default_factory=dict)
    usage: DecisionUsage = Field(default_factory=DecisionUsage)
    ok: bool = True
    error: Optional[str] = None

    def get_noul(self, key: str) -> Optional[DecisionNoulAnswer]:
        ans = self.answers.get(key)
        return ans if isinstance(ans, DecisionNoulAnswer) else None

    def get_choice(self, key: str) -> Optional[DecisionChoiceAnswer]:
        ans = self.answers.get(key)
        return ans if isinstance(ans, DecisionChoiceAnswer) else None

    def get_score(self, key: str) -> Optional[DecisionScoreAnswer]:
        ans = self.answers.get(key)
        return ans if isinstance(ans, DecisionScoreAnswer) else None


def resolve_decision_api_key(explicit_key: Optional[str] = None) -> Optional[str]:
    """Resolve Google/Gemini API key for the Decisions API."""
    if explicit_key:
        return explicit_key
    return os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")


def _normalize_answer(raw: Any) -> Optional[DecisionAnswer]:
    """Convert a google-genai answer object or raw dict into a normalized model."""
    if raw is None:
        return None
    if isinstance(raw, (DecisionNoulAnswer, DecisionChoiceAnswer, DecisionScoreAnswer)):
        return raw

    if isinstance(raw, dict):
        data = raw
    elif hasattr(raw, "model_dump"):
        data = raw.model_dump()
    else:
        a_type = getattr(raw, "type", None)
        if a_type == "noul":
            data = {"type": "noul", "noul": getattr(raw, "noul", 0.5), "confidence": getattr(raw, "confidence", 0.5)}
        elif a_type == "choice":
            data = {
                "type": "choice",
                "choice": getattr(raw, "choice", ""),
                "probabilities": dict(getattr(raw, "probabilities", {}) or {}),
                "confidence": getattr(raw, "confidence", 0.0),
            }
        elif a_type == "score":
            data = {
                "type": "score",
                "score": getattr(raw, "score", 0.0),
                "probabilities": dict(getattr(raw, "probabilities", {}) or {}),
                "legend": dict(getattr(raw, "legend", {}) or {}),
                "confidence": getattr(raw, "confidence", 0.0),
            }
        else:
            return None

    q_type = data.get("type")
    if q_type == "noul":
        return DecisionNoulAnswer(**data)
    if q_type == "choice":
        return DecisionChoiceAnswer(**data)
    if q_type == "score":
        return DecisionScoreAnswer(**data)
    return None


def _extract_usage(raw_usage: Any) -> DecisionUsage:
    if raw_usage is None:
        return DecisionUsage()
    if isinstance(raw_usage, dict):
        return DecisionUsage(
            total_input_tokens=int(raw_usage.get("total_input_tokens") or 0),
            total_output_tokens=int(raw_usage.get("total_output_tokens") or 0),
            total_tokens=int(raw_usage.get("total_tokens") or 0),
        )
    return DecisionUsage(
        total_input_tokens=int(getattr(raw_usage, "total_input_tokens", None) or 0),
        total_output_tokens=int(getattr(raw_usage, "total_output_tokens", None) or 0),
        total_tokens=int(getattr(raw_usage, "total_tokens", None) or 0),
    )


class DecisionClient:
    """Wrapper over `google.genai.Client.decisions` with chunking, usage tracking, and fail-open option."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = DECISION_MODEL_ID,
        sdk_client: Any = None,
        fail_open: bool = True,
        on_usage: Optional[Callable[[int], None]] = None,
    ):
        self.api_key = resolve_decision_api_key(api_key)
        self.model = model
        self._sdk_client = sdk_client
        self.fail_open = fail_open
        self.on_usage = on_usage

        self.total_calls: int = 0
        self.total_input_tokens: int = 0
        self.failed_calls: int = 0
        self.last_error: Optional[str] = None

    @classmethod
    def from_env(
        cls,
        api_key: Optional[str] = None,
        model: str = DECISION_MODEL_ID,
        fail_open: bool = True,
        on_usage: Optional[Callable[[int], None]] = None,
    ) -> "DecisionClient":
        return cls(
            api_key=resolve_decision_api_key(api_key),
            model=model,
            fail_open=fail_open,
            on_usage=on_usage,
        )

    def is_configured(self) -> bool:
        return self._sdk_client is not None or bool(self.api_key)

    def _get_sdk_client(self) -> Any:
        if self._sdk_client is not None:
            return self._sdk_client
        if not self.api_key:
            raise ValueError(
                "No GEMINI_API_KEY or GOOGLE_API_KEY configured for Gemini Decisions API."
            )
        from google import genai

        self._sdk_client = genai.Client(
            api_key=self.api_key,
            http_options={"api_version": DECISIONS_API_VERSION},
        )
        return self._sdk_client

    def decide(
        self,
        input_data: Union[str, dict[str, Any], list[dict[str, Any]]],
        questions: dict[str, dict[str, Any]],
        *,
        model: Optional[str] = None,
    ) -> DecisionBatchResult:
        """Evaluate 1..N questions against `input_data`, chunking batches >1,000 automatically."""
        if not input_data:
            return DecisionBatchResult(ok=False, error="Decisions API input cannot be empty.")
        if not questions:
            return DecisionBatchResult(ok=True, answers={})

        target_model = model or self.model
        keys = list(questions.keys())
        merged_answers: dict[str, DecisionAnswer] = {}
        total_in = 0
        total_tok = 0

        try:
            sdk = self._get_sdk_client()
            for start in range(0, len(keys), MAX_QUESTIONS_PER_REQUEST):
                chunk_keys = keys[start : start + MAX_QUESTIONS_PER_REQUEST]
                chunk_questions = {k: questions[k] for k in chunk_keys}
                response = sdk.decisions.create(
                    model=target_model,
                    input=input_data,
                    questions=chunk_questions,
                )
                self.total_calls += 1
                usage = _extract_usage(getattr(response, "usage", None))
                total_in += usage.total_input_tokens
                total_tok += usage.total_tokens or usage.total_input_tokens
                self.total_input_tokens += usage.total_input_tokens
                if self.on_usage and usage.total_input_tokens:
                    self.on_usage(usage.total_input_tokens)

                raw_answers = getattr(response, "answers", None) or {}
                for k, raw_ans in raw_answers.items():
                    norm = _normalize_answer(raw_ans)
                    if norm is not None:
                        merged_answers[k] = norm

            self.last_error = None
            return DecisionBatchResult(
                answers=merged_answers,
                usage=DecisionUsage(
                    total_input_tokens=total_in,
                    total_output_tokens=0,
                    total_tokens=total_tok,
                ),
                ok=True,
            )
        except Exception as exc:
            self.failed_calls += 1
            self.last_error = str(exc)
            if not self.fail_open:
                raise
            return DecisionBatchResult(
                answers=merged_answers,
                usage=DecisionUsage(
                    total_input_tokens=total_in,
                    total_output_tokens=0,
                    total_tokens=total_tok,
                ),
                ok=False,
                error=str(exc),
            )

    def get_usage_summary(self) -> dict[str, Any]:
        return {
            "model": self.model,
            "decision_calls": self.total_calls,
            "failed_calls": self.failed_calls,
            "input_tokens": self.total_input_tokens,
            "output_tokens": 0,
            "estimated_cost_usd": round(
                (self.total_input_tokens / 1_000_000.0) * DECISION_INPUT_COST_PER_M_TOKENS,
                6,
            ),
            "last_error": self.last_error,
        }
