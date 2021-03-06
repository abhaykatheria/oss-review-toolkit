/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

@file:Suppress("TooManyFunctions")

package com.here.ort.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.type.CollectionType
import com.fasterxml.jackson.databind.type.TypeFactory
import com.fasterxml.jackson.databind.util.ClassUtil

import com.vdurmont.semver4j.Semver

import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.CopyOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.TreeSet

/**
 * Return a string of hexadecimal digits representing the bytes in the array.
 */
fun ByteArray.toHexString(): String = joinToString("") { String.format("%02x", it) }

/**
 * Return the absolute file with a leading "~" in a Unix path expanded to the current user's home directory.
 */
fun File.expandTilde(): File =
    if (System.getenv("SHELL") != null) {
        File(path.replace(Regex("^~"), Regex.escapeReplacement(System.getProperty("user.home"))))
    } else {
        this
    }.absoluteFile

/**
 * Return the hexadecimal digest of the given hash [algorithm] for this [File].
 */
fun File.hash(algorithm: String = "SHA-1"): String =
    MessageDigest.getInstance(algorithm).digest(readBytes()).toHexString()

/**
 * Return true if and only if this file is a symbolic link.
 */
fun File.isSymbolicLink(): Boolean =
    try {
        // Note that we cannot use exists() to check beforehand whether a symbolic link exists to avoid a
        // NoSuchFileException to be thrown as it returns "false" e.g. for dangling Windows junctions.
        Files.readAttributes(toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).let {
            it.isSymbolicLink || (Os.isWindows && it.isOther)
        }
    } catch (e: NoSuchFileException) {
        false
    }

/**
 * Resolve the file to the real underlying file. In contrast to Java's [File.getCanonicalFile], this also works to
 * resolve symbolic links on Windows.
 */
fun File.realFile(): File = toPath().toRealPath().toFile()

/**
 * Copy files recursively without following symbolic links (Unix) or junctions (Windows).
 */
fun File.safeCopyRecursively(target: File, overwrite: Boolean = false) {
    if (!exists()) {
        return
    }

    val sourcePath = absoluteFile.toPath()
    val targetPath = target.absoluteFile.toPath()

    val copyOptions = mutableListOf<CopyOption>(LinkOption.NOFOLLOW_LINKS).apply {
        if (overwrite) add(StandardCopyOption.REPLACE_EXISTING)
    }.toTypedArray()

    // This call to walkFileTree() implicitly uses EnumSet.noneOf(FileVisitOption.class), i.e.
    // FileVisitOption.FOLLOW_LINKS is not used, so symbolic links are not followed.
    Files.walkFileTree(sourcePath, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val isUnixLink = !Os.isWindows && attrs.isSymbolicLink
            val isWindowsLink = Os.isWindows && attrs.isOther
            if (isUnixLink || isWindowsLink) {
                // Do not follow symbolic links or junctions.
                return FileVisitResult.SKIP_SUBTREE
            }

            val targetDir = targetPath.resolve(sourcePath.relativize(dir))
            targetDir.toFile().safeMkdirs()

            return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            val targetFile = targetPath.resolve(sourcePath.relativize(file))
            Files.copy(file, targetFile, *copyOptions)

            return FileVisitResult.CONTINUE
        }
    })
}

/**
 * Delete files recursively without following symbolic links (Unix) or junctions (Windows). If [force] is `true`, it is
 * tried to make undeletable files writable before trying again to delete them. Throws an [IOException] if a file could
 * not be deleted.
 */
fun File.safeDeleteRecursively(force: Boolean = false) {
    if (isDirectory && !isSymbolicLink()) {
        Files.newDirectoryStream(toPath()).use { stream ->
            stream.forEach { path ->
                path.toFile().safeDeleteRecursively(force)
            }
        }
    }

    if (!delete() && force && setWritable(true)) {
        // Try again.
        delete()
    }

    if (exists()) throw IOException("Could not delete file '$absolutePath'.")
}

/**
 * Create all missing intermediate directories without failing if any already exists.
 *
 * @throws IOException if any missing directory could not be created.
 */
fun File.safeMkdirs() {
    // Do not blindly trust mkdirs() returning "false" as it can fail for edge-cases like
    // File(File("/tmp/parent1/parent2"), "/").mkdirs() if parent1 does not exist, although the directory is
    // successfully created.
    if (isDirectory || mkdirs() || isDirectory) {
        return
    }

    throw IOException("Could not create directory '$absolutePath'.")
}

/**
 * Search [this] directory upwards towards the root until a contained sub-directory called [searchDirName] is found and
 * return the parent of [searchDirName], or return null if no such directory is found.
 */
fun File.searchUpwardsForSubdirectory(searchDirName: String): File? {
    if (!isDirectory) return null

    var currentDir: File? = absoluteFile

    while (currentDir != null && !File(currentDir, searchDirName).isDirectory) {
        currentDir = currentDir.parentFile
    }

    return currentDir
}

/**
 * Construct a "file:" URI in a safe way by never using a null authority for wider compatibility.
 */
fun File.toSafeURI(): URI {
    val fileUri = toURI()
    return URI("file", "", fileUri.path, fileUri.query, fileUri.fragment)
}

/*
 * Convenience function for [JsonNode] that returns an empty iterator if [JsonNode.fieldNames] is called on a null
 * object, or the field names otherwise.
 */
fun JsonNode?.fieldNamesOrEmpty(): Iterator<String> = this?.fieldNames() ?: ClassUtil.emptyIterator()

