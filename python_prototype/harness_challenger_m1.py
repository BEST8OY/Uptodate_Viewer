"""Empirical Challenger M1 Iteration 2 Stress Harness.

Rigorous verification of:
1. Malformed / SQL injection IDs across all tools and DB methods (Zero Aliasing).
2. Concurrency stress with 15+ threads invoking `invoke` and 20+ coroutines invoking `ainvoke` against ClinRefDatabase.
3. Exception tracking (sqlite3.ProgrammingError, sqlite3.OperationalError, SIGSEGV).
"""

import asyncio
import concurrent.futures
import json
from pathlib import Path
import sqlite3
import sys
import threading
import time

from database import ClinRefDatabase
import tools

DB_DIR = Path(__file__).parent.parent


def test_malformed_ids(db: ClinRefDatabase) -> dict:
    print("\n" + "=" * 60)
    print("SUBTEST 1: MALFORMED / SQL INJECTION TOPIC IDS VERIFICATION")
    print("=" * 60)

    # First get ground truth for topic 1 and topic 123 to verify no aliasing
    topic_1_title = db.get_topic_title("1")
    topic_123_title = db.get_topic_title("123")
    print(f"Ground truth Topic 1 title: '{topic_1_title}'")
    print(f"Ground truth Topic 123 title: '{topic_123_title}'")

    malformed_ids = [
        "-1",
        "1' OR 1=1--",
        "topic-1-bad",
        "123\x00",
        "abc",
        "",
        "   ",
        "\t\n",
        "1; DROP TABLE topic_asset;--",
        "' UNION SELECT * FROM topic_asset--",
        "abc123def",
        "-999",
        "1 2",
        "1\n2",
        "1.0",
        "NaN",
        "None",
        "💊",
        "🩺",
        "١٢٣",
        "🔥",
        "topic-",
        "topic--1",
        "1 OR 1=1",
        "1'",
        "'1'",
    ]

    failures = []
    tests_run = 0

    for tid in malformed_ids:
        # 1. tools.get_topic_overview
        tests_run += 1
        res = tools.get_topic_overview.invoke({"topic_id": tid})
        try:
            data = json.loads(res)
            if "error" not in data:
                failures.append(f"get_topic_overview('{tid}') did NOT return error! Got: {res[:100]}")
            elif topic_1_title and topic_1_title.lower() in res.lower() and tid not in ["1"]:
                failures.append(f"get_topic_overview('{tid}') aliased to Topic 1! Title in res: {topic_1_title}")
        except Exception as e:
            failures.append(f"get_topic_overview('{tid}') crashed with exception: {e}")

        # 2. tools.get_topic_sections_text
        tests_run += 1
        res = tools.get_topic_sections_text.invoke({"topic_id": tid, "section_ids": ["s1"]})
        try:
            data = json.loads(res)
            if "error" not in data:
                failures.append(f"get_topic_sections_text('{tid}') did NOT return error! Got: {res[:100]}")
        except Exception as e:
            failures.append(f"get_topic_sections_text('{tid}') crashed with exception: {e}")

        # 3. tools.get_related_topics
        tests_run += 1
        res = tools.get_related_topics.invoke({"topic_id": tid})
        try:
            data = json.loads(res)
            if "error" not in data:
                failures.append(f"get_related_topics('{tid}') did NOT return error! Got: {res[:100]}")
        except Exception as e:
            failures.append(f"get_related_topics('{tid}') crashed with exception: {e}")

        # 5. tools.search_topic_sections
        tests_run += 1
        res = tools.search_topic_sections.invoke({"topic_id": tid, "query": "dosing"})
        try:
            data = json.loads(res)
            if data.get("matches"):
                failures.append(f"search_topic_sections('{tid}') returned matches for invalid topic! Got: {res[:100]}")
            if "not found" not in data.get("message", "").lower() and "must be non-empty" not in data.get("message", "").lower():
                failures.append(f"search_topic_sections('{tid}') message unexpected: {data.get('message')}")
        except Exception as e:
            failures.append(f"search_topic_sections('{tid}') crashed with exception: {e}")

        # 6. Direct DB methods
        tests_run += 1
        overview_data = db.get_topic_overview_data(tid)
        if overview_data is not None:
            failures.append(f"db.get_topic_overview_data('{tid}') returned non-None: {overview_data}")

        tests_run += 1
        asset = db.get_topic_asset(tid)
        if asset is not None:
            failures.append(f"db.get_topic_asset('{tid}') returned non-None: {asset}")

        tests_run += 1
        title = db.get_topic_title(tid)
        if title is not None:
            failures.append(f"db.get_topic_title('{tid}') returned non-None: {title}")

        tests_run += 1
        outline = db.get_topic_outline(tid)
        if outline is not None:
            failures.append(f"db.get_topic_outline('{tid}') returned non-None: {outline}")

        tests_run += 1
        body = db.get_topic_body(tid)
        if body is not None:
            failures.append(f"db.get_topic_body('{tid}') returned non-None: {body}")

        tests_run += 1
        has_asset = db.has_topic_asset(tid)
        if has_asset is not False:
            failures.append(f"db.has_topic_asset('{tid}') returned True!")

    print(f"Total malformed ID checks executed: {tests_run}")
    print(f"Total failures: {len(failures)}")
    if failures:
        for f in failures[:10]:
            print(f"  FAIL: {f}")
    else:
        print("  ALL MALFORMED ID CHECKS PASSED with ZERO aliasing!")

    return {"tests_run": tests_run, "failures": failures}


