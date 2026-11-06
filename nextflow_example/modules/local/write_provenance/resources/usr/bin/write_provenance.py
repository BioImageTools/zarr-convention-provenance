#!/usr/bin/env python3
"""Append provenance steps to OME-Zarr stores.

Reads the ``*.prov.json`` fragments written by modules through
``lib/Provenance.groovy`` and appends one step per task (or one per process for
fragments marked ``collapse``) to ``provenance:steps`` of each target store,
following the Zarr provenance convention v0.1.0:
https://github.com/BioImageTools/zarr-convention-provenance

Only the Python standard library is used: zarr.json (Zarr v3) and .zattrs
(Zarr v2) are plain JSON files, so no zarr package or custom container is needed.

Behaviour:
- Steps are appended, never replaced, in order of their start time.
- Each store also gets the steps of the stores it was made from (its file://
  inputs), so it carries its whole history from the first image. Turn off with
  ``--no-inherit``.
- A step whose Nextflow task id(s) are already recorded on the store is skipped,
  so ``-resume`` and re-runs do not add duplicates.
- The ``zarr_conventions`` entry is added once, next to any existing ones.
- A Zarr v2 ``.zmetadata`` (consolidated metadata) in the store is updated too.
- Files are replaced atomically and keep their permissions.
"""

import argparse
import copy
import json
import os
import pathlib
import shutil
import sys
import tempfile
from collections import OrderedDict
from urllib.parse import unquote, urlparse

CONVENTION = {
    "uuid": "0d4ef942-0f74-4187-ae72-016ddf083fe1",
    "schema_url": "https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v0.1.0/schema.json",
    "spec_url": "https://github.com/BioImageTools/zarr-convention-provenance/blob/v0.1.0/README.md",
    "name": "provenance:",
    "description": "The processing steps that produced or modified this node",
}


COMMIT_PLACEHOLDER = "@pipeline-commit@"
PIPELINE_PLACEHOLDER = "@pipeline@"
REPO_PLACEHOLDER = "@pipeline-repository@"


def add_run_info(step, run):
    """Fill in what modules leave out so that -resume keeps working: name, commit and
    URL of the pipeline for its own scripts, and the details of this Nextflow run."""
    software = step.get("software") or {}
    if software.get("name") == PIPELINE_PLACEHOLDER:
        software["name"] = run.get("pipeline") or "unknown"
    if software.get("version") == COMMIT_PLACEHOLDER:
        software["version"] = run.get("commit") or "unknown"
    if software.get("uri") == REPO_PLACEHOLDER:
        sha = (run.get("commit") or "").replace("-dirty", "")
        repo = (run.get("repository") or "").rstrip("/")
        if repo and len(sha) == 40 and all(c in "0123456789abcdef" for c in sha):
            software["uri"] = repo + "/tree/" + sha
        elif repo:
            software["uri"] = repo
        else:
            software.pop("uri")
    run_info = {k: v for k, v in run.items() if v}
    if run_info:
        step.setdefault("nextflow:run", run_info)
    return step


def uri_to_path(uri):
    parsed = urlparse(uri)
    if parsed.scheme != "file":
        return None
    return unquote(parsed.path)


def task_ids(step):
    ids = set(step.get("nextflow:task_ids") or [])
    if step.get("nextflow:task_id"):
        ids.add(step["nextflow:task_id"])
    return ids


def _key(value):
    return json.dumps(value, sort_keys=True)


def split_common(dicts):
    """Split dicts into what all share and what each has on its own, recursively.

    {"cli": {"diameter": 30, "x_min": 0}} and {"cli": {"diameter": 30, "x_min": 100}}
    give common {"cli": {"diameter": 30}} and remainders {"cli": {"x_min": 0}}, ...
    """
    dicts = [d or {} for d in dicts]
    keys = []
    for d in dicts:
        keys.extend(k for k in d if k not in keys)
    common = {}
    varying = [{} for _ in dicts]
    for k in keys:
        present = all(k in d for d in dicts)
        values = [d.get(k) for d in dicts]
        if present and len({_key(v) for v in values}) == 1:
            common[k] = values[0]
        elif present and all(isinstance(v, dict) for v in values):
            sub_common, sub_varying = split_common(values)
            if sub_common:
                common[k] = sub_common
            for rest, sub in zip(varying, sub_varying):
                if sub:
                    rest[k] = sub
        else:
            for rest, d in zip(varying, dicts):
                if k in d:
                    rest[k] = d[k]
    return common, varying


def unique(items):
    seen, out = set(), []
    for item in items:
        k = _key(item)
        if k not in seen:
            seen.add(k)
            out.append(item)
    return out


