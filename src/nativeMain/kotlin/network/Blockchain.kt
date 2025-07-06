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
    }

    /**
     * Retorna uma cópia segura da cadeia de chats atual.
     */
    suspend fun getChain(): List<Chat> {
        return lock.withLock { chain.toList() }
    }

    /**
     * Adiciona um único chat à cadeia. Esta função não faz validação,
     * assumindo que o chat já foi validado ou minerado.
     */
    suspend fun addChat(chat: Chat) {
        lock.withLock {
            chain.add(chat)
        }
    }

    /**
     * Substitui a cadeia local por um novo histórico, se o novo for mais longo e válido.
     *
     * @return True se a cadeia foi substituída, False caso contrário.
     */
    suspend fun replaceChain(newHistory: List<Chat>): Boolean {
        lock.withLock {
            if (newHistory.size <= chain.size) {
                logger.info { "Histórico recebido não é mais longo que o atual. Ignorando." }
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

    /**
     * Verifica a integridade de um histórico completo de chats.
     * Itera por cada chat e valida seu hash em relação aos chats anteriores.
     */
    fun verifyHistory(history: List<Chat>): Boolean {
        if (history.isEmpty()) return true

        history.forEachIndexed { i, _ ->
            val subHistory = history.subList(0, i + 1)
            val lastChat = subHistory.last()

            // 1. O hash do último chat deve começar com dois bytes zero.
            if (lastChat.md5Hash[0] != 0.toByte() || lastChat.md5Hash[1] != 0.toByte()) {
                logger.error { "Erro de validação no chat #${i}: O hash não começa com dois bytes zero." }
                logger.debug { "Hash recebido: ${lastChat.md5Hash.joinToString(", ") { it.toString() }}" }
                return false
            }

            // 2. O hash do último chat deve ser igual ao hash calculado sobre a sequência S.
            val startIndex = max(0, subHistory.size - VALIDATION_WINDOW_SIZE)
            val chatsToHash = subHistory.subList(startIndex, subHistory.size)

            val expectedHash = calculateMd5ForValidation(chatsToHash)

            if (!lastChat.md5Hash.contentEquals(expectedHash)) {
                logger.error { "Erro de validação no chat #${i}: O hash MD5 não corresponde ao conteúdo." }
                logger.debug { "Hash esperado: ${expectedHash.joinToString(", ") { it.toString() }}" }
                logger.debug { "Hash recebido: ${lastChat.md5Hash.joinToString(", ") { it.toString() }}" }
                return false
            }
        }

        logger.info { "Histórico verificado com sucesso." }
        return true
    }

    /**
     * Minera um novo chat para ser adicionado à cadeia.
     * Fica em loop gerando códigos de verificação até encontrar um que produza um hash válido.
     */
    suspend fun mineChat(messageText: String): Chat = withContext(Dispatchers.Default) {
        while (true) {
            val currentChain = getChain()
            val startIndex = max(0, currentChain.size - VALIDATION_WINDOW_SIZE)
            val previousChats = currentChain.subList(startIndex, currentChain.size)

            logger.info { "Iniciando mineração com base em cadeia de tamanho ${currentChain.size}..." }

            val minedChat = mineChatWithPrevious(previousChats, messageText)

            val wasAdded = lock.withLock {
                if (chain.size == currentChain.size && chain == currentChain) {
                    chain.add(minedChat)
                    logger.info { "Chat adicionado com sucesso à cadeia local!" }
                    true
                } else {
                    logger.warn { "A cadeia local foi alterada durante a mineração. Reiniciando processo." }
                    false
                }
            }

            if (wasAdded) return@withContext minedChat
        }

        @Suppress("UNREACHABLE_CODE")
        error("Erro inesperado durante a mineração do chat.")
    }

    /**
     * Helper que calcula o hash MD5 para uma lista de chats, conforme as regras de validação.
     * A sequência S é formada pelos bytes de todos os `chatsToHash`, exceto os
     * últimos 16 bytes (o campo hash) do último chat na lista.
     */
    @OptIn(InternalAPI::class)
    private fun calculateMd5ForValidation(chatsToHash: List<Chat>): ByteArray {
        val dataToHash = buildPacket {
            chatsToHash.forEachIndexed { index, chat ->
                val serializedChat = messageCodec.serialize(chat)

                require(serializedChat.size >= MessageCodec.MD5_HASH_LENGTH) { "Chat serializado é muito pequeno para conter um hash MD5." }

                if (index == chatsToHash.lastIndex) {
                    writeFully(serializedChat, 0, serializedChat.size - MessageCodec.MD5_HASH_LENGTH)
                } else {
                    writeFully(serializedChat)
                }
            }
        }.readBytes()

        return MD5().digest(dataToHash)
    }


    private suspend fun mineChatWithPrevious(
        previousChats: List<Chat>,
        messageText: String
    ): Chat = withContext(Dispatchers.Default) {
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
            if (attempts % 1_000_000 == 0L) {
                logger.info { "...ainda minerando... ($attempts tentativas)" }
            }
        }

        @Suppress("UNREACHABLE_CODE")
        error("Erro inesperado durante a mineração do chat.")
    }
}
