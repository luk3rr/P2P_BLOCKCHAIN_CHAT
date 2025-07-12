# P2P Blockchain Chat

## Descrição

Este projeto implementa um sistema de chat P2P (par-a-par) em Kotlin Native. A integridade e a ordem das mensagens são garantidas por uma estrutura de blockchain distribuída, onde novos chats são adicionados através de um mecanismo de mineração (Proof-of-Work).

Este programa é o produto de um trabalho prático da disciplina de Redes de Computadores do [Departamento de Ciência da Computação da UFMG](https://dcc.ufmg.br).

O nó P2P oferece:

* Descoberta de pares e formação de rede descentralizada.
* Sincronização de um histórico de chats distribuído (blockchain).
* Validação da integridade de todo o histórico recebido da rede.
* Mineração (Proof-of-Work com MD5) para adicionar novos chats à blockchain.
* Comunicação via TCP em uma porta fixa, utilizando um protocolo binário customizado.
* Gerenciamento de múltiplas conexões de forma assíncrona com Kotlin Coroutines e Ktor.

## Requisitos

* Um ambiente Linux para compilação e execução.
* Gradle para realizar o build do projeto.
* (Para testes locais) Permissões `sudo` para configuração de endereços de rede na interface de loopback.
* Acesso à internet em uma rede sem CGNAT (com possibilidade de encaminhamento de porta) para participação plena na rede principal.

## Uso

O programa é um executável de linha de comando que representa um único nó na rede. Para criar uma rede, múltiplas instâncias são executadas e conectadas entre si.

### Compilação
Para compilar o projeto, execute o seguinte comando na raiz do repositório:

```bash
./gradlew build
```

O executável será gerado em `build/bin/native/releaseExecutable/p2pblockchainchat.kexe`.

### Formato do Comando de Execução

```bash
./p2pblockchainchat.kexe [opções]
```

#### Parâmetros

##### --host-ip=\<ip>
- Descrição: O endereço IP no qual o nó deve ouvir por conexões.
- Padrão: 0.0.0.0 (ouve em todas as interfaces de rede).

##### --port=\<porta>
- Descrição: A porta de rede para toda a comunicação TCP.
- Padrão: 51511.

##### --id=\<identificador>
- Descrição: Uma string que identifica seu grupo ou nó. Obrigatório para o modo de operação normal (não-servidor).
- Padrão: P2P-Node.

##### --peer=\<ip_peer>
- Descrição: (Opcional) O endereço IP de um nó já na rede para se conectar e iniciar o processo de descoberta.
- Padrão: Nenhum.

##### --advertised-ip=<ip_publico>
- Descrição: (Opcional) O endereço IP "público" do nó, usado para evitar que ele se conecte a si mesmo em ambientes com NAT ou Load Balancers (como Kubernetes).
- Padrão: Nenhum.

##### --server
- Descrição: (Flag) Quando presente, executa o nó em modo servidor: sem interação via terminal para o usuário e com logs sendo impressos diretamente na console.
- Padrão: Desativado.

-----

### Exemplos de Uso

#### Teste Local (Múltiplos Nós na Mesma Máquina)

Para simular uma rede localmente, você pode adicionar IPs extras à sua interface de loopback e abrir um terminal para cada nó.

**1. (Opcional, em Linux) Adicione IPs:**

```bash
sudo ip addr add 127.0.0.2/8 dev lo
sudo ip addr add 127.0.0.3/8 dev lo
```

**2. Inicie o Nó Semente (Terminal 1):**
Este nó não se conecta a ninguém, apenas espera por conexões.

```bash
./p2pblockchainchat.kexe --id="Grupo-Alfa"
```

**3. Inicie o Segundo Nó (Terminal 2):**
Este nó se conecta ao nó semente para entrar na rede.

```bash
./p2pblockchainchat.kexe --host-ip=127.0.0.2 --id="Grupo-Beta" --peer=127.0.0.1
```

**3. Inicie o Terceiro Nó (Terminal 3):**
Este nó se conecta ao nó semente para entrar na rede.

```bash
./p2pblockchainchat.kexe --host-ip=127.0.0.3 --id="Grupo-Gama" --peer=127.0.0.1
```

#### Execução em Servidor / Kubernetes
Este comando executa o nó em modo "servidor", ideal para um nó semente ou um nó rodando em um ambiente como o k3s.

```bash
./p2pblockchainchat.kexe --server --advertised-ip=<IP_EXTERNO_DO_SERVICE>
```

---

### Interação
Após o nó iniciar e se conectar à rede:

* **Para enviar uma mensagem:** Simplesmente digite a mensagem e pressione Enter. O processo de mineração será iniciado.
* **Para ver o histórico local:** Digite `/h` e pressione Enter.

## Funcionamento Interno

* **Comunicação:** A troca de mensagens entre os nós é feita via TCP, na porta `51511`.
* **Protocolo:** Utiliza um protocolo binário customizado para os 5 tipos de mensagens (`PeerRequest`, `PeerList`, `ArchiveRequest`, `ArchiveResponse`, `NotificationMessage`).
* **Concorrência:** Múltiplas conexões e tarefas de fundo são gerenciadas de forma não-bloqueante usando Kotlin Coroutines e a biblioteca de I/O do Ktor.
* **Blockchain:** A integridade do histórico é garantida por uma cadeia de hashes MD5. Um novo bloco só é adicionado após a resolução de um desafio de Proof-of-Work (encontrar um hash com prefixo `00`).
* **Descoberta:** Os nós enviam mensagens `PeerRequest` periodicamente aos seus vizinhos para manter uma visão atualizada da topologia da rede.

## Logs e Monitoramento

* Os logs da aplicação são salvos no arquivo `log/blockchain.log`.
* O arquivo de log registra eventos importantes como conexões, mensagens recebidas/enviadas, erros de validação e o progresso da mineração.