package com.clinref.shared.platform

/**
 * Cross-platform file system abstraction for database file validation and path resolution.
 */
interface PlatformFileSystem {
    fun exists(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun listFiles(directoryPath: String): List<String>
    fun joinPath(parent: String, child: String): String
}

expect fun getPlatformFileSystem(): PlatformFileSystem
