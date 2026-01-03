package com.caleb.scheduledplayer.data.database;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.caleb.scheduledplayer.data.dao.TaskDao;
import com.caleb.scheduledplayer.data.dao.TaskLogDao;
import com.caleb.scheduledplayer.data.entity.TaskEntity;
import com.caleb.scheduledplayer.data.entity.TaskLogEntity;

/**
 * Room 数据库
 */
@Database(
        entities = {TaskEntity.class, TaskLogEntity.class},
        version = 7,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "noise_retaliation.db";
    private static volatile AppDatabase instance;

    /**
     * 获取任务 DAO
     */
    public abstract TaskDao taskDao();

    /**
     * 获取任务日志 DAO
     */
    public abstract TaskLogDao taskLogDao();

    /**
     * 数据库迁移：版本 1 -> 2（添加 task_logs 表）
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // 创建 task_logs 表
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_logs` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`task_id` INTEGER NOT NULL, " +
                    "`start_time` INTEGER NOT NULL, " +
                    "`end_time` INTEGER, " +
                    "`status` INTEGER NOT NULL, " +
                    "`played_files` TEXT, " +
                    "`error_type` INTEGER, " +
                    "`error_message` TEXT, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`task_id`) REFERENCES `tasks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            );
            // 创建索引
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_task_logs_task_id` ON `task_logs` (`task_id`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_task_logs_start_time` ON `task_logs` (`start_time`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_task_logs_created_at` ON `task_logs` (`created_at`)");
        }
    };

    /**
     * 数据库迁移：版本 2 -> 3（将 duration_minutes 改为 end_time）
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // 添加 end_time 列
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `end_time` TEXT NOT NULL DEFAULT '00:00'");
            // 根据 start_time 和 duration_minutes 计算 end_time
            // 由于 SQLite 限制，这里设置默认值，用户需要重新编辑任务设置结束时间
            // 删除 duration_minutes 列（SQLite 不支持直接删除列，需要重建表）
            // 创建新表
            database.execSQL(
                    "CREATE TABLE `tasks_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`start_time` TEXT NOT NULL, " +
                    "`end_time` TEXT NOT NULL, " +
                    "`audio_paths` TEXT, " +
                    "`play_mode` INTEGER NOT NULL, " +
                    "`volume` INTEGER NOT NULL, " +
                    "`repeat_days` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            );
            // 复制数据，将 duration_minutes 转换为 end_time（简单处理：设置为 start_time 后1小时）
            database.execSQL(
                    "INSERT INTO `tasks_new` (`id`, `name`, `enabled`, `start_time`, `end_time`, `audio_paths`, `play_mode`, `volume`, `repeat_days`, `created_at`, `updated_at`) " +
                    "SELECT `id`, `name`, `enabled`, `start_time`, " +
                    "printf('%02d:%02d', (CAST(substr(`start_time`, 1, 2) AS INTEGER) + `duration_minutes` / 60) % 24, " +
                    "(CAST(substr(`start_time`, 4, 2) AS INTEGER) + `duration_minutes` % 60) % 60), " +
                    "`audio_paths`, `play_mode`, `volume`, `repeat_days`, `created_at`, `updated_at` FROM `tasks`"
            );
            // 删除旧表
            database.execSQL("DROP TABLE `tasks`");
            // 重命名新表
            database.execSQL("ALTER TABLE `tasks_new` RENAME TO `tasks`");
        }
    };

    /**
     * 数据库迁移：版本 3 -> 4（添加 output_device 列）
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `output_device` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * 数据库迁移：版本 4 -> 5（添加 all_day_play 列）
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `all_day_play` INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * 数据库迁移：版本 5 -> 6（添加随机暂停相关列）
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `random_pause_enabled` INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `min_pause_minutes` INTEGER NOT NULL DEFAULT 2");
            database.execSQL("ALTER TABLE `tasks` ADD COLUMN `max_pause_minutes` INTEGER NOT NULL DEFAULT 6");
        }
    };

    /**
     * 数据库迁移：版本 6 -> 7（删除随机暂停相关列）
     */
    static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            // SQLite 不支持直接删除列，需要重建表
            database.execSQL(
                    "CREATE TABLE `tasks_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, " +
                    "`start_time` TEXT NOT NULL, " +
                    "`end_time` TEXT NOT NULL, " +
                    "`audio_paths` TEXT, " +
                    "`play_mode` INTEGER NOT NULL, " +
                    "`volume` INTEGER NOT NULL, " +
                    "`repeat_days` INTEGER NOT NULL, " +
                    "`output_device` INTEGER NOT NULL DEFAULT 0, " +
                    "`all_day_play` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            );
            // 复制数据（不包含随机暂停字段）
            database.execSQL(
                    "INSERT INTO `tasks_new` (`id`, `name`, `enabled`, `start_time`, `end_time`, `audio_paths`, " +
                    "`play_mode`, `volume`, `repeat_days`, `output_device`, `all_day_play`, `created_at`, `updated_at`) " +
                    "SELECT `id`, `name`, `enabled`, `start_time`, `end_time`, `audio_paths`, " +
                    "`play_mode`, `volume`, `repeat_days`, `output_device`, `all_day_play`, `created_at`, `updated_at` FROM `tasks`"
            );
            // 删除旧表
            database.execSQL("DROP TABLE `tasks`");
            // 重命名新表
            database.execSQL("ALTER TABLE `tasks_new` RENAME TO `tasks`");
        }
    };

    /**
     * 获取数据库单例
     */
    public static AppDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            DATABASE_NAME
                    )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return instance;
    }
}
