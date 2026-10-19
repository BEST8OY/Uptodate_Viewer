package com.clinref.shared.platform

/**
 * Cross-platform logging abstraction replacing Android's Log.
 */
expect object PlatformLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}
