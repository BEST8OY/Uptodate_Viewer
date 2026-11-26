#!/usr/bin/env bash
# ClinRef Remediation & Self-Correction Evaluation Benchmark Shortcut
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/python_prototype"

exec uv run python eval_remediation.py "$@"
