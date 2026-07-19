@file:JvmName("Coroutines_jvmKt")

package ai.koog.utils.io

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Local shim for the JVM variant of Koog's Dispatchers.SuitableForIO.
 *
 * The Android variant (Coroutines.android.kt) exists in the utils-android artifact
 * but R8 resolves to the JVM class name (Coroutines_jvmKt) because OkHttpKoogHttpClient
 * was compiled against the JVM target. This shim satisfies that reference.
 */
actual val Dispatchers.SuitableForIO: CoroutineDispatcher
    get() = Dispatchers.IO
