package com.example.clock.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AlarmEntity::class], version = 1, exportSchema = true)
abstract class AlarmDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao

    companion object {
        @Volatile
        private var instance: AlarmDatabase? = null

        fun get(context: Context): AlarmDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlarmDatabase::class.java,
                    "clock.db",
                ).build().also { instance = it }
            }

        /**
         * 仅供测试：关闭并清空单例。
         *
         * Robolectric 每个测试都会新建 Application，但 object / companion object 里的
         * 静态状态**不会**随之重置。不在 @Before 里调用它，后一个测试就会拿到上一个
         * 测试遗留的、指向已失效目录的数据库实例。
         */
        @VisibleForTesting
        fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
