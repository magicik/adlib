package com.example.adlib.testing

import com.example.adlib.core.testmode.RevenueSnapshot
import com.example.adlib.core.testmode.RevenueStore

/** In-memory [RevenueStore]; [saved] records every save. */
class FakeRevenueStore(var current: RevenueSnapshot = RevenueSnapshot()) : RevenueStore {
    val saved = mutableListOf<RevenueSnapshot>()

    override fun load(): RevenueSnapshot = current

    override fun save(snapshot: RevenueSnapshot) {
        current = snapshot
        saved += snapshot
    }
}
