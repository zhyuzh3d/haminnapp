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
)
