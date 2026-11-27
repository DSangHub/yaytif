#!/usr/bin/env python3
"""
Repeatable verification script for Yaytif Android i18n, XML integrity,
on-device privacy guardrails, secret hygiene, and zero TODO/FIXME policy.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ANDROID_DIR = SCRIPT_DIR.parent
APP_DIR = ANDROID_DIR / "app"
SRC_DIR = APP_DIR / "src"
MAIN_DIR = SRC_DIR / "main"
RES_DIR = MAIN_DIR / "res"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

EXPECTED_LOCALES = [
    ("en", "values"),
    ("pt-BR", "values-pt-rBR"),
    ("es", "values-es"),
    ("fr", "values-fr"),
    ("sw", "values-sw"),
    ("yo", "values-yo"),
    ("ha", "values-ha"),
    ("ig", "values-ig"),
    ("zu", "values-zu"),
    ("af", "values-af"),
    ("ar", "values-ar"),
    ("hi", "values-hi"),
    ("id", "values-id"),
]

FORMAT_SPEC_RE = re.compile(r"%\d+\$[sd]")
TODO_RE = re.compile(r"\b(TODO|FIXME)\b")
FORBIDDEN_REMOTE_TOKENS = (
    "LocalNotificationEntity",
    "LocalNotificationDao",
    "senderName",
    "notificationKey",
    "packageName",
    "appLabel",
)


def parse_strings_xml(path: Path) -> dict[str, str]:
    tree = ET.parse(path)
    root = tree.getroot()
    if root.tag != "resources":
        raise ValueError(f"{path}: expected <resources> root, got <{root.tag}>")
    entries: dict[str, str] = {}
    for elem in root.findall("string"):
        name = elem.attrib.get("name")
        if not name:
            raise ValueError(f"{path}: <string> missing name attribute")
        if name in entries:
            raise ValueError(f"{path}: duplicate string key '{name}'")
        text = "".join(elem.itertext())
        if not text.strip():
            raise ValueError(f"{path}: empty translation for key '{name}'")
        entries[name] = text
    return entries


def verify_xml_well_formedness() -> list[str]:
    errors: list[str] = []
    xml_files = [
        MAIN_DIR / "AndroidManifest.xml",
        RES_DIR / "xml" / "locales_config.xml",
        RES_DIR / "xml" / "data_extraction_rules.xml",
        RES_DIR / "xml" / "backup_rules.xml",
        RES_DIR / "values" / "themes.xml",
        *sorted((RES_DIR / "drawable").glob("*.xml")),
        *sorted(RES_DIR.glob("values*/strings.xml")),
    ]
    for xml_path in xml_files:
        if not xml_path.is_file():
            errors.append(f"Missing required XML resource: {xml_path}")
            continue
        try:
            ET.parse(xml_path)
        except ET.ParseError as exc:
            errors.append(f"Malformed XML in {xml_path}: {exc}")
    return errors


def verify_locales_and_strings() -> tuple[list[str], int]:
    errors: list[str] = []

    locales_config_path = RES_DIR / "xml" / "locales_config.xml"
    locales_root = ET.parse(locales_config_path).getroot()
    configured_tags = [
        el.attrib.get(f"{ANDROID_NS}name", "")
        for el in locales_root.findall("locale")
    ]
    expected_tags = [tag for tag, _ in EXPECTED_LOCALES]
    if configured_tags != expected_tags:
        errors.append(
            f"locales_config.xml mismatch: got {configured_tags}, expected {expected_tags}"
        )

    base_path = RES_DIR / "values" / "strings.xml"
    base_strings = parse_strings_xml(base_path)

    for tag, folder in EXPECTED_LOCALES:
        strings_path = RES_DIR / folder / "strings.xml"
        if not strings_path.is_file():
            errors.append(f"Missing locale strings file for '{tag}': {strings_path}")
            continue
        try:
            localized = parse_strings_xml(strings_path)
        except ValueError as exc:
            errors.append(str(exc))
            continue

        missing = sorted(set(base_strings.keys()) - set(localized.keys()))
        extra = sorted(set(localized.keys()) - set(base_strings.keys()))
        if missing:
            errors.append(f"{folder}/strings.xml missing keys: {missing}")
        if extra:
            errors.append(f"{folder}/strings.xml unexpected extra keys: {extra}")

        for key, base_val in base_strings.items():
            if key not in localized:
                continue
            expected_specs = sorted(FORMAT_SPEC_RE.findall(base_val))
            actual_specs = sorted(FORMAT_SPEC_RE.findall(localized[key]))
            if expected_specs != actual_specs:
                errors.append(
                    f"{folder}/strings.xml key '{key}' format spec mismatch: "
                    f"{actual_specs} != {expected_specs}"
                )

    return errors, len(base_strings)


def verify_manifest_and_backup_privacy() -> list[str]:
    errors: list[str] = []
    manifest_root = ET.parse(MAIN_DIR / "AndroidManifest.xml").getroot()
    app = manifest_root.find("application")
    if app is None:
        return ["AndroidManifest.xml missing <application> element"]

    expected_attrs = {
        f"{ANDROID_NS}supportsRtl": "true",
        f"{ANDROID_NS}allowBackup": "false",
        f"{ANDROID_NS}localeConfig": "@xml/locales_config",
        f"{ANDROID_NS}dataExtractionRules": "@xml/data_extraction_rules",
        f"{ANDROID_NS}fullBackupContent": "@xml/backup_rules",
    }
    for attr, expected in expected_attrs.items():
        actual = app.attrib.get(attr)
        if actual != expected:
            errors.append(
                f"AndroidManifest.xml <application> {attr}='{actual}', expected '{expected}'"
            )

    extraction_root = ET.parse(RES_DIR / "xml" / "data_extraction_rules.xml").getroot()
    for section in ("cloud-backup", "device-transfer"):
        sec_elem = extraction_root.find(section)
        if sec_elem is None:
            errors.append(f"data_extraction_rules.xml missing <{section}>")
            continue
        excluded_domains = {
            ex.attrib.get("domain") for ex in sec_elem.findall("exclude")
        }
        for required_domain in ("database", "sharedpref", "file"):
            if required_domain not in excluded_domains:
                errors.append(
                    f"data_extraction_rules.xml <{section}> must exclude domain='{required_domain}'"
                )

    return errors


def verify_no_todos_and_privacy_boundary() -> list[str]:
    errors: list[str] = []
    for path in sorted(SRC_DIR.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix not in {".kt", ".java", ".xml"}:
            continue
        content = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(content.splitlines(), start=1):
            if TODO_RE.search(line):
                errors.append(
                    f"Disallowed TODO/FIXME at {path.relative_to(ANDROID_DIR)}:{line_no}: {line.strip()}"
                )

    remote_dir = MAIN_DIR / "java" / "com" / "yaytif" / "app" / "data" / "remote"
    for kt_file in sorted(remote_dir.rglob("*.kt")):
        content = kt_file.read_text(encoding="utf-8")
        for token in FORBIDDEN_REMOTE_TOKENS:
            if token in content:
                errors.append(
                    f"Privacy violation: forbidden token '{token}' found in {kt_file.relative_to(ANDROID_DIR)}"
                )
    return errors


def verify_no_committed_signing_secrets() -> list[str]:
    errors: list[str] = []
    forbidden_names = {"keystore.properties"}
    forbidden_exts = {".jks", ".keystore", ".p12", ".pepk"}
    for root, dirs, files in os.walk(ANDROID_DIR):
        dirs[:] = [d for d in dirs if d not in {".gradle", "build"}]
        for filename in files:
            path = Path(root) / filename
            if filename in forbidden_names or path.suffix.lower() in forbidden_exts:
                errors.append(
                    f"Forbidden signing secret file present in workspace: {path.relative_to(ANDROID_DIR)}"
                )
    return errors


def main() -> int:
    errors: list[str] = []
    errors.extend(verify_xml_well_formedness())
    locale_errors, key_count = verify_locales_and_strings()
    errors.extend(locale_errors)
    errors.extend(verify_manifest_and_backup_privacy())
    errors.extend(verify_no_todos_and_privacy_boundary())
    errors.extend(verify_no_committed_signing_secrets())

    if errors:
        print("Yaytif Android verification FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(
        f"Yaytif Android verification PASSED: "
        f"{len(EXPECTED_LOCALES)} locales x {key_count} string keys, "
        f"RTL + backup privacy rules verified, 0 TODO/FIXME, 0 signing secrets."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
