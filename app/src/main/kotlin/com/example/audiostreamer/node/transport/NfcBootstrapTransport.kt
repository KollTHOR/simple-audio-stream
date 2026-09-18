package com.example.audiostreamer.node.transport

/**
 * Placeholder for Near-Field Communication (NFC) out-of-band bootstrap transport.
 *
 * Requirements:
 * - NFC is NOT an audio transport.
 * - It is a discovery/bootstrap mechanism.
 * - Do not implement NFC yet.
 */
class NfcBootstrapTransport : HatTransport {
    override val type: HatTransportType = HatTransportType.NFC_BOOTSTRAP
    override val isAvailable: Boolean = false
    override val state: TransportState = TransportState.UNAVAILABLE
    override val localAddress: TransportAddress? = null
    override val remoteAddress: TransportAddress? = null

    override fun connect(remote: TransportAddress): Result<Unit> =
        Result.failure(UnsupportedOperationException("NFC bootstrap is not implemented yet"))

    override fun listen(port: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("NFC bootstrap is not implemented yet"))

    override fun close() {}

    override fun getDiagnostics(): Map<String, Any?> = linkedMapOf(
        "type" to type.name,
        "isAudioTransport" to false,
        "isBootstrapOnly" to true,
        "isAvailable" to false,
        "state" to state.name,
        "role" to "DISCOVERY_BOOTSTRAP",
        "status" to "NOT_IMPLEMENTED"
    )
}
