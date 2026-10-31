package com.azdo.dashboard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Entity(tableName = "selections", primaryKeys = ["projectId", "definitionId"])
data class Selection(
    val projectId: String, val projectName: String, val definitionId: Int,
    val name: String, val categoryId: Long? = null
)
@Entity(tableName = "categories")
data class Category(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)
@Entity(tableName = "cache", primaryKeys = ["key", "part"])
data class Cache(val key: String, val json: String, val updated: Long, val part: Int = 0)

@Dao
abstract class DashboardDao {
    @Query("SELECT * FROM selections ORDER BY name") abstract fun selections(): Flow<List<Selection>>
    @Query("SELECT * FROM categories ORDER BY name") abstract fun categories(): Flow<List<Category>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putSelections(rows: List<Selection>)
    @Query("DELETE FROM selections WHERE projectId = :project") abstract suspend fun removeProjectSelections(project: String)
    @Transaction
    open suspend fun replaceProjectSelections(project: String, rows: List<Selection>) {
        removeProjectSelections(project)
        putSelections(rows)
    }
    @Query("UPDATE selections SET categoryId = :category WHERE projectId = :project AND definitionId = :definition")
    abstract suspend fun assign(project: String, definition: Int, category: Long?)
    @Insert abstract suspend fun addCategory(category: Category): Long
    @Query("UPDATE categories SET name = :name WHERE id = :id") abstract suspend fun renameCategory(id: Long, name: String)
    @Query("UPDATE selections SET categoryId = NULL WHERE categoryId = :id") abstract suspend fun unassignCategory(id: Long)
    @Query("DELETE FROM categories WHERE id = :id") abstract suspend fun removeCategory(id: Long)
    @Transaction open suspend fun deleteCategory(id: Long) { unassignCategory(id); removeCategory(id) }
    @Query("SELECT * FROM cache WHERE `key` = :key ORDER BY part") abstract suspend fun cacheParts(key: String): List<Cache>
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun putCacheParts(parts: List<Cache>)
    @Query("DELETE FROM cache WHERE `key` = :key") abstract suspend fun removeCache(key: String)
    @Transaction
    open suspend fun cache(key: String): Cache? {
        val parts = cacheParts(key)
        if (parts.isEmpty()) return null
        return Cache(key, parts.joinToString("") { it.json }, parts.first().updated)
    }
    @Transaction
    open suspend fun putCache(cache: Cache) {
        removeCache(cache.key)
        // Keep individual rows well below Android's CursorWindow limit.
        val parts = mutableListOf<Cache>()
        var offset = 0
        while (offset < cache.json.length) {
            var end = (offset + 128 * 1024).coerceAtMost(cache.json.length)
            if (end < cache.json.length && cache.json[end - 1].isHighSurrogate() &&
                cache.json[end].isLowSurrogate()) end--
            parts += cache.copy(json = cache.json.substring(offset, end), part = parts.size)
            offset = end
        }
        putCacheParts(parts)
    }
    @Query("DELETE FROM cache") abstract suspend fun clearCache()
    @Query("DELETE FROM selections") abstract suspend fun clearSelections()
    @Query("DELETE FROM categories") abstract suspend fun clearCategories()
    @Transaction open suspend fun clearAccount() { clearCache(); clearSelections(); clearCategories() }
}

@Database(entities = [Selection::class, Category::class, Cache::class], version = 1, exportSchema = false)
abstract class DashboardDatabase : RoomDatabase() {
    abstract fun dao(): DashboardDao
    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, DashboardDatabase::class.java, "dashboard.db").build()
    }
}

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private val alias = "azure-dashboard-account"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): Account? = try {
        val encrypted = prefs.getString("pat", null)
        val iv = prefs.getString("iv", null)
        val org = prefs.getString("organization", null)
        if (encrypted == null || iv == null || org == null) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            Account(org, String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        }
    } catch (_: Exception) { null }
    fun save(account: Account) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(account.pat.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("organization", account.organization)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("pat", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}
