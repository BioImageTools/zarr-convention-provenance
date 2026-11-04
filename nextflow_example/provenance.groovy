import groovy.json.JsonOutput

/**
 * Provenance for OME-Zarr stores, following the Zarr provenance convention
 * (https://github.com/BioImageTools/zarr-convention-provenance, v0.1.0).
 *
 * Modules never touch the stores' metadata themselves. Each module only writes a
 * small JSON fragment (`<id>.<task hash>.prov.json`) describing one step; the
 * WRITE_PROVENANCE module appends all fragments to their target stores at the end
 * of the workflow, in one task, so parallel tasks never race on zarr.json/.zattrs.
 *
 * Usage inside a module's script block:
 *
 *     """
 *     ${Provenance.start()}
 *
 *     my_tool.py ...
 *
 *     ${Provenance.write(task, meta,
 *         name      : 'Cellpose-SAM segmentation',
 *         category  : 'Segmentation',
 *         software  : Provenance.ownSoftware('cellpose_sam_zarr.py', ['cellpose']),
 *         params_file: 'params_used.json',
 *         inputs    : [[file: image, name: 'image']],
 *         outputs   : [[file: output_zarr, name: 'label image']],
 *         target    : output_zarr)}
 *     """
 *
 * and declare the fragment as an optional output:
 *
 *     tuple val(meta), path("*.prov.json"), emit: provenance, optional: true
 *
 * Nothing here needs a container change: the fragment is assembled by a short
 * standard-library Python snippet run with whatever `python3` the module's
 * container already has. If there is none, the task prints a warning and goes on.
 */
class Provenance {

    // Placeholders for what changes between runs; WRITE_PROVENANCE fills them in.
    static final String COMMIT_PLACEHOLDER   = '@pipeline-commit@'
    static final String PIPELINE_PLACEHOLDER = '@pipeline@'
    static final String REPO_PLACEHOLDER     = '@pipeline-repository@'

    private static final Map<String, String> COMMITS = [:]

    /** Shell line to put at the very top of a script block: records the start time. */
    static String start() {
        return 'PROV_STARTED="$(date -Iseconds)"'
    }

    /**
     * Commit of the pipeline the workflow runs from, with "-dirty" appended when tracked
     * files of a local checkout have uncommitted changes. Computed once per run.
     */
    static String repoCommit(def workflow) {
        def dir = workflow.projectDir.toString()
        synchronized (COMMITS) {
            if (!COMMITS.containsKey(dir)) {
                COMMITS[dir] = gitCommit(workflow)
            }
            return COMMITS[dir]
        }
    }

    /**
     * Software object for a script that lives in the pipeline's own repository (e.g.
     * a module's resources/usr/bin script). `packages` are Python packages whose
     * installed versions are recorded alongside, e.g. ['cellpose', 'zarr'].
     *
     * Name, version and URL are placeholders that WRITE_PROVENANCE fills in with the
     * pipeline name, repository commit and URL: anything that changes between runs must
     * stay out of the task script, or Nextflow would never reuse cached tasks.
     */
    static Map ownSoftware(String entryPoint, List packages = []) {
        def software = [name: PIPELINE_PLACEHOLDER, version: COMMIT_PLACEHOLDER, entry_point: entryPoint,
                        uri: REPO_PLACEHOLDER]
        if (packages) {
            software.packages = packages
        }
        return software
    }

    /**
     * Repository URL of the pipeline: the GitHub repository it was pulled from, else the
     * `origin` remote of the local checkout, else manifest.homePage.
     */
    static String repository(def workflow) {
        if (workflow.repository) {
            return workflow.repository.toString().replaceAll(/\.git$/, '')
        }
        def remote = git(workflow.projectDir.toString(), ['remote', 'get-url', 'origin'])
        if (remote) {
            // git@github.com:org/repo.git -> https://github.com/org/repo
            def m = remote =~ /^git@([^:]+):(.+?)(\.git)?$/
            if (m.matches()) {
                return "https://${m.group(1)}/${m.group(2)}".toString()
            }
            return remote.replaceAll(/\.git$/, '').replaceAll(/\/\/[^\/@]+@/, '//')
        }
        return workflow.manifest?.homePage ?: null
    }

