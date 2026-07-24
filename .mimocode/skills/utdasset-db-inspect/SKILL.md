---
name: utdasset-db-inspect
description: >
  Inspect the UpToDate asset database (utdasset.sqlite) used by ClinRef.
  Covers: schema inspection, AES decryption of encrypted payloads, gzip
  decompression, JSON parsing of topic_asset and graphic_asset, link/section
  extraction from bodyHtml/outlineHtml, and running the test suite.
  Use when: "check utdasset", "inspect database", "decrypt payload",
  "what's in topic", "run database tests", "test_database_tools".
---

# UtdAsset Database Inspection

Inspect and query the UpToDate asset database (`utdasset.sqlite`) used by the ClinRef Android app.

## Database Location

| File | Path |
|------|------|
| Main DB (project root) | `/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite` |
| Companion Python project | `/home/best8oy/Ongoing Projects/Python_Uptodate/utdasset.sqlite` |
| Test suite | `/home/best8oy/Ongoing Projects/Uptodate_Viewer/test_database_tools.py` |

## Schema Overview

Two primary tables with gzipped JSON payloads:

| Table | Key columns | Payload format |
|-------|-------------|----------------|
| `topic_asset` | `id INTEGER`, `payload BLOB` | gzip → JSON with `bodyHtml`, `outlineHtml`, `title`, etc. |
| `graphic_asset` | `id INTEGER`, `payload BLOB` | gzip → JSON with `graphicInfo`, `imageHtml`, `base64Image`, etc. |

### Quick schema check
```bash
sqlite3 "/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite" ".tables"
sqlite3 "/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite" ".schema topic_asset"
sqlite3 "/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite" "SELECT COUNT(*) FROM topic_asset; SELECT COUNT(*) FROM graphic_asset;"
```

## Payload Inspection Patterns

### 1. Read a topic's JSON payload

```python
import sqlite3, gzip, json

conn = sqlite3.connect('/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite')
cursor = conn.execute('SELECT payload FROM topic_asset WHERE id = ?', (TOPIC_ID,))
blob = cursor.fetchone()[0]
data = json.loads(gzip.decompress(blob))
conn.close()

# Key fields: bodyHtml, outlineHtml, title, topicId
print(json.dumps(data, indent=2)[:3000])
```

### 2. Read a graphic's JSON payload

```python
import sqlite3, gzip, json

conn = sqlite3.connect('/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite')
cursor = conn.execute('SELECT payload FROM graphic_asset WHERE id = ?', (GRAPHIC_ID,))
blob = cursor.fetchone()[0]
data = json.loads(gzip.decompress(blob))
conn.close()

# Key fields: graphicInfo (displayName, id, type, subtype, title),
#             imageHtml, base64Image, imageSource
print(json.dumps(data['graphicInfo'], indent=2))
```

### 3. Find topics with tables/images in bodyHtml

```python
import sqlite3, gzip, json

conn = sqlite3.connect('/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite')
cur = conn.execute('SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL ORDER BY length(payload) DESC LIMIT 20')

for row_id, payload in cur.fetchall():
    j = json.loads(gzip.decompress(payload))
    body = j.get('bodyHtml', '')
    has_table = '<table' in body.lower()
    has_img = '<img' in body.lower()
    if has_table or has_img:
        print(f'Topic {row_id}: table={has_table}, img={has_img}, size={len(body)}')

conn.close()
```

### 4. Extract sections from outlineHtml

```python
import re

A_TAG_RE = re.compile(r'<a\s+[^>]*href=[\'"](.+?)[\'"][^>]*>(.*?)</a>', re.IGNORECASE | re.DOTALL)
SECTION_RE = re.compile(r'section(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")', re.IGNORECASE)
STRIP_RE = re.compile(r'<[^>]*>')

outline = data.get('outlineHtml', '')
sections = []
for m in A_TAG_RE.finditer(outline):
    href, inner = m.group(1), m.group(2)
    sm = SECTION_RE.search(href)
    if sm:
        sections.append({'id': sm.group(1), 'title': STRIP_RE.sub('', inner).strip()})
```

### 5. Extract links (medical/drug) from bodyHtml

