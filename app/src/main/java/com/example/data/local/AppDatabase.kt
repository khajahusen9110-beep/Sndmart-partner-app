package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_assignments")
data class CachedAssignmentEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val status: String,
    val orderNumber: String,
    val storeName: String,
    val storeAddress: String,
    val storePhone: String,
    val storeLat: Double,
    val storeLng: Double,
    val customerName: String,
    val customerAddress: String,
    val customerPhone: String,
    val customerLat: Double,
    val customerLng: Double,
    val itemsSummary: String,
    val itemCount: Int,
    val totalAmount: Double,
    val paymentMode: String,
    val deliveryFee: Double,
    val estimatedPickupDistanceKm: Double,
    val estimatedDropDistanceKm: Double,
    val pickupPhotoUrl: String?,
    val deliveredPhotoUrl: String?,
    val batchId: String?,
    val batchCode: String?,
    val createdAt: String
)

@Entity(tableName = "cached_earnings")
data class CachedEarningEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val orderNumber: String,
    val deliveryFee: Double,
    val incentive: Double,
    val penalty: Double,
    val netEarning: Double,
    val status: String,
    val createdAt: String
)

@Entity(tableName = "cached_cash_ledger")
data class CachedCashLedgerEntity(
    @PrimaryKey val id: String,
    val entryType: String,
    val amount: Double,
    val orderId: String?,
    val orderNumber: String?,
    val notes: String?,
    val createdAt: String
)

@Dao
interface DeliveryDao {
    @Query("SELECT * FROM cached_assignments ORDER BY createdAt DESC")
    fun getAllAssignments(): Flow<List<CachedAssignmentEntity>>

    @Query("SELECT * FROM cached_assignments WHERE status IN ('accepted', 'arrived_pickup', 'picked_up', 'out_for_delivery', 'arrived_drop')")
    fun getActiveAssignments(): Flow<List<CachedAssignmentEntity>>

    @Query("SELECT * FROM cached_assignments WHERE id = :id LIMIT 1")
    suspend fun getAssignmentById(id: String): CachedAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignments(assignments: List<CachedAssignmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignment(assignment: CachedAssignmentEntity)

    @Query("DELETE FROM cached_assignments")
    suspend fun clearAssignments()

    // Earnings
    @Query("SELECT * FROM cached_earnings ORDER BY createdAt DESC")
    fun getAllEarnings(): Flow<List<CachedEarningEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEarnings(earnings: List<CachedEarningEntity>)

    // Cash Ledger
    @Query("SELECT * FROM cached_cash_ledger ORDER BY createdAt DESC")
    fun getAllLedgerEntries(): Flow<List<CachedCashLedgerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: CachedCashLedgerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntries(entries: List<CachedCashLedgerEntity>)
}

@Database(
    entities = [
        CachedAssignmentEntity::class,
        CachedEarningEntity::class,
        CachedCashLedgerEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deliveryDao(): DeliveryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sndmart_partner_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
