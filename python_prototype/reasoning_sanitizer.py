"""Clinical reasoning token hygiene sanitizer — mirrors Kotlin ReasoningTokenSanitizer.kt."""

import re
from typing import NamedTuple

REASONING_TAG_PATTERN = re.compile(
    r"(?is)<(thought|think|reasoning)(?:\s+[^>]*)?>([\s\S]*?)(?:</\1>|$)"
)


class SanitizedOutput(NamedTuple):
    clean_text: str
    reasoning_text: str


def sanitize_reasoning(raw_text: str) -> SanitizedOutput:
    """Extract reasoning blocks and return clean clinical text + isolated reasoning."""
    if not raw_text:
        return SanitizedOutput(clean_text="", reasoning_text="")

    extracted: list[str] = []
    for match in REASONING_TAG_PATTERN.finditer(raw_text):
        content = match.group(2).strip()
        if content:
            extracted.append(content)

    clean = REASONING_TAG_PATTERN.sub("", raw_text).strip()
    return SanitizedOutput(
        clean_text=clean,
        reasoning_text="\n\n".join(extracted),
    )


def strip_reasoning(raw_text: str) -> str:
    """Convenience helper returning only clean clinical response text."""
    if not raw_text:
        return ""
    return REASONING_TAG_PATTERN.sub("", raw_text).strip()


def extract_reasoning(raw_text: str) -> str:
    """Convenience helper returning isolated reasoning text."""
    return sanitize_reasoning(raw_text).reasoning_text


def contains_reasoning(raw_text: str) -> bool:
    """Return True if raw_text contains any reasoning or thought tags."""
    if not raw_text:
        return False
    return bool(REASONING_TAG_PATTERN.search(raw_text))


class ReasoningTokenSanitizer:
    """Pure deterministic sanitizer for chain-of-thought and reasoning tokens.

    Mirrors Kotlin ReasoningTokenSanitizer.kt.
    """

    @staticmethod
    def sanitize(raw: str) -> SanitizedOutput:
        return sanitize_reasoning(raw)

    @staticmethod
    def strip_reasoning(raw: str) -> str:
        return strip_reasoning(raw)

    @staticmethod
    def extract_reasoning(raw: str) -> str:
        return extract_reasoning(raw)

    @staticmethod
    def contains_reasoning(raw: str) -> bool:
        return contains_reasoning(raw)


class ReasoningStreamFilter:
    """Stateful chunk/delta filter for incremental reasoning token hygiene in Python streaming."""

    def __init__(self):
        self.clean_chunks: list[str] = []
        self.reasoning_chunks: list[str] = []
        self._tag_buffer: list[str] = []
        self._in_reasoning: bool = False

    def process_delta(self, delta: str) -> tuple[str, str]:
        """Process incoming delta chunk.

        Returns:
            tuple[clean_delta, reasoning_delta]
        """
        clean_out = []
        reasoning_out = []

        for ch in delta:
            if self._tag_buffer:
                self._tag_buffer.append(ch)
                buf_str = "".join(self._tag_buffer)
                if ch == ">":
                    self._tag_buffer.clear()
                    lower = buf_str.lower()
                    if lower in ("<thought>", "<think>", "<reasoning>") or lower.startswith(
                        ("<thought ", "<think ", "<reasoning ")
                    ):
                        self._in_reasoning = True
                    elif lower in ("</thought>", "</think>", "</reasoning>"):
                        self._in_reasoning = False
                    else:
                        if self._in_reasoning:
                            reasoning_out.append(buf_str)
                        else:
                            clean_out.append(buf_str)
                elif not self._is_potential_tag(buf_str) or len(buf_str) > 64:
                    self._tag_buffer.clear()
                    if self._in_reasoning:
                        reasoning_out.append(buf_str)
                    else:
                        clean_out.append(buf_str)
            else:
                if ch == "<":
                    self._tag_buffer.append(ch)
                else:
                    if self._in_reasoning:
                        reasoning_out.append(ch)
                    else:
                        clean_out.append(ch)

        clean_delta = "".join(clean_out)
        reasoning_delta = "".join(reasoning_out)

        if clean_delta:
            self.clean_chunks.append(clean_delta)
        if reasoning_delta:
            self.reasoning_chunks.append(reasoning_delta)

        return clean_delta, reasoning_delta

    @staticmethod
    def _is_potential_tag(s: str) -> bool:
        lower = s.lower()
        candidates = [
            "<", "<t", "<th", "<tho", "<thou", "<thoug", "<thought",
            "<thi", "<thin", "<think",
            "<r", "<re", "<rea", "<reas", "<reaso", "<reason", "<reasoni", "<reasonin", "<reasoning",
            "</", "</t", "</th", "</tho", "</thou", "</thoug", "</thought",
            "</thi", "</thin", "</think",
            "</r", "</re", "</rea", "</reas", "</reaso", "</reason", "</reasoni", "</reasonin", "</reasoning",
        ]
        if any(c.startswith(lower) for c in candidates):
            return True
        return lower.startswith(("<thought ", "<think ", "<reasoning "))

    @property
    def clean_text(self) -> str:
        return "".join(self.clean_chunks)

    @property
    def reasoning_text(self) -> str:
        return "".join(self.reasoning_chunks)