```python
LINK_RE = re.compile(r'<a\s+[^>]*href=[\'"](.+?)[\'"][^>]*>(.*?)</a>', re.IGNORECASE | re.DOTALL)
TYPE_RE = re.compile(r'"type"\s*:\s*"(medical|drug|graphic)"', re.IGNORECASE)

for m in LINK_RE.finditer(body_html):
    href, text = m.group(1), STRIP_RE.sub('', m.group(2)).strip()
    if TYPE_RE.search(href):
        print(f'  Link: {text} → {href[:80]}')
```

### 6. Check payload encryption (first bytes)

```bash
sqlite3 "/home/best8oy/Ongoing Projects/Uptodate_Viewer/utdasset.sqlite" \
  "SELECT typeof(payload), length(payload), hex(substr(payload, 1, 4)) FROM graphic_asset LIMIT 3;"
```

Gzipped payloads start with `1f8b` (gzip magic bytes). Encrypted payloads start with different markers.

## AES Decryption (when payloads are encrypted)

The Python_Uptodate companion project has a `DatabaseManager` that handles decryption:

```python
import sys
sys.path.insert(0, '/home/best8oy/Ongoing Projects/Python_Uptodate')
from src.database.manager import DatabaseManager

dm = DatabaseManager()
conn = dm.get_assets_conn()
# conn now has decrypted access
```

Or manually:
```python
from Crypto.Cipher import AES
from Crypto.Hash import SHA1
from Crypto.Protocol.KDF import PBKDF2

PASSWORD = 'hs;d,hghdk[;ak'
ITERATIONS = 19
STATIC_IV = bytes.fromhex('1173696667686f6b6c7a787776626e6d')

def derive_key(salt_id):
    salt = str(salt_id).ljust(8).encode('ascii')
    return PBKDF2(PASSWORD, salt, dkLen=32, count=ITERATIONS, prf=lambda p, s: HMAC.new(p, s, SHA1).digest())
```

## Running Tests

```bash
python3 "/home/best8oy/Ongoing Projects/Uptodate_Viewer/test_database_tools.py"
```

The test suite validates `DatabaseManager`, `MedicalDatabaseTools`, content extraction, and link filtering.

## Common Inspection Tasks

| Task | Command |
|------|---------|
| Count all assets | `sqlite3 utdasset.sqlite "SELECT 'topic', COUNT(*) FROM topic_asset UNION ALL SELECT 'graphic', COUNT(*) FROM graphic_asset;"` |
| List table schemas | `sqlite3 utdasset.sqlite ".schema"` |
| Find topic by title | `python3 -c "import sqlite3,gzip,json; conn=sqlite3.connect('utdasset.sqlite'); [print(r[0], json.loads(gzip.decompress(r[1])).get('title','?')) for r in conn.execute('SELECT id,payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 50').fetchall()]"` |
| Check graphic subtypes | `python3 -c "import sqlite3,gzip,json; conn=sqlite3.connect('utdasset.sqlite'); [print(r[0], json.loads(gzip.decompress(r[1])).get('graphicInfo',{}).get('subtype','?')) for r in conn.execute('SELECT id,payload FROM graphic_asset WHERE payload IS NOT NULL LIMIT 20').fetchall()]"` |
| Sample bodyHtml size | `python3 -c "import sqlite3,gzip,json; conn=sqlite3.connect('utdasset.sqlite'); print(max(len(json.loads(gzip.decompress(r[0])).get('bodyHtml','')) for r in conn.execute('SELECT payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 100').fetchall()))"` |

## Gotchas

- **Two DB copies exist** — `Uptodate_Viewer/utdasset.sqlite` (project root) and `Python_Uptodate/utdasset.sqlite`. They may differ in encryption state.
- **Payloads are gzip-compressed JSON**, not raw JSON. Always `gzip.decompress()` before `json.loads()`.
- **Some payloads may be AES-encrypted** before gzip. Use the `DatabaseManager` from Python_Uptodate for those.
- **Graphic bodyHtml links use global IDs** (from `data[0].id` in graphic_asset JSON), not sequential local references.
- **`graphicInfo.subtype`** values: `graphic_table`, `graphic_figure`, `graphic_diagnosticimage`, `graphic_algorithm`.
