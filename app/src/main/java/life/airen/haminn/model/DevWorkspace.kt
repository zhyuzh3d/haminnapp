package life.airen.haminn.model

import org.json.JSONObject

data class DevWorkspace(
    val appId: String,
    val baseReleaseId: String,
    val generation: String,
    val revision: Long,
    val treeHash: String,
    val dirty: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toJson(activeReleaseId: String? = baseReleaseId): JSONObject = JSONObject()
        .put("appId", appId)
        .put("baseReleaseId", baseReleaseId)
        .put("generation", generation)
        .put("revision", revision)
        .put("treeHash", treeHash)
        .put("dirty", dirty)
        .put("baseOutdated", activeReleaseId != null && activeReleaseId != baseReleaseId)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
}
