package dev.cinematv.data.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkEntity::class, SeasonEntity::class, SourceMappingEntity::class,
        EpisodeEntity::class, LibraryEntryEntity::class, EpisodeProgressEntity::class, LibraryMetadataEntity::class],
    version = 1,
    exportSchema = true
)
abstract class CinemaLibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var instance: CinemaLibraryDatabase? = null

        fun get(context: Context): CinemaLibraryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                CinemaLibraryDatabase::class.java, "cinema_library.db")
                // Deliberately no allowMainThreadQueries or destructive fallback.
                // Future schema versions must add explicit, tested Migration objects.
                .build().also { instance = it }
        }
    }
}
