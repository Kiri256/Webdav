package com.example.webdavmanager

import android.net.Uri
import java.io.File

internal fun remoteJumpPath(paths: DavPaths, input: String): String {
    require(input.startsWith('/') && !input.contains("://")) { "Enter a directory path, not a server URL" }
    val normalized = normalizePath(input)
    paths.url(normalized, true)
    return normalized
}

internal fun localJumpUri(root: String, input: String): String {
    require(input.startsWith('/') && input.none { it.code < 32 })
    val target = Uri.fromFile(File(input)).toString()
    DirectFiles.inside(root, target)
    return target
}

internal fun localParentChain(root: String, path: String): List<String> {
    val base = DirectFiles.file(root)
    var current = DirectFiles.file(path).parentFile
    val parents = mutableListOf<String>()
    while (current != null && current.toPath().startsWith(base.toPath())) {
        parents += Uri.fromFile(current).toString()
        if (current == base) break
        current = current.parentFile
    }
    return if (root == path) emptyList() else parents.asReversed()
}
