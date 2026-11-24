package com.example.adlib.testing

import com.example.adlib.core.gateway.AdFormat
import com.example.adlib.core.gateway.NativeHandle
import com.example.adlib.core.nativead.NativeAdSource
import com.example.adlib.core.placement.Placement
import kotlinx.coroutines.flow.MutableStateFlow

/** Manually driven [NativeAdSource]: requests wait in [pending] until the test answers them. */
class FakeNativeAdSource(premium: Boolean = false) : NativeAdSource {
    override val isPremium = MutableStateFlow(premium)

    class Request(val placement: Placement, private val onResult: (NativeHandle?) -> Unit) {
        fun answer(ad: NativeHandle?) = onResult(ad)
    }

    val pending = ArrayDeque<Request>()
    var requestCount = 0
        private set
    val released = mutableListOf<NativeHandle>()

    override fun request(placement: Placement, onResult: (NativeHandle?) -> Unit) {
        requestCount++
        pending.addLast(Request(placement, onResult))
    }

    override fun release(handle: NativeHandle) {
        released += handle
        handle.destroy()
    }

    /** Answers the oldest pending request with a fresh ad (or [ad]); returns what was delivered. */
    fun answerNext(ad: NativeHandle? = newAd()): NativeHandle? {
        pending.removeFirst().answer(ad)
        return ad
    }

    fun newAd(): FakeAdHandle = FakeAdHandle("native", AdFormat.NATIVE, 0L)
}
