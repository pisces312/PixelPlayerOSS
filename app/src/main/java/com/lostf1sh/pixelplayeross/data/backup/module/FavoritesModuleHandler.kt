package com.lostf1sh.pixelplayeross.data.backup.module

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lostf1sh.pixelplayeross.data.backup.model.BackupSection
import com.lostf1sh.pixelplayeross.data.database.FavoritesDao
import com.lostf1sh.pixelplayeross.data.database.FavoritesEntity
import com.lostf1sh.pixelplayeross.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesModuleHandler @Inject constructor(
    private val favoritesDao: FavoritesDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.FAVORITES

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(favoritesDao.getAllFavoritesOnce())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        favoritesDao.getAllFavoritesOnce().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        // Parse field-by-field so a missing `isFavorite` (legacy rows that only stored song_id)
        // becomes true instead of Gson's JVM default false — those rows were favorites.
        val array = JsonParser.parseString(payload).asJsonArray
        val favorites = array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val songId = readLong(obj, "songId", "song_id") ?: return@mapNotNull null
            FavoritesEntity(
                songId = songId,
                isFavorite = readBoolean(obj, "isFavorite", "is_favorite") ?: true,
                timestamp = readLong(obj, "timestamp", "addedAt", "added_at")
                    ?: System.currentTimeMillis(),
                rating = readInt(obj, "rating") ?: 0,
            )
        }
        favoritesDao.replaceAll(favorites)
    }

    private fun readBoolean(obj: JsonObject, vararg keys: String): Boolean? {
        return keys.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive }
            .firstOrNull()
            ?.let { primitive ->
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isString -> primitive.asString.toBooleanStrictOrNull()
                    else -> null
                }
            }
    }

    private fun readLong(obj: JsonObject, vararg keys: String): Long? {
        return keys.asSequence()
            .mapNotNull { key -> obj.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive }
            .firstOrNull()
            ?.let { primitive ->
                when {
                    primitive.isNumber -> primitive.asNumber.toLong()
                    primitive.isString -> primitive.asString.toLongOrNull()
                    else -> null
                }
            }
    }

    private fun readInt(obj: JsonObject, vararg keys: String): Int? =
        readLong(obj, *keys)?.toInt()

    override suspend fun rollback(snapshot: String) = restore(snapshot)
}
