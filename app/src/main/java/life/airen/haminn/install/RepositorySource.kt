package life.airen.haminn.install

import java.net.URI

enum class RepositoryProvider(val adapter: String, val label: String) {
    GITHUB("github", "GitHub"),
    GITLAB("gitlab", "GitLab"),
    GITEE("gitee", "Gitee"),
}

data class RepositorySource(
    val provider: RepositoryProvider,
    val namespace: String,
    val repository: String,
    val ref: String? = null,
    val path: String = "",
) {
    val webUrl: String get() = when (provider) {
        RepositoryProvider.GITHUB -> "https://github.com/$namespace/$repository"
        RepositoryProvider.GITLAB -> "https://gitlab.com/$namespace/$repository"
        RepositoryProvider.GITEE -> "https://gitee.com/$namespace/$repository"
    }
    val cloneUrl: String get() = "$webUrl.git"
}

data class RepositoryDirectory(val path: String, val label: String)

/** Pure URL and archive rules, kept outside Android APIs so they can be unit tested. */
object RepositorySourceRules {
    private val directoryPriority = listOf("dist", "build", "release", "web", "public", "www", "docs")
    private val slug = Regex("[A-Za-z0-9_.-]{1,100}")

    fun parse(raw: String): RepositorySource? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.userInfo != null || uri.fragment != null) return null
        val provider = when (uri.host?.lowercase()) {
            "github.com", "www.github.com" -> RepositoryProvider.GITHUB
            "gitlab.com", "www.gitlab.com" -> RepositoryProvider.GITLAB
            "gitee.com", "www.gitee.com" -> RepositoryProvider.GITEE
            else -> return null
        }
        val query = runCatching {
            uri.rawQuery.orEmpty().split('&').mapNotNull { item ->
                if (item.isBlank()) null else item.substringBefore('=') to java.net.URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
            }.toMap()
        }.getOrElse { return null }
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }.toMutableList()
        val marker = if (provider == RepositoryProvider.GITLAB) parts.indexOf("-").takeIf { it >= 0 } else null
        val treeIndex = if (marker != null && parts.getOrNull(marker + 1) == "tree") marker + 1 else parts.indexOf("tree")
        val repoEnd = when {
            marker != null && marker >= 2 -> marker
            treeIndex >= 2 -> treeIndex
            else -> parts.size
        }
        if (repoEnd < 2) return null
        val repository = parts[repoEnd - 1].removeSuffix(".git")
        val namespaceParts = parts.subList(0, repoEnd - 1)
        if (repository.isBlank() || !slug.matches(repository) || namespaceParts.isEmpty() || namespaceParts.any { !slug.matches(it) }) return null
        val treeRef = if (treeIndex >= 0) parts.getOrNull(treeIndex + 1) else null
        val treePath = if (treeIndex >= 0) parts.drop(treeIndex + 2).joinToString("/") else ""
        val ref = query["ref"]?.takeIf { validRef(it) } ?: treeRef?.takeIf { validRef(it) }
        val path = (query["path"] ?: treePath).trim('/').takeIf { validRelative(it) } ?: return null
        return RepositorySource(provider, namespaceParts.joinToString("/"), repository, ref, path)
    }

    fun directoryChoices(entries: Collection<String>, root: String = ""): List<RepositoryDirectory> {
        val normalizedRoot = root.trim('/')
        val relative = entries.mapNotNull { raw ->
            val path = raw.trim('/')
            when {
                normalizedRoot.isEmpty() -> path
                path.startsWith("$normalizedRoot/") -> path.removePrefix("$normalizedRoot/")
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
        val topDirectories = relative.mapNotNull { it.substringBefore('/', "").takeIf(String::isNotBlank) }.toSet()
        val options = directoryPriority.filter { it in topDirectories }.map { name -> RepositoryDirectory(name, name) }.toMutableList()
        options += RepositoryDirectory("", "全部文件")
        return options
    }

    fun validRelative(path: String): Boolean = path.isEmpty() || path.length <= 512 && !path.startsWith('/') && !path.contains('\\') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." }

    private fun validRef(ref: String): Boolean = ref.isNotBlank() && ref.length <= 200 && !ref.contains("..") &&
        ref.none { it <= '\u001F' || it == '\\' }
}
