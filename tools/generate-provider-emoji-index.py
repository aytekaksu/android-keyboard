#!/usr/bin/env python3
"""Generate the prediction-only emoji assets used by the FlorisBoard provider."""

import gzip
import json
import shutil
import sys
from collections import Counter, OrderedDict
from pathlib import Path


def valid_field(value: str) -> bool:
    return value != "" and not any(char in value for char in "\t\n\r")


def write_shortcuts(path: Path, shortcuts: OrderedDict[str, str]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for word, emoji in shortcuts.items():
            if valid_field(word) and valid_field(emoji):
                output.write(f"{word}\t{emoji}\n")


def english_index(*source_paths: Path) -> tuple[OrderedDict[str, str], set[str]]:
    entries = []
    for source_path in source_paths:
        entries.extend(json.loads(source_path.read_text(encoding="utf-8")))

    shortcuts: OrderedDict[str, str] = OrderedDict()
    people = set()
    for entry in entries:
        emoji = entry["emoji"]
        if entry.get("category") == "People & Body":
            people.add(emoji)
        description = entry.get("description", "")
        if description and " " not in description:
            shortcuts.setdefault(description.lower(), emoji)
    for entry in entries:
        emoji = entry["emoji"]
        for alias in entry.get("aliases", []):
            shortcuts.setdefault(alias.lower(), emoji)
    for entry in entries:
        if entry.get("category") == "ASCII":
            continue
        emoji = entry["emoji"]
        for tag in entry.get("tags", []):
            shortcuts.setdefault(tag.lower(), emoji)
    low_quality = {
        "flag", "united", "japanese", "mrs", "lady", "empty", "small", "last",
        "flat", "high", "old", "low", "long", "american", "cape", "costa",
        "cook", "diego", "central", "south", "heard", "north", "san", "el", "us",
    }
    for entry in entries:
        emoji = entry["emoji"]
        if entry.get("category") == "ASCII":
            continue
        for shortcut_source in entry.get("tags", []) + entry.get("aliases", []):
            if "_" not in shortcut_source:
                continue
            shortcut = shortcut_source.split("_", 1)[0].lower()
            if shortcut not in low_quality:
                shortcuts.setdefault(shortcut, emoji)
    return shortcuts, people


def translated_indices(source_path: Path) -> dict[str, OrderedDict[str, str]]:
    result: dict[str, OrderedDict[str, str]] = {}
    language = None
    with gzip.open(source_path, "rt", encoding="utf-8") as source:
        for raw_line in source:
            line = raw_line.rstrip("\n")
            if line.startswith("#"):
                language = line[1:].strip()
                continue
            if not language or "_" in language or language in {"en", "root"}:
                language = None
                continue
            translations = json.loads(line)
            counts = Counter()
            words_by_emoji = []
            for emoji, names in translations.items():
                words = {word for name in names for word in name.split() if len(word) > 1}
                counts.update(words)
                words_by_emoji.append((emoji, names, words))
            shortcuts: OrderedDict[str, str] = OrderedDict()
            for emoji, names, words in words_by_emoji:
                for word in sorted(words):
                    if counts[word] == 1:
                        shortcuts.setdefault(word.lower(), emoji)
                if names and " " not in names[-1]:
                    shortcuts.setdefault(names[-1].lower(), emoji)
            result[language] = shortcuts
            language = None
    return result


def main() -> None:
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: generate-provider-emoji-index.py "
            "emoji_i18n.jsondlgz gemoji.json supplemental_emotes.json output_dir"
        )
    translations_path, gemoji_path, supplemental_path, output_path = map(Path, sys.argv[1:])
    if output_path.exists():
        shutil.rmtree(output_path)
    output_path.mkdir(parents=True)

    english, people = english_index(gemoji_path, supplemental_path)
    write_shortcuts(output_path / "en.tsv", english)
    for language, shortcuts in translated_indices(translations_path).items():
        write_shortcuts(output_path / f"{language}.tsv", shortcuts)
    (output_path / "people.txt").write_text(
        "".join(f"{emoji}\n" for emoji in sorted(people)),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
