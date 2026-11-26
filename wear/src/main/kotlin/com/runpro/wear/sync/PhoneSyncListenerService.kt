package com.runpro.wear.sync

import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService

/** Woken by Play services when the phone app becomes reachable or goes away; starts a sync pass. */
class PhoneSyncListenerService : WearableListenerService() {
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.nodes.isNotEmpty()) PhoneSync.request(this)
    }
}
