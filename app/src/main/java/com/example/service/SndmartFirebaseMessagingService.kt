package com.example.service

/**
 * Legacy service alias pointing to DeliveryPartnerFcmService
 */
class SndmartFirebaseMessagingService : DeliveryPartnerFcmService() {
    companion object {
        const val CHANNEL_INCOMING_ORDERS = "new_delivery_alerts"
        const val CHANNEL_DELIVERY_UPDATES = "delivery_updates"
    }
}

