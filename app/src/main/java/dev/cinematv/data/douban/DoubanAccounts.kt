package dev.cinematv.data.douban

import android.content.Context

/** Single process owner, separate from the pure/testable account coordinator. */
object DoubanAccounts {
    @Volatile private var instance: DoubanAccountService? = null
    fun get(context: Context): DoubanAccountService = instance ?: synchronized(this) {
        instance ?: DoubanAccountService(DoubanVault(context.applicationContext)).also { instance = it }
    }
}
