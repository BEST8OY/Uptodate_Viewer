"""Database layer for ClinRef Python prototype.

Connects to the same SQLite databases used by the Android app:
- utdtoc.db: Table of contents + TOCMap
- fsearch.db: FTS4 topic & video search
- utdasset.sqlite: Compressed topic/graphic assets (bodyHtml, outlineHtml)
"""

import gzip
import json
import re
import sqlite3
from pathlib import Path
from typing import Optional

# Default paths — override via environment or constructor
_DB_DIR = Path(__file__).parent.parent


class ClinRefDatabase:
    """Unified access to all ClinRef SQLite databases."""

    def __init__(self, db_dir: Optional[Path] = None):
        self.db_dir = db_dir or _DB_DIR
        self._toc_conn: Optional[sqlite3.Connection] = None
        self._search_conn: Optional[sqlite3.Connection] = None
        self._asset_conn: Optional[sqlite3.Connection] = None
        self._qf_conn: Optional[sqlite3.Connection] = None
        self._unidex_conn: Optional[sqlite3.Connection] = None

    def _get_conn(self, attr: str, filename: str) -> sqlite3.Connection:
        conn = getattr(self, attr)
        if conn is None:
            path = self.db_dir / filename
            if not path.exists():
                raise FileNotFoundError(f"Database not found: {path}")
            conn = sqlite3.connect(str(path))
            conn.row_factory = sqlite3.Row
            setattr(self, attr, conn)
        return conn

    @property
    def toc(self) -> sqlite3.Connection:
        return self._get_conn("_toc_conn", "utdtoc.db")

    @property
    def search(self) -> sqlite3.Connection:
        return self._get_conn("_search_conn", "fsearch.db")

    @property
    def asset(self) -> sqlite3.Connection:
        return self._get_conn("_asset_conn", "utdasset.sqlite")

    def close(self):
        for attr in ("_toc_conn", "_search_conn", "_asset_conn", "_qf_conn", "_unidex_conn"):
            conn = getattr(self, attr)
            if conn:
                conn.close()
                setattr(self, attr, None)

    # ── Search ──────────────────────────────────────────────────────────

    def search_topics(self, query: str, limit: int = 10) -> list[dict]:
        """Search topics using unidex only (no FTS fallback).

        FTS fallback returns low-quality results. Unidex has curated mappings.
        Filters out topics without assets to prevent LLM dead ends.
        """
        # 1. Try unidex (exact query → topic hits)
        unidex_results = self._search_unidex(query)
        if unidex_results:
            filtered = [r for r in unidex_results if self.has_topic_asset(r["id"])]
            if filtered:
                return filtered[:limit]

        # 2. If query has special chars, strip them and retry unidex
        cleaned = re.sub(r'[^a-zA-Z0-9 ]', '', query).lower().strip()
        if cleaned and cleaned != query:
            cleaned_results = self._search_unidex(cleaned)
            if cleaned_results:
                filtered = [r for r in cleaned_results if self.has_topic_asset(r["id"])]
                if filtered:
                    return filtered[:limit]

        # 3. No FTS fallback — return empty, LLM will use suggestions
        return []

    def _search_unidex(self, query: str) -> list[dict]:
        """Search unidex.en.sqlite for exact query → topic hits."""
        try:
            row = self.unidex.execute(
                "SELECT x.topic_hits FROM query q, query_topic x "
                "WHERE q.disp = ? AND x.nqid = q.nqid AND x.pref = 'X'",
                (query,),
            ).fetchone()

            if not row or not row["topic_hits"]:
                return []

            # Parse binary blob (matches Kotlin parseHitsBlob)
            hits_blob = row["topic_hits"]
            hex_string = hits_blob.hex()
            topic_ids = []

            i = 4  # Skip first 4 bytes (header)
            while i < len(hex_string):
                chunk = hex_string[i : i + 8]
                if len(chunk) < 8:
                    break
                topic_id = int(chunk, 16)
                if topic_id > 0:
                    topic_ids.append(str(topic_id))
                i += 8

            if not topic_ids:
                return []

            # Fetch titles for topic IDs
            results = []
            for tid in topic_ids[:20]:  # Limit to 20
                title = self._get_topic_title_from_toc(tid) or ""
                results.append({"id": tid, "title": title, "url": f"Topic-{tid}"})

            return results

        except Exception:
            return []

    def _get_topic_title_from_toc(self, topic_id: str) -> Optional[str]:
        """Get topic title from TOC database."""
        try:
            # First try TOCMap to find the TOC entry
            row = self.toc.execute(
                "SELECT tocId FROM TOCMap WHERE topicId = ?", (topic_id,)
            ).fetchone()
            if row:
                toc_row = self.toc.execute(
                    "SELECT title FROM TOC WHERE id = ?", (row["tocId"],)
                ).fetchone()
                if toc_row:
                    return toc_row["title"]

            # Fallback: try asset database
            asset = self.get_topic_asset(topic_id)
            if asset:
                info = asset.get("topicInfo", {})
                for t in info.get("translatedTopicInfos", []):
                    if t.get("languageCode") == "en-US":
                        return t.get("title")
                return info.get("title")

        except Exception:
            pass
        return None

    # ── Suggestions ────────────────────────────────────────────────────

    @property
    def qf(self) -> sqlite3.Connection:
        return self._get_conn("_qf_conn", "utdqf.sqlite")

    @property
    def unidex(self) -> sqlite3.Connection:
        return self._get_conn("_unidex_conn", "unidex.en.sqlite")

    def get_suggestions(self, query: str, limit: int = 30) -> list[str]:
        """Get search query suggestions from unidex (primary) or qf (fallback).

        Returns popular queries that start with or match the given prefix.
        For long queries, tries progressively shorter word prefixes.
        """
        query = query.strip()
        if not query:
            return []

        # Try the full query first
        results = self._query_suggestions(query, limit)
        if results:
            return results

        words = query.split()

        # Try individual words (longest first) — finds core medical terms
        # e.g., "how to treat diabetes" -> try "diabetes", "treat", "how"
        for word in sorted(words, key=len, reverse=True):
            if len(word) > 3:  # Skip short words like "how", "for", "the"
                results = self._query_suggestions(word, limit)
                if results:
                    return results

        # Try progressively shorter word prefixes
        for i in range(len(words) - 1, 0, -1):
            prefix = " ".join(words[:i])
            results = self._query_suggestions(prefix, limit)
            if results:
                return results

        return []

    def _query_suggestions(self, prefix: str, limit: int) -> list[str]:
        """Query unidex (primary) or qf (fallback) for suggestions matching a prefix."""
        p_len = len(prefix)
        if p_len == 0:
            return []

        # Try unidex first (matches app behavior)
        try:
            if p_len == 1:
                rows = self.unidex.execute(
                    "SELECT disp, weight FROM query WHERE d1 = ? AND hide IS NULL ORDER BY weight DESC, disp ASC LIMIT ?",
                    (prefix, limit),
                ).fetchall()
            elif p_len == 2:
                rows = self.unidex.execute(
                    "SELECT disp, weight FROM query WHERE d2 = ? AND hide IS NULL ORDER BY weight DESC, disp ASC LIMIT ?",
                    (prefix, limit),
                ).fetchall()
            elif p_len == 3:
                rows = self.unidex.execute(
                    "SELECT disp, weight FROM query WHERE d3 = ? AND hide IS NULL ORDER BY weight DESC, disp ASC LIMIT ?",
                    (prefix, limit),
                ).fetchall()
            else:
                rows = self.unidex.execute(
                    "SELECT disp, weight FROM query WHERE disp LIKE ? AND hide IS NULL ORDER BY weight DESC, disp ASC LIMIT ?",
                    (f"{prefix}%", limit),
                ).fetchall()

            if rows:
                return list(dict.fromkeys(r["disp"] for r in rows if r["disp"]))
        except Exception:
            pass  # Fall through to qf

        # Fallback to qf
        if p_len <= 3:
            col = f"q{p_len}"
            rows = self.qf.execute(
                f"SELECT u, f FROM qf WHERE {col} = ? ORDER BY f DESC, q ASC LIMIT ?",
                (prefix, limit),
            ).fetchall()
        else:
            rows = self.qf.execute(
                "SELECT u, f FROM qf WHERE q LIKE ? ORDER BY f DESC, q ASC LIMIT ?",
                (f"{prefix}%", limit),
            ).fetchall()

        return list(dict.fromkeys(r["u"] for r in rows if r["u"]))

    # ── TOC ─────────────────────────────────────────────────────────────

    def get_toc_for_topic(self, topic_id: str) -> list[dict]:
        """Get table of contents entries mapped to a topic ID."""
        row = self.toc.execute(
            "SELECT tocId FROM TOCMap WHERE topicId = ?", (topic_id,)
        ).fetchone()
        if not row:
            return []
        toc_id = row["tocId"]
        return [
            dict(r)
            for r in self.toc.execute(
                "SELECT id, title, parentId, leaf, section FROM TOC WHERE id = ?",
                (toc_id,),
            ).fetchall()
        ]

    # ── Topic Assets ────────────────────────────────────────────────────

    def has_topic_asset(self, topic_id: str) -> bool:
        """Check if a topic has a usable asset (outlineHtml not empty)."""
        row = self.asset.execute(
            "SELECT payload FROM topic_asset WHERE id = ? LIMIT 1", (topic_id,)
        ).fetchone()
        if not row or not row["payload"]:
            return False
        try:
            data = json.loads(gzip.decompress(row["payload"]))
            return bool(data.get("outlineHtml"))
        except Exception:
            return False

    def get_topic_asset(self, topic_id: str) -> Optional[dict]:
        """Load and decompress a topic asset from utdasset.sqlite.

        Returns dict with keys: topicInfo, bodyHtml, outlineHtml, etc.
        """
        row = self.asset.execute(
            "SELECT payload FROM topic_asset WHERE id = ?", (topic_id,)
        ).fetchone()
        if not row or not row["payload"]:
            return None
        try:
            decompressed = gzip.decompress(row["payload"])
            return json.loads(decompressed)
        except (gzip.BadGzipFile, json.JSONDecodeError):
            return None

    def get_graphic_asset(self, graphic_id: str) -> Optional[dict]:
        """Load and decompress a graphic asset."""
        row = self.asset.execute(
            "SELECT payload FROM graphic_asset WHERE id = ?", (graphic_id,)
        ).fetchone()
        if not row or not row["payload"]:
            return None
        try:
            decompressed = gzip.decompress(row["payload"])
            return json.loads(decompressed)
        except (gzip.BadGzipFile, json.JSONDecodeError):
            return None

    def get_topic_title(self, topic_id: str) -> Optional[str]:
        """Get English title for a topic ID."""
        asset = self.get_topic_asset(topic_id)
        if not asset:
            return None
        info = asset.get("topicInfo", {})
        for t in info.get("translatedTopicInfos", []):
            if t.get("languageCode") == "en-US":
                return t.get("title")
        return info.get("title")

    def get_topic_outline(self, topic_id: str) -> Optional[str]:
        """Get raw outlineHtml for a topic."""
        asset = self.get_topic_asset(topic_id)
        return asset.get("outlineHtml") if asset else None

    def get_topic_body(self, topic_id: str) -> Optional[str]:
        """Get raw bodyHtml for a topic."""
        asset = self.get_topic_asset(topic_id)
        return asset.get("bodyHtml") if asset else None

    def get_topic_graphics(self, topic_id: str) -> list[dict]:
        """Get related graphics metadata for a topic."""
        asset = self.get_topic_asset(topic_id)
        if not asset:
            return []
        return asset.get("relatedGraphics", [])

    def get_topic_related_topics(self, topic_id: str) -> list[dict]:
        """Get related topic IDs/titles for a topic."""
        asset = self.get_topic_asset(topic_id)
        if not asset:
            return []
        return asset.get("relatedTopics", [])
