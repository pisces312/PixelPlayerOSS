package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

@Dao
interface FavoritesDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setFavorite(favorite: FavoritesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<FavoritesEntity>)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: Long)

    /**
     * 软删除：仅清除收藏标记，保留评分。
     * 取消收藏应调用此方法而非 removeFavorite——直接 DELETE 会连带丢掉评分。
     */
    @Query("UPDATE favorites SET isFavorite = 0 WHERE songId = :songId")
    suspend fun clearFavoriteFlag(songId: Long)

    /** 清除无意义行（既未收藏也未评分），避免表中残留空记录。 */
    @Query("DELETE FROM favorites WHERE songId = :songId AND isFavorite = 0 AND rating = 0")
    suspend fun purgeIfEmpty(songId: Long)

    @Query("SELECT isFavorite FROM favorites WHERE songId = :songId")
    suspend fun isFavorite(songId: Long): Boolean?

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    fun getFavoriteSongIdsRaw(): Flow<List<Long>>

    fun getFavoriteSongIds(): Flow<List<Long>> = getFavoriteSongIdsRaw().distinctUntilChanged()

    @Query("SELECT songId FROM favorites WHERE isFavorite = 1 ORDER BY songId")
    suspend fun getFavoriteSongIdsOnce(): List<Long>

    @Query("SELECT * FROM favorites")
    suspend fun getAllFavoritesOnce(): List<FavoritesEntity>

    @Query("SELECT rating FROM favorites WHERE songId = :songId")
    suspend fun getRating(songId: Long): Int?

    /**
     * 写入评分而不改变收藏状态：行不存在时插入一行（isFavorite=0），存在时只更新 rating，
     * 保留 isFavorite 与 timestamp。评分与收藏相互独立。
     */
    @Query(
        """
        INSERT INTO favorites (songId, isFavorite, timestamp, rating)
        VALUES (:songId, 0, :timestamp, :rating)
        ON CONFLICT(songId) DO UPDATE SET rating = excluded.rating
        """
    )
    suspend fun upsertRating(songId: Long, rating: Int, timestamp: Long)

    @Query("DELETE FROM favorites")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(favorites: List<FavoritesEntity>) {
        clearAll()
        if (favorites.isNotEmpty()) insertAll(favorites)
    }
}
