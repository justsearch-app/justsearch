"""Immutable, content-addressed distribution snapshots and cached content digests.

The engine and its extraction children launch from a read-only snapshot, never from the
mutable build folder. A snapshot mirrors the minimal repository layout the eval launcher
reads: the installed distribution, the runtime resources the engine loads through its repo
root (SSOT, the headless application.yaml) and the launch scripts themselves. Its digest
(sorted relative paths, per-file SHA-256 and executable bit) is the index key's code
component; the provenance receipt is the only file left out.

Layout under ``ROOT`` (``~/.cache/justsearch-quality/dists``)::

  store/<sha256>.<r|x>        one read-only copy per content and executable bit
  snapshots/<digest>/         read-only tree of hard links into the store
  snapshots/<digest>.json     record: digest and the source identity it was built from
  snapshots/<digest>.lock     held shared while an engine may run from the snapshot
  sources/<sha>.json          source identity -> digest (lookup for an equal checkout)
  checkouts/<sha>.json        newest snapshot per checkout (retention)
  refs/<digest>/<sha>         cache entries built against a snapshot (retention)
  digests.v1.json             content digests keyed by file identity and change time

A snapshot directory appears by one rename after it is complete, and its record only after
that: an interrupted install or copy leaves no adoptable half snapshot.
"""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import uuid
from typing import Iterable

from .types import atomic_write_text, canonical_json, sha256_text

SCHEMA = "jseval.quality.distribution.v1"
# JSEVAL_QUALITY_DISTRIBUTIONS relocates the store (tests and their worker subprocesses).
ROOT = Path(os.environ.get("JSEVAL_QUALITY_DISTRIBUTIONS") or Path.home() / ".cache/justsearch-quality/dists")
INSTALL = "modules/ui/build/install/ui"
RECEIPT = ".jseval-source.json"
LAUNCHER = "scripts/dev/eval-runner.cjs"
# Repository files the engine or the launcher reads at run time through its repo root.
RESOURCES = ("SSOT", "modules/ui/src/main/resources/headless-config/application.yaml",
             "contracts/native-targets/spec.v1.json", "governance/exit-ledger.v1.schema.json",
             "governance/supervision-contract.v1.json")
# A change younger than this may share a timestamp tick with a later write: hash it every time.
RACY_SECONDS = 2.0


class DistributionRefused(RuntimeError):
    """No trustworthy snapshot for this checkout, or a snapshot input is unusable."""


# --------------------------------------------------------------------------- content digests

_digests: dict | None = None
_dirty = False


def _digest_file() -> Path:
    return ROOT / "digests.v1.json"


def _identity(st: os.stat_result) -> list:
    return [st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns, st.st_ctime_ns]


