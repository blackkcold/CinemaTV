package dev.cinematv.data.douban

import android.content.Context

/** Single process owner, separate from the pure/testable account coordinator. */
object DoubanAccounts {
    @Volatile private var instance: DoubanAccountService? = null
    fun get(context: Context): DoubanAccountService = instance ?: synchronized(this) {
        instance ?: DoubanAccountService(DoubanVault(context.applicationContext),
            access = DoubanDeveloperOptions.get(context).access).also { instance = it }
    }
    /** Cleanup deliberately works while disabled, and never loads a previously saved session. */
    internal suspend fun clearExisting(context: Context) {
        instance?.disconnect() ?: DoubanVault(context.applicationContext).clear()
    }
}
