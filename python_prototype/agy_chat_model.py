"""LangChain BaseChatModel adapter over the local Antigravity CLI (`agy`).

Bridges ClinRef's two-phase LangGraph agent (`Phase 1: bind_tools` tool retrieval
and `Phase 2: zero-tool closed-book synthesis`) to `agy --print --output-format=json`
with an isolated temporary working directory so workspace files and slash commands
do not pollute clinical context.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import uuid
from typing import Any, Callable, Optional, Sequence

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import Field


# Every object in this schema declares explicit `properties` and `required` keys
# so it remains valid across Gemini, Claude, and GPT-OSS backends in `agy`.
PHASE1_TOOL_CALL_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "tool_calls": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "args_json": {"type": "string"},
                },
                "required": ["name", "args_json"],
            },
        },
        "content": {"type": "string"},
    },
    "required": ["tool_calls", "content"],
}

_MAX_CLI_PROMPT_BYTES = 100_000

_STATELESS_GUARDRAIL = (
    "HEADLESS MODEL ADAPTER INSTRUCTIONS (CRITICAL):\n"
    "- You are acting strictly as a stateless clinical language model inside an automated evaluation harness.\n"
    "- NEVER call any Antigravity IDE/workspace/shell/browser tools (read_file, run_command, grep_search, etc.).\n"
    "- Rely ONLY on the conversation messages and ClinRef tool results provided below."
)


def _strip_json_fences(raw: str) -> str:
    text = (raw or "").strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\s*```$", "", text)
    return text.strip()


def _parse_args_dict(raw_args: Any) -> dict[str, Any]:
    if isinstance(raw_args, dict):
        return raw_args
    if not raw_args or not isinstance(raw_args, str):
        return {}
    cleaned = _strip_json_fences(raw_args)
    try:
        parsed = json.loads(cleaned)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        match = re.search(r"\{.*\}", cleaned, re.DOTALL)
        if match:
            try:
                parsed = json.loads(match.group(0))
                return parsed if isinstance(parsed, dict) else {}
            except Exception:
                return {}
    return {}


def _extract_structured_from_response(raw: str) -> dict[str, Any]:
    """Decode one or more concatenated JSON objects from `agy`'s `response` string."""
    cleaned = _strip_json_fences(raw)
    if not cleaned:
        return {"tool_calls": [], "content": ""}

    decoder = json.JSONDecoder()
    objs: list[dict[str, Any]] = []
    idx = 0
    n = len(cleaned)
    while idx < n:
        start = cleaned.find("{", idx)
        if start < 0:
            break
        try:
            parsed, end_idx = decoder.raw_decode(cleaned, start)
            if isinstance(parsed, dict):
                objs.append(parsed)
            idx = end_idx
        except json.JSONDecodeError:
            idx = start + 1

    if not objs:
        return {"tool_calls": [], "content": raw}

    # If multi-turn CLI execution concatenated multiple JSON envelopes, prefer the last
    # object carrying non-empty tool_calls; otherwise fall back to the final object.
    with_calls = [obj for obj in objs if obj.get("tool_calls")]
    return with_calls[-1] if with_calls else objs[-1]


def _serialize_bound_tool(tool: Any) -> dict[str, Any]:
    """Normalize a LangChain BaseTool / dict / callable into {name, description, parameters}."""
    if isinstance(tool, dict) and "name" in tool and "parameters" in tool:
        return {
            "name": str(tool["name"]),
            "description": str(tool.get("description", "")),
            "parameters": tool.get("parameters") or {},
        }
    openai_spec = convert_to_openai_tool(tool)
    fn = openai_spec.get("function", openai_spec)
    return {
        "name": str(fn.get("name", "")),
        "description": str(fn.get("description", "")),
        "parameters": fn.get("parameters") or {},
    }


def _extract_agy_json_envelope(stdout: str) -> Optional[dict[str, Any]]:
    """Parse the final JSON status object emitted by `agy --output-format=json`."""
    lines = [line.strip() for line in (stdout or "").splitlines() if line.strip()]
    for line in reversed(lines):
        if line.startswith("{") and line.endswith("}"):
            try:
                data = json.loads(line)
                if isinstance(data, dict) and ("status" in data or "response" in data or "structured_output" in data):
                    return data
            except Exception:
                continue
    return None


