"""Export Crowdin-compatible JSON and merge reviewed local translations into HushGram's TSVs.

Copyright 2026 HushGram contributors. GPL-3.0-only.
https://github.com/SysAdminDoc/HushGram

This program uses local files only. It neither authenticates with Crowdin nor approves text.
Imports validate the entire input before replacing one table. Run gen-l10n.py afterwards.
"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import stat
import sys
import tempfile
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parent.parent
TABLE_DIRECTORY = Path("extensions/shared/library/src/main/l10n")
MAX_BYTES = 2 * 1024 * 1024
MAX_ENTRIES = 4096
MAX_TEXT = 8192

spec = importlib.util.spec_from_file_location("hushgram_l10n_generator", ROOT / "scripts/gen-l10n.py")
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class ImportError(ValueError):
    """A file cannot be applied safely."""


class Snapshot(NamedTuple):
    data: bytes | None
    stamp: tuple | None


def identifier(english):
    return "hg_" + hashlib.sha256(english.encode("utf-8")).hexdigest()


def language_tag(value):
    match = re.fullmatch(r"([a-z]{2,3})(?:-(?:r)?([a-z]{2}))?", value, re.IGNORECASE)
    if not match:
        raise ImportError("invalid language tag")
    language, region = match.groups()
    language = {"id": "in", "he": "iw", "yi": "ji"}.get(language.lower(), language.lower())
    if language in ("con", "prn", "aux", "nul"):
        raise ImportError("language tag is a reserved file name")
    tag = language + ("-r" + region.upper() if region else "")
    if tag in ("en", "en-rXA", "ar-rXB"):
        raise ImportError("English and the runtime pseudo-locales do not use translation tables")
    return tag


def stamp(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise ImportError("expected a regular file: " + str(path))
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns, info.st_mode


def bounded_bytes(path):
    with path.open("rb") as handle:
        data = handle.read(MAX_BYTES + 1)
    if len(data) > MAX_BYTES:
        raise ImportError(f"file exceeds the {MAX_BYTES}-byte limit: {path}")
    return data


def snapshot(path):
    try:
        before = stamp(path)
    except FileNotFoundError:
        return Snapshot(None, None)
    data = bounded_bytes(path)
    if stamp(path) != before:
        raise ImportError("file changed while it was being read: " + str(path))
    return Snapshot(data, before)


def table_rows(data, path):
    if data is None:
        raise ImportError("no translation table: " + str(path))
    try:
        rows = generator.read_text(data.decode("utf-8"), str(path))
    except (SystemExit, UnicodeError) as error:
        raise ImportError(str(error)) from error
    if len(rows) > MAX_ENTRIES:
        raise ImportError("too many translation rows")
    for key, value in rows.items():
        validate_text(key)
        validate_text(value)
    return rows


def validate_text(text):
    if not isinstance(text, str) or not text.strip():
        raise ImportError("translations must be nonempty strings")
    if len(text) > MAX_TEXT:
        raise ImportError(f"text exceeds the {MAX_TEXT}-character limit")
    prohibited = generator.invisible(text)
    if prohibited is not None:
        raise ImportError(f"text contains prohibited U+{ord(prohibited):04X}")
    if "\u2013" in text or "\u2014" in text or " - " in text:
        raise ImportError("text contains a prose dash")


def catalog(root):
    root = Path(root).resolve()
    folder = root / TABLE_DIRECTORY
    if not folder.is_dir() or not folder.resolve().is_relative_to(root):
        raise ImportError("no translation directory inside the repository")
    keys, others = generator.source_catalog(str(root))
    allowed = generator.allowed_keys(keys, others)
    if len(allowed) > MAX_ENTRIES:
        raise ImportError("source catalog exceeds the entry limit")
    for key in allowed:
        validate_text(key)
    tables = {}
    for path in sorted(folder.glob("*.tsv")):
        if language_tag(path.stem) != path.stem:
            raise ImportError("table name is not a canonical Android language tag: " + path.name)
        saved = snapshot(path)
        rows = table_rows(saved.data, path)
        generator.validate_keys(rows, keys, others)
        tables[path.stem] = saved, rows
    if not tables:
        raise ImportError("no translation tables")
    mapping = {identifier(key): key for key in allowed}
    if len(mapping) != len(allowed):
        raise ImportError("source identifier collision")
    return folder, keys, tables, mapping


def json_bytes(rows):
    output = {identifier(key): value for key, value in rows.items()}
    data = (json.dumps(output, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ImportError("export exceeds the file-size limit")
    return data


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ImportError("duplicate JSON identifier: " + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ImportError("invalid JSON constant: " + value)


def translations(path, mapping):
    data = bounded_bytes(Path(path))
    try:
        parsed = json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique_object,
                            parse_constant=reject_constant)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ImportError("malformed UTF-8 JSON: " + str(error)) from error
    if not isinstance(parsed, dict) or not parsed:
        raise ImportError("expected a nonempty flat JSON object")
    if len(parsed) > MAX_ENTRIES:
        raise ImportError("too many JSON entries")
    rows = {}
    for key, value in parsed.items():
        if key not in mapping:
            raise ImportError("unknown source identifier")
        validate_text(value)
        english = mapping[key]
        problem = generator.placeholder_problem(english, value)
        if problem:
            raise ImportError(problem + " for " + key)
        rows[english] = value
    return rows


@contextmanager
def transaction_lock(path):
    """Exclusive creation serializes importers; another writer's lock is never reclaimed."""
    lock = path.with_name("." + path.name + ".import-lock")
    token = json.dumps({"pid": os.getpid(), "owner": uuid.uuid4().hex}).encode("ascii")
    try:
        descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError as error:
        raise ImportError("another import holds the transaction lock for " + path.name) from error
    identity = os.fstat(descriptor)
    identity = identity.st_dev, identity.st_ino

    def owned():
        current = lock.lstat()
        if (current.st_dev, current.st_ino) != identity or lock.read_bytes() != token:
            raise ImportError("transaction lock ownership changed")

    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(token)
            handle.flush()
            os.fsync(handle.fileno())
        # Closing the lock handle can fail. Do it before permitting a commit, while the
        # exclusive marker still holds the transaction until the finally block removes it.
        yield owned
    finally:
        # Do not remove a replacement lock, including a symlink, even after a failed write.
        try:
            current = lock.lstat()
            if ((current.st_dev, current.st_ino) == identity and stat.S_ISREG(current.st_mode)
                    and lock.read_bytes() == token):
                lock.unlink()
        except FileNotFoundError:
            pass
        except OSError as error:
            # A committed replacement must not be reported as unapplied because lock cleanup
            # failed. Preserve the original body exception, if any, and report the leftover.
            print(f"translation sync warning: lock cleanup failed for {lock}: {error}", file=sys.stderr)


