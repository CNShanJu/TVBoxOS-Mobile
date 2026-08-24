package com.github.tvbox.osc.log;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import android.content.Context;

/**
 * 日志独立数据库（log 模块自包含，不并入 AppDataBase，
 * 为将来拆独立 gradle 模块留好边界）。
 */
@Database(entities = {LogEntry.class}, version = 1, exportSchema = false)
public abstract class LogDatabase extends RoomDatabase {

    public abstract LogDao logDao();

    private static volatile LogDatabase instance;

    public static LogDatabase get(Context context) {
        if (instance == null) {
            synchronized (LogDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                                    LogDatabase.class, "tvbox_log.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
