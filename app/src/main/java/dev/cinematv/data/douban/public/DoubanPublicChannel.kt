package dev.cinematv.data.douban.public

import android.content.Context
import android.util.AtomicFile
import java.io.File

/**
 * One process-wide public reader. It is deliberately separate from the personal-account
 * service: no cookie, no vault, no account identity ever reaches this object.
 */
object DoubanPublicChannel {
    @Volatile private var chart: DoubanPublicChart? = null
    @Volatile private var repository: DoubanPublicRepository? = null

    fun chart(context: Context): DoubanPublicChart = chart ?: synchronized(this) {
        chart ?: DoubanPublicChart(repository(context)).also { chart = it }
    }

    fun gate(context: Context): DoubanPublicGate = repository(context).gate

    fun repository(context: Context): DoubanPublicRepository = repository ?: synchronized(this) {
        repository ?: run {
            val app = context.applicationContext
            DoubanPublicRepository(
                transport = OfficialDoubanPublicTransport(),
                gate = DoubanPublicGate(FileStore(File(app.noBackupFilesDir, "douban-public-gate.v1"))),
                cache = DoubanPublicCache(File(app.cacheDir, "douban-public"))
            ).also { repository = it }
        }
    }

    /** Clearing the public cache also releases a stored pause; it holds no user data. */
    fun clear(context: Context) {
        repository(context).gate.reset()
        DoubanPublicCache(File(context.applicationContext.cacheDir, "douban-public")).clear()
    }

    private class FileStore(private val file: File) : DoubanPublicStore {
        private val atomic = AtomicFile(file)
        override fun read(): String? = runCatching { atomic.readFully().toString(Charsets.UTF_8) }.getOrNull()
        override fun write(value: String) {
            val stream = atomic.startWrite()
            try {
                stream.write(value.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
    }
}
