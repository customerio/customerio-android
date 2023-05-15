package io.customer.android.sample.kotlin_compose.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class User(
    @PrimaryKey val email: String,
    @ColumnInfo(name = "name") val name: String?,
    @ColumnInfo(name = "is_guest") val isGuest: Boolean = false
)
