"""Token usage tracker for LangChain callbacks."""

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult


class TokenTracker(BaseCallbackHandler):
    """Tracks cumulative token usage across all LLM and Gemini Decision calls."""

    def __init__(self):
        self.total_input = 0
        self.total_output = 0
        self.total_tokens = 0
        self.call_count = 0
        self.decision_calls = 0
        self.decision_input_tokens = 0

    def on_llm_end(self, response: LLMResult, **kwargs) -> None:
        """Extract token usage from LLM response."""
        if response.llm_output and "token_usage" in response.llm_output:
            usage = response.llm_output["token_usage"]
            self.total_input += usage.get("prompt_tokens", 0)
            self.total_output += usage.get("completion_tokens", 0)
            self.total_tokens += usage.get("total_tokens", 0)
            self.call_count += 1

    def on_decision_end(self, input_tokens: int) -> None:
        """Record one Gemini Decision API call (always 0 output tokens)."""
        self.decision_calls += 1
        self.decision_input_tokens += int(input_tokens or 0)

    def get_summary(self) -> dict:
        """Return cumulative token usage summary."""
        summary = {
            "llm_calls": self.call_count,
            "input_tokens": self.total_input,
            "output_tokens": self.total_output,
            "total_tokens": self.total_tokens,
        }
        if self.decision_calls > 0:
            summary["decision_calls"] = self.decision_calls
            summary["decision_input_tokens"] = self.decision_input_tokens
        return summary

    def print_summary(self, elapsed_seconds: float | None = None):
        """Print formatted token usage summary."""
        summary = self.get_summary()
        print(f"\n{'='*40}")
        print(f"Token Usage Summary")
        print(f"{'='*40}")
        if elapsed_seconds is not None:
            print(f"Elapsed:        {elapsed_seconds:.2f}s")
        print(f"LLM calls:      {summary['llm_calls']}")
        print(f"Input tokens:   {summary['input_tokens']:,}")
        print(f"Output tokens:  {summary['output_tokens']:,}")
        print(f"Total tokens:   {summary['total_tokens']:,}")
        if self.decision_calls > 0:
            est_cost = (self.decision_input_tokens / 1_000_000.0) * 0.10
            print(f"Decision calls: {self.decision_calls}")
            print(f"Decision in:    {self.decision_input_tokens:,} (0 out, ~${est_cost:.4f})")
        print(f"{'='*40}")
