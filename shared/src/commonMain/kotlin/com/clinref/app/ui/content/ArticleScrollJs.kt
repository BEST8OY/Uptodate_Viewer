package com.clinref.app.ui.content

import com.clinref.app.domain.ReadingPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * In-page script that restores and reports the reading position of an HTML article rendered in a
 * real browser engine (Android WebView).
 *
 * - **Anchors, not pixels.** Leaf block elements are tagged in document order (`data-b`). A position
 *   is `b:<blockIndex>:<fraction>`, the fraction being how far the viewport top is into that block,
 *   so it survives reflow (rotation, font scale, theme CSS). `top` and `p` (progress only) are the
 *   other anchor forms; a `contentRev` mismatch also falls back to portable `progress`.
 * - **Instant restore.** The article CSS sets `scroll-behavior: smooth`; the script temporarily
 *   disables it so restoring does not animate down from the top.
 * - **Stabilization.** The start target is re-applied on image load / body resize until the first
 *   user interaction or [STABILIZE_MS], so late layout shifts don't leave the reader off-target.
 *   Reports are suppressed while restoring so intermediate positions never overwrite the stored one.
 * - **Reporting.** Scroll is debounced and sent through `Android.onScroll(json)`;
 *   `window.__clinrefFlush()` reports synchronously (called by the host before teardown).
 */
object ArticleScrollJs {

    const val STABILIZE_MS = 1500
    const val PUSH_DEBOUNCE_MS = 250

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns [html] with the start target and scroll script embedded in `<head>`. */
    fun inject(html: String, start: StartTarget, contentRev: String): String {
        val snippet = buildString {
            append("<script>window.__clinrefStart=")
            append(startJson(start).toString().replace("</", "<\\/"))
            append(";window.__clinrefRev=")
            append(JsonPrimitive(contentRev).toString().replace("</", "<\\/"))
            append(";</script><script>")
            append(SCRIPT)
            append("</script>")
        }
        val headEnd = html.indexOf("</head>", ignoreCase = true)
        return if (headEnd >= 0) {
            html.substring(0, headEnd) + snippet + html.substring(headEnd)
        } else {
            snippet + html
        }
    }

    /** Parses a position reported by the page; returns null for malformed payloads. */
    fun parsePosition(payload: String): ReadingPosition? =
        runCatching { json.decodeFromString<ReadingPosition>(payload) }.getOrNull()

