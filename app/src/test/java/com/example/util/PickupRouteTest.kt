package com.example.util

import com.example.data.model.DeliveryAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupRouteTest {
    private fun shop(id: String, lat: Double, lng: Double) = DeliveryAssignment(id = id, storeName = id, storeLat = lat, storeLng = lng)

    @Test fun nearestShopFirstThenTheNextNearest() {
        val a = shop("A", 0.0, 0.10)
        val b = shop("B", 0.0, 0.05)
        val c = shop("C", 0.0, 0.20)
        val route = planPickupRoute(0.0, 0.0, listOf(a, c, b))
        assertEquals(listOf("B", "A", "C"), route.map { it.assignment.id })
        assertTrue(route[0].kmFromPrevious < route[2].kmFromPrevious + route[1].kmFromPrevious)
    }

    @Test fun routeStartsFromTheRidersOwnPlace() {
        val west = shop("W", 0.0, -0.05)
        val east = shop("E", 0.0, 0.30)
        assertEquals("W", planPickupRoute(0.0, -0.2, listOf(east, west)).first().assignment.id)
        assertEquals("E", planPickupRoute(0.0, 0.4, listOf(east, west)).first().assignment.id)
    }

    @Test fun nothingToCollectGivesAnEmptyRoute() {
        assertTrue(planPickupRoute(0.0, 0.0, emptyList()).isEmpty())
    }

    @Test fun pickedUpShopsAreRecognised() {
        assertTrue(isPickedUp(DeliveryAssignment(id = "x", status = "picked_up")))
        assertTrue(!isPickedUp(DeliveryAssignment(id = "x", status = "accepted")))
    }
}
