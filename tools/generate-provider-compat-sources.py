#!/usr/bin/env python3
"""Generate compile-time R/BuildConfig shims for the UI-free provider module."""

import re
import sys
import xml.etree.ElementTree as ElementTree
from collections import defaultdict
from pathlib import Path


RESOURCE_REFERENCE = re.compile(
    r"(?<![\w.])R\.([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)"
)


def source_references(source_roots: list[Path]) -> dict[str, set[str]]:
    references: dict[str, set[str]] = defaultdict(set)
    for source_root in source_roots:
        for path in source_root.rglob("*"):
            if path.suffix not in {".java", ".kt"}:
                continue
            for resource_type, name in RESOURCE_REFERENCE.findall(
                path.read_text(encoding="utf-8")
            ):
                if resource_type != "class":
                    references[resource_type].add(name)
    return references


def actual_resources(resource_roots: list[Path]) -> set[tuple[str, str]]:
    resources: set[tuple[str, str]] = set()
    for resource_root in resource_roots:
        if not resource_root.exists():
            continue
        for path in resource_root.rglob("*"):
            if not path.is_file():
                continue
            directory_type = path.parent.name.split("-", 1)[0]
            if directory_type != "values":
                name = path.name.split(".", 1)[0]
                resources.add((directory_type, name))
                continue
            root = ElementTree.parse(path).getroot()
            for element in root:
                name = element.attrib.get("name")
                if not name:
                    continue
                tag = element.tag
                if tag in {"string-array", "integer-array"}:
                    tag = "array"
                elif tag == "item":
                    tag = element.attrib.get("type", "")
                if tag and tag != "declare-styleable":
                    resources.add((tag, name))
    return resources


def styleable_groups(resource_roots: list[Path]) -> set[str]:
    groups = set()
    for resource_root in resource_roots:
        for path in resource_root.glob("values*/**/*.xml"):
            root = ElementTree.parse(path).getroot()
            groups.update(
                element.attrib["name"]
                for element in root.findall("declare-styleable")
                if "name" in element.attrib
            )
    return groups


def generate_r(
    path: Path,
    references: dict[str, set[str]],
    actual: set[tuple[str, str]],
    styleables: set[str],
) -> None:
    lines = [
        "package org.futo.inputmethod.latin;",
        "",
        "/** Compile-time compatibility IDs; the provider packages only its engine resources. */",
        "public final class R {",
        "    private R() {}",
    ]
    for resource_type in sorted(references):
        lines.append(f"    public static final class {resource_type} {{")
        lines.append(f"        private {resource_type}() {{}}")
        for name in sorted(references[resource_type]):
            if resource_type == "styleable" and name in styleables:
                declaration = f"int[] {name} = new int[0]"
            elif (resource_type, name) in actual:
                declaration = (
                    f"int {name} = "
                    f"org.futo.inputmethod.latin.provider.R.{resource_type}.{name}"
                )
            else:
                declaration = f"int {name} = 0"
            lines.append(f"        public static final {declaration};")
        lines.append("    }")
    lines.extend(["}", ""])
    path.write_text("\n".join(lines), encoding="utf-8")


def generate_build_config(path: Path) -> None:
    provider = "org.futo.inputmethod.latin.provider.BuildConfig"
    delegated_fields = {
        "boolean": ["DEBUG"],
        "int": ["VERSION_CODE"],
        "String": [
            "FUTOPAY_PRICE",
            "FUTOPAY_URL",
            "GOOGLEPAY_PRICE",
            "GOOGLEPAY_URL",
            "PAYMENT_PRICE",
            "VERSION_NAME",
        ],
    }
    lines = [
        "package org.futo.inputmethod.latin;",
        "",
        "/** Compatibility facade for source retained from the upstream application module. */",
        "public final class BuildConfig {",
        "    private BuildConfig() {}",
        "    public static final boolean IS_PLAYSTORE_BUILD = false;",
        "    public static final boolean UPDATE_CHECKING = false;",
        "    public static final boolean UPDATE_CHECKING_NETWORK = false;",
        '    public static final String APPLICATION_ID = "org.futo.inputmethod.latin.unstable";',
        '    public static final String BRANCH = "floris-provider";',
        '    public static final String FLAVOR = "provider";',
    ]
    for field_type, names in delegated_fields.items():
        lines.extend(
            f"    public static final {field_type} {name} = {provider}.{name};"
            for name in names
        )
    lines.extend(["}", ""])
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: generate-provider-compat-sources.py output_dir generated_resource_dir"
        )
    root = Path(__file__).resolve().parent.parent
    output = Path(sys.argv[1])
    generated_resources = Path(sys.argv[2])
    source_roots = [root / "common/src", root / "java/src", root / "java/unstable/java"]
    resource_roots = [root / "floris-autocorrect-provider/src/main/res", generated_resources]

    package_directory = output / "org/futo/inputmethod/latin"
    package_directory.mkdir(parents=True, exist_ok=True)
    references = source_references(source_roots)
    actual = actual_resources(resource_roots)
    styleables = styleable_groups([root / "java/res", root / "java/res-large"])
    generate_r(package_directory / "R.java", references, actual, styleables)
    generate_build_config(package_directory / "BuildConfig.java")


if __name__ == "__main__":
    main()
