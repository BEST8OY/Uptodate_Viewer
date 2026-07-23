# Clinical Safety Validator — Rules Specification

## Data Types

```python
TurnContext:
    tool_calls: List[ToolCallRecord]
    answer: str
    citations: List[Citation]
    tool_results: List[str]
    fetched_sections: List[FetchedSection]
    graphic_ids: Set[str]
    user_question: str  # Patient values from the original question

ToolCallRecord:
    tool_name: str
    arguments: Dict[str, str]
    result: str
    success: bool

Citation:
    topic_title: str
    section_title: str
    section_id: str

FetchedSection:
    topic_id: str
    section_id: str
    section_title: str
```

## Validation Order

Rules execute in strict order. First hard block stops execution.

1. Tool calls required
2. Section content required
3. Citation consistency
4. No invented numbers
5. Citation required (with density)
6. No graphic interpretation

## Rules

### Rule 1: Tool calls required

**Check:** `len(context.tool_calls) == 0`

**If true:** BLOCK — "No tool calls were made. Clinical answers require database retrieval."

---

### Rule 2: Section content required

**Check:** No successful `getTopicSectionText` call exists

**If true:** BLOCK — "Answer requires actual section content. Only topic titles or outlines were retrieved."

---

### Rule 3: Citation consistency

**For each citation in answer:**
- Check if `citation.section_id` exists in `fetched_sections`
- If not found: check if `citation.section_title` matches any `fetched_section.section_title` (bidirectional substring, case-insensitive)
  - If title matches: add warning (fuzzy match fallback)
  - If neither matches: BLOCK — "Citation references unretrieved section"

---

### Rule 4: No invented numbers

**Step 1: Strip formatting from answer**
- Citation lines (`Topic: ...`)
- Markdown list markers (`1.`, `-`, `*`)
- Markdown headers (`### ...`)
- Section dividers (`---`)
- Bold/italic markers (`**500mg**` → `500mg`)
- List marker artifacts (`1,`, `2,`)

**Step 2: Extract numbers**
- Regex: `\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?`
- Extract from: answer, tool_results, user_question

**Step 3: For each number in answer, check if allowed**

| Check | Logic | Example |
|---|---|---|
| Full token in tool text | Boundary-aware: `(?<![\d])TOKEN(?![\d])` | "42" matches "42 mL" ✓ |
| Numeric-only in tool text | Strip units, check numeric part | "42 mL" → "42" matches "42" ✓ |
| Question numeric match | Extract numeric part from question numbers | "42" matches "42 mL" from question ✓ |

**Boundary rules:**
- `(?<![\d])` — reject if preceded by digit (prevents "12" matching "123")
- `(?![\d])` — reject if followed by digit (prevents "12" matching "123")
- Trailing periods are sentence punctuation, not part of number

**If no match found:** BLOCK — "Answer contains numbers not found in retrieved content"

---

### Rule 5: Citation required (with density)

**Step 1:** If `len(citations) == 0` → BLOCK

**Step 2:** Citation density check
- `cited_ids = {c.section_id for c in citations}`
- `fetched_ids = {fs.section_id for fs in fetched_sections}`
- `uncited = fetched_ids - cited_ids`
- If `len(uncited) > 1` → BLOCK — "Fetched sections not cited in answer"

---

### Rule 6: No graphic interpretation

**Step 1:** Check for visual interpretation patterns

**Patterns:**
1. `the (?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film|rogram) (?:shows?|demonstrates?|reveals?|suggests?|indicates?|displays?|depicts?|illustrates?)`
2. `(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film) (?:findings?|abnormalities?|results?|features?|characteristics?)`
3. `(?:visual|visualized?|visible|appears? to show|can be seen)`
4. `(?:the (?:figure|algorithm|diagram|picture) (?:shows?|demonstrates?|reveals?|depicts?))`

**Step 2:** If no visual language → PASS

**Step 3:** If visual language present:
- If `graphic_ids` not empty OR `Graphic-*` refs in answer → BLOCK
- Else → WARN (advisory — might be quoting text)

---

## Numeric Token Regex

```
\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?
```

## Graphic Reference Regex

```
Graphic-[a-zA-Z0-9_-]+
```
