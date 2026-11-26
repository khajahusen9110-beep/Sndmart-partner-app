package com.example.data.local

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.DeliveryAssignment
import com.example.data.model.DeliveryEarning
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SnapshotCacheTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val cache = SnapshotCache(ApplicationProvider.getApplicationContext(), moshi)
    private val listType = Types.newParameterizedType(List::class.java, DeliveryAssignment::class.java)

    @Test fun savedListComesBackAndIsPerUser() {
        val a = DeliveryAssignment(id = "a1", orderId = "o1", status = "delivered")
        cache.save("u1", "assignments", listType, listOf(a))
        val back = cache.load<List<DeliveryAssignment>>("u1", "assignments", listType)
        assertEquals(listOf("a1"), back?.map { it.id })
        assertEquals("delivered", back?.first()?.status)
        assertNull(cache.load<List<DeliveryAssignment>>("u2", "assignments", listType))
    }

    @Test fun damagedFileIsIgnoredAndClearWipes() {
        cache.save("u1", "earnings", Types.newParameterizedType(List::class.java, DeliveryEarning::class.java), emptyList<DeliveryEarning>())
        java.io.File(java.io.File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "snapshots"), "u1_assignments.json").writeText("{ not json")
        assertNull(cache.load<List<DeliveryAssignment>>("u1", "assignments", listType))
        cache.clear()
        assertNull(cache.load<List<DeliveryEarning>>("u1", "earnings", Types.newParameterizedType(List::class.java, DeliveryEarning::class.java)))
    }
}