def replace_if_unchanged(path, expected, data):
    """Validate the snapshot again under the exclusive import lock, then replace atomically."""
    if len(data) > MAX_BYTES:
        raise ImportError("table exceeds the file-size limit")
    temporary = None
    with transaction_lock(path) as owned:
        if snapshot(path) != expected:
            raise ImportError("destination changed; export or review the latest table first")
        if data == expected.data:
            owned()
            return False
        if expected.stamp and not expected.stamp[-1] & (stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH):
            raise ImportError("destination is read-only")
        try:
            with tempfile.NamedTemporaryFile(mode="wb", delete=False, dir=path.parent,
                                             prefix="." + path.name + ".", suffix=".tmp") as handle:
                temporary = Path(handle.name)
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            if expected.stamp:
                os.chmod(temporary, stat.S_IMODE(expected.stamp[-1]))
            owned()
            if snapshot(path) != expected:
                raise ImportError("destination changed during staging; nothing was imported")
            os.replace(temporary, path)
            # The temporary name no longer belongs to this import. Do not inspect or
            # remove anything recreated there, or let cleanup misreport a committed write.
            temporary = None
            return True
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError as error:
                    print(f"translation sync warning: temporary cleanup failed for {temporary}: {error}",
                          file=sys.stderr)


def merged_table(raw, rows, changed):
    """Retain untouched row bytes, comments, BOM and line endings, including no-change imports."""
    if raw is None:
        header = b"# HushGram translations. English key, tab, translation.\n"
        return header + "".join(generator.encode_field(key) + "\t" + generator.encode_field(value) + "\n"
                                 for key, value in sorted(changed.items())).encode("utf-8")
    remaining = dict(changed)
    output = []
    for number, line in enumerate(raw.splitlines(keepends=True)):
        prefix = b"\xef\xbb\xbf" if number == 0 and line.startswith(b"\xef\xbb\xbf") else b""
        body = line[len(prefix):]
        if body.endswith(b"\r\n"):
            body, ending = body[:-2], b"\r\n"
        elif body.endswith(b"\n"):
            body, ending = body[:-1], b"\n"
        else:
            ending = b""
        if b"\t" in body:
            source, _ = body.split(b"\t", 1)
            key = generator.decode_field(source.decode("utf-8"))
            value = remaining.pop(key, rows[key])
            if value != rows[key]:
                line = prefix + source + b"\t" + generator.encode_field(value).encode("utf-8") + ending
        output.append(line)
    if remaining:
        if output and not output[-1].endswith(b"\n"):
            output[-1] += b"\n"
        output.extend((generator.encode_field(key) + "\t" + generator.encode_field(value) + "\n").encode("utf-8")
                      for key, value in sorted(remaining.items()))
    return b"".join(output)


