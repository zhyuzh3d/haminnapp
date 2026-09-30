package life.airen.haminn.model

data class CodeRelease(
    val releaseId: String,
    val appId: String,
    val treeHash: String,
    val provenance: String,
    val versionCode: Long?,
    val versionName: String?,
    val sourceRevision: String?,
    val entryPath: String,
    val relativeRoot: String,
    val createdAt: Long,
    val routing: String = "hash",
    val happId: String? = null,
    val publisherKeyId: String? = null,
    /**
     * The packager's own account of this package, raw, exactly as it arrived. Display only: it is
     * never parsed into a decision, and a package without one is a perfectly normal package.
     */
    val declaredBuild: String? = null,
)
