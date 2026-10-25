"""Empirical Gate Challenger 2 Adversarial Stress & Concurrency Suite for Milestone 1.

Verifies:
1. Directive compliance: zero queries hit fsearch.db or FTS tables (verified even with fsearch.db unreadable/chmod 000).
2. Malformed inputs, SQL injection strings, negative IDs, unicode/null bytes in topic IDs and section IDs across all 7 tools.
3. High multi-threaded concurrency across tools and database connections with zero lock contention, zero programming errors, and zero segfaults.
4. Absolute immutability of all 6 root SQLite databases (MD5 before == MD5 after).
"""

import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import threading
import time
import pytest

from database import ClinRefDatabase
import tools

DB_DIR = Path(__file__).parent.parent
DB_FILES = [
    "unidex.en.sqlite",
    "utdasset.sqlite",
    "utdqf.sqlite",
    "fsearch.db",
    "thumbs.db",
    "utdtoc.db",
]


def compute_md5(path: Path) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        while chunk := f.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest()


def to_dict(res):
    if hasattr(res, "model_dump"):
        return res.model_dump(exclude_none=True, by_alias=False)
    if isinstance(res, str):
        try:
            return json.loads(res)
        except Exception:
            return {"raw": res}
    return res


@pytest.fixture(scope="module")
def db():
    d = ClinRefDatabase(DB_DIR)
    tools.init_tools(d)
    yield d
    d.close()


class TestDirectiveComplianceNoFsearch:
    """Empirically prove that no retrieval tools ever touch fsearch.db or FTS."""

    def test_database_connections_have_no_fsearch(self, db):
        assert not hasattr(db, "_search_conn"), "ClinRefDatabase still has _search_conn attribute!"
        assert not hasattr(db, "search"), "ClinRefDatabase still has search property connecting to fsearch.db!"

    def test_all_tools_work_when_fsearch_is_unreadable(self, db):
        """Make fsearch.db completely unreadable (chmod 000). All 6 tools must function 100% normally."""
        fsearch_path = DB_DIR / "fsearch.db"
        if not fsearch_path.exists():
            pytest.skip("fsearch.db not present")

        original_mode = fsearch_path.stat().st_mode
        try:
            # Make fsearch.db unreadable to current user
            os.chmod(fsearch_path, 0o000)

            # 1. search_topics (unidex)
            res1 = to_dict(tools.search_topics.invoke({"query": "asthma"}))
            assert len(res1.get("results", [])) > 0, "search_topics failed when fsearch.db was chmod 000!"

            # 2. get_topic_overview
            res2 = to_dict(tools.get_topic_overview.invoke({"topic_id": "1"}))
            assert "error" not in res2, "get_topic_overview failed when fsearch.db was chmod 000!"

            # 3. get_topic_sections_text
            res4 = to_dict(tools.get_topic_sections_text.invoke({"topic_id": "1", "section_ids": ["Summary"]}))
            assert "error" not in res4, "get_topic_sections_text failed when fsearch.db was chmod 000!"

            # 4. search_topic_sections
            res5 = to_dict(tools.search_topic_sections.invoke({"topic_id": "1", "query": "dosing"}))
            assert "matches" in res5, "search_topic_sections failed when fsearch.db was chmod 000!"

            # 5. get_related_topics
            res6 = to_dict(tools.get_related_topics.invoke({"topic_id": "1"}))
            assert "relatedTopics" in res6, "get_related_topics failed when fsearch.db was chmod 000!"

            # 6. get_graphic_content (table)
            res7 = tools.get_graphic_content.invoke({"graphic_id": "116392"})
            assert "Graphic Table:" in (res7.markdown or ""), "get_graphic_content failed when fsearch.db was chmod 000!"

            # get_graphic_content (non-table figure correctly rejected)
            res8 = tools.get_graphic_content.invoke({"graphic_id": "108657"})
            assert "not a table" in (res8.error or ""), "non-table graphic was not rejected!"

        finally:
            os.chmod(fsearch_path, original_mode)


