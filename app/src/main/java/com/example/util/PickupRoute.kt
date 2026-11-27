package com.example.util

import com.example.data.model.DeliveryAssignment
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance in km between two points on the earth. */
fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6371.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(a))
}

/** One stop of a rider's trip with how far it is from the point before it. */
data class RouteStop(val assignment: DeliveryAssignment, val kmFromPrevious: Double)

/**
 * The order to collect a customer's several shops in: the rider goes to the NEAREST shop first, then to the shop nearest to that one,
 * and so on; the customer comes last. [start] is where the rider is now. Shops already picked up are not part of the route.
 */
fun planPickupRoute(startLat: Double, startLng: Double, pickups: List<DeliveryAssignment>): List<RouteStop> {
    val left = pickups.toMutableList()
    val out = ArrayList<RouteStop>(left.size)
    var lat = startLat
    var lng = startLng
    while (left.isNotEmpty()) {
        val next = left.minByOrNull { distanceKm(lat, lng, it.storeLat, it.storeLng) }!!
        out += RouteStop(next, distanceKm(lat, lng, next.storeLat, next.storeLng))
        lat = next.storeLat; lng = next.storeLng
        left.remove(next)
    }
    return out
}

/** Whether the rider already has this shop's order in hand. */
fun isPickedUp(a: DeliveryAssignment) = a.status in setOf("picked_up", "out_for_delivery", "arrived_drop", "delivered")
