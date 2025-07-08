package network

import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import model.*
import utils.Logger

/**
 * Esta classe é responsável por serializar e desserializar mensagens
 * conforme o protocolo da rede P2P.
 *
 * Ela usa as ferramentas de I/O do Ktor, que são compatíveis com Kotlin/Native,
 * para garantir a correta manipulação de bytes e a ordem de bytes de rede (Big Endian).
 */
class MessageCodec {
    companion object {
        const val VERIFICATION_CODE_LENGTH: Int = 16
        const val MD5_HASH_LENGTH: Int = 16
        const val PEER_REQUEST_CODE: Byte = 0x1
        const val PEER_LIST_CODE: Byte = 0x2
        const val ARCHIVE_REQUEST_CODE: Byte = 0x3
        const val ARCHIVE_RESPONSE_CODE: Byte = 0x4
        const val NOTIFICATION_MESSAGE_CODE: Byte = 0x5
        const val MAX_NOTIFICATION_SIZE: Int = 255
        const val MAX_CHAT_SIZE: Int = 255
        const val IPV4_LENGTH: Int = 4

        val CHAR_ENCODING: Charset = Charsets.UTF_8
    }

    private val logger = Logger("MessageCodec")

    /**
     * Serializa um objeto Message para um ByteArray pronto para envio pela rede.
     */
    fun serialize(message: Message): ByteArray {
        val packet = buildPacket {
            when (message) {
                is PeerRequest -> writeByte(PEER_REQUEST_CODE)
                is ArchiveRequest -> writeByte(ARCHIVE_REQUEST_CODE)
                is PeerList -> {
                    writeByte(PEER_LIST_CODE)
                    writeInt(message.peerIps.size)
                    message.peerIps.forEach { ipString ->
                        val ipBytes = ipString.split('.').map { it.toInt().toByte() }.toByteArray()

                        if (ipBytes.size == IPV4_LENGTH) {
                            writeFully(ipBytes)
                        }
                    }
                }

                is ArchiveResponse -> {
                    writeByte(ARCHIVE_RESPONSE_CODE)
                    writeInt(message.history.size)
                    message.history.forEach { chat ->
                        writeFully(serialize(chat))
                    }
                }

                is NotificationMessage -> {
                    writeByte(NOTIFICATION_MESSAGE_CODE)
                    val textBytes = message.notification.toByteArray(CHAR_ENCODING)

                    require(textBytes.size <= MAX_NOTIFICATION_SIZE) {
                        "Tamanho da notificação em bytes excede o limite de 255"
                    }

                    writeByte(textBytes.size.toByte())
                    writeFully(textBytes)
                }
            }
        }
        return packet.readBytes()
    }

    /**
     * Serializa um único objeto Chat para um ByteArray.
     * Formato: [1 byte: tamanho N] + [N bytes: texto] + [16 bytes: código] + [16 bytes: hash]
     */
    fun serialize(chat: Chat): ByteArray {
        val packet = buildPacket {
            val textBytes = chat.text.toByteArray(CHAR_ENCODING)

            require(textBytes.size <= MAX_CHAT_SIZE) {
                "Tamanho do chat em bytes excede o limite de 255"
            }

            writeByte(textBytes.size.toByte())
            writeFully(textBytes)
            writeFully(chat.verificationCode)
            writeFully(chat.md5Hash)
        }
        return packet.readBytes()
    }

    /**
     * Desserializa bytes de um ByteReadChannel para um objeto Message.
     * Esta função lê o código da mensagem e direciona para o método de desserialização correto.
     */
    suspend fun deserializeMessage(byteReader: ByteReadChannel): Message {
        val messageCode = byteReader.readByte().toInt()

        return when (messageCode) {
            PEER_REQUEST_CODE.toInt() -> PeerRequest
            PEER_LIST_CODE.toInt() -> deserializePeerList(byteReader)
            ARCHIVE_REQUEST_CODE.toInt() -> ArchiveRequest
            ARCHIVE_RESPONSE_CODE.toInt() -> deserializeArchiveResponse(byteReader)
            NOTIFICATION_MESSAGE_CODE.toInt() -> deserializeNotificationMessage(byteReader)
            else -> throw IllegalArgumentException("Código de mensagem desconhecido: $messageCode")
        }
    }

    /**
     * Desserializa um único Chat a partir de um canal de leitura de bytes.
     */
    suspend fun deserializeChat(byteReader: ByteReadChannel): Chat {
        val textSize = byteReader.readByte().toInt() and 0xFF
        val textBytes = ByteArray(textSize).also { byteReader.readFully(it) }
        val verificationCode = ByteArray(VERIFICATION_CODE_LENGTH).also { byteReader.readFully(it) }
        val md5Hash = ByteArray(MD5_HASH_LENGTH).also { byteReader.readFully(it) }

        return Chat(
            text = textBytes.decodeToString(),
            verificationCode = verificationCode,
            md5Hash = md5Hash
        )
    }

    private suspend fun deserializePeerList(byteReader: ByteReadChannel): PeerList {
        val peerCount = byteReader.readInt() // Ktor lê em Big Endian por padrão
        val ips = List(peerCount) {
            val ipBytes = ByteArray(IPV4_LENGTH).also { byteReader.readFully(it) }
            ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        }
        return PeerList(ips)
    }

    private suspend fun deserializeArchiveResponse(byteReader: ByteReadChannel): ArchiveResponse {
        val chatCount = byteReader.readInt()
        val chats = List(chatCount) {
            deserializeChat(byteReader)
        }

        logger.debug { "Deserializando $chatCount chats do histórico." }

        return ArchiveResponse(chats)
    }

    private suspend fun deserializeNotificationMessage(byteReader: ByteReadChannel): NotificationMessage {
        val textSize = byteReader.readByte().toInt() and 0xFF
        val textBytes = ByteArray(textSize).also { byteReader.readFully(it) }
        return NotificationMessage(textBytes.decodeToString())
    }
}
