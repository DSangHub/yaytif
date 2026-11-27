package com.yaytif.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yaytif.app.data.local.dao.LocalNotificationDao
import com.yaytif.app.data.local.dao.SponsoredAlertDao
import com.yaytif.app.data.local.entity.LocalNotificationEntity
import com.yaytif.app.data.local.entity.SponsoredAlertEntity

@Database(
    entities = [
        LocalNotificationEntity::class,
        SponsoredAlertEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(YaytifTypeConverters::class)
abstract class YaytifDatabase : RoomDatabase() {

    abstract fun localNotificationDao(): LocalNotificationDao

    abstract fun sponsoredAlertDao(): SponsoredAlertDao

    companion object {
        const val DATABASE_NAME = "yaytif_local.db"

        @Volatile
        private var instance: YaytifDatabase? = null

        fun getInstance(context: Context): YaytifDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    YaytifDatabase::class.java,
                    DATABASE_NAME
                ).build().also { db ->
                    instance = db
                }
            }
        }
    }
}
