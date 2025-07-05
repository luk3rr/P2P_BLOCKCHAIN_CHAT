package model

import kotlinx.serialization.Serializable

/**
 * Classe selada que representa todas as mensagens possíveis no protocolo da rede P2P.
 * O uso de 'sealed' garante que apenas os tipos definidos aqui são mensagens válidas.
 */
@Serializable
sealed class Message

/**
 * Mensagem para requisitar a lista de pares. [Código 0x1]
 */
@Serializable
object PeerRequest : Message()

/**
 * Mensagem com a lista de endereços IP dos pares. [Código 0x2]
 *
 * @param peerIps Lista com os endereços IP dos pares conhecidos.
 */
@Serializable
data class PeerList(val peerIps: List<String>) : Message()

/**
 * Mensagem para requisitar o histórico de chats (archive). [Código 0x3]
 */
@Serializable
object ArchiveRequest : Message()

/**
 * Mensagem com o histórico completo de chats. [Código 0x4]
 *
 * @param history A lista de chats que forma o histórico.
 */
@Serializable
data class ArchiveResponse(val history: List<Chat>) : Message()

/**
 * Mensagem de notificação de erro ou status. [Código 0x5]
 *
 * @param notification O texto da notificação.
 */
@Serializable
data class NotificationMessage(val notification: String) : Message()
