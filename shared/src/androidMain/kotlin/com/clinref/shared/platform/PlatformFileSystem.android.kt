package com.clinref.shared.platform

import java.io.File

class AndroidFileSystem : PlatformFileSystem {
    override fun exists(path: String): Boolean = File(path).exists()
    override fun isDirectory(path: String): Boolean = File(path).isDirectory
    override fun listFiles(directoryPath: String): List<String> =
        File(directoryPath).list()?.toList() ?: emptyList()
    override fun joinPath(parent: String, child: String): String =
        File(parent, child).absolutePath
}

actual fun getPlatformFileSystem(): PlatformFileSystem = AndroidFileSystem()