async def run_async_worker(worker_id: int, num_calls: int, results_tracker: list):
    """Run concurrent coroutines executing ainvoke on tools."""
    for i in range(num_calls):
        try:
            # Alternating calls between valid topic, calculator topic, and search
            if i % 3 == 0:
                res = await tools.get_topic_overview.ainvoke({"topic_id": "1"})
                data = json.loads(res)
                assert "error" not in data
                assert data["topicId"] == "1"
            elif i % 3 == 1:
                res = await tools.get_topic_overview.ainvoke({"topicId": "148929"})
                data = json.loads(res)
                assert "error" not in data
                assert data["topicId"] == "148929"
                assert data["topicType"] == "calc"
            else:
                res = await tools.search_topics.ainvoke({"query": "asthma"})
                data = json.loads(res)
                assert "error" not in data
                assert len(data.get("results", [])) > 0
            results_tracker.append({"status": "ok", "type": "async", "worker": worker_id})
        except Exception as e:
            results_tracker.append({"status": "error", "type": "async", "worker": worker_id, "error": str(e), "exc_type": type(e).__name__})


def run_thread_worker(worker_id: int, num_calls: int, db: ClinRefDatabase, results_tracker: list):
    """Run concurrent threads executing invoke and direct DB queries."""
    for i in range(num_calls):
        try:
            if i % 4 == 0:
                res = tools.get_topic_overview.invoke({"topic_id": "1"})
                data = json.loads(res)
                assert "error" not in data
            elif i % 4 == 1:
                res = tools.get_topic_sections_text.invoke({"topic_id": "1", "section_ids": ["Summary"]})
                data = json.loads(res)
                assert "error" not in data
            elif i % 4 == 2:
                results = db.search_topics("apixaban", limit=5)
                assert len(results) > 0
            else:
                suggs = db.get_suggestions("aspir", limit=10)
                assert len(suggs) > 0
            results_tracker.append({"status": "ok", "type": "thread", "worker": worker_id})
        except Exception as e:
            results_tracker.append({"status": "error", "type": "thread", "worker": worker_id, "error": str(e), "exc_type": type(e).__name__})


def test_concurrency_stress(db: ClinRefDatabase) -> dict:
    print("\n" + "=" * 60)
    print("SUBTEST 2: CONCURRENT MULTITHREADED & ASYNC STRESS VERIFICATION")
    print("=" * 60)

    results_tracker = []
    num_threads = 16
    thread_calls_per_worker = 10
    num_coroutines = 20
    async_calls_per_worker = 8

    print(f"Launching {num_threads} concurrent OS threads ({thread_calls_per_worker} calls each = {num_threads * thread_calls_per_worker} calls)...")
    print(f"Launching {num_coroutines} concurrent async coroutines ({async_calls_per_worker} calls each = {num_coroutines * async_calls_per_worker} calls)...")

    start_time = time.time()

    # We will run threads and async coroutines simultaneously
    async def run_all_async():
        tasks = [run_async_worker(i, async_calls_per_worker, results_tracker) for i in range(num_coroutines)]
        await asyncio.gather(*tasks)

    def thread_pool_target():
        with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
            futures = [
                executor.submit(run_thread_worker, i, thread_calls_per_worker, db, results_tracker)
                for i in range(num_threads)
            ]
            concurrent.futures.wait(futures)

    # Spawn thread pool in background thread while event loop runs
    t_pool = threading.Thread(target=thread_pool_target)
    t_pool.start()

    # Run async coroutines in main thread event loop
    asyncio.run(run_all_async())

    # Wait for thread pool to complete
    t_pool.join()

    duration = time.time() - start_time

    total_ops = len(results_tracker)
    errors = [r for r in results_tracker if r["status"] == "error"]
    programming_errors = [e for e in errors if "ProgrammingError" in e.get("exc_type", "")]
    operational_errors = [e for e in errors if "OperationalError" in e.get("exc_type", "")]

    print(f"Total concurrent operations completed: {total_ops} in {duration:.2f}s ({total_ops / duration:.1f} ops/sec)")
    print(f"Total errors: {len(errors)}")
    print(f"sqlite3.ProgrammingError count: {len(programming_errors)}")
    print(f"sqlite3.OperationalError count: {len(operational_errors)}")

    if errors:
        for e in errors[:10]:
            print(f"  FAIL: Worker {e['worker']} ({e['type']}) -> {e['exc_type']}: {e['error']}")
    else:
        print("  CONCURRENCY STRESS TEST PASSED with ZERO errors, ZERO ProgrammingErrors, and ZERO crashes!")

    return {
        "total_ops": total_ops,
        "duration": duration,
        "errors": errors,
        "programming_errors": len(programming_errors),
        "operational_errors": len(operational_errors),
    }


def main():
    print("Initializing ClinRefDatabase...")
    db = ClinRefDatabase(DB_DIR)
    tools.init_tools(db)

    try:
        subtest1_res = test_malformed_ids(db)
        subtest2_res = test_concurrency_stress(db)

        all_passed = (len(subtest1_res["failures"]) == 0 and len(subtest2_res["errors"]) == 0)

        print("\n" + "=" * 60)
        print("OVERALL CHALLENGER VERIFICATION VERDICT")
        print("=" * 60)
        if all_passed:
            print("STATUS: VERIFIED - ALL ADVERSARIAL STRESS TESTS PASSED")
            print("RECOMMENDATION: APPROVE")
            sys.exit(0)
        else:
            print("STATUS: FAILED - UNRESOLVED ADVERSARIAL VULNERABILITIES DETECTED")
            print("RECOMMENDATION: REJECT")
            sys.exit(1)
    finally:
        db.close()


if __name__ == "__main__":
    main()
