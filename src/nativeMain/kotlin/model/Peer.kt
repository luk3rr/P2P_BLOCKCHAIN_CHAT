package model

import io.ktor.network.sockets.InetSocketAddress

/**
 * Representa um par (peer) na rede P2P.
 *
 * @param ipAddress O endereço IP do par.
 */
data class Peer(val ipAddress: String) {
    /**
     * Propriedade computada para obter o endereço de soquete,
     * útil para iniciar conexões.
     */
    fun getSocketAddress(port: Int): InetSocketAddress {
        return InetSocketAddress(ipAddress, port)
    }
}
