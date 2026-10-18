"""Adversarial stress-test suite for compound retrieval tool (get_topic_overview).

Empirically verifies:
1. Malformed topic IDs: empty string, whitespace, non-numeric strings, negative numbers, SQL injection fragments, Unicode/emojis.
2. Non-existent topic IDs: clean structured error {"error": ...} returned, never throwing unhandled exceptions.
3. Calculator topics: topicType == 'calc' handled gracefully without outline crashes.
4. Calculator and article overview contracts: outline returned without prefetch for articles, FULL content for calculators.
5. High concurrency / rapid calls: thread safety and concurrency behavior across workers.
"""

import concurrent.futures
import json
from pathlib import Path
import pytest

from database import ClinRefDatabase
import tools

DB_DIR = Path(__file__).parent.parent

pytestmark = pytest.mark.skipif(
    not (DB_DIR / "utdasset.sqlite").exists(),
    reason="UpToDate databases not found in project root",
)


@pytest.fixture(scope="module")
def db():
    d = ClinRefDatabase(DB_DIR)
    yield d
    d.close()


@pytest.fixture(autouse=True)
def setup_tools(db):
    tools.init_tools(db)


class TestAdversarialTopicIds:
    """Stress tests on malformed and adversarial topic IDs."""

    @pytest.mark.parametrize(
        "topic_id",
        [
            "",
            "   ",
            "\t\n",
            "abc",
            "topic_foo",
            "invalid!@#",
            "💊",
            "🩺",
            "123\u0000",
            "١٢٣",
            "🔥",
        ],
    )
    def test_malformed_non_numeric_topic_ids(self, topic_id):
        raw = tools.get_topic_overview.invoke({"topic_id": topic_id})
        data = json.loads(raw)
        assert "error" in data, f"Expected error for non-numeric/empty topic ID '{topic_id}', got: {data}"
        assert "Topic not found" in data["error"]

    @pytest.mark.parametrize(
        "topic_id",
        [
            "-1",
            "-999",
            "1' OR 1=1--",
            "1; DROP TABLE topic_asset;--",
            "' UNION SELECT * FROM topic_asset--",
            "topic-1-bad",
            "abc123def",
        ],
    )
    def test_malformed_numeric_and_sql_injection_topic_ids(self, topic_id):
        """Negative numbers and SQL injection fragments must NOT alias to valid topic 1."""
        raw = tools.get_topic_overview.invoke({"topic_id": topic_id})
        data = json.loads(raw)
        assert "error" in data, (
            f"VULNERABILITY: Malformed/injection topic ID '{topic_id}' was treated as valid topic "
            f"and returned title '{data.get('title')}' instead of returning structured error!"
        )
        assert "Topic not found" in data["error"]

    @pytest.mark.parametrize("topic_id", ["99999999", "0", "88888888"])
    def test_nonexistent_topic_ids(self, topic_id):
        raw = tools.get_topic_overview.invoke({"topic_id": topic_id})
        data = json.loads(raw)
        assert "error" in data
        assert f"Topic not found: {topic_id}" in data["error"]


class TestAdversarialCalculators:
    """Stress tests on calculator topics."""

    def test_calculator_graceful_handling(self):
        raw = tools.get_topic_overview.invoke({"topic_id": "148929"})
        data = json.loads(raw)
        assert "error" not in data
        assert data["topicId"] == "148929"
        assert data["topicType"] == "calc"
        assert len(data["sections"]) == 1
        assert data["sections"][0]["id"] == "FULL"
        assert "=== Calculator:" in data["markdown"]


class TestAdversarialOverviewInputs:
    """Stress tests on boundary topic_id inputs for get_topic_overview."""

    def test_overview_valid_topic_outline(self):
        raw = tools.get_topic_overview.invoke({"topic_id": "1"})
        data = json.loads(raw)
        assert "error" not in data
        assert len(data["sections"]) > 0
        assert data["topicType"] == "article"

    def test_overview_whitespace_topic_id(self):
        raw = tools.get_topic_overview.invoke({"topic_id": "   1   "})
        data = json.loads(raw)
        assert "error" not in data
        assert data["topicId"] == "1"

    def test_overview_empty_topic_id(self):
        raw = tools.get_topic_overview.invoke({"topic_id": ""})
        data = json.loads(raw)
        assert "error" in data

    def test_overview_nonexistent_topic_id(self):
        raw = tools.get_topic_overview.invoke({"topic_id": "99999999"})
        data = json.loads(raw)
        assert "error" in data


class TestAdversarialConcurrency:
    """Stress tests for concurrency and SQLite locking / thread crashes."""

    def test_rapid_sequential_calls(self):
        """50 rapid sequential calls must not leak or fail."""
        for i in range(50):
            tid = "1" if i % 2 == 0 else "148929"
            raw = tools.get_topic_overview.invoke({"topic_id": tid})
            data = json.loads(raw)
            assert "error" not in data

    def test_multithreaded_concurrency(self):
        """Concurrent calls across threads must be thread-safe without SQLite exceptions or memory corruption."""
        def call_tool(i):
            tid = "1" if i % 2 == 0 else "148929"
            return tools.get_topic_overview.invoke({"topic_id": tid})

        errors = []
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            futures = [executor.submit(call_tool, i) for i in range(30)]
            for f in concurrent.futures.as_completed(futures):
                try:
                    res = f.result()
                    data = json.loads(res)
                    if "error" in data:
                        errors.append(data["error"])
                except Exception as e:
                    errors.append(f"{type(e).__name__}: {str(e)}")

        assert len(errors) == 0, f"Concurrency test failed with errors: {errors}"
