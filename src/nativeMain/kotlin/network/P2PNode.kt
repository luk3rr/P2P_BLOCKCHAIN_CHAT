package network

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import model.*
import utils.Logger

/**
 * A classe principal que orquestra a rede P2P, gerenciando conexões,
 * mensagens e a interação com a blockchain.
 *
 * @param scope O CoroutineScope no qual as tarefas de rede serão executadas.
 * @param config As configurações de inicialização da aplicação.
 * @param blockchain A instância da blockchain para gerenciar o histórico de chats.
 * @param codec O codec para serializar e desserializar mensagens de rede.
 */
class P2PNode(
    private val scope: CoroutineScope,
    private val config: AppConfig,
    private val blockchain: Blockchain,
    private val codec: MessageCodec
) {
    private val activePeers = mutableMapOf<String, Socket>()
    private val peersLock = Mutex()
    private val logger = Logger("P2PNode")

    private val archiveResponses = mutableMapOf<String, List<Chat>>() // peerIp -> chain
    private val archiveResponsesLock = Mutex()

    /**
     * Ponto de entrada. Inicia o nó, conectando-se ao peer inicial (se houver)
     * e começando a ouvir por novas conexões e a descobrir outros peers.
     */
    fun start() {
        logger.info { "Nó P2P iniciando em ${config.hostIp}:${config.port}..." }

        scope.launch { listenForConnections() }
        scope.launch { sendPeriodicPeerRequests() }

        config.initialPeerIp?.let {
            scope.launch {
                connectToPeer(it)
            }
        }
    }

    /**
     * Ouve por conexões de entrada e lança uma coroutine para lidar com cada uma.
     */
    private suspend fun listenForConnections() = withContext(Dispatchers.IO) {
        val selectorManager = SelectorManager(Dispatchers.Default)
        val serverSocket = aSocket(selectorManager).tcp().bind(config.hostIp, config.port)
        logger.info { "Ouvindo por conexões em ${config.hostIp}:${config.port}" }

        while (true) {
            val socket = serverSocket.accept()
            val peerIp = (socket.remoteAddress as InetSocketAddress).hostname

            logger.info { "Conexão aceita de $peerIp" }

            scope.launch { handleConnection(socket) }
        }
    }

    /**
     * Tenta se conectar a um peer. Se bem-sucedido, inicia o tratamento da conexão.
     */
    private suspend fun connectToPeer(peerIp: String) {
        peersLock.withLock {
            if (peerIp == config.hostIp || activePeers.containsKey(peerIp)) {
                return
            }
        }

        try {
            val selectorManager = SelectorManager(Dispatchers.Default)
            val socket = aSocket(selectorManager).tcp().connect(peerIp, config.port)

            logger.info { "Conexão com $peerIp estabelecida com sucesso." }

            sendMessage(socket, ArchiveRequest)

            handleConnection(socket)
        } catch (e: Exception) {
            logger.error { "Falha ao conectar ao peer $peerIp: ${e.message}" }
        }
    }

    /**
     * Coração do nó. Fica em loop lendo e processando mensagens de um socket conectado.
     */
    private suspend fun handleConnection(socket: Socket) {
        val peerIp = (socket.remoteAddress as InetSocketAddress).hostname
        val readChannel = socket.openReadChannel()

        peersLock.withLock {
            activePeers[peerIp] = socket
        }

        try {
            while (true) {
                val message = codec.deserializeMessage(readChannel)

                if (message !is NotificationMessage) {
                    logger.info { "Mensagem recebida de $peerIp: ${message::class.simpleName}" }
                }

                when (message) {
                    is PeerRequest -> {
                        val peersToSend = peersLock.withLock { activePeers.keys.toList() }
                        sendMessage(socket, PeerList(peersToSend))
                    }

                    is PeerList -> {
                        message.peerIps.forEach { newPeerIp ->
                            scope.launch { connectToPeer(newPeerIp) }
                        }
                    }

                    is ArchiveRequest -> {
                        val history = blockchain.getChain()
                        sendMessage(socket, ArchiveResponse(history))
                    }

                    is ArchiveResponse -> {
                        blockchain.replaceChain(message.history)

                        archiveResponsesLock.withLock {
                            archiveResponses[peerIp] = message.history
                        }
                    }

                    is NotificationMessage -> {
                        //logger.info { "Notificação recebida: ${message.notification}" }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error { e.message.toString() }
        } finally {
            peersLock.withLock {
                activePeers.remove(peerIp)
            }
            socket.close()
            logger.info { "Conexão com $peerIp fechada." }
        }
    }

    /**
     * Envia uma mensagem para um socket específico.
     */
    private suspend fun sendMessage(socket: Socket, message: Message) {
        try {
            val bytes = codec.serialize(message)
            socket.openWriteChannel(autoFlush = true).writeAvailable(bytes)
            logger.info { "Mensagem ${message::class.simpleName} enviada para ${(socket.remoteAddress as InetSocketAddress).hostname}" }
        } catch (e: Exception) {
            logger.error {
                "Erro ao enviar mensagem ${message::class.simpleName} para ${(socket.remoteAddress as InetSocketAddress).hostname}: ${e.message}"
            }
        }
    }

    /**
     * Envia uma mensagem para todos os peers conectados.
     */
    private suspend fun broadcast(message: Message) {
        val bytes = codec.serialize(message)
        val peersToBroadcast = peersLock.withLock { activePeers.values.toList() }

        if (peersToBroadcast.isEmpty()) {
            logger.warn { "Nenhum peer conectado para transmitir a mensagem ${message::class.simpleName}." }
            return
        }

        logger.info { "Transmitindo ${message::class.simpleName} para ${peersToBroadcast.size} peer(s)." }

        peersToBroadcast.forEach { socket ->
            try {
                socket.openWriteChannel(autoFlush = true).writeAvailable(bytes)
            } catch (e: Exception) {
                logger.error {
                    "Erro ao enviar mensagem ${message::class.simpleName} para ${(socket.remoteAddress as InetSocketAddress).hostname}: ${e.message}"
                }
            }
        }
    }

    /**
     * Envia PeerRequests periodicamente para descobrir novos nós na rede.
     */
    private suspend fun sendPeriodicPeerRequests() {
        while (true) {
            delay(5000)
            broadcast(PeerRequest)
        }
    }

    /**
     * Orquestra a mineração e o envio de um novo chat para toda a rede.
     */
    suspend fun createAndBroadcastChat(messageText: String) {
        val newChat = blockchain.mineChat(messageText)
        logger.info { "Novo chat minerado: ${newChat.text}" }

        val updatedHistory = blockchain.getChain()

        repeat(10) { attempt ->
            broadcast(ArchiveResponse(updatedHistory))

            delay(1000)

            broadcast(ArchiveRequest)

            delay(2000)

            val confirmedCount = archiveResponsesLock.withLock {
                archiveResponses.values.count { chain ->
                    chain.contains(newChat)
                }
            }

            val totalPeers = peersLock.withLock { activePeers.size }
            val majorityReached = confirmedCount >= (totalPeers / 2 + 1)

            if (majorityReached) {
                logger.info { "Bloco aceito por $confirmedCount de $totalPeers peers." }
                return
            } else {
                logger.warn { "Bloco ainda não foi aceito pela maioria (${confirmedCount}/$totalPeers). Nova tentativa ($attempt)..." }
            }

            delay(1000)
        }

        logger.error { "Falha ao confirmar bloco na maioria dos peers após múltiplas tentativas." }
    }
}
