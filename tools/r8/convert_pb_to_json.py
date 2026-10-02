#!/usr/bin/env python3
"""
Convert R8 BlastRadius protobuf report to JSON.
Follows the official r8-analyzer skill Path A/Path B specifications.
"""
import sys
import os
import glob

# Ensure script directory is in python search path
script_dir = os.path.dirname(os.path.abspath(__file__))
if script_dir not in sys.path:
    sys.path.insert(0, script_dir)

try:
    from google.protobuf import json_format
    import keep_radius_pb2
except ImportError as e:
    print(f"Error importing protobuf or keep_radius_pb2: {e}", file=sys.stderr)
    print("Ensure 'protobuf' is installed (e.g., pip install protobuf)", file=sys.stderr)
    sys.exit(1)


def convert_pb_to_json(input_pb_path, output_json_path):
    bundle = keep_radius_pb2.BlastRadiusContainer()

    try:
        with open(input_pb_path, "rb") as pb_file:
            binary_data = pb_file.read()
    except Exception as e:
        print(f"Error reading file {input_pb_path}: {e}", file=sys.stderr)
        return False

    try:
        bundle.ParseFromString(binary_data)
    except Exception as e:
        print(f"Error parsing protobuf from {input_pb_path}: {e}", file=sys.stderr)
        return False

    try:
        json_string = json_format.MessageToJson(
            bundle,
            always_print_fields_with_no_presence=True,
            preserving_proto_field_name=True,
            indent=4
        )
        os.makedirs(os.path.dirname(os.path.abspath(output_json_path)), exist_ok=True)
        with open(output_json_path, "w", encoding="utf-8") as json_file:
            json_file.write(json_string)
        print(f"Successfully converted {input_pb_path} -> {output_json_path}")
        return True
    except Exception as e:
        print(f"Error writing JSON: {e}", file=sys.stderr)
        return False


if __name__ == "__main__":
    input_pb = sys.argv[1] if len(sys.argv) > 1 else None
    if not input_pb:
        candidates = (
            glob.glob("tmp/r8analysis/*.pb")
            + glob.glob("**/build/reports/r8/*.pb", recursive=True)
            + glob.glob("tmp/keepradius/*.pb")
        )
        if not candidates:
            print("Error: No .pb file found in search paths", file=sys.stderr)
            sys.exit(1)
        input_pb = sorted(candidates)[-1]

    output_json = sys.argv[2] if len(sys.argv) > 2 else "tmp/r8analysis/keepruleradius.json"
    if not convert_pb_to_json(input_pb, output_json):
        sys.exit(1)
