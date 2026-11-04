# Provenance Convention Metadata

- **UUID**: 0d4ef942-0f74-4187-ae72-016ddf083fe1
- **Name**: Provenance
- **Schema URL**: "https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v1/schema.json"
- **Spec URL**: "https://github.com/BioImageTools/zarr-convention-provenance/blob/v1/README.md"
- **Scope**: Array, Group
- **Extension Maturity Classification**: Proposal
- **Owner**: @BioImageTools/zarr-convention-mantainers

## Description

This convention records **workflow provenance** for a Zarr node: which software produced it, from which
inputs, under which identifiers, and how it was checked. All properties use the `provenance:` namespace prefix
(or a nested `provenance` object) and are placed at the root `attributes` level following the
[Zarr Conventions Specification](https://github.com/zarr-conventions/zarr-conventions-spec).

It deliberately says nothing about the image itself. Axes, transforms, voxel sizes, pyramids and labels belong
to [OME-Zarr](https://ngff.openmicroscopy.org/); this convention sits beside that metadata, on the same node,
and describes the *process* that wrote it rather than the *data* that was written.

The key set is intentionally small while the convention is a proposal. The fields below are the ones every
surveyed producer already writes in some form; candidates under discussion are listed in
[Open questions](#open-questions).

- Examples:
  - [Convention metadata only](examples/minimal_example.json)
  - [Key-prefixed pattern (recommended)](examples/key_prefixed_example.json)
  - [Nested pattern](examples/nested_example.json)

## Motivation

- **Reproducibility**: a node names the software and inputs that made it, so the same bytes can be produced
  again or the difference explained.
- **Auditability**: a reader can tell whether a store is still what its producer wrote, and whether the
  upstream files it was derived from have changed.
- **Tool independence**: producers today each invent their own key (`_software`, `mia`, `tensorswitch`,
  `lmvd`, `metadata` inside `multiscales`). One namespace lets galleries, catalogues and training pipelines
  read provenance without knowing which tool wrote the store.
- **Composability**: the key-prefixed pattern lets other conventions and domain metadata extend the same
  node without collisions.

## Convention Registration

The convention must be registered in `zarr_conventions`:

```json
{
  "zarr_conventions": [
    {
      "schema_url": "https://raw.githubusercontent.com/BioImageTools/zarr-convention-provenance/refs/tags/v1/schema.json",
      "spec_url": "https://github.com/BioImageTools/zarr-convention-provenance/blob/v1/README.md",
      "uuid": "0d4ef942-0f74-4187-ae72-016ddf083fe1",
      "name": "provenance:",
      "description": "Workflow provenance: the software, inputs and checks behind a Zarr node"
    }
  ]
}
```

## Applicable To

This convention can be used with these parts of the Zarr hierarchy:

- [x] Group
- [x] Array

A producer that writes a hierarchy (for example an OME-Zarr multiscale group with arrays beneath it) SHOULD
place the provenance on the highest node it wrote and MAY repeat or refine it on child nodes.

## Properties

All properties are placed at the root `attributes` level. To avoid attribute name collisions with other
conventions, this convention supports the following patterns:

#### 1. Key-Prefixed Pattern (Recommended)

Individual attributes are prefixed with `provenance:`.

**Convention metadata name**: `provenance:`

| Field Name           | Type                                       | Description                                              |
| -------------------- | ------------------------------------------ | -------------------------------------------------------- |
| provenance:software  | [Software Object](#software-object)        | **REQUIRED**. The program that wrote this node.          |
| provenance:inputs    | \[[Input Object](#input-object)]           | The files or stores this node was derived from.          |

**Example**:

```json
{
  "attributes": {
    "zarr_conventions": [{ "name": "provenance:", "spec_url": "..." }],
    "provenance:software": { "name": "mia-harness", "version": "de4b985206c86137e2ddf1fe32617f0764b73cb9" },
    "provenance:inputs": [{ "url": "https://zenodo.org/records/6400327/files/data.zip" }]
  }
}
```

#### 2. Nested Pattern

All convention properties are nested under a single `provenance` key.

**Convention metadata name**: `provenance`

| Field Name | Type                                    | Description                                       |
| ---------- | --------------------------------------- | ------------------------------------------------- |
| provenance | [Provenance Object](#provenance-object) | **REQUIRED**. Provenance convention properties    |

**Example**:

```json
{
  "attributes": {
    "zarr_conventions": [{ "name": "provenance", "spec_url": "..." }],
    "provenance": {
      "software": { "name": "TensorSwitch", "version": "2.0.3", "url": "https://github.com/JaneliaSciComp/tensorswitch" },
      "inputs": [{ "path": "/data/20260221_FlyID26.nd2" }]
    }
  }
}
```

### Provenance Object

When using the nested pattern, all properties are contained in the `provenance` object:

| Field Name | Type                                | Description                                      |
| ---------- | ----------------------------------- | ------------------------------------------------ |
| software   | [Software Object](#software-object) | **REQUIRED**. The program that wrote this node.  |
| inputs     | \[[Input Object](#input-object)]    | The files or stores this node was derived from.  |

### Software Object

The program that wrote the node. A version string is enough for a released tool; a commit hash is preferred
for anything built from source.

| Field Name | Type   | Description                                                             |
| ---------- | ------ | ----------------------------------------------------------------------- |
| name       | string | **REQUIRED**. The tool's name.                                          |
| version    | string | **REQUIRED**. Release version or VCS commit.                            |
| url        | string | Where the tool lives (repository or documentation).                     |

### Input Object

One source the node was derived from. Exactly one of `url` and `path` MUST be given. `path` is relative to
the node unless it is absolute; it MUST NOT contain `.` or `..` segments.

| Field Name | Type   | Description                                                             |
| ---------- | ------ | ----------------------------------------------------------------------- |
| url        | string | Remote location of the input.                                           |
| path       | string | Local location of the input.                                            |

## Open questions

Fields in use by the producers surveyed (mia-harness, TensorSwitch, the LMD ingest) that are not yet in the
convention. Each needs a decision on whether it belongs here, in a sibling convention, or nowhere.

- **Input identity**: HTTP validators (etag, last-modified, size), content hashes (sha256), a sub-member of
  an archive (`train/*.tif` inside a zip), and the reader or plugin that decoded the file.
- **Environment**: a digest of the dependency lockfile, container image, or preset name.
- **Node identity**: a recipe hash over all inputs and parameters, so equal recipes give equal ids.
- **Parameters**: the free-form settings the run used, and a digest of them.
- **Verification**: checks run on the written node, their result, time, and the checking software.
- **Integrity**: digests of the metadata documents as left on disk.
- **Rights and tracking**: license, and a link to the ticket or request that produced the node.
- **Time**: when the node was written.

## Known Implementations

### Libraries and Tools

_None yet._

### Datasets Using This Convention

_None yet._

_If you implement or use this convention, please add your implementation to this list by submitting a pull request._

## Acknowledgements

This convention follows the [zarr-conventions template](https://github.com/zarr-conventions/template),
which is based on the [STAC extensions template](https://github.com/stac-extensions/template). The
[thumbnails convention](https://github.com/zarr-conventions/thumbnails) served as a worked example.