class AgyChatModel(BaseChatModel):
    """LangChain chat model backed by the local `agy` CLI (`--print`)."""

    model_name: Optional[str] = None
    temperature: float = 0.15
    timeout: int = 180
    retries: int = 1
    effort: Optional[str] = None
    agy_binary: str = "agy"
    max_prompt_bytes: int = _MAX_CLI_PROMPT_BYTES
    bound_tools: list[dict[str, Any]] = Field(default_factory=list)
    runner: Optional[Callable[..., Any]] = Field(default=None, exclude=True)

    @property
    def _llm_type(self) -> str:
        return "agy-cli"

    @property
    def _identifying_params(self) -> dict[str, Any]:
        return {"model_name": self.model_name or "agy-default"}

    def bind_tools(
        self,
        tools: Sequence[Any],
        **kwargs: Any,
    ) -> "AgyChatModel":
        """Bind ClinRef retrieval tools via structured JSON tool-call emulation."""
        serialized = [_serialize_bound_tool(t) for t in tools]
        return self.model_copy(update={"bound_tools": serialized})

    def _format_messages_prompt(self, messages: list[BaseMessage]) -> str:
        """Render LangChain message history + optional tool schema instructions."""
        call_id_to_name: dict[str, str] = {}
        system_parts: list[str] = []
        turn_blocks: list[str] = []

        for msg in messages:
            content = msg.content if isinstance(msg.content, str) else json.dumps(msg.content, ensure_ascii=False)
            if isinstance(msg, SystemMessage):
                if content.strip():
                    system_parts.append(content.strip())
            elif isinstance(msg, HumanMessage):
                turn_blocks.append(f"[USER]\n{content}")
            elif isinstance(msg, AIMessage):
                tcs = getattr(msg, "tool_calls", None) or []
                for tc in tcs:
                    tc_id = str(tc.get("id") or "")
                    tc_name = str(tc.get("name") or "")
                    if tc_id and tc_name:
                        call_id_to_name[tc_id] = tc_name
                if tcs:
                    tc_str = json.dumps(
                        [{"name": tc.get("name"), "args": tc.get("args", {})} for tc in tcs],
                        ensure_ascii=False,
                    )
                    turn_blocks.append(f"[ASSISTANT]\n{content}\n<tool_calls>{tc_str}</tool_calls>".strip())
                else:
                    turn_blocks.append(f"[ASSISTANT]\n{content}")
            elif isinstance(msg, ToolMessage):
                tc_id = str(getattr(msg, "tool_call_id", "") or "")
                t_name = call_id_to_name.get(tc_id, "tool")
                turn_blocks.append(f"[TOOL RESULT: {t_name} (id={tc_id})]\n{content}")
            else:
                role = getattr(msg, "type", "message").upper()
                turn_blocks.append(f"[{role}]\n{content}")

        sections: list[str] = [_STATELESS_GUARDRAIL]
        if system_parts:
            sections.append("[SYSTEM INSTRUCTIONS]\n" + "\n\n".join(system_parts))

        if self.bound_tools:
            tools_json = json.dumps(self.bound_tools, separators=(",", ":"), ensure_ascii=False)
            sections.append(
                "[AVAILABLE CLINREF TOOLS]\n"
                f"{tools_json}\n\n"
                "[RESPONSE FORMAT REQUIREMENT]\n"
                "Respond with a JSON object matching the required schema:\n"
                "- To call one or more ClinRef tools: set `tool_calls` to an array of "
                "`{\"name\": \"<exact tool name>\", \"args_json\": \"<JSON object string of arguments>\"}` "
                "and leave `content` as `\"\"`.\n"
                "- Only after all required evidence sections/tables are retrieved, set `tool_calls` to `[]` "
                "and put a brief note in `content`. Do NOT emit a tool call named `finish`."
            )
        else:
            sections.append(
                "[CLOSED-BOOK SYNTHESIS MODE]\n"
                "No tools are available in this phase. Output ONLY the final clinical response text "
                "following the system instructions and directive cleanly, with no extra preamble or JSON wrapper."
            )

        # Compact oldest middle turn blocks if prompt approaches Linux CLI argv limits.
        header = "\n\n".join(sections)
        convo_text = "\n\n".join(turn_blocks)
        full_prompt = f"{header}\n\n[CONVERSATION]\n{convo_text}" if convo_text else header
        if len(full_prompt.encode("utf-8")) <= self.max_prompt_bytes:
            return full_prompt

        kept_tail = list(turn_blocks)
        while len(kept_tail) > 2:
            candidate = f"{header}\n\n[CONVERSATION]\n" + "\n\n".join(kept_tail)
            if len(candidate.encode("utf-8")) <= self.max_prompt_bytes:
                return candidate
            # Drop the oldest middle assistant/tool message pair while keeping the original user query.
            if (
                len(kept_tail) > 3
                and kept_tail[1].startswith("[ASSISTANT]")
                and kept_tail[2].startswith("[TOOL RESULT")
            ):
                kept_tail.pop(1)
                kept_tail.pop(1)
            else:
                kept_tail.pop(1)

        candidate = f"{header}\n\n[CONVERSATION]\n" + "\n\n".join(kept_tail)
        if len(candidate.encode("utf-8")) <= self.max_prompt_bytes:
            return candidate
        encoded = candidate.encode("utf-8")
        return encoded[: self.max_prompt_bytes].decode("utf-8", errors="ignore")

    def _invoke_cli(self, prompt: str, *, use_schema: bool) -> dict[str, Any]:
        """Run `agy --print` in an isolated temp workspace and return the parsed envelope."""
        if self.runner is None and not shutil.which(self.agy_binary):
            raise RuntimeError(
                f"Antigravity CLI binary {self.agy_binary!r} not found on PATH."
            )

        cmd = [
            self.agy_binary,
            "--disable-slash-commands",
            "--dangerously-skip-permissions",
            "--output-format=json",
            f"--print-timeout={max(5, int(self.timeout))}s",
        ]
        if self.model_name and self.model_name not in ("default", "agy"):
            cmd.append(f"--model={self.model_name}")
        if self.effort:
            cmd.append(f"--effort={self.effort}")
        if use_schema:
            cmd.append(f"--json-schema={json.dumps(PHASE1_TOOL_CALL_SCHEMA, separators=(',', ':'))}")
        cmd.append(f"--print={prompt}")

        last_error = ""
        for attempt in range(self.retries + 1):
            with tempfile.TemporaryDirectory(prefix="clinref_agy_") as tmpdir:
                env = os.environ.copy()
                try:
                    if self.runner is not None:
                        proc = self.runner(cmd, cwd=tmpdir, timeout=self.timeout)
                    else:
                        proc = subprocess.run(
                            cmd,
                            cwd=tmpdir,
                            stdin=subprocess.DEVNULL,
                            capture_output=True,
                            text=True,
                            timeout=self.timeout + 15,
                            env=env,
                        )
                except subprocess.TimeoutExpired:
                    last_error = f"agy CLI timed out after {self.timeout}s"
                    continue

                envelope = _extract_agy_json_envelope(getattr(proc, "stdout", "") or "")
                if envelope is not None:
                    structured = envelope.get("structured_output")
                    resp_str = str(envelope.get("response") or "").strip()
                    is_error = str(envelope.get("status") or "").upper() == "ERROR"
                    if use_schema and is_error and not isinstance(structured, dict):
                        has_payload = False
                    else:
                        has_payload = isinstance(structured, dict) or bool(resp_str)
                    if has_payload:
                        return envelope
                    last_error = str(envelope.get("error") or envelope.get("status") or "Empty agy response")
                else:
                    stdout_txt = (getattr(proc, "stdout", "") or "").strip()
                    stderr_txt = (getattr(proc, "stderr", "") or "").strip()
                    if getattr(proc, "returncode", 1) == 0 and stdout_txt and not use_schema:
                        return {"status": "SUCCESS", "response": stdout_txt, "usage": {}}
                    last_error = stderr_txt or stdout_txt or f"agy exited with code {getattr(proc, 'returncode', 1)}"

        raise RuntimeError(f"agy CLI failed after {self.retries + 1} attempt(s): {last_error}")

    def _to_ai_message(self, envelope: dict[str, Any]) -> tuple[AIMessage, dict[str, int]]:
        """Convert parsed `agy` output envelope into AIMessage + LangChain token_usage dict."""
        usage_raw = envelope.get("usage") or {}
        in_tok = int(usage_raw.get("input_tokens") or 0)
        out_tok = int(usage_raw.get("output_tokens") or 0)
        think_tok = int(usage_raw.get("thinking_tokens") or 0)
        tot_tok = int(usage_raw.get("total_tokens") or (in_tok + out_tok + think_tok))

        token_usage = {
            "prompt_tokens": in_tok,
            "completion_tokens": out_tok + think_tok,
            "total_tokens": tot_tok,
        }
        usage_metadata = {
            "input_tokens": in_tok,
            "output_tokens": out_tok + think_tok,
            "total_tokens": tot_tok,
        }

        if self.bound_tools:
            structured = envelope.get("structured_output")
            if not isinstance(structured, dict):
                structured = _extract_structured_from_response(str(envelope.get("response") or ""))

            canonical_map = {t["name"].lower(): t["name"] for t in self.bound_tools if t.get("name")}
            parsed_calls: list[dict[str, Any]] = []
            finish_content = ""
            for raw_tc in (structured.get("tool_calls") or []):
                if not isinstance(raw_tc, dict):
                    continue
                raw_name = str(raw_tc.get("name") or "").strip()
                if not raw_name:
                    continue
                if "args" in raw_tc and isinstance(raw_tc["args"], dict):
                    args_dict = raw_tc["args"]
                else:
                    args_dict = _parse_args_dict(raw_tc.get("args_json", "{}"))
                if raw_name.lower() == "finish" and "finish" not in canonical_map:
                    finish_content = str(args_dict.get("content") or args_dict.get("answer") or finish_content).strip()
                    continue
                name = canonical_map.get(raw_name.lower(), raw_name)
                parsed_calls.append(
                    {
                        "name": name,
                        "args": args_dict,
                        "id": f"agy_tc_{uuid.uuid4().hex[:8]}",
                        "type": "tool_call",
                    }
                )

            text = str(structured.get("content") or finish_content or "").strip()
            if not parsed_calls and not text:
                text = "Evidence gathering complete."
            msg = AIMessage(content=text, tool_calls=parsed_calls, usage_metadata=usage_metadata)
            return msg, token_usage

        text = str(envelope.get("response") or "").strip()
        msg = AIMessage(content=text, usage_metadata=usage_metadata)
        return msg, token_usage

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: Optional[list[str]] = None,
        run_manager: Optional[CallbackManagerForLLMRun] = None,
        **kwargs: Any,
    ) -> ChatResult:
        prompt = self._format_messages_prompt(messages)
        envelope = self._invoke_cli(prompt, use_schema=bool(self.bound_tools))
        ai_msg, token_usage = self._to_ai_message(envelope)
        return ChatResult(
            generations=[ChatGeneration(message=ai_msg)],
            llm_output={"token_usage": token_usage, "model_name": self.model_name or "agy-default"},
        )


def call_gemini_via_agy(
    prompt: str,
    model: Optional[str] = None,
    timeout: int = 180,
    retries: int = 1,
) -> str:
    """Spawn `agy --print` in an isolated temp workspace to get plain-text output."""
    chat_model = AgyChatModel(
        model_name=model or "",
        timeout=float(timeout),
        retries=retries,
    )
    envelope = chat_model._invoke_cli(prompt, use_schema=False)
    resp = str(envelope.get("response") or "").strip()
    if not resp and str(envelope.get("status") or "").upper() == "ERROR":
        err = str(envelope.get("error") or "Unknown agy error")
        raise RuntimeError(f"agy CLI execution failed: {err}")
    return resp
