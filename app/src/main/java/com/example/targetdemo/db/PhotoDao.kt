package com.example.targetdemo.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity)

    @Query("SELECT * FROM photos ORDER BY timestamp DESC")
    suspend fun getAll(): List<PhotoEntity>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Int)
}