def file_sha256(path: Path) -> str:
    """SHA-256 of a regular file's bytes, cached by inode, size, mtime and ctime.

    Every content change updates ctime; an entry is only cached once its change time is
    older than ``RACY_SECONDS``, so a write within the same timestamp tick is never hidden.
    """
    global _digests, _dirty
    if _digests is None:
        try:
            _digests = json.loads(_digest_file().read_text(encoding="utf-8"))
        except (OSError, ValueError):
            _digests = {}
        if not isinstance(_digests, dict):
            _digests = {}
    path = Path(os.path.abspath(path))
    before = path.stat()
    if not stat.S_ISREG(before.st_mode):
        raise DistributionRefused(f"not a regular file: {path}")
    key = str(path)
    cached = _digests.get(key)
    if isinstance(cached, list) and cached[:5] == _identity(before) and isinstance(cached[5], str):
        return cached[5]
    with path.open("rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    after = path.stat()
    if _identity(after) != _identity(before):
        raise DistributionRefused(f"file changed while hashing: {path}")
    if time.time_ns() - max(after.st_mtime_ns, after.st_ctime_ns) > RACY_SECONDS * 1e9:
        _digests[key] = [*_identity(after), digest]
        _dirty = True
    return digest


def flush_digests() -> None:
    """Persist new digest entries; concurrent writers merge, and vanished paths are dropped."""
    global _dirty
    if not _dirty or _digests is None:
        return
    try:
        current = json.loads(_digest_file().read_text(encoding="utf-8"))
        if not isinstance(current, dict):
            current = {}
    except (OSError, ValueError):
        current = {}
    current.update(_digests)
    kept = {k: v for k, v in current.items() if os.path.lexists(k)}
    try:
        atomic_write_text(_digest_file(), json.dumps(kept, separators=(",", ":")))
        _dirty = False
    except OSError:
        pass  # A cache; recomputing is always correct.


def tree_digest(root: Path, exclude=lambda rel: False) -> dict[str, str]:
    """Per-file digests of a directory, following file links; unreadable entries refuse."""
    out = {}
    for rel, path in _walk(root):
        if exclude(rel):
            continue
        try:
            out[rel] = file_sha256(path.resolve(strict=True))
        except (OSError, DistributionRefused) as e:
            raise DistributionRefused(f"unresolvable input {path}: {e}") from e
    return out


def _unreadable(error: OSError):
    raise DistributionRefused(f"unreadable folder {error.filename}: {error.strerror or error}") from error


def _walk(root: Path) -> Iterable[tuple[str, Path]]:
    """Every file below ``root``; a folder that cannot be listed refuses instead of looking empty."""
    for directory, dirs, files in os.walk(root, followlinks=True, onerror=_unreadable):
        dirs.sort()
        for name in sorted(files):
            path = Path(directory) / name
            yield path.relative_to(root).as_posix(), path


# --------------------------------------------------------------------------- snapshot contents


def launch_files(checkout: Path) -> list[str]:
    """The eval launcher and every module it requires, including lazy requires (static closure)."""
    seen, todo = set(), [checkout / LAUNCHER]
    while todo:
        path = todo.pop()
        rel = path.resolve().relative_to(checkout.resolve()).as_posix()
        if rel in seen:
            continue
        if not path.is_file():
            raise DistributionRefused(f"launch file missing: {rel}")
        seen.add(rel)
        text = path.read_text(encoding="utf-8")
        for spec in re.findall(r"""require\(\s*['"](\.{1,2}/[^'"]+)['"]\s*\)""", text):
            target = (path.parent / spec).resolve()
            todo.append(target if target.suffix else target.with_suffix(".js"))
    return sorted(seen)


def snapshot_inputs(checkout: Path) -> dict[str, Path]:
    """Relative snapshot path -> source file in the checkout, for a complete install."""
    checkout = checkout.resolve()
    install = checkout / INSTALL
    if not (install / "bin/ui").is_file():
        raise DistributionRefused("install distribution missing")
    files = {}
    for rel, path in _walk(install):
        if rel != RECEIPT:
            files[f"{INSTALL}/{rel}"] = path
    for name in RESOURCES:
        source = checkout / name
        if source.is_dir():
            files.update({f"{name}/{rel}": path for rel, path in _walk(source)})
        elif source.is_file():
            files[name] = source
        else:
            raise DistributionRefused(f"runtime resource missing: {name}")
    files.update({rel: checkout / rel for rel in launch_files(checkout)})
    for rel, path in files.items():
        if path.is_symlink() or not path.is_file():
            raise DistributionRefused(f"snapshot input is not a regular file: {rel}")
    return files


def _classpath(script: str) -> set[str]:
    """Install-relative jars the start script's CLASSPATH names (the launcher loads lib/*)."""
    match = re.search(r"^CLASSPATH=(.*)$", script, re.M)
    if not match:
        raise DistributionRefused("installed start script names no CLASSPATH")
    jars = {entry.removeprefix("$APP_HOME/") for entry in match.group(1).split(":") if entry}
    if any(not jar.startswith("lib/") or jar.count("/") != 1 or ".." in jar for jar in jars):
        raise DistributionRefused("installed start script names a classpath entry outside lib/")
    return jars


def _launcher_inventory(tree: Path, rels: Iterable[str]) -> None:
    """Refuse a snapshot that lacks or adds a jar the launcher would load from lib/*."""
    prefix = f"{INSTALL}/"
    lib = {rel[len(prefix):] for rel in rels if rel.startswith(f"{prefix}lib/")}
    jars = _classpath((tree / INSTALL / "bin/ui").read_text(encoding="utf-8"))
    if lib != jars:
        raise DistributionRefused(f"incomplete install: classpath jars missing {sorted(jars - lib)[:5]}, "
                                  f"unnamed files in lib/ {sorted(lib - jars)[:5]}")


def _rows(entries: dict[str, tuple[str, bool]]) -> list:
    return sorted([rel, sha, "x" if executable else "r"] for rel, (sha, executable) in entries.items())


def digest_of(entries: dict[str, tuple[str, bool]]) -> str:
    return sha256_text(canonical_json(_rows(entries)))


def _executable(st: os.stat_result) -> bool:
    return bool(st.st_mode & stat.S_IXUSR)


# --------------------------------------------------------------------------- store and snapshots


def path(digest: str) -> Path:
    if not re.fullmatch(r"[0-9a-f]{64}", digest or ""):
        raise DistributionRefused(f"not a snapshot digest: {digest!r}")
    return ROOT / "snapshots" / digest


@contextlib.contextmanager
def _locked(name: str, kind: int = fcntl.LOCK_EX):
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / name).open("a+") as lock:
        fcntl.flock(lock, kind)
        yield