    internal fun startJson(start: StartTarget): JsonObject = buildJsonObject {
        when (start) {
            StartTarget.Top -> put("type", "top")
            is StartTarget.Section -> {
                put("type", "section")
                put("id", start.id)
            }
            is StartTarget.Resume -> {
                put("type", "resume")
                put("anchor", start.position.anchor)
                put("progress", start.position.progress)
                put("rev", start.position.contentRev)
                put("sectionId", start.position.sectionId?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
    }

    private val SCRIPT = """
(function () {
  var START = window.__clinrefStart || { type: 'top' };
  var DOC_REV = window.__clinrefRev || '';
  var LEAF_SELECTOR = 'p,li,h1,h2,h3,h4,h5,h6,td,th,figure,blockquote,dd,dt,div';
  var STABILIZE_MS = $STABILIZE_MS;
  var PUSH_DEBOUNCE_MS = $PUSH_DEBOUNCE_MS;
  var root = document.documentElement;
  var blocks = [];
  var restoring = false;
  var pushTimer = null;
  var restoreTimer = null;
  var observer = null;
  var lastJson = null;
  var USER_EVENTS = ['touchstart', 'wheel', 'keydown', 'mousedown'];

  function pageY() {
    return window.pageYOffset || root.scrollTop || (document.body && document.body.scrollTop) || 0;
  }
  function maxY() {
    var h = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
    return Math.max(0, h - window.innerHeight);
  }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function tagBlocks() {
    var all = document.body.querySelectorAll(LEAF_SELECTOR);
    blocks = [];
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.querySelector(LEAF_SELECTOR)) continue;
      el.setAttribute('data-b', String(blocks.length));
      blocks.push(el);
    }
  }

  function jump(y) {
    var prev = root.style.scrollBehavior;
    root.style.scrollBehavior = 'auto';
    window.scrollTo(0, Math.max(0, y));
    root.style.scrollBehavior = prev;
  }

  function elementForId(id) {
    var safe = String(id).replace(/"/g, '\\"');
    return document.getElementById(id) ||
      document.querySelector('[id="' + safe + '"]') ||
      document.querySelector('a[name="' + safe + '"]');
  }

  function startY() {
    if (START.type === 'section') {
      var sec = elementForId(START.id);
      return sec ? pageY() + sec.getBoundingClientRect().top : null;
    }
    if (START.type === 'resume') {
      if (START.anchor === 'top') return 0;
      var m = /^b:(\d+):(-?[\d.]+)$/.exec(START.anchor || '');
      if (m && START.rev === DOC_REV) {
        var el = blocks[parseInt(m[1], 10)];
        if (el) {
          var r = el.getBoundingClientRect();
          if (r.height > 0) return pageY() + r.top + parseFloat(m[2]) * r.height;
        }
      }
      return (START.progress || 0) * maxY();
    }
    return null;
  }

  function applyStart() {
    var y = startY();
    if (y === null || Math.abs(pageY() - y) < 1) return;
    jump(y);
  }

  function capture() {
    var y = pageY();
    if (y <= 1) return { anchor: 'top', progress: 0, sectionId: null, contentRev: DOC_REV };
    var m = maxY();
    var progress = m > 0 ? Math.round(clamp(y / m, 0, 1) * 10000) / 10000 : 0;
    var idx = -1, frac = 0;
    for (var i = 0; i < blocks.length; i++) {
      var r = blocks[i].getBoundingClientRect();
      if (r.height <= 0) continue;
      if (r.bottom > 1) { idx = i; frac = clamp(-r.top / r.height, -1, 1); break; }
    }
    var sectionId = null;
    for (var j = idx; j >= 0; j--) {
      var b = blocks[j];
      if (/^H[1-6]$/.test(b.tagName) && b.id) { sectionId = b.id; break; }
    }
    return {
      anchor: idx < 0 ? 'p' : 'b:' + idx + ':' + frac.toFixed(4),
      progress: progress,
      sectionId: sectionId,
      contentRev: DOC_REV
    };
  }

  function push() {
    pushTimer = null;
    if (restoring) return;
    var payload = JSON.stringify(capture());
    if (payload === lastJson) return;
    lastJson = payload;
    if (window.Android && window.Android.onScroll) window.Android.onScroll(payload);
  }
  function schedulePush() {
    if (pushTimer) clearTimeout(pushTimer);
    pushTimer = setTimeout(push, PUSH_DEBOUNCE_MS);
  }

  function onLayoutChange() { if (restoring) applyStart(); }
  function onUserInteraction() { stopRestoring(); }
  function stopRestoring() {
    if (!restoring) return;
    restoring = false;
    if (restoreTimer) clearTimeout(restoreTimer);
    if (observer) observer.disconnect();
    document.removeEventListener('load', onLayoutChange, true);
    for (var i = 0; i < USER_EVENTS.length; i++) {
      window.removeEventListener(USER_EVENTS[i], onUserInteraction, true);
    }
  }

  function beginRestore() {
    if (START.type === 'resume' && START.anchor === 'top') return;
    restoring = true;
    applyStart();
    document.addEventListener('load', onLayoutChange, true);
    window.addEventListener('load', onLayoutChange);
    for (var i = 0; i < USER_EVENTS.length; i++) {
      window.addEventListener(USER_EVENTS[i], onUserInteraction, { capture: true, passive: true });
    }
    if (window.ResizeObserver) {
      observer = new ResizeObserver(onLayoutChange);
      observer.observe(document.body);
    }
    restoreTimer = setTimeout(function () { stopRestoring(); schedulePush(); }, STABILIZE_MS);
  }

  window.addEventListener('scroll', function () { if (!restoring) schedulePush(); }, { passive: true });
  window.addEventListener('pagehide', function () { window.__clinrefFlush(); });
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') window.__clinrefFlush();
  });
  window.__clinrefFlush = function () {
    if (restoring) return 'restoring';
    if (pushTimer) { clearTimeout(pushTimer); pushTimer = null; }
    push();
    return 'ok';
  };

  function init() {
    tagBlocks();
    if (START.type === 'section' || START.type === 'resume') beginRestore();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
""".trimIndent()
}