    /** Pipeline name: manifest.name, else the repository name. */
    static String pipelineName(def workflow) {
        if (workflow.manifest?.name) {
            return workflow.manifest.name
        }
        def repo = repository(workflow)
        return repo ? repo.tokenize('/')[-1] : workflow.projectDir.getName().toString()
    }

    /** Run details passed to write_provenance.py by the WRITE_PROVENANCE module. */
    static String writerArgs(def workflow) {
        def quote = { def v -> "'" + v.toString().replace("'", "'\"'\"'") + "'" }
        def opts = [
            '--pipeline'        : pipelineName(workflow),
            '--repository'      : repository(workflow),
            '--commit'          : repoCommit(workflow),
            '--workflow'        : workflow.projectDir?.getName(),
            '--user'            : workflow.userName,
            '--run-name'        : workflow.runName,
            '--session-id'      : workflow.sessionId,
            '--nextflow-version': workflow.nextflow?.version,
            '--command-line'    : workflow.commandLine,
        ]
        return opts.findAll { k, v -> v != null && v.toString() }
                   .collect { k, v -> "${k} ${quote(v)}" }
                   .join(' ')
    }

    /**
     * Shell snippet that writes this task's provenance fragment. Put it at the end of
     * the script block, after the tool ran.
     *
     * Options (all optional except name and software):
     *   name, category, description : strings
     *   software    : [name:, version:, entry_point:, uri:] -- or `version_from_package:`
     *                 instead of `version:` to read the version of an installed Python
     *                 package inside the container at run time
     *   parameters  : map of parameters known in Nextflow (e.g. a CLI option list)
     *   params_file : JSON file the tool wrote with the parameters it really ran with
     *                 (merged into `parameters`)
     *   inputs, outputs : list of maps, each with one of
     *                   file: a staged input, a work-dir output or an absolute path
     *                   uri:  a URI string (kept as is)
     *                   path: a node inside the target store (relative to its root)
     *                 plus optional name: and checksum: true (sha256 of a regular file)
     *                 Entries whose file name starts with NO_ (sentinels) are dropped.
     *   target      : the store the step is recorded on (file or absolute path)
     *   collapse    : true to merge all tasks of this process that share a target into
     *                 one step (use for per-tile processes)
     *   settings    : map of what you set in the samplesheet / params for this step
     *                 (recorded as nextflow:settings)
     *
     * Set PROV_SKIP=1 in the script to skip writing the fragment (e.g. when a step
     * found its output already present and did nothing).
     */
    static String write(Map opts, def task, def meta) {
        if (!opts.name || !opts.software || opts.target == null) {
            throw new IllegalArgumentException("Provenance.write needs name, software and target (${task.process})")
        }
        def givenVersion = opts.software.version
        if (givenVersion != null && givenVersion != COMMIT_PLACEHOLDER) {
            throw new IllegalArgumentException(
                "Provenance.write (${task.process}): give software.version_from_package, not a fixed version")
        }
        def spec = [
            id          : meta?.id?.toString() ?: 'step',
            target      : opts.target.toString(),
            collapse    : opts.collapse ? true : false,
            params_file : opts.params_file?.toString(),
            container_version: containerVersion(task.container),
            results_file: opts.results_file?.toString(),
            step        : [
                name       : opts.name,
                category   : opts.category,
                description: opts.description,
                software   : opts.software,
                parameters : opts.parameters ?: [:],
                inputs     : refs(opts.inputs),
                outputs    : refs(opts.outputs),
            ],
            extra       : [
                'nextflow:process'  : task.process,
                'nextflow:container': task.container,
                'nextflow:settings' : opts.settings ?: null,
                // No run name, session id or commit here: they change every run and would
                // stop -resume from reusing this task. WRITE_PROVENANCE adds them.
            ],
        ]
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(plain(spec)))
        return [
            '# ---- provenance fragment (lib/Provenance.groovy) ----',
            'PROV_ENDED="$(date -Iseconds)"',
            'if [ -z "${PROV_SKIP:-}" ]; then',
            "cat > .provenance_spec.json <<'__PROV_SPEC__'",
            json,
            '__PROV_SPEC__',
            'if command -v python3 >/dev/null 2>&1; then',
            "PROV_STARTED=\"\${PROV_STARTED:-}\" PROV_ENDED=\"\$PROV_ENDED\" python3 - <<'__PROV_PY__' || echo 'WARNING: provenance fragment not written' >&2",
            FRAGMENT_PY,
            '__PROV_PY__',
            'else',
            "echo 'WARNING: python3 not found, provenance fragment not written' >&2",
            'fi',
            'fi',
        ].join('\n')
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Version given by a container reference: its digest when pinned by one, else its
     * tag. "ghcr.io/embl-mif/zarrsmith:0.6.1" -> "0.6.1",
     * "ilastik/ilastik@sha256:ab12..." -> "sha256:ab12...", "/path/ilastik_1.4.1.sif" ->
     * "ilastik_1.4.1.sif". Null for no container or an untagged or "latest" image.
     */
    static String containerVersion(def container) {
        if (!container) {
            return null
        }
        def ref = container.toString().replaceFirst(/^[a-z]+:\/\//, '')
        if (ref.contains('@')) {
            return ref.substring(ref.lastIndexOf('@') + 1)
        }
        def name = ref.tokenize('/')[-1]
        if (name.endsWith('.sif') || name.endsWith('.img')) {
            return name
        }
        def tag = name.contains(':') ? name.substring(name.lastIndexOf(':') + 1) : null
        return tag && tag != 'latest' ? tag : null
    }

    private static List refs(def items) {
        if (!items) {
            return []
        }
        return items.findAll { it != null }.collect { Map item ->
            def ref = [:]
            item.each { k, v -> if (v != null) { ref[k.toString()] = (v instanceof Boolean) ? v : v.toString() } }
            ref
        }.findAll { Map ref ->
            def f = ref.file
            !(f && new File(f).name.startsWith('NO_'))
        }
    }

    /** Turns GStrings, Paths and other objects into plain JSON-able values. */
    private static Object plain(def value) {
        if (value == null || value instanceof Boolean || value instanceof Number) {
            return value
        }
        if (value instanceof Map) {
            def out = [:]
            value.each { k, v -> out[k.toString()] = plain(v) }
            return out
        }
        if (value instanceof Collection || value instanceof Object[]) {
            return value.collect { plain(it) }
        }
        return value.toString()
    }

    private static String git(String dir, List<String> args) {
        try {
            def proc = (['git', '-C', dir] + args).execute()
            def out = proc.text.trim()
            proc.waitFor()
            return proc.exitValue() == 0 ? out : null
        } catch (Exception ignored) {
            return null
        }
    }

    private static String gitCommit(def workflow) {
        if (workflow.commitId) {
            return workflow.commitId.toString()     // pulled from GitHub with `nextflow run org/repo`
        }
        def dir = workflow.projectDir.toString()
        def sha = git(dir, ['rev-parse', 'HEAD'])
        if (!sha) {
            return workflow.manifest?.version ?: 'unknown'
        }
        def dirty = git(dir, ['status', '--porcelain', '--untracked-files=no'])
        return dirty ? "${sha}-dirty".toString() : sha
    }

    /**
     * Standard-library Python run inside the task: resolves file:// URIs, checksums,
     * the software version, timings and compute details, and writes the fragment.
     * Kept free of backslashes and dollar signs so it survives Groovy and bash as is.
     */
    private static final String FRAGMENT_PY = '''
import hashlib, json, os, pathlib, platform, re, socket, subprocess

spec = json.load(open(".provenance_spec.json"))
cwd = os.getcwd()
task_id = "/".join(pathlib.PurePosixPath(cwd).parts[-2:])


def to_uri(value):
    if "://" in value:
        return value.rstrip("/")
    p = value
    if os.path.islink(p):
        # A staged input or a linked output: record where it really is, one level
        # down only, so mount paths such as /g/mif-hd/... are kept as you know them.
        target = os.readlink(p)
        p = target if os.path.isabs(target) else os.path.join(os.path.dirname(p), target)
    if not os.path.isabs(p):
        p = os.path.join(cwd, p)
    return pathlib.PurePosixPath(os.path.normpath(p)).as_uri()


def local_path(value):
    if value.startswith("file://"):
        from urllib.parse import unquote, urlparse
        return unquote(urlparse(value).path)
    return value


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return "sha256:" + h.hexdigest()


def resolve(ref):
    out = {}
    if ref.get("path"):
        out["path"] = ref["path"]
    if ref.get("file"):
        out["uri"] = to_uri(ref["file"])
    elif ref.get("uri"):
        out["uri"] = to_uri(ref["uri"])
    if ref.get("name"):
        out["name"] = ref["name"]
    if ref.get("checksum") in (True, "true") and out.get("uri", "").startswith("file://"):
        p = local_path(out["uri"])
        if os.path.isfile(p):
            out["checksum"] = sha256(p)
    return out if ("path" in out or "uri" in out) else None


step = {k: v for k, v in spec["step"].items() if v not in (None, "", [], {})}
software = dict(step["software"])
pkg = software.pop("version_from_package", None)
if pkg:
    try:
        from importlib.metadata import version
        software["version"] = version(pkg)
        software["nextflow:version_source"] = "python package " + pkg
    except Exception:
        pass
if not software.get("version") and spec.get("container_version"):
    software["version"] = spec["container_version"]
    software["nextflow:version_source"] = "container"
software.setdefault("version", "unknown")
packages = {}
for name in software.pop("packages", None) or []:
    try:
        from importlib.metadata import version
        packages[name] = version(name)
    except Exception:
        packages[name] = "unknown"
if packages:
    software["nextflow:packages"] = packages
step["software"] = software

params = {}
if spec.get("params_file") and os.path.isfile(spec["params_file"]):
    with open(spec["params_file"]) as fh:
        params.update(json.load(fh))
params.update(spec["step"].get("parameters") or {})
if params:
    step["parameters"] = params

for key in ("inputs", "outputs"):
    refs = [r for r in (resolve(x) for x in spec["step"].get(key) or []) if r]
    if refs:
        step[key] = refs
    else:
        step.pop(key, None)

started = os.environ.get("PROV_STARTED")
ended = os.environ.get("PROV_ENDED")
if started:
    step["started"] = started
if ended:
    step["ended"] = ended

if spec.get("results_file") and os.path.isfile(spec["results_file"]):
    with open(spec["results_file"]) as fh:
        step["nextflow:results"] = json.load(fh)

compute = {
    "host": socket.gethostname(),
    "os": platform.system(),
    "architecture": platform.machine(),
    "python": platform.python_version(),
}
try:
    compute["cpu_cores"] = len(os.sched_getaffinity(0))
except Exception:
    compute["cpu_cores"] = os.cpu_count()
try:
    with open("/proc/meminfo") as fh:
        kb = int(next(l for l in fh if l.startswith("MemTotal")).split()[1])
    compute["system_memory_gb"] = round(kb / 1024 / 1024, 1)
except Exception:
    pass
for env_name, key in (("SLURM_JOB_ID", "slurm_job_id"), ("SLURM_CPUS_PER_TASK", "slurm_cpus"),
                      ("SLURM_MEM_PER_NODE", "slurm_memory_mb"), ("SLURM_JOB_PARTITION", "slurm_partition")):
    if os.environ.get(env_name):
        compute[key] = os.environ[env_name]
try:
    gpus = subprocess.run(
        ["nvidia-smi", "--query-gpu=name", "--format=csv,noheader"],
        capture_output=True, text=True, timeout=20,
    )
    names = [g.strip() for g in gpus.stdout.splitlines() if g.strip()]
    if gpus.returncode == 0 and names:
        compute["gpu"] = names
except Exception:
    pass

step["nextflow:task_id"] = task_id
for k, v in spec["extra"].items():
    if v not in (None, "", {}, []):
        step[k] = v
step["nextflow:compute"] = compute

fragment = {
    "target": to_uri(spec["target"]),
    "collapse": bool(spec.get("collapse")),
    "step": step,
}
safe_id = re.sub("[^A-Za-z0-9._-]", "_", spec.get("id") or "step")
name = safe_id + "." + task_id.replace("/", "") + ".prov.json"
with open(name, "w") as fh:
    json.dump(fragment, fh, indent=2)
print("provenance fragment written: " + name)
'''.stripIndent().trim()
}