def _store(source: Path, sha: str, executable: bool) -> Path:
    """A store file with exactly ``sha`` content: copied, re-hashed from the copy, made read-only."""
    store = ROOT / "store"
    store.mkdir(parents=True, exist_ok=True)
    target = store / f"{sha}.{'x' if executable else 'r'}"
    if target.exists():
        try:
            if file_sha256(target) == sha:
                return target
        except (OSError, DistributionRefused):
            pass
    pending = store / f".tmp-{uuid.uuid4().hex}"
    try:
        shutil.copyfile(source, pending)
        with pending.open("rb") as f:
            copied = hashlib.file_digest(f, "sha256").hexdigest()
        if copied != sha:
            raise DistributionRefused(f"build folder changed during snapshot: {source}")
        os.chmod(pending, 0o555 if executable else 0o444)
        # Replaces a corrupt store file; snapshots linked to the old inode then fail verification.
        os.replace(pending, target)
    finally:
        pending.unlink(missing_ok=True)
    return target


def _make_writable(tree: Path) -> None:
    for directory, dirs, _ in os.walk(tree):
        os.chmod(directory, 0o755)


def _remove_tree(tree: Path) -> None:
    if tree.exists():
        _make_writable(tree)
        shutil.rmtree(tree)


def create(checkout: Path, source: list) -> str:
    """Copy the checkout's complete install into a snapshot; return its digest.

    Called inside the installation's own hold (the Gradle slot's worktree lock), so no other
    install runs meanwhile. Every build-folder file is hashed, stored through a verified copy,
    and its file identity is rechecked after the snapshot is complete; the install folder is
    walked again and must hold exactly the snapshot's files, and lib/ exactly the jars the start
    script's CLASSPATH names. Any difference, or a folder that cannot be listed, refuses this
    snapshot before its record exists.
    """
    files = snapshot_inputs(checkout)
    entries, before = {}, {}
    for rel, src in files.items():
        st = src.stat()
        before[rel] = _identity(st)
        entries[rel] = (file_sha256(src), _executable(st))
    digest = digest_of(entries)
    final = path(digest)
    with _locked(".lock"):
        try:
            intact = _record(digest) is not None and verify(digest) == digest
        except DistributionRefused:
            intact = False  # rebuilt below from verified store copies
        if not intact:
            stored = {rel: _store(files[rel], sha, x) for rel, (sha, x) in entries.items()}
            partial = ROOT / "snapshots" / f".partial-{uuid.uuid4().hex}"
            partial.mkdir(parents=True)
            try:
                for rel, store_file in stored.items():
                    (partial / rel).parent.mkdir(parents=True, exist_ok=True)
                    os.link(store_file, partial / rel)
                for directory, _, _ in sorted(os.walk(partial), reverse=True):
                    os.chmod(directory, 0o555)
                if final.exists():
                    _remove_tree(final)  # interrupted before its record, or damaged
                os.rename(partial, final)
            finally:
                _remove_tree(partial)
        for rel, src in files.items():
            if _identity(src.stat()) != before[rel]:
                raise DistributionRefused(f"build folder changed during snapshot: {rel}")
        # Every file the install folder holds now is in the snapshot, and nothing it lost is.
        now = {f"{INSTALL}/{rel}" for rel, _ in _walk(checkout.resolve() / INSTALL) if rel != RECEIPT}
        if now != {rel for rel in files if rel.startswith(f"{INSTALL}/")}:
            raise DistributionRefused("build folder changed during snapshot: install folder inventory differs")
        if verify(digest, record=False) != digest:
            raise DistributionRefused("snapshot differs from its digest")
        atomic_write_text(ROOT / "snapshots" / f"{digest}.json", canonical_json(
            {"schema": SCHEMA, "digest": digest, "source": list(source)}) + "\n")
    flush_digests()
    return digest