def collapse(steps):
    """Merge the steps of one process (e.g. one per tile) into a single step."""
    if len(steps) == 1:
        return steps[0]
    steps = sorted(steps, key=lambda s: s.get("nextflow:task_id", ""))
    merged = copy.deepcopy(steps[0])
    ids = sorted(set().union(*(task_ids(s) for s in steps)))
    merged.pop("nextflow:task_id", None)
    merged["nextflow:task_ids"] = ids
    merged["nextflow:task_count"] = len(steps)

    starts = [s["started"] for s in steps if s.get("started")]
    ends = [s["ended"] for s in steps if s.get("ended")]
    if starts:
        merged["started"] = min(starts)
    if ends:
        merged["ended"] = max(ends)

    per_task = [{"nextflow:task_id": s.get("nextflow:task_id")} for s in steps]
    for field, per_task_key in (
        ("parameters", "parameters"),
        ("nextflow:settings", "nextflow:settings"),
    ):
        common, varying = split_common([s.get(field) for s in steps])
        if common:
            merged[field] = common
        else:
            merged.pop(field, None)
        for entry, rest in zip(per_task, varying):
            if rest:
                entry[per_task_key] = rest
    if any(len(e) > 1 for e in per_task):
        merged["nextflow:per_task"] = per_task

    for field in ("inputs", "outputs"):
        refs = unique(r for s in steps for r in s.get(field) or [])
        if refs:
            merged[field] = refs

    computes = unique(s.get("nextflow:compute") for s in steps if s.get("nextflow:compute"))
    if computes:
        merged["nextflow:compute"] = computes if len(computes) > 1 else computes[0]
    return merged


def attrs_file(store):
    """Return (kind, path) of the file holding the node's attributes."""
    v3 = os.path.join(store, "zarr.json")
    if os.path.isfile(v3):
        return "v3", v3
    if any(os.path.isfile(os.path.join(store, f)) for f in (".zattrs", ".zgroup", ".zarray")):
        return "v2", os.path.join(store, ".zattrs")
    return None, None


def write_json_atomic(path, doc):
    directory = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(dir=directory, prefix=".prov_tmp_")
    try:
        with os.fdopen(fd, "w") as fh:
            json.dump(doc, fh, indent=2)
            fh.write("\n")
        if os.path.exists(path):
            shutil.copymode(path, tmp)
        else:
            os.chmod(tmp, 0o664)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def step_identity(step):
    """Task ids when the step came from Nextflow, otherwise its full content."""
    ids = task_ids(step)
    return frozenset(ids) if ids else _key(step)


def is_recorded(step, known_ids, known_keys):
    ids = task_ids(step)
    if ids:
        return ids <= known_ids
    return _key(step) in known_keys


def read_steps(store):
    """provenance:steps of a store, or [] if it has none or is not a Zarr node."""
    kind, path = attrs_file(store)
    if kind is None or not os.path.isfile(path):
        return []
    with open(path) as fh:
        doc = json.load(fh)
    attrs = doc.get("attributes", {}) if kind == "v3" else doc
    return attrs.get("provenance:steps") or []


def rebase(step, store):
    """Copy of an upstream store's step, valid inside another store: references by
    `path` were relative to the upstream store, so they become absolute URIs."""
    step = copy.deepcopy(step)
    base = pathlib.PurePosixPath(store)
    for field in ("inputs", "outputs"):
        for ref in step.get(field) or []:
            if "path" in ref and "uri" not in ref:
                ref["uri"] = (base / ref.pop("path")).as_uri()
            elif "path" in ref:
                ref.pop("path")
    return step


def upstream_stores(steps, own_store):
    """Local Zarr stores the steps read from (their file:// inputs), in order."""
    stores = []
    for step in steps:
        for ref in step.get("inputs") or []:
            p = uri_to_path(ref.get("uri", ""))
            if p and os.path.normpath(p) != os.path.normpath(own_store) and attrs_file(p)[0] and p not in stores:
                stores.append(p)
    return stores


def append_steps(store, new_steps, inherited=(), dry_run=False):
    """Append inherited (upstream) and new steps that are not on the store yet."""
    kind, path = attrs_file(store)
    if kind is None:
        return {"store": store, "status": "missing", "added": 0, "inherited": 0}

    if os.path.isfile(path):
        with open(path) as fh:
            doc = json.load(fh, object_pairs_hook=OrderedDict)
    else:
        doc = OrderedDict()
    attrs = doc.setdefault("attributes", OrderedDict()) if kind == "v3" else doc

    existing = attrs.get("provenance:steps") or []
    known_ids = set().union(*(task_ids(s) for s in existing)) if existing else set()
    known_keys = {_key(s) for s in existing}

    to_add, n_inherited = [], 0
    for origin, steps in (("inherited", inherited), ("new", new_steps)):
        for step in steps:
            if is_recorded(step, known_ids, known_keys):
                continue
            to_add.append(step)
            known_ids |= task_ids(step)
            known_keys.add(_key(step))
            n_inherited += origin == "inherited"

    if not to_add:
        return {"store": store, "status": "up to date", "added": 0, "inherited": 0}

    # Steps are listed in the order they ran. Existing steps stay as they are; what is
    # added is ordered by start time (upstream steps always started earlier).
    to_add.sort(key=lambda st: (st.get("started") or "", st.get("ended") or ""))

    conventions = attrs.setdefault("zarr_conventions", [])
    if not any(c.get("uuid") == CONVENTION["uuid"] for c in conventions if isinstance(c, dict)):
        conventions.append(dict(CONVENTION))
    attrs["provenance:steps"] = list(existing) + to_add

    if not dry_run:
        write_json_atomic(path, doc)
        zmeta = os.path.join(store, ".zmetadata")
        if kind == "v2" and os.path.isfile(zmeta):
            with open(zmeta) as fh:
                consolidated = json.load(fh, object_pairs_hook=OrderedDict)
            consolidated.setdefault("metadata", OrderedDict())[".zattrs"] = attrs
            write_json_atomic(zmeta, consolidated)

    return {"store": store, "status": "updated", "added": len(to_add) - n_inherited,
            "inherited": n_inherited, "format": kind}


