package com.company.supportvisit.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [VisitEntity::class, DraftEntity::class, ClientEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        // v2: clients table; visit type, parts, photos, technician signature and server sync fields.
        AutoMigration(from = 1, to = 2),
        // v3: billing (break, billable override, hourly rate snapshot, client hours allowance).
        AutoMigration(from = 2, to = 3)
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun visitDao(): VisitDao

    abstract fun draftDao(): DraftDao

    abstract fun clientDao(): ClientDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "support_visits.db").build()
    }
}
