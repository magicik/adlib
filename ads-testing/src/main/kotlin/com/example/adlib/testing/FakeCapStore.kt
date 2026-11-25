package com.example.adlib.testing

import com.example.adlib.core.fullscreen.interstitial.CapSnapshot
import com.example.adlib.core.fullscreen.interstitial.CapStore

/** In-memory [CapStore]; share one instance between two caps objects to simulate a restart. [saved] records every save. */
class FakeCapStore(var current: CapSnapshot = CapSnapshot()) : CapStore {
    val saved = mutableListOf<CapSnapshot>()

    override fun load(): CapSnapshot = current

    override fun save(snapshot: CapSnapshot) {
        current = snapshot
        saved += snapshot
    }
}
