"""Check the contract: OpenAPI validity, every event fixture against events.schema.json (and the
stream rules in events.md), and JSON fixtures against their OpenAPI schemas.

    python -m venv .venv && .venv/bin/pip install openapi-spec-validator==0.7.1
    .venv/bin/python scripts/validate.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate as validate_openapi
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parent.parent
TERMINAL = {"run.completed", "run.failed", "run.cancelled"}
# fixture → the OpenAPI schema its body must match
JSON_FIXTURES = {"agents.json": {"type": "object", "required": ["items"],
                                 "properties": {"items": {"type": "array", "items": {"$ref": "openapi#/components/schemas/Agent"}}}}}


def parse_sse(path: Path) -> list[tuple[str, str, dict]]:
    events, block = [], {}
    for line in path.read_text().splitlines() + [""]:
        if not line:
            if block:
                events.append((block.get("id", ""), block.get("event", ""), json.loads(block["data"])))
                block = {}
            continue
        if line.startswith(":"):
            continue
        key, _, value = line.partition(": ")
        block[key] = value
    return events


def main() -> int:
    errors: list[str] = []
    spec = yaml.safe_load((ROOT / "openapi.yaml").read_text())
    try:
        validate_openapi(spec)
    except Exception as e:  # noqa: BLE001
        errors.append(f"openapi.yaml: {e}")
    schema = json.loads((ROOT / "schemas" / "events.schema.json").read_text())
    events = Draft202012Validator(schema, format_checker=FormatChecker())
    for path in sorted((ROOT / "fixtures").glob("*.sse")):
        stream = parse_sse(path)
        for i, (eid, name, data) in enumerate(stream, 1):
            where = f"{path.name} #{i}"
            for err in events.iter_errors(data):
                errors.append(f"{where}: {err.message}")
            if name != data.get("type"):
                errors.append(f"{where}: event: {name!r} differs from type {data.get('type')!r}")
            if eid != str(data.get("seq")) or data.get("seq") != i:
                errors.append(f"{where}: id/seq must be {i}")
        if path.name.startswith("run-"):
            types = [d["type"] for _, _, d in stream]
            if not types or types[0] != "run.started":
                errors.append(f"{path.name}: a run stream starts with run.started")
            if [t for t in types if t in TERMINAL] != types[-1:] or types[-1] not in TERMINAL:
                errors.append(f"{path.name}: a run stream ends with exactly one terminal event")
    registry = Registry().with_resource("openapi", Resource(contents=spec, specification=DRAFT202012))
    for name, body_schema in JSON_FIXTURES.items():
        body = json.loads((ROOT / "fixtures" / name).read_text())
        for err in Draft202012Validator(body_schema, registry=registry).iter_errors(body):
            errors.append(f"{name}: {err.message} at {list(err.absolute_path)}")
    for e in errors:
        print("✗", e)
    print("contract OK" if not errors else f"{len(errors)} problem(s)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
