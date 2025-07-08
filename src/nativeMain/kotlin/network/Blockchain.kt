package network

import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import model.Chat
import org.kotlincrypto.hash.md.MD5
import utils.Logger
import kotlin.math.max
import kotlin.random.Random

/**
 * Gerencia a cadeia de chats (blockchain).
 * Esta classe é thread-safe, usando um Mutex para proteger o acesso à cadeia.
 *
 * @param messageCodec Uma instância do codec para serializar chats antes de hasheá-los.
 */
class Blockchain(private val messageCodec: MessageCodec) {

    private val chain = mutableListOf<Chat>()
    private val lock = Mutex()
    private val logger = Logger("Blockchain")

    companion object {
        private const val VALIDATION_WINDOW_SIZE = 20
        private const val MINING_PREVIOUS_CHATS_COUNT = 19
    }

    suspend fun getChain(): List<Chat> {
        return lock.withLock { chain.toList() }
    }

    suspend fun replaceChain(newHistory: List<Chat>): Boolean {
        lock.withLock {
            if (newHistory.size <= chain.size) {
                if (newHistory.size < chain.size) {
                    logger.info { "Histórico recebido é menor que o atual. Ignorando." }
                }
                return false
            }
            if (!verifyHistory(newHistory)) {
                logger.error { "Histórico recebido é inválido. Ignorando." }
                return false
            }
            logger.info { "Substituindo a cadeia local com um novo histórico de ${newHistory.size} chats." }
            chain.clear()
            chain.addAll(newHistory)
            return true
        }
    }

    fun verifyHistory(history: List<Chat>): Boolean {
        if (history.isEmpty()) return true

        history.forEachIndexed { i, _ ->
            val subHistory = history.subList(0, i + 1)
            val lastChat = subHistory.last()

            if (lastChat.md5Hash[0] != 0.toByte() || lastChat.md5Hash[1] != 0.toByte()) {
                logger.error { "Erro de validação no chat #${i}: O hash não começa com dois bytes zero." }
                logger.debug { "Hash recebido: ${lastChat.md5Hash.joinToString()}" }
                return false
            }

            val startIndex = max(0, subHistory.size - VALIDATION_WINDOW_SIZE)
            val chatsToHash = subHistory.subList(startIndex, subHistory.size)
            val expectedHash = calculateMd5ForValidation(chatsToHash)

            if (!lastChat.md5Hash.contentEquals(expectedHash)) {
                logger.error { "Erro de validação no chat #${i}: O hash MD5 não corresponde ao conteúdo." }
                logger.debug { "Hash esperado: ${expectedHash.joinToString()}" }
                logger.debug { "Hash recebido: ${lastChat.md5Hash.joinToString()}" }
                return false
            }
        }
        logger.info { "Histórico verificado com sucesso." }
        return true
    }

    suspend fun mineChat(messageText: String): Chat {
        while (true) {
            val currentChain = getChain()
            logger.info { "Iniciando mineração com base em cadeia de tamanho ${currentChain.size}..." }

            val minedChat = mineChatWorker(currentChain, messageText)

            val wasAdded = lock.withLock {
                if (chain.size == currentChain.size && chain.contentEquals(currentChain)) {
                    chain.add(minedChat)
                    logger.info { "Chat minerado adicionado com sucesso à cadeia local!" }
                    true
                } else {
                    logger.warn { "A cadeia local foi alterada durante a mineração. Reiniciando processo." }
                    false
                }
            }
            if (wasAdded) return minedChat
        }
    }

    private suspend fun mineChatWorker(
        currentChain: List<Chat>,
        messageText: String
    ): Chat = withContext(Dispatchers.Default) {
        val startIndex = max(0, currentChain.size - MINING_PREVIOUS_CHATS_COUNT)
        val previousChats = currentChain.subList(startIndex, currentChain.size)

        var attempts = 0L
        while (true) {
            val verificationCode = Random.nextBytes(MessageCodec.VERIFICATION_CODE_LENGTH)
            val tempChat = Chat(messageText, verificationCode, ByteArray(MessageCodec.MD5_HASH_LENGTH))
            val chatsToHash = previousChats + tempChat

            val calculatedHash = calculateMd5ForValidation(chatsToHash)

            if (calculatedHash[0] == 0.toByte() && calculatedHash[1] == 0.toByte()) {
                logger.info { "Mineração bem-sucedida após $attempts tentativas!" }
                return@withContext Chat(messageText, verificationCode, calculatedHash)
            }

            attempts++
            if (attempts > 0 && attempts % 2_000_000 == 0L) {
                logger.info { "...ainda minerando... ($attempts tentativas)" }
            }
        }

        @Suppress("UNREACHABLE_CODE")
        error("Erro inesperado durante a mineração do chat.")
    }


    @OptIn(InternalAPI::class)
    private fun calculateMd5ForValidation(chatsToHash: List<Chat>): ByteArray {
        val dataToHash = buildPacket {
            chatsToHash.forEachIndexed { index, chat ->
                val serializedChat = messageCodec.serialize(chat)
                if (index == chatsToHash.lastIndex) {
                    writeFully(serializedChat, 0, serializedChat.size - MessageCodec.MD5_HASH_LENGTH)
                } else {
                    writeFully(serializedChat)
                }
            }
        }.readBytes()

        return MD5().digest(dataToHash)
    }
}

private fun <T> List<T>.contentEquals(other: List<T>): Boolean {
    if (this.size != other.size) return false
    for (i in this.indices) {
        if (this[i] != other[i]) return false
    }
    return true
}
