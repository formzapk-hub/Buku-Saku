package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val category: String,
    val imageUri: String? = null,
    val cloudId: String? = null,
    val authorEmail: String? = null,
    val isShared: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