/*
 * Convenience function for [JsonNode] that returns an empty iterator if [JsonNode.fields] is called on a null object,
 * or the fields otherwise.
 */
fun JsonNode?.fieldsOrEmpty(): Iterator<Map.Entry<String, JsonNode>> = this?.fields() ?: ClassUtil.emptyIterator()

/**
 * Convenience function for [JsonNode] that returns an empty string if [JsonNode.textValue] is called on a null object,
 * or the text value is null.
 */
fun JsonNode?.textValueOrEmpty(): String = this?.textValue()?.let { it }.orEmpty()

/**
 * Merge two maps by iterating over the combined key set of both maps and applying [operation] to the entries for the
 * same key. Parameters passed to [operation] can be null if there is no entry for a key in one of the maps.
 */
inline fun <K, V, W> Map<K, V>.zip(other: Map<K, V>, operation: (V?, V?) -> W): Map<K, W> =
    (this.keys + other.keys).associateWith { key ->
        operation(this[key], other[key])
    }

/**
 * Merge two maps by iterating over the combined key set of both maps and applying [operation] to the entries for the
 * same key. If there is no entry for a key in one of the maps, [default] is used.
 */
inline fun <K, V, W> Map<K, V>.zipWithDefault(other: Map<K, V>, default: V, operation: (V, V) -> W): Map<K, W> =
    (this.keys + other.keys).associateWith { key ->
        operation(this[key] ?: default, other[key] ?: default)
    }

/**
 * Return the string encoded for safe use as a file name or "unknown", if the string is empty.
 */
fun String.encodeOrUnknown() = fileSystemEncode().takeUnless { it.isEmpty() } ?: "unknown"

/**
 * Return the string encoded for safe use as a file name. Also limit the length to 255 characters which is the maximum
 * length in most modern filesystems, see
 * [comparison of file system limits](https://en.wikipedia.org/wiki/Comparison_of_file_systems#Limits).
 */
fun String.fileSystemEncode() =
    percentEncode()
        // Percent-encoding does not necessarily encode some characters that are invalid in some file systems, so map
        // these afterwards.
        .replace(Regex("(^\\.|\\.$)"), "%2E")
        .take(255)

/**
 * Return the [percent-encoded](https://en.wikipedia.org/wiki/Percent-encoding) string.
 */
fun String.percentEncode(): String =
    java.net.URLEncoder.encode(this, "UTF-8")
        // As "encode" above actually performs encoding for forms, not for query strings, spaces are encoded as
        // "+" instead of "%20", so apply the proper mapping here afterwards ("+" in the original string is
        // encoded as "%2B").
        .replace("+", "%20")
        // "*" is a reserved character in RFC 3986.
        .replace("*", "%2A")
        // "~" is an unreserved character in RFC 3986.
        .replace("%7E", "~")

/**
 * True if the string is a valid semantic version of the given [type], false otherwise.
 */
fun String.isSemanticVersion(type: Semver.SemverType = Semver.SemverType.STRICT) =
    runCatching { Semver(this, type) }.isSuccess

/**
 * True if the string is a valid [URI], false otherwise.
 */
fun String.isValidUri() = runCatching { URI(this) }.isSuccess

/**
 * True if the string is a valid [URL], false otherwise.
 */
fun String.isValidUrl() = runCatching { URL(this) }.isSuccess

/**
 * A regular expression matching the non-linux line breaks "\r\n" and "\r".
 */
val NON_LINUX_LINE_BREAKS = Regex("\\r\\n?")

/**
 * Replace "\r\n" and "\r" line breaks with "\n".
 */
fun String.normalizeLineBreaks() = replace(NON_LINUX_LINE_BREAKS, "\n")

/**
 * Strip any user name / password off the URL represented by this [String]. Return the unmodified [String] if it does
 * not represent a URL or if it does not include a user name.
 */
fun String.stripCredentialsFromUrl() =
    try {
        // Use an URI instead of an URL as the former allows to specify the userInfo separately.
        URI(this).let {
            URI(it.scheme, null, it.host, it.port, it.path, it.query, it.fragment).toString()
        }
    } catch (e: URISyntaxException) {
        this
    }

/**
 * Recursively collect the messages of this [Throwable] and all its causes.
 */
fun Throwable.collectMessages(): List<String> {
    val messages = mutableListOf<String>()
    var cause: Throwable? = this
    while (cause != null) {
        val suppressed = cause.suppressed.joinToString { "\nSuppressed: ${it.javaClass.simpleName}: ${it.message}" }
        messages += "${cause.javaClass.simpleName}: ${cause.message}$suppressed"
        cause = cause.cause
    }
    return messages
}

/**
 * Recursively collect the messages of this [Throwable] and all its causes and join them to a single [String].
 */
fun Throwable.collectMessagesAsString() = collectMessages().joinToString("\nCaused by: ")

/**
 * Print the stack trace of the [Throwable] if [printStackTrace] is set to true.
 */
fun Throwable.showStackTrace() {
    // We cannot use a function expression for a single "if"-statement, see
    // https://discuss.kotlinlang.org/t/if-operator-in-function-expression/7227.
    if (printStackTrace) printStackTrace()
}

/**
 * Function for constructing a [TreeSet] [CollectionType].
 */
fun TypeFactory.constructTreeSetType(elementClass: Class<*>): CollectionType =
    constructCollectionType(TreeSet::class.java, elementClass)

/**
 * Check whether the URI has a fragment that looks like a VCS revision.
 */
fun URI.hasRevisionFragment() = fragment?.let { Regex("[a-fA-F0-9]{7,}$").matches(it) } == true
