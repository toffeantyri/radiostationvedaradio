package ru.music.radiostationvedaradio

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.music.radiostationvedaradio.busines.SharedPreferenceProvider
import ru.music.radiostationvedaradio.busines.database.room.AntiHoroDatabase

class App : Application() {

    companion object {
        lateinit var db: RoomDatabase
    }

    override fun onCreate() {
        super.onCreate()

        SharedPreferenceProvider.getSharedPreferences(this)

        db = Room.databaseBuilder(this, AntiHoroDatabase::class.java, "AntiHoroDataBase")
            .fallbackToDestructiveMigration()
            .build()

    }

}