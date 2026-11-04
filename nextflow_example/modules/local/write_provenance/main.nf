process WRITE_PROVENANCE {
    tag "${fragments instanceof List ? fragments.size() : 1} fragments"
    label 'process_low'
    container 'docker.io/library/python:3.12-slim'
    // Always re-run: the writer skips steps already recorded, and a cached run would
    // miss stores that were re-created since.
    cache false

    input:
    path(fragments, stageAs: 'fragments/*')
    // Anything the workflow must finish first, so no other task is still writing to a
    // store's metadata while provenance is appended (e.g. the collected final outputs).
    val(_wait_for)

    output:
    path 'provenance_report.json', emit: report

    when:
    task.ext.when == null || task.ext.when

    script:
    def args = task.ext.args ?: ''
    """
    write_provenance.py \\
        --report provenance_report.json \\
        ${Provenance.writerArgs(workflow)} \\
        ${args} \\
        fragments/*.prov.json
    """

    stub:
    """
    echo '[]' > provenance_report.json
    """
}