def export_catalog(root, output):
    _, keys, tables, _ = catalog(root)
    source = set(keys) | {key for _, rows in tables.values() for key in rows}
    files = {"en": json_bytes({key: key if key in keys else generator.plural_base(key) for key in source})}
    files.update({language: json_bytes(rows) for language, (_, rows) in tables.items()})
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    for language, data in files.items():
        path = output / (language + ".json")
        replace_if_unchanged(path, snapshot(path), data)
    return len(source), sum(len(rows) for _, rows in tables.values())


def import_translations(root, language, source, partial=False, new_language=False):
    folder, keys, tables, mapping = catalog(root)
    language = language_tag(language)
    path = folder / (language + ".tsv")
    if new_language == (language in tables):
        raise ImportError("use --new-language only when explicitly adding an absent table")
    expected, previous = tables.get(language, (Snapshot(None, None), {}))
    incoming = translations(source, mapping)
    required = set(keys) | set(previous)
    if (not partial or new_language) and required - incoming.keys():
        raise ImportError(f"incomplete translations: {len(required - incoming.keys())} required rows missing")
    result = dict(previous)
    result.update(incoming)
    generator.validate_keys(result, keys, {generator.plural_base(key) for key in mapping.values()
                                         if generator.PLURAL_VARIANT.match(key)})
    data = merged_table(expected.data, previous, incoming)
    if table_rows(data, path) != result:
        raise ImportError("staged TSV does not preserve the imported text")
    changed = replace_if_unchanged(path, expected, data)
    return changed, len(incoming), len(previous.keys() - incoming.keys())


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="write English and existing languages as flat JSON")
    export.add_argument("--output", type=Path, required=True)
    merge = commands.add_parser("import", help="validate and atomically replace one TSV table")
    merge.add_argument("--language", required=True)
    merge.add_argument("--input", type=Path, required=True)
    merge.add_argument("--partial", action="store_true", help="retain rows absent from the input")
    merge.add_argument("--new-language", action="store_true", help="explicitly add a complete new language")
    args = parser.parse_args()
    try:
        if args.command == "export":
            keys, rows = export_catalog(args.root, args.output)
            print(f"exported {keys} source keys and {rows} translations")
        else:
            changed, imported, retained = import_translations(args.root, args.language, args.input,
                                                              args.partial, args.new_language)
            status = "imported" if changed else "unchanged"
            print(f"{status}: {imported} supplied rows, {retained} retained rows")
    except (OSError, ValueError) as error:
        print("translation sync failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
