package com.example.service

/**
 * Legacy service alias pointing to DeliveryPartnerFcmService
 */
class SndmartFirebaseMessagingService : DeliveryPartnerFcmService() {
    companion object {
        const val CHANNEL_INCOMING_ORDERS = OrderAlertNotifier.CHANNEL_ALARM
        const val CHANNEL_DELIVERY_UPDATES = "delivery_updates"
    }
}

