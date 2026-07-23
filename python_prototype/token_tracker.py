"""Token usage tracker for LangChain callbacks."""

from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.outputs import LLMResult


class TokenTracker(BaseCallbackHandler):
    """Tracks cumulative token usage across all LLM calls."""

    def __init__(self):
        self.total_input = 0
        self.total_output = 0
        self.total_tokens = 0
        self.call_count = 0

    def on_llm_end(self, response: LLMResult, **kwargs) -> None:
        """Extract token usage from LLM response."""
        if response.llm_output and "token_usage" in response.llm_output:
            usage = response.llm_output["token_usage"]
            self.total_input += usage.get("prompt_tokens", 0)
            self.total_output += usage.get("completion_tokens", 0)
            self.total_tokens += usage.get("total_tokens", 0)
            self.call_count += 1

    def get_summary(self) -> dict:
        """Return cumulative token usage summary."""
        return {
            "llm_calls": self.call_count,
            "input_tokens": self.total_input,
            "output_tokens": self.total_output,
            "total_tokens": self.total_tokens,
        }

    def print_summary(self):
        """Print formatted token usage summary."""
        summary = self.get_summary()
        print(f"\n{'='*40}")
        print(f"Token Usage Summary")
        print(f"{'='*40}")
        print(f"LLM calls:      {summary['llm_calls']}")
        print(f"Input tokens:   {summary['input_tokens']:,}")
        print(f"Output tokens:  {summary['output_tokens']:,}")
        print(f"Total tokens:   {summary['total_tokens']:,}")
        print(f"{'='*40}")
