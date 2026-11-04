# Provenance Convention Metadata

- **UUID**: 0d4ef942-0f74-4187-ae72-016ddf083fe1
- **Name**: provenance:
- **Schema URL**: "https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v0.2.0/schema.json"
- **Spec URL**: "https://github.com/BioImageTools/zarr-convention-provenance/blob/v0.2.0/README.md"
- **Scope**: Array, Group
- **Extension Maturity Classification**: Proposal
- **Owner**: @BioImageTools/zarr-convention-mantainers

## Description

This convention records **the processing steps that produced or modified a Zarr node**: which software ran,
with which parameters, on which inputs, writing which outputs, and when. Properties use the `provenance:`
prefix at the root `attributes` level, following the
[Zarr Conventions Specification](https://github.com/zarr-conventions/zarr-conventions-spec).

Version 0.1 contained only what every surveyed producer already records (see
[Survey](#survey-of-existing-practice)). Version 0.2 adds optional, nice-to-have [ontology terms](#ontology-term-object) for the
kind of operation a step performed and the type and format of the data it read and wrote. The convention says
nothing about the image itself: axes, transforms, pyramids and labels belong to
[OME-Zarr](https://ngff.openmicroscopy.org/), and licensing to the [license convention](https://github.com/clbarnes/zarr-convention-license). Every object here allows extra keys,
so tools and later versions can add more without breaking validation.

It merges two drafts from the November 2026 BioImageTools hackathon: this repository and
[BioinfoTongLI/bioimage-analysis-spec](https://github.com/BioinfoTongLI/bioimage-analysis-spec) v0.1.0, whose
step list, data references and survey it adopts.

- Examples:
  - [Minimal: one step, software only](examples/minimal_example.json)
  - [Format conversion, TensorSwitch (Janelia)](examples/tensorswitch_conversion_example.json)
  - [Format conversion, BioImage Archive](examples/bia_conversion_example.json)
  - [Sparse staging, MIA harness (Janelia)](examples/mia_stage_example.json)
  - [Segmentation, Fractal task run from Nextflow](examples/fractal_label_example.json)
  - [The same as a Zarr v2 `.zattrs`](examples/zarr_v2_zattrs_example.json)

## Motivation

- **Provenance travels with the data.** Images are copied, converted and re-hosted; a side file or database
  row is lost on the way, a node's own attributes are not.
- **One place to look.** Today each tool writes its own key (`_software`, `tensorswitch`, `mia`,
  `additional_metadata`, `processing.json`). A reader should not need to know the tool to learn what happened.
- **Multi-step history.** A label image or table is usually several steps deep (conversion, preprocessing,
  segmentation, measurement). An ordered list of steps keeps that history.
- **Reproducibility.** "Which software, version and parameters made this?" is the question behind a paper, a
  re-run, or a check that two datasets were processed the same way.

## Convention Registration

The convention must be registered in `zarr_conventions`:

```json
{
  "zarr_conventions": [
    {
      "uuid": "0d4ef942-0f74-4187-ae72-016ddf083fe1",
      "schema_url": "https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v0.2.0/schema.json",
      "spec_url": "https://github.com/BioImageTools/zarr-convention-provenance/blob/v0.2.0/README.md",
      "name": "provenance:",
      "description": "The processing steps that produced or modified this node"
    }
  ]
}
```

## Applicable To

- [x] Group
- [x] Array

## Properties

All properties are placed at the root `attributes` level using the key-prefixed pattern.

**Convention metadata name**: `provenance:`

| Field Name       | Type                           | Description                                                                                 |
| ---------------- | ------------------------------ | ------------------------------------------------------------------------------------------- |
| provenance:steps | \[[Step Object](#step-object)] | **REQUIRED**. Steps that created or modified this node, in execution order. At least one. |

**Example**:

```json
{
  "attributes": {
    "zarr_conventions": [{ "name": "provenance:", "uuid": "0d4ef942-0f74-4187-ae72-016ddf083fe1" }],
    "provenance:steps": [
      {
        "name": "Threshold Segmentation",
        "category": "Segmentation",
        "software": { "name": "fractal-tasks-core", "version": "2.1.0" },
        "operations": [
          { "iri": "http://purl.obolibrary.org/obo/OBI_0003918", "label": "image segmentation", "ontology": "obi" }
        ],
        "parameters": { "segmentation_method": { "method": "Otsu" } },
        "inputs": [
          {
            "path": "B/03/0",
            "name": "image",
            "type": { "iri": "http://edamontology.org/data_2968", "label": "Image", "ontology": "edam" }
          }
        ],
        "outputs": [
          {
            "path": "B/03/0/labels/nuclei",
            "name": "label image",
            "type": { "iri": "http://purl.obolibrary.org/obo/OBI_0003360", "label": "image segmentation map", "ontology": "obi" }
          }
        ],
        "started": "2026-11-04T11:55:00+01:00",
        "ended": "2026-11-04T11:55:02+01:00"
      }
    ]
  }
}
```

### provenance:steps

- **Placement.** Written on the node a step created or modified. A tool that modifies a node SHOULD append its
  step to the existing list rather than replace it.
- **Order.** Steps are listed in the order they ran; the last is the most recent.
- **Upstream steps.** A node MAY repeat the steps that produced its inputs, or a reader can follow `inputs` to
  the input node and read its own `provenance:steps`.
- **Zarr v2.** The attributes object is the content of `.zattrs`; `provenance:steps` sits beside `multiscales`
  or `image-label`. The schema validates either a `zarr.json` document or a bare attributes object.

### Step Object

One execution of one step: a conversion, a filter, a segmentation, a measurement.

| Field Name  | Type                                               | Description                                                     |
| ----------- | -------------------------------------------------- | --------------------------------------------------------------- |
| software    | [Software Object](#software-object)                | **REQUIRED**. The software that performed the step.             |
| name        | string                                             | What the step did, e.g. `Image conversion`.                      |
| category    | string                                             | The kind of processing, e.g. `Segmentation`, `Conversion`.       |
| description | string                                             | Free text about the step.                                       |
| operations  | \[[Ontology Term Object](#ontology-term-object)]   | What kind of operation the step performed, e.g. segmentation.   |
| parameters  | object                                             | Parameters the software ran with. Free-form.                    |
| inputs      | \[[Data Reference Object](#data-reference-object)] | Data the step read.                                             |
| outputs     | \[[Data Reference Object](#data-reference-object)] | Data the step wrote, if not only this node.                     |
| started     | string                                             | When the step started: ISO 8601 date-time with time zone.       |
| ended       | string                                             | When the step ended: ISO 8601 date-time with time zone.         |

`category` groups steps across tools, so that a reader can find, say, every segmentation in a history without
knowing each tool's step names. It is free text; to name the operation with a term from an ontology, use
`operations` as well.

`parameters` SHOULD be recorded as the software received them, with defaults applied where the software can
tell, so that the step can be repeated. A command-line tool MAY record its arguments as a list under one key
(see the [BioImage Archive example](examples/bia_conversion_example.json)).

### Software Object

| Field Name  | Type   | Description                                                                          |
| ----------- | ------ | ------------------------------------------------------------------------------------ |
| name        | string | **REQUIRED**. Name of the software, package or tool.                                 |
| version     | string | **REQUIRED**. Released version, e.g. `2.1.0`. Use the commit if there is no release. |
| entry_point | string | The part of the software that ran: a module, command or function.                    |
| uri         | string | Where the software can be found: repository or package page.                         |

One package often holds many tools: `entry_point` says which of them ran, e.g. the Python module
`mypackage.tasks.segment_nuclei`, the command `mytool convert`, or a function name.

### Data Reference Object

At least one of `path`, `uri` or `id` is **REQUIRED**.

| Field Name | Type   | Description                                                                  |
| ---------- | ------ | ---------------------------------------------------------------------------- |
| path       | string | Path of a node relative to the root of this Zarr hierarchy, e.g. `B/03/0`.   |
| uri        | string | URI of data outside this hierarchy, or where a node of it was when read.     |
| id         | string | Identifier of the data in a catalogue or archive, e.g. a UUID or accession.  |
| name       | string | Human-readable name or role of the data, e.g. `image`, `label image`.        |
| type       | [Ontology Term Object](#ontology-term-object) | What kind of data this is, e.g. an image or a segmentation map. |
| format     | [Ontology Term Object](#ontology-term-object) | The file or storage format, e.g. Zarr or OME-TIFF.             |
| checksum   | string | `<algorithm>:<lowercase hex digest>`, e.g. `sha256:9f86d0…`.                  |

A node of this hierarchy is identified by its `path`, which stays right when the hierarchy is copied or moved.
It MAY also carry a `uri`: the absolute location it had when the step ran, e.g.
`s3://bucket/plate.zarr/B/03/0`, for a reader to trace where the data was. That `uri` is a record, not a
reference: once the hierarchy has moved it can point to an old location, so a reader SHOULD resolve the node
from its `path`.

### Ontology Term Object

A term from a published ontology, so that readers can find, for example, every segmentation step or every label
image without knowing each tool's own step names. Terms can be looked up in the
[EMBL-EBI Ontology Lookup Service (OLS)](https://www.ebi.ac.uk/ols4/).

Ontology terms are nice-to-have: `operations`, `type` and `format` are all optional, and so is every field of a
term. A writer that only knows a label can record just the label and add the IRI later.

| Field Name | Type   | Description                                                                                   |
| ---------- | ------ | --------------------------------------------------------------------------------------------- |
| iri        | string | Full IRI of the term, e.g. `http://edamontology.org/operation_3443`.                           |
| label      | string | The term's label in its ontology, e.g. `Image analysis`.                                       |
| ontology   | string | The ontology's id in OLS, e.g. `edam`, `obi`, `ncit`.                                          |

When an identifier is given, it should be the full IRI rather than a compact identifier (CURIE), because
prefixes are not used consistently: OLS itself shows the same EDAM term as `EDAM:operation_3434` and
`EDAM_operation:3434`. If `iri` is present it must be a valid IRI.

Any ontology may be used. These are suggested:

| Where                          | Ontology and branch                                                                  | Examples                                                                                                                                                                                       |
| ------------------------------ | ------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Step `operations`              | [EDAM](https://www.ebi.ac.uk/ols4/ontologies/edam) operation, then [OBI](https://www.ebi.ac.uk/ols4/ontologies/obi) or [NCIT](https://www.ebi.ac.uk/ols4/ontologies/ncit) | [Image analysis](http://edamontology.org/operation_3443), [Data formatting](http://edamontology.org/operation_0335), [Feature extraction](http://edamontology.org/operation_3937), [image segmentation](http://purl.obolibrary.org/obo/OBI_0003918) (OBI), [Image Registration](http://purl.obolibrary.org/obo/NCIT_C80145) (NCIT) |
| Data reference `type`          | EDAM data, then OBI                                                                  | [Image](http://edamontology.org/data_2968), [image segmentation map](http://purl.obolibrary.org/obo/OBI_0003360) (OBI)                                                                          |
| Data reference `format`        | EDAM format                                                                          | [Zarr](http://edamontology.org/format_3915), [OME-TIFF](http://edamontology.org/format_3727), [TIFF](http://edamontology.org/format_3591)                                                       |

A step MAY list several operations, for example a general EDAM term and a more specific OBI term.

### Extending

Every object allows additional properties. Tools MAY add their own keys, preferably prefixed
(e.g. `fractal:task_name` inside a step), without breaking validation.

## Versioning

The version is part of the schema and spec URLs and follows semantic versioning: patch for wording, minor for
new optional fields, major for incompatible changes. While the major version is `0`, minor versions may also
be incompatible.

- **0.2.0**: optional step `category` and software `entry_point`; a data reference's `uri` may record where a
  node of this hierarchy was when read; optional ontology terms: step `operations`, data reference `type` and
  `format`.
- **0.1.0**: first version.

## Survey of existing practice

Version 0.1 keeps the concepts every source below records, or that all but one record and the last can trivially
add. Software name and version are required because every source has them.

| Concept                   | BioImage Archive      | TensorSwitch           | MIA harness                        | Allen `processing.json`            | Fractal / Nextflow | In v0.1                   |
| ------------------------- | --------------------- | ---------------------- | ---------------------------------- | ---------------------------------- | ------------------ | ------------------------- |
| Software name and version | `conversion_function` | `_software`            | `harness_commit`                   | `code.name`, `code.version`        | yes                | `software` (**required**) |
| Parameters                | `conversion_config`   | `tensorswitch`         | `plan`                             | `code.parameters`                  | yes                | `parameters`              |
| Inputs                    | input UUID            | `lmvd.source_path`     | `derived_from`                     | `code.input_data`                  | yes                | `inputs`                  |
| Software URL              |                       | `_software.url`        |                                    | `code.url`                         | yes                | `software.uri`            |
| Step name                 | `provenance`          |                        |                                    | `process_type`, `name`             | yes                | `name`                    |
| Start and end time        |                       |                        | `verification.time`                | `start_date_time`, `end_date_time` | yes                | `started`, `ended`        |
| Outputs                   | (the node itself)     | (the node itself)      | `artifact`                         | `output_path`                      | yes                | `outputs`                 |

## Deferred

Recorded by some sources or asked for by the working group, but not yet agreed. Each is a candidate for a minor
version, and can be written today as an extra key.

- **Commit** separate from version (MIA, Allen, Fractal).
- **Container or environment**: image, engine, digest; MIA's lockfile digest (MIA, Allen, Fractal).
- **Workflow** the step ran in: name, version, uri (MIA, Allen, Fractal).
- **Execution id**: UUID or workflow-engine task id (BIA, Fractal).
- **Compute**: CPU, memory, GPU model and count (Allen, Fractal; working group).
- **Models**: trained models loaded at run time (Allen, Fractal; working group).
- **Manual steps**, e.g. proofreading, with the
  [BioImage Archive MIFA model](https://www.ebi.ac.uk/bioimage-archive/mifa-model-reference/) as vocabulary
  (working group).
- **Scripts, logs and fuller records**, e.g. a Workflow Run RO-Crate (working group).
- **Node identity and verification**: a recipe hash over inputs and parameters, and the result of checks run on
  the written node (MIA).

Out of scope: license (license convention), people and funding (dataset-level metadata such as
[Allen biodata-schema](https://github.com/AllenInstitute/biodata-schema)), image geometry (OME-Zarr).

## Known Implementations

### Libraries and Tools

- **[fractal-multistep-nextflow](https://github.com/BioImageTools/fractal-multistep-nextflow)**: Nextflow
  pipeline chaining fractal-tasks-core tasks on OME-Zarr. Status: planned. Maintainer: @BioinfoTongLI.

### Datasets Using This Convention

_None yet._

_If you implement or use this convention, please add your implementation to this list by submitting a pull request._

## Acknowledgements

Follows the [zarr-conventions template](https://github.com/zarr-conventions/template), itself based on the
[STAC extensions template](https://github.com/stac-extensions/template). The step model and survey come from
[BioinfoTongLI/bioimage-analysis-spec](https://github.com/BioinfoTongLI/bioimage-analysis-spec); the survey
draws on provenance written by the BioImage Archive, Janelia (TensorSwitch, MIA), the Allen Institute and the
Fractal / Nextflow examples. Related: the [STAC processing extension](https://github.com/stac-extensions/processing).
