package com.binteum.core

/** Transient explicit selections only. No playback history or automatic selection is retained. */
class MediaSelectionQueue {
    @ConsistentCopyVisibility
    data class Request internal constructor(val epoch: Long, val sequence: Long, val videoId: String)

    private var epoch = 0L
    private var sequence = 0L
    private var documentReady = false
    private var pending: Request? = null
    private var inFlight: Request? = null

    fun select(videoId: String) {
        require(videoId.matches(Regex("[A-Za-z0-9_-]{11}")))
        pending = Request(epoch, ++sequence, videoId)
    }

    fun markDocumentReady() { documentReady = true }

    /** Claims one callback round-trip; further taps replace the pending selection. */
    fun claim(): Request? {
        if (!documentReady || inFlight != null) return null
        return pending?.also { inFlight = it }
    }

    /** Returns true only when a newer selection can be dispatched immediately. */
    fun acknowledge(request: Request, accepted: Boolean): Boolean {
        if (request.epoch != epoch || inFlight != request) return false
        inFlight = null
        if (accepted && pending == request) pending = null
        // A missing HTML function retries on the next ready/poll callback, not recursively.
        return pending != null && pending != request
    }

    /** A destroyed or unsafe surface must never replay a queued selection when reopened. */
    fun reset() {
        epoch++
        documentReady = false
        pending = null
        inFlight = null
    }
}
