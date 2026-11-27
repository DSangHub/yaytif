package com.yaytif.app.data.local

import androidx.room.TypeConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class YaytifTypeConverters {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    @TypeConverter
    fun fromStringList(values: List<String>): String {
        return json.encodeToString(values)
    }

    @TypeConverter
    fun toStringList(serialized: String): List<String> {
        if (serialized.isBlank()) {
            return emptyList()
        }
        return runCatching {
            json.decodeFromString<List<String>>(serialized)
        }.getOrDefault(emptyList())
    }
}
