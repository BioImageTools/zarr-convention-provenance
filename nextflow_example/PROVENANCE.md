# Recording provenance in OME-Zarr stores

Workflows in this repository can record what they did to each OME-Zarr store in
the store itself: as `provenance:steps` in its attributes (`zarr.json` for Zarr v3,
`.zattrs` for Zarr v2), following the
[Zarr provenance convention v0.1.0](https://github.com/BioImageTools/zarr-convention-provenance).

For every step, this records:

- the software, its version and the entry point that ran;
- every parameter the tool ran with, defaults included;
- what you set in the samplesheet or params;
- inputs and outputs, as `file:///...` URIs;
- start and end time;
- the container and the compute used (host, CPU cores, memory, GPU, SLURM job);
- the run: pipeline, repository, commit, user, run name and command line.

Each store also gets the steps of the stores it was made from, so a derived
image shows its whole history back to the first image.

## How it works

1. Each module writes a small JSON fragment per task (`<id>.<task hash>.prov.json`)
   through `lib/Provenance.groovy`. The fragment is assembled with the `python3`
   already in the module's container (standard library only), so modules need no
   container change.
2. The `WRITE_PROVENANCE` module collects all fragments at the end of the
   workflow and appends them to their stores in a single task, so no two tasks
   ever write the same store metadata at once.

Steps already on a store are skipped, so `-resume` and re-runs add no duplicates.

## Files

| File | Purpose |
| --- | --- |
| `lib/Provenance.groovy` | Helper used by modules to write fragments |
| `modules/local/write_provenance/main.nf` | Process that writes fragments into the stores |
| `modules/local/write_provenance/resources/usr/bin/write_provenance.py` | The writer (must be executable) |

`nextflow.enable.moduleBinaries = true` must be set (e.g. in `conf/modules.config`),
and `-lib <repo>/lib` passed to `nextflow run` if the workflow is not run from the
repository root.

## Which modules need it

Every module that **creates or changes an OME-Zarr store**: conversion,
projections, filters, segmentation, label writing, pyramids, pixel
classification, tracking, and so on.

Modules that only write tables (e.g. CSV measurements) can record a step too:
use the store they read as `target` and list the table under `outputs`.

## Updating a module: four changes

### 1. Add an output

```groovy
output:
tuple val(meta), path(output_zarr_link), emit: zarr                        // existing
tuple val(meta), path("*.prov.json"),    emit: provenance, optional: true  // new
```

`optional: true` keeps stubs, and runs where a step is skipped, working.

### 2. Build the provenance snippet in `script:`, before the `"""`

```groovy
def provenance = Provenance.write(task, meta,
    name       : 'Maximum intensity projection',          // required
    category   : 'Projection',                            // recommended
    software   : Provenance.ownSoftware('my_script.py', ['zarr', 'numpy']),   // required
    params_file: 'params_used.json',                      // if the script writes one (step 4)
    inputs     : [[file: input_zarr, name: 'image']],
    outputs    : [[file: output_zarr, name: 'projected image']],
    target     : output_zarr,                             // required: store the step is recorded on
    settings   : meta.subMap(['channels', 'z_min', 'z_max']))   // what you set in the CSV/params
```

### 3. Add two lines to the script

```groovy
"""
${Provenance.start()}

my_script.py ...

${provenance}
"""
```

`${Provenance.start()}` must be the first line (it records the start time) and
`${provenance}` must come after the tool has run.

### 4. Record the parameters in your own Python scripts

Add this helper to the script:

```python
import json


def _json_default(value):
    """JSON fallback for numpy scalars/arrays and other objects."""
    if hasattr(value, "item"):
        try:
            return value.item()
        except (TypeError, ValueError):
            pass
    if hasattr(value, "tolist"):
        return value.tolist()
    return str(value)


def _record_params(update, path="params_used.json"):
    """Merge `update` into params_used.json in the task directory (read by
    lib/Provenance.groovy to record the parameters this run really used)."""
    try:
        with open(path) as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        data = {}
    data.update(update)
    with open(path, "w") as fh:
        json.dump(data, fh, indent=2, default=_json_default)
```

and call it right after parsing the arguments:

```python
args = parser.parse_args()
_record_params({"cli": vars(args)})      # every option, defaults included
```

If the script builds its own arguments for a library call, record those too,
just before the call:

```python
_record_params({"model_eval": eval_kwargs})
result = model.eval(image, **eval_kwargs)
```

## `Provenance.write` options

| Option | Required | Meaning |
| --- | --- | --- |
| `name` | yes | What the step did, e.g. `Image conversion` |
| `software` | yes | See [Software and versions](#software-and-versions) |
| `target` | yes | The store the step is recorded on: staged path or absolute path |
| `category` | | Kind of processing; use one fixed list, e.g. `Conversion`, `Projection`, `Filtering`, `Segmentation`, `Measurement`, `Pixel classification`, `Tracking` |
| `description` | | Free text |
| `params_file` | | JSON file the tool wrote with the parameters it ran with |
| `parameters` | | Map of parameters known in Nextflow, e.g. a CLI option list for an external tool |
| `inputs`, `outputs` | | Lists of `[file: ..., name: ...]` or `[uri: ..., name: ...]`; add `checksum: true` for small files such as models (sha256) |
| `settings` | | What you set in the samplesheet/params, recorded as `nextflow:settings` |
| `collapse` | | `true` for per-tile processes: all tasks with the same target become one step |
| `results_file` | | JSON file the tool wrote about what it produced (e.g. object counts), recorded as `nextflow:results` |

## Software and versions

Versions are never written by hand.

- **Scripts in this repository:**
  `Provenance.ownSoftware('my_script.py', ['python', 'packages'])`. The version is
  the repository commit (with `-dirty` if there were uncommitted changes); the
  listed packages' installed versions are recorded alongside.
- **External Python tools:**
  `[name: 'tool', version_from_package: 'tool', entry_point: 'tool convert', uri: 'https://...']`.
  The version is read from the installed package inside the container.
- **External non-Python tools:** leave `version_from_package` out. The version is
  the container's tag or digest (`1.4.1` from `registry/tool:1.4.1`). Images
  tagged `latest` or untagged give `unknown`, so pin your images.

`software."nextflow:version_source"` says where the version came from, and the
full image is always recorded as `nextflow:container`. Passing a fixed
`version:` stops the run with an error.

## Rules

- **`target` must be an absolute path** to the final store. A relative path points
  into the task's work directory.
- **Per-tile processes:** add `collapse: true` and set `target` to the merged
  store (e.g. pass it in `meta.provenance_target`). Otherwise each tile is
  recorded on its own temporary store.
- **Steps that can skip work** (e.g. output already exists): set `PROV_SKIP=1` in
  that branch of the shell script, so no step is recorded for work not done.
- **Nothing that changes between runs** (dates, run name, random values) in
  `Provenance.write(...)`: it would stop `-resume` from reusing the task.
  `meta` values are fine. Run details are added by `WRITE_PROVENANCE`.
- **`file:` paths** can be staged inputs, work-directory outputs or absolute paths.
  Staged inputs are recorded at their original location (one symlink level, so
  mount paths are kept as you know them). Sentinel files whose name starts with
  `NO_` are dropped.
- **The container needs `python3`.** Without it the task prints a warning and
  carries on; provenance never breaks a step.

## Adding the writer to a workflow

```groovy
include { WRITE_PROVENANCE } from '../../modules/local/write_provenance/main'

ch_provenance = Channel.empty()
    .mix(STEP_A.out.provenance, STEP_B.out.provenance, MY_SUBWORKFLOW.out.provenance)
    .map { _meta, fragment -> fragment }
    .collect()

// Second input: wait until nothing writes to the stores any more,
// e.g. the collected outputs of the last step.
WRITE_PROVENANCE(ch_provenance, LAST_STEP.out.csv.collect().ifEmpty([]))
```

A subworkflow passes its modules' fragments on with an extra emit:

```groovy
emit:
provenance = STEP_C.out.provenance.mix(STEP_D.out.provenance)
```

### Writer options (`ext.args` of `WRITE_PROVENANCE`)

| Option | Effect |
| --- | --- |
| `--no-inherit` | Do not copy the steps of input stores into stores made from them |
| `--omit-user` | Do not record who ran the workflow (e.g. for data shared outside the institute) |
| `--strict` | Fail when a target has no Zarr metadata |
| `--dry-run` | Report what would be added without writing |

The writer writes directly into the stores, outside its work directory, so its
container must see the same storage as your other modules (cluster mounts).

## Checking a run

- `provenance_report.json` in the writer's work directory lists every store as
  `updated` or `up to date`, with the number of steps added and inherited.
- Validate a store against the convention's schema (needs the `jsonschema`
  package; use `zarr.json` for a Zarr v3 store, `.zattrs` for Zarr v2):

  ```bash
  curl -sO https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v0.1.0/schema.json
  python3 -c '
  import json, sys, jsonschema
  schema = json.load(open("schema.json"))
  jsonschema.validate(json.load(open(sys.argv[1])), schema)
  print("ok")
  ' path/to/image.ome.zarr/zarr.json
  ```

  It prints `ok`, or the first field that does not match the convention.