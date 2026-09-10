package com.mybrowser.userscript

import com.mybrowser.core.TextDownloader
import java.net.URI

data class UserScriptMetadata(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val excludeMatches: List<String>,
    val grants: List<String>,
    val requires: List<String>,
    val runAt: String,
    val noframes: Boolean,
    val unsupported: List<String>,
) {
    val id: String = TextDownloader.sha256(namespace + "\n" + name).take(32)
    val supported: Boolean get() = unsupported.isEmpty()
    fun grants(api: String): Boolean = api in grants || api.replace("GM_", "GM.") in grants
    val needsStorage: Boolean get() = grants.any { it in STORAGE_GRANTS }

    fun matchesUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host == null || url.length > 8192) return false
        return (matches.any { matchPattern(it, url) } || includes.any { glob(it, url) }) &&
            excludes.none { glob(it, url) } && excludeMatches.none { matchPattern(it, url) }
    }

    companion object {
        const val MAX_SOURCE_BYTES = 1024 * 1024
        val STORAGE_GRANTS = setOf("GM_getValue", "GM_setValue", "GM_deleteValue", "GM_listValues",
            "GM.getValue", "GM.setValue", "GM.deleteValue", "GM.listValues")
        val SUPPORTED_GRANTS = STORAGE_GRANTS + setOf("none", "unsafeWindow", "GM_info", "GM.info",
            "GM_addStyle", "GM.addStyle", "GM_log", "GM.log", "GM_openInTab", "GM.openInTab")
        private val PATTERN = Regex("^(\\*|https?)://(\\*|\\*\\.[a-zA-Z0-9.-]+|[a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\])(/.*)$")

        fun parse(source: String): UserScriptMetadata {
            require(source.toByteArray(Charsets.UTF_8).size <= MAX_SOURCE_BYTES && !source.contains('\u0000')) { "Script too large" }
            val lines = source.removePrefix("\uFEFF").lineSequence().toList()
            val start = lines.indexOfFirst { it.trim() == "// ==UserScript==" }
            val end = lines.indexOfFirst { it.trim() == "// ==/UserScript==" }
            require(start in 0..20 && end > start && end - start <= 512) { "Missing UserScript metadata" }
            require(lines.take(start).all { it.isBlank() || it.trimStart().startsWith("//") }) { "Code before metadata" }
            val fields = linkedMapOf<String, MutableList<String>>()
            val metadataLine = Regex("^\\s*//\\s*@([\\w-]+)(?:\\s+(.*))?\\s*$")
            for (line in lines.subList(start + 1, end)) {
                val match = metadataLine.matchEntire(line) ?: continue
                val value = match.groupValues[2].trim()
                require(value.length <= 2048) { "Metadata too long" }
                fields.getOrPut(match.groupValues[1]) { mutableListOf() }.add(value)
            }
            fun list(key: String) = fields[key]?.distinct().orEmpty()
            fun first(key: String) = fields[key]?.firstOrNull().orEmpty()
            val name = first("name")
            require(name.isNotBlank() && name.length <= 128) { "Missing script name" }
            val problems = mutableListOf<String>()
            val matches = list("match")
            val includes = list("include")
            val excludes = list("exclude")
            val excludeMatches = list("exclude-match")
            if (matches.isEmpty() && includes.isEmpty()) problems += "@match / @include"
            (matches + excludeMatches).filterNot(::validPattern).forEach { problems += "@match " + it }
            (includes + excludes).filter { it.startsWith('/') || it.length > 1024 }
                .forEach { problems += "@include / @exclude " + it }
            val grants = list("grant").ifEmpty { listOf("none") }
            grants.filterNot { it in SUPPORTED_GRANTS }.forEach { problems += "@grant " + it }
            if ("none" in grants && grants.size > 1) problems += "@grant none + GM"
            val runAt = first("run-at").ifEmpty { "document-idle" }
            if (runAt !in setOf("document-start", "document-end", "document-idle")) problems += "@run-at " + runAt
            val requires = list("require")
            if (requires.size > 8 || requires.any { !TextDownloader.isHttpUrl(it) }) problems += "@require URL"
            listOf("resource", "unwrap", "top-level-await").filter { fields.containsKey(it) }.forEach { problems += "@" + it }
            return UserScriptMetadata(name, first("namespace").take(256), first("version").take(64),
                first("description").take(2048), matches, includes, excludes, excludeMatches, grants,
                requires, runAt, fields.containsKey("noframes"), problems.distinct())
        }

        fun validPattern(pattern: String): Boolean = pattern == "<all_urls>" ||
            (pattern.length <= 1024 && PATTERN.matches(pattern))

        fun matchPattern(pattern: String, url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (uri.scheme !in listOf("http", "https") || uri.host == null) return false
            if (pattern == "<all_urls>") return true
            val match = PATTERN.matchEntire(pattern) ?: return false
            val (scheme, host, path) = match.destructured
            if (scheme != "*" && !scheme.equals(uri.scheme, true)) return false
            val actual = uri.host.lowercase()
            val expected = host.lowercase()
            if (expected != "*" && expected != actual &&
                !(expected.startsWith("*.") && (actual == expected.drop(2) || actual.endsWith(expected.drop(1))))) return false
            val pathAndQuery = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?" + it } ?: "")
            return glob(path, pathAndQuery)
        }

        /** Linear wildcard matching avoids backtracking regular expressions from imported metadata. */
        fun glob(pattern: String, value: String): Boolean {
            var p = 0
            var v = 0
            var star = -1
            var mark = 0
            while (v < value.length) {
                if (p < pattern.length && pattern[p] == value[v]) { p++; v++ }
                else if (p < pattern.length && pattern[p] == '*') { star = p++; mark = v }
                else if (star >= 0) { p = star + 1; v = ++mark }
                else return false
            }
            while (p < pattern.length && pattern[p] == '*') p++
            return p == pattern.length
        }
    }
}

data class InstalledUserScript(
    val metadata: UserScriptMetadata,
    val source: String,
    val sourceUrl: String? = null,
    val requiredCode: List<String> = emptyList(),
    val enabled: Boolean = true,
    val values: String = "{}",
)
