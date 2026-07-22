#!/usr/bin/env python3
import argparse
import json
import random
import re
from collections import Counter

parser = argparse.ArgumentParser()
parser.add_argument("--dataset", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--min-accuracy", type=float, default=0.80)
args = parser.parse_args()

with open(args.dataset, encoding="utf-8") as handle:
    rows = [json.loads(line) for line in handle if line.strip()]

random.Random(42).shuffle(rows)
keywords = {}
for row in rows:
    keywords.setdefault(row["label"], Counter()).update(
        re.findall(r"[a-zäöüß0-9]+", row["text"].lower())
    )

rules = {
    label: [word for word, _ in counter.most_common(20)]
    for label, counter in keywords.items()
}

def predict(text):
    words = set(re.findall(r"[a-zäöüß0-9]+", text.lower()))
    return max(rules, key=lambda label: len(words & set(rules[label])))

accuracy = sum(predict(row["text"]) == row["label"] for row in rows) / len(rows)
if accuracy < args.min_accuracy:
    raise SystemExit(
        f"Validation accuracy {accuracy:.3f} is below required {args.min_accuracy:.3f}"
    )

model = {
    "format": "jarvis-keyword-v1",
    "accuracy": accuracy,
    "sample_count": len(rows),
    "labels": rules,
}
with open(args.output, "w", encoding="utf-8") as handle:
    json.dump(model, handle, ensure_ascii=False, indent=2)

print(f"accuracy={accuracy:.3f}; samples={len(rows)}")