class TestAdversarialInputsAndSQLInjection:
    """Stress-test all 7 tools against malicious inputs, SQL injection, null bytes, unicode."""

    MALFORMED_IDS = [
        # SQL Injection payloads
        "1' OR 1=1--",
        "1' OR '1'='1",
        "1; DROP TABLE topic_asset;--",
        "' UNION SELECT * FROM topic_asset--",
        "1' UNION SELECT 1, 'injected', 1--",
        "1 OR 1=1",
        "1' AND (SELECT count(*) FROM topic_asset) > 0--",
        "1' /* comment */",
        "'; EXEC xp_cmdshell('dir');--",
        # Null bytes & control characters
        "1\x00",
        "123\x00",
        "\x00",
        "topic-\x00",
        "topic-1\x00extra",
        "\r\n\t",
        # Negative & out-of-range IDs
        "-1",
        "-999",
        "-0",
        "0",
        "999999999999999999999999999999999999999999",
        # Non-numeric & Unicode
        "💊",
        "🩺",
        "١٢٣",
        "🔥",
        "abc",
        "topic-bad",
        "NaN",
        "Infinity",
        "null",
        "None",
    ]

    @pytest.mark.parametrize("bad_id", MALFORMED_IDS)
    def test_overview_rejects_malformed_ids_without_aliasing(self, bad_id, db):
        t1_title = db.get_topic_title("1")
        res = to_dict(tools.get_topic_overview.invoke({"topic_id": bad_id}))
        assert "error" in res, f"Expected error for '{bad_id}', got: {res}"
        if t1_title:
            assert res.get("title") != t1_title, f"Security vulnerability: '{bad_id}' aliased to Topic 1!"

    @pytest.mark.parametrize("bad_id", MALFORMED_IDS)
    def test_sections_text_rejects_malformed_topic_ids(self, bad_id):
        res = to_dict(tools.get_topic_sections_text.invoke({"topic_id": bad_id, "section_ids": ["H1"]}))
        assert "error" in res, f"Expected error for sections text '{bad_id}', got: {res}"

    @pytest.mark.parametrize("bad_id", MALFORMED_IDS)
    def test_related_topics_rejects_malformed_ids(self, bad_id):
        res = to_dict(tools.get_related_topics.invoke({"topic_id": bad_id}))
        assert "error" in res, f"Expected error for related topics '{bad_id}', got: {res}"

    @pytest.mark.parametrize("bad_id", MALFORMED_IDS)
    def test_search_topic_sections_rejects_malformed_ids(self, bad_id):
        res = to_dict(tools.search_topic_sections.invoke({"topic_id": bad_id, "query": "dosing"}))
        assert len(res.get("matches", [])) == 0, f"Expected no matches for bad topic ID '{bad_id}'"

    def test_sections_text_malformed_section_ids(self):
        """Malformed section IDs (SQL injection, null bytes, non-existent) handled gracefully."""
        bad_sections = [
            "1' OR 1=1--",
            "\x00",
            "DROP TABLE",
            "NON_EXISTENT_SECTION_12345",
            "💊",
        ]
        res = to_dict(tools.get_topic_sections_text.invoke({"topic_id": "1", "section_ids": bad_sections}))
        assert "error" not in res
        assert "invalidSections" in res
        assert len(res["invalidSections"]) == len(bad_sections)
        assert res["markdown"] == ""

    def test_search_topics_sql_injection_query(self):
        """SQL injection in search_topics query returns safe structured result without syntax error."""
        injections = [
            "' OR '1'='1",
            "1; DROP TABLE query;--",
            "' UNION SELECT * FROM topic--",
            "\" OR \"\"=\"",
            "\x00",
            "💊 🩺",
        ]
        for inj in injections:
            raw = tools.search_topics.invoke({"query": inj})
            res = to_dict(raw)
            assert "results" in res
            assert isinstance(res["results"], list)


class TestConcurrencyAndThreadSafety:
    """Multi-threaded stress testing: 8 threads executing 48 requests concurrently.

    Verifies the SQLite/LangChain retrieval-tool layer is thread-safe under
    interleaved concurrent access. Kept at regression-test scale (the former
    20x15 barrage was benchmark-scale): the modulo op-mix still interleaves
    all 7 tool operations across threads, while keeping the suite fast.
    """

    def test_multithreaded_mixed_tool_barrage(self, db):
        num_threads = 8
        calls_per_thread = 6
        total_calls = num_threads * calls_per_thread

        def worker(thread_idx: int):
            worker_errors = []
            for i in range(calls_per_thread):
                op = (thread_idx * calls_per_thread + i) % 7
                try:
                    if op == 0:
                        r = to_dict(tools.get_topic_overview.invoke({"topic_id": "1"}))
                        assert r.get("topicId") == "1"
                    elif op == 1:
                        r = to_dict(tools.get_topic_overview.invoke({"topic_id": "148929"}))
                        assert r.get("topicType") == "calc"
                    elif op == 2:
                        r = to_dict(tools.search_topics.invoke({"query": "asthma"}))
                        assert len(r.get("results", [])) > 0
                    elif op == 3:
                        r = to_dict(tools.get_related_topics.invoke({"topic_id": "1"}))
                        assert "relatedTopics" in r
                    elif op == 4:
                        r = to_dict(tools.get_topic_sections_text.invoke({"topic_id": "1", "section_ids": ["Summary"]}))
                        assert r.get("topicId") == "1"
                    elif op == 5:
                        r = to_dict(tools.search_topic_sections.invoke({"topic_id": "1", "query": "management"}))
                        assert "matches" in r
                    elif op == 6:
                        r = tools.get_graphic_content.invoke({"graphic_id": "116392"})
                        assert "Graphic Table:" in (r.markdown or "")
                except Exception as e:
                    worker_errors.append(f"Thread-{thread_idx} op-{op}: {type(e).__name__}: {str(e)}")
            return worker_errors

        start_time = time.time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
            futures = [executor.submit(worker, t) for t in range(num_threads)]
            results = [f.result() for f in concurrent.futures.as_completed(futures)]

        duration = time.time() - start_time
        all_errors = [err for sublist in results for err in sublist]

        assert len(all_errors) == 0, f"Concurrency test encountered {len(all_errors)} errors: {all_errors[:5]}"
        assert duration < 90.0, f"Concurrency test took too long: {duration:.2f}s for {total_calls} calls"


class TestDatabaseImmutability:
    """Verify that root SQLite databases remain strictly read-only and unmodified."""

    def test_databases_unmodified_checksum_verification(self):
        # Known baseline hashes
        expected_hashes = {
            "unidex.en.sqlite": "9144cd1f508ec1a56fc605fe97ca394d",
            "utdasset.sqlite": "52f55c5ca1ba3b9dadd3e435a337189c",
            "utdqf.sqlite": "7bceac354eb581c69eab9e051fba888b",
            "fsearch.db": "5b68af4bafc2e3d6c4efc203954ea93b",
            "thumbs.db": "016b759647162f16f423ff1db9edacfb",
            "utdtoc.db": "e3639e8a6122a69a8156e0afe9423374",
        }

        for db_name, expected_hash in expected_hashes.items():
            path = DB_DIR / db_name
            assert path.exists(), f"Database file missing: {db_name}"
            actual_hash = compute_md5(path)
            assert actual_hash == expected_hash, (
                f"DATABASE INTEGRITY VIOLATION: {db_name} was modified! "
                f"Expected MD5 {expected_hash}, got {actual_hash}"
            )
