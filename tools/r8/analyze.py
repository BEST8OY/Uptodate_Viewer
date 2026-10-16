#!/usr/bin/env python3
"""
Analyze R8 configuration, compute scores, and generate markdown report.
Adheres strictly to the official r8-analyzer skill REPORT_FORMAT.md conventions.
"""
import json
import os
import sys
import glob


def analyze(json_path, output_report_path="tmp/keepradius/analysis_result.txt", markdown_summary_path=None):
    data = None
    if os.path.exists(json_path):
        try:
            with open(json_path, 'r', encoding='utf-8') as f:
                data = json.load(f)
        except Exception as e:
            print(f"Warning: Could not parse {json_path}: {e}", file=sys.stderr)

    lines = []

    if data:
        # Build reference constraints map
        c_map = {c.get('id'): set(c.get('constraints', [])) for c in data.get('keep_constraints_table', [])}
        r_map = {r.get('id'): c_map.get(r.get('constraints_id'), set()) for r in data.get('keep_rule_blast_radius_table', [])}

        tot_opt = tot_obf = tot_shr = tot_items = 0

        for tbl in ('kept_class_info_table', 'kept_field_info_table', 'kept_method_info_table'):
            for i in data.get(tbl, []):
                tot_items += 1
                kb = i.get('kept_by', [])
                if any('DONT_OPTIMIZE' in r_map.get(r, set()) for r in kb):
                    tot_opt += 1
                if any('DONT_OBFUSCATE' in r_map.get(r, set()) for r in kb):
                    tot_obf += 1
                if any('DONT_SHRINK' in r_map.get(r, set()) for r in kb):
                    tot_shr += 1

        bi = data.get('build_info', {})
        live = sum(int(bi.get(k, 0)) for k in ('live_class_count', 'live_field_count', 'live_method_count'))
        denom = live if live > 0 else tot_items

        globals_src = [g.get('source', '').lower() for g in data.get('global_keep_rule_blast_radius_table', [])]

        def calc_score(cnt, flag):
            if any(flag in src for src in globals_src):
                return 0.0
            return max(0.0, 100.0 - ((cnt / denom * 100.0) if denom > 0 else 0.0))

        opt_score = calc_score(tot_opt, '-dontoptimize')
        obf_score = calc_score(tot_obf, '-dontobfuscate')
        shr_score = calc_score(tot_shr, '-dontshrink')

        lines.append("## 3. Optimization summary")
        lines.append("")
        lines.append(f"- **Optimization score**: {opt_score:.2f}% code is available for R8 optimizations (e.g., inlining, merging). {100.0 - opt_score:.2f}% of codebase can't be optimized by R8.")
        lines.append(f"- **Shrinking score**: {shr_score:.2f}% of code will be optimized by R8 by removing unused classes, fields and methods. {100.0 - shr_score:.2f}% of codebase contains redundant classes, fields and methods that can't be removed by R8.")
        lines.append(f"- **Obfuscation score**: {obf_score:.2f}% of the codebase is available for R8 to obfuscate.")
        lines.append("")

        # Rule evaluation & ranking
        processed = []
        for r in data.get('keep_rule_blast_radius_table', []):
            br = r.get('blast_radius', {})
            c = len(br.get('class_blast_radius', []))
            f = len(br.get('field_blast_radius', []))
            m = len(br.get('method_blast_radius', []))
            impact = c + f + m
            if impact == 0:
                continue
            impact_pct = (impact / denom * 100.0) if denom > 0 else 0.0
            processed.append({
                'id': r.get('id'),
                'source': r.get('source', ''),
                'impact': impact,
                'impact_pct': impact_pct,
                'classes': c,
                'fields': f,
                'methods': m,
                'subsumed_by': br.get('subsumed_by', [])
            })

        processed.sort(key=lambda x: x['impact'], reverse=True)
        top_rules = [r for r in processed if not r['subsumed_by']][:5]
        subsumed = [r for r in processed if r['subsumed_by']]

        if top_rules:
            lines.append("## 4. Keep rules evaluation")
            lines.append("")
            for r in top_rules:
                lines.append(f"### `{r['source']}`")
                lines.append(f"- **Keeps**: {r['impact']} items ({r['impact_pct']:.2f}% of codebase). Classes: {r['classes']}, Fields: {r['fields']}, Methods: {r['methods']}")
                lines.append("- **Action**: Refine (Too broad, evaluate narrowing class/member scope)")
                lines.append("")

        if subsumed:
            lines.append("## 5. Subsumed keep rules")
            lines.append("")
            for r in subsumed:
                lines.append(f"### `{r['source']}`")
                lines.append(f"- **Subsumed By**: IDs {r['subsumed_by']}")
                lines.append("- **Action**: Remove")
                lines.append("")

    else:
        # Fallback evaluation based on R8 mapping and usage files
        mapping_files = glob.glob("**/mapping.txt", recursive=True)
        usage_files = glob.glob("**/usage.txt", recursive=True)
        seeds_files = glob.glob("**/seeds.txt", recursive=True)

        stripped_count = 0
        seeds_count = 0
        mapping_count = 0

        if usage_files and os.path.exists(usage_files[0]):
            with open(usage_files[0], 'r', encoding='utf-8', errors='ignore') as uf:
                stripped_count = sum(1 for _ in uf)
        if seeds_files and os.path.exists(seeds_files[0]):
            with open(seeds_files[0], 'r', encoding='utf-8', errors='ignore') as sf:
                seeds_count = sum(1 for _ in sf)
        if mapping_files and os.path.exists(mapping_files[0]):
            with open(mapping_files[0], 'r', encoding='utf-8', errors='ignore') as mf:
                mapping_count = sum(1 for _ in mf)

        lines.append("## 3. Optimization summary")
        lines.append("")
        lines.append(f"- **Stripped Dead Code**: {stripped_count} members stripped by R8 tree shaking.")
        lines.append(f"- **Kept Seeds**: {seeds_count} entries preserved by keep rules.")
        lines.append(f"- **Obfuscated Symbols**: {mapping_count} de-obfuscation translations generated.")
        lines.append("")

    report_content = "\n".join(lines)
    print(report_content)

    os.makedirs(os.path.dirname(os.path.abspath(output_report_path)), exist_ok=True)
    with open(output_report_path, 'w', encoding='utf-8') as f:
        f.write(report_content)

    if markdown_summary_path and os.path.exists(os.path.dirname(os.path.abspath(markdown_summary_path))):
        with open(markdown_summary_path, 'a', encoding='utf-8') as f:
            f.write("\n" + report_content + "\n")


if __name__ == "__main__":
    search_json = sys.argv[1] if len(sys.argv) > 1 else None
    if not search_json or not os.path.exists(search_json):
        candidates = (
            glob.glob("tmp/keepradius/*.json")
            + glob.glob("tmp/r8analysis/*.json")
            + glob.glob("**/build/reports/r8/*.json", recursive=True)
        )
        if candidates:
            search_json = sorted(candidates)[-1]
        else:
            search_json = "tmp/keepradius/keepruleradius.json"

    output_txt = sys.argv[2] if len(sys.argv) > 2 else "tmp/keepradius/analysis_result.txt"
    analyze(search_json, output_report_path=output_txt, markdown_summary_path=os.environ.get("GITHUB_STEP_SUMMARY"))
