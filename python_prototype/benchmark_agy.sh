#!/usr/bin/env bash
# Quick-runner for ClinRef CDS evaluation benchmark on real Gemini via agy CLI.
# Usage:
#   ./benchmark_agy.sh                      # Full 20-case CDS evaluation suite
#   ./benchmark_agy.sh --verbose            # Full answers and diagnostic telemetry
#   ./benchmark_agy.sh --case <case-id>     # Run single case (e.g. pediatric-ceftriaxone)
#   ./benchmark_agy.sh --category <name>    # Run by category (e.g. patient_profile)

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

uv run python eval_prompt.py --provider gemini "$@"
