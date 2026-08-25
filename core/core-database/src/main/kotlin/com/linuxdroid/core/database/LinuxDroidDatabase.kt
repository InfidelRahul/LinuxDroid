package com.linuxdroid.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.linuxdroid.core.database.dao.EnvironmentDao
import com.linuxdroid.core.database.entity.EnvironmentEntity

/**
 * LinuxDroid Room database.
 *
 * Stores Android-side metadata about environments, sessions, and settings.
 * The Linux rootfs is NEVER stored in this database — only filesystem paths.
 */
@Database(
    entities = [EnvironmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LinuxDroidDatabase : RoomDatabase() {

    abstract fun environmentDao(): EnvironmentDao

    companion object {
        private const val DATABASE_NAME = "linuxdroid.db"

        @Volatile
        private var INSTANCE: LinuxDroidDatabase? = null

        fun getInstance(context: Context): LinuxDroidDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LinuxDroidDatabase::class.java,
                    DATABASE_NAME,
                )
                    .fallbackToDestructiveMigration(from = 1)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