def _record(digest: str) -> dict | None:
    try:
        record = json.loads((ROOT / "snapshots" / f"{digest}.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return record if isinstance(record, dict) and record.get("digest") == digest else None


def verify(digest: str, *, record: bool = True) -> str:
    """Recompute a snapshot's digest from its current bytes; refuse any difference."""
    tree = path(digest)
    if record and _record(digest) is None:
        raise DistributionRefused(f"snapshot {digest[:12]} has no complete record")
    if not tree.is_dir():
        raise DistributionRefused(f"snapshot {digest[:12]} is missing")
    entries = {}
    try:
        for rel, p in _walk(tree):
            st = p.lstat()
            if not stat.S_ISREG(st.st_mode):
                raise DistributionRefused(f"snapshot entry is not a regular file: {rel}")
            entries[rel] = (file_sha256(p), _executable(st))
    except OSError as e:
        raise DistributionRefused(f"snapshot {digest[:12]} unreadable: {e}") from e
    actual = digest_of(entries)
    if actual != digest:
        raise DistributionRefused(f"snapshot {digest[:12]} bytes differ from its digest")
    try:
        _launcher_inventory(tree, entries)
    except OSError as e:
        raise DistributionRefused(f"snapshot {digest[:12]} start script unreadable: {e}") from e
    flush_digests()
    return actual


def _source_file(source: list) -> Path:
    return ROOT / "sources" / f"{sha256_text(canonical_json(list(source)))}.json"


def _checkout_file(checkout: Path) -> Path:
    return ROOT / "checkouts" / f"{sha256_text(str(checkout.resolve()))}.json"


def _recorded(source: list) -> str | None:
    try:
        doc = json.loads(_source_file(source).read_text(encoding="utf-8"))
        return doc["digest"] if doc.get("source") == list(source) else None
    except (OSError, ValueError, KeyError, TypeError):
        return None


def lookup(source: list) -> str | None:
    """The verified snapshot recorded for exactly this source identity, if any."""
    digest = _recorded(source)
    try:
        return verify(digest) if digest else None
    except DistributionRefused:
        return None  # preparation reinstalls and repairs it


def publish(checkout: Path, source: list, digest: str) -> None:
    doc = {"schema": SCHEMA, "source": list(source), "digest": digest}
    atomic_write_text(_source_file(source), canonical_json(doc) + "\n")
    # Machine-local retention bookkeeping, never part of a record or key.
    atomic_write_text(_checkout_file(checkout), canonical_json(
        {**doc, "checkout": str(checkout.resolve())}) + "\n")


def current(checkout: Path, source: list) -> str:
    """Scoring: the snapshot built from exactly the checkout's current sources, or refuse."""
    digest = _recorded(source)
    if digest is not None:
        return verify(digest)  # tampered or missing snapshot bytes refuse with their reason
    else:
        commit, dirty = source
        state = f"HEAD {commit[:12]}" + (f" with working-tree changes {dirty[:12]}" if dirty else "")
        raise DistributionRefused(
            f"not ready: stale distribution: no snapshot was installed from the checkout's current "
            f"sources ({state}); run quality prepare outside the scoring window")
    return digest


def lease(digest: str):
    """A shared hold that keeps the snapshot from being pruned while an engine may use it."""
    (ROOT / "snapshots").mkdir(parents=True, exist_ok=True)
    handle = (ROOT / "snapshots" / f"{digest}.lock").open("a+")
    fcntl.flock(handle, fcntl.LOCK_SH)
    if not path(digest).is_dir():
        handle.close()
        raise DistributionRefused(f"snapshot {digest[:12]} is missing")
    return handle


def reference(digest: str, entry_root: Path) -> None:
    """Record that a cache entry was built against ``digest``; retention keeps it."""
    atomic_write_text(ROOT / "refs" / digest / sha256_text(str(entry_root.resolve())),
                      str(entry_root.resolve()) + "\n")


def retained() -> set[str]:
    keep = set()
    for doc in (ROOT / "checkouts").glob("*.json"):
        with contextlib.suppress(OSError, ValueError, KeyError, TypeError):
            pointer = json.loads(doc.read_text(encoding="utf-8"))
            if not Path(pointer["checkout"]).is_dir():
                doc.unlink()  # a removed worktree no longer holds its snapshot
                continue
            keep.add(pointer["digest"])
    for ref in (ROOT / "refs").glob("*/*"):
        with contextlib.suppress(OSError):
            if (Path(ref.read_text(encoding="utf-8").strip()) / "entry.json").is_file():
                keep.add(ref.parent.name)
                continue
            ref.unlink()
    return keep


def prune() -> list[str]:
    """Remove snapshots neither newest for a checkout nor referenced by a cache entry nor in use."""
    removed = []
    with _locked(".lock"):
        keep = retained()
        for record in sorted((ROOT / "snapshots").glob("*.json")):
            digest = record.stem
            if digest in keep:
                continue
            with (ROOT / "snapshots" / f"{digest}.lock").open("a+") as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    continue  # an engine may still run from it
                record.unlink(missing_ok=True)
                _remove_tree(path(digest))
                removed.append(digest)
        for source in (ROOT / "sources").glob("*.json"):
            with contextlib.suppress(OSError, ValueError, KeyError, TypeError):
                if not (ROOT / "snapshots" / f"{json.loads(source.read_text())['digest']}.json").is_file():
                    source.unlink()
        for leftover in (ROOT / "snapshots").glob(".partial-*"):
            _remove_tree(leftover)
        # Store files no snapshot links to anymore.
        for stored in (ROOT / "store").glob("*.[rx]"):
            with contextlib.suppress(OSError):
                if stored.stat().st_nlink == 1:
                    stored.unlink()
    return removed
