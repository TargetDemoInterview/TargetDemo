package com.example.targetdemo.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val imagePath: String,
    val location: String?,
    val deviceName: String,
    val timestamp: Long
)