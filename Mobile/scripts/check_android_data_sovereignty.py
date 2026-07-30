#!/usr/bin/env python3
"""Fail when Android production code gains unregistered domain-data ownership."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Rule:
    rule_id: str
    pattern: re.Pattern[str]
    path_pattern: re.Pattern[str] | None = None

    def applies_to(self, relative_path: str) -> bool:
        return self.path_pattern is None or self.path_pattern.search(relative_path) is not None


RULES = (
    Rule("BUSINESS_API_PATH", re.compile(r'"/api/v1/[^"\r\n]*"')),
    Rule(
        "DIRECT_HTTP_OUTSIDE_TRANSPORT",
        re.compile(r"\bHttpURLConnection\b"),
        re.compile(r"^(?!com/mineg/mobile/platform/AndroidTransportPort\.kt$).+"),
    ),
    Rule(
        "DOMAIN_PREFERENCE_CACHE",
        re.compile(r'getSharedPreferences\("mineg_(?!secure_values|media_permission|backup_scheduler)[^"\r\n]+"'),
    ),
    Rule(
        "VIEWMODEL_DOMAIN_SIMULATION",
        re.compile(r"\b(?:finishMockDownload|toggleShare|submitFeedback)\b"),
        re.compile(r"^com/mineg/mobile/app/"),
    ),
    Rule(
        "PLATFORM_BUSINESS_DATA_OWNER",
        re.compile(r"\b(?:class|object)\s+(?:AndroidAccountClient|MockMineGRepository)\b"),
        re.compile(r"^com/mineg/mobile/(?:account|app)/"),
    ),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--exceptions", type=Path, required=True)
    parser.add_argument("--report", action="store_true")
    return parser.parse_args()


def line_number(content: str, offset: int) -> int:
    return content.count("\n", 0, offset) + 1


def main() -> int:
    args = parse_args()
    manifest = json.loads(args.exceptions.read_text(encoding="utf-8"))
    if manifest.get("defaultPolicy") != "DENY":
        print("platform data exception manifest must use defaultPolicy=DENY", file=sys.stderr)
        return 2

    allowances: dict[tuple[str, str, str], int] = {}
    for exception in manifest.get("exceptions", []):
        required = {"id", "trackingId", "ruleId", "path", "owner", "reason", "removalCondition", "allowedMatches"}
        missing = required.difference(exception)
        if missing:
            print(f"exception {exception.get('id', '<unknown>')} misses {sorted(missing)}", file=sys.stderr)
            return 2
        relative_path = exception["path"]
        if relative_path.startswith("/") or ".." in Path(relative_path).parts or "*" in relative_path:
            print(f"exception path must be an exact production-relative path: {relative_path}", file=sys.stderr)
            return 2
        for allowed in exception["allowedMatches"]:
            key = (exception["ruleId"], relative_path, allowed["value"])
            if key in allowances or not isinstance(allowed.get("maxOccurrences"), int) or allowed["maxOccurrences"] < 1:
                print(f"invalid or duplicate allowance: {key}", file=sys.stderr)
                return 2
            allowances[key] = allowed["maxOccurrences"]

    observed: Counter[tuple[str, str, str]] = Counter()
    locations: dict[tuple[str, str, str], list[int]] = {}
    for source_file in sorted(args.source.rglob("*.kt")):
        relative_path = source_file.relative_to(args.source).as_posix()
        content = source_file.read_text(encoding="utf-8")
        for rule in RULES:
            if not rule.applies_to(relative_path):
                continue
            for match in rule.pattern.finditer(content):
                key = (rule.rule_id, relative_path, match.group(0))
                observed[key] += 1
                locations.setdefault(key, []).append(line_number(content, match.start()))

    if args.report:
        for (rule_id, path, value), count in sorted(observed.items()):
            print(json.dumps({"ruleId": rule_id, "path": path, "value": value,
                              "count": count, "lines": locations[(rule_id, path, value)]}, ensure_ascii=False))
        return 0

    violations = []
    for key, count in sorted(observed.items()):
        allowed_count = allowances.get(key, 0)
        if count > allowed_count:
            violations.append((key, count, allowed_count, locations[key]))
    if violations:
        print("Android data-sovereignty violations:", file=sys.stderr)
        for (rule_id, path, value), count, allowed_count, lines in violations:
            print(
                f"  {path}:{lines[0]} [{rule_id}] {value!r} observed={count} allowed={allowed_count}",
                file=sys.stderr,
            )
        print("Register a narrow, owned removal exception or move the logic into C++ Core.", file=sys.stderr)
        return 1

    print(f"Android data-sovereignty scan passed: {sum(observed.values())} registered transitional hits")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