def order_targets(steps_by_store):
    """Stores whose steps read other stores of this run come after those stores, so
    they inherit an already complete history."""
    ordered, visiting = [], set()

    def visit(store):
        if store in ordered or store in visiting:
            return
        visiting.add(store)
        for up in upstream_stores(steps_by_store[store], store):
            if up in steps_by_store:
                visit(up)
        visiting.discard(store)
        ordered.append(store)

    for store in steps_by_store:
        visit(store)
    return ordered


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fragments", nargs="+", help="*.prov.json fragments")
    parser.add_argument("--report", default=None, help="Write a JSON report of what was done")
    parser.add_argument("--dry-run", action="store_true", help="Do not write anything")
    parser.add_argument("--strict", action="store_true", help="Fail when a target store is missing")
    parser.add_argument("--no-inherit", action="store_true",
                        help="Do not copy the steps of input stores into the stores made from them")
    parser.add_argument("--omit-user", action="store_true", help="Do not record who ran the workflow")
    parser.add_argument("--commit", default=None, help="Commit of the pipeline repository the workflow runs from")
    parser.add_argument("--repository", default=None, help="Pipeline repository URL")
    parser.add_argument("--pipeline", default=None, help="Pipeline name")
    parser.add_argument("--user", default=None, help="User who ran the workflow")
    parser.add_argument("--run-name", default=None)
    parser.add_argument("--session-id", default=None)
    parser.add_argument("--nextflow-version", default=None)
    parser.add_argument("--workflow", default=None)
    parser.add_argument("--command-line", default=None)
    args = parser.parse_args(argv)
    # Recorded on the steps this run adds. A step whose task was cached is normally on
    # the store already (and skipped); if a previous run stopped before writing it, it
    # is recorded now, with this run's details.
    run = {
        "pipeline": args.pipeline,
        "workflow": args.workflow,
        "repository": args.repository,
        "commit": args.commit,
        "user": None if args.omit_user else args.user,
        "name": args.run_name,
        "session_id": args.session_id,
        "nextflow_version": args.nextflow_version,
        "command_line": args.command_line,
    }

    by_target = OrderedDict()
    for f in args.fragments:
        with open(f) as fh:
            frag = json.load(fh)
        add_run_info(frag["step"], run)
        by_target.setdefault(frag["target"], []).append(frag)

    report, failed = [], False
    steps_by_store = OrderedDict()
    for target, frags in by_target.items():
        store = uri_to_path(target)
        if store is None:
            report.append({"store": target, "status": "not a file:// target, skipped", "added": 0})
            continue
        singles = [f["step"] for f in frags if not f.get("collapse")]
        groups = OrderedDict()
        for f in frags:
            if f.get("collapse"):
                groups.setdefault(f["step"].get("nextflow:process"), []).append(f["step"])
        steps = singles + [collapse(group) for group in groups.values()]
        steps.sort(key=lambda s: (s.get("started") or "", s.get("ended") or ""))
        steps_by_store[os.path.normpath(store)] = steps

    for store in order_targets(steps_by_store):
        steps = steps_by_store[store]
        inherited = []
        if not args.no_inherit:
            # Everything that led to the input stores, so each store carries its whole
            # history from the first image (the upstream stores are already updated).
            for up in upstream_stores(steps, store):
                inherited.extend(rebase(s, up) for s in read_steps(up))
        result = append_steps(store, steps, inherited, dry_run=args.dry_run)
        if result["status"] == "missing":
            failed = failed or args.strict
            print(f"WARNING: no Zarr metadata found in {store}, provenance not written", file=sys.stderr)
        else:
            print(f"{store}: {result['status']} (+{result['added']} steps, +{result['inherited']} inherited)")
        report.append(result)

    if args.report:
        with open(args.report, "w") as fh:
            json.dump(report, fh, indent=2)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())