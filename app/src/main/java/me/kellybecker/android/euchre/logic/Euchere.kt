package me.kellybecker.android.euchre.logic

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import java.net.URI

var trump: String = ""

/**
 * Check if suit is a bower suit
 */
fun isBowerSuit(suit: String): Boolean {
    val black = listOf("♠", "♣")
    val red = listOf("♥", "♦")
    return (suit in black && trump in black) 
        || (suit in red && trump in red)
}

/**
 * Check if suit qualifies as a bower
 */
fun isBower(suit: String): String {
    return if(isBowerSuit(suit)) {
        if(suit == trump) { "J1" } else { "J2" }
    } else {
        "J"
    }
}

val scoreOrder: List<String> = listOf(
    "T", "J1", "J2", "A", "K", "Q", "J", "10", "9",
    "AA", "AK", "AQ", "AJ", "A10", "A9",
)

/**
 * Weights for ordering the cards by scoring values
 */
fun scoreOrderIndex(card: Card, reverse: Boolean = false): Int {
    val tmpScoreOrder: List<String> = if(reverse) {
        scoreOrder.asReversed()
    } else {
        scoreOrder.toList()
    }

    var scoreCard: String = ""
    val score = if(trump != "" && card.card == "J" && isBowerSuit(card.suit)) {
        scoreCard = isBower(card.suit)
        tmpScoreOrder.indexOf(isBower(card.suit))
    } else {
        if(trump != "" && card.card != "T" && trump != card.suit) {
            scoreCard = "A${card.card}"
            tmpScoreOrder.indexOf("A${card.card}")
        } else {
            scoreCard = card.card
            tmpScoreOrder.indexOf(card.card)
        }
    }

    Log.d("EUCHRE", "$card ($scoreCard): $score")
    return score
}

@Serializable
data class WSData(
    val methodID: String,
    val playerID: Int = -1,
    val playerAlt: Int = -1,
    val roomID: String = "",
    val loopback: Boolean = false,
    val relayed: Boolean = false,
    var boolean: Boolean = false,
    var string: String = "",
    var stack: Stack = Stack(),
    var card: Card = Card("", ""),
)

class WebSocket {
    val client: HttpClient = HttpClient {
        install(WebSockets)
    }

    lateinit var wsURI: URI
    lateinit var session: DefaultClientWebSocketSession
    lateinit var receiverJob: Job

    var playerID: Int = -1
    lateinit var roomID: String

    val receiverFunc = mutableListOf<suspend (WSData, Boolean) -> Unit>()
    val receiverFlow = MutableSharedFlow<WSData>()
    val closingFunc  = mutableListOf<suspend (Boolean) -> Unit>()

    fun setURI(uri: URI = URI("")) {
        if(uri.toString() == "") {
            wsURI = URI("ws://10.0.2.2:8080/ws")
        } else {
            wsURI = uri
        }

        println("Host: ${wsURI.host}")
        println("Port: ${wsURI.port}")
        println("Path: ${wsURI.path}")
    }

    suspend fun connect(uri: URI = URI("")) {
        setURI(uri)
        close()

        session = client.webSocketSession(
            method = HttpMethod.Get,
            host = wsURI.host,
            port = wsURI.port,
            path = wsURI.path
        )

        // Register room with the server by sending #connect
        send(WSData(methodID = "#connect"))

        onReceive { _: String, obj: WSData ->
            Log.d("WS_RECEIVER", receiverFunc.toString())
            wsMessage(obj, obj.relayed)
            receiverFlow.emit(obj)
        }
    }

    private suspend fun onReceive(block: suspend (txt: String) -> Unit) {
        receiverJob = session.launch {
            session.incoming.consumeEach {
                when (it) {
                    is Frame.Text -> {
                        val txt: String = it.readText()
                        Log.d("WS_RECEIVE", "Incoming: $txt")
                        block(txt)
                    }

                    is Frame.Close -> {
                        close()
                        Log.d("WS_RECEIVE", "Frame Close")
                    }

                    is Frame.Ping -> {
                        Log.d("WS_RECEIVE", "PING")
                    }

                    is Frame.Pong -> {
                        Log.d("WS_RECEIVE", "PONG")
                    }

                    is Frame.Binary -> {
                        /*no implement*/
                    }
                }
            }
        }
    }

    private suspend fun onReceive(block: suspend (txt: String, obj: WSData) -> Unit) {
        onReceive { txt: String ->
            try {
                val obj = Json.decodeFromString<WSData>(txt)
                Log.d("WS_RECEIVE", "Object:\n\t$obj")
                block(txt, obj.copy(relayed = true))
            } catch(e: Throwable) {
                Log.e("WS_RECEIVE", "${e.toString()}")
            }
        }
    }

    fun isConnected(): Boolean {
        if(this::session.isInitialized) {
            return session.isActive
        }

        return false
    }

    suspend fun close() {
        Log.d("WS_CLOSE", "Closed Websocket Connection...")
        if(this::receiverJob.isInitialized) {
            receiverJob.cancel(
                message = "Closed by Client"
            )
        }

        if(this::session.isInitialized && session.isActive) {
            session.close(CloseReason(
                CloseReason.Codes.GOING_AWAY,
                "Closed by Client"
            ))
        }

        closingFunc.forEach { it(isConnected()) }
    }

    suspend fun wsMessage(obj: WSData, relayed: Boolean = true) { receiverFunc.forEach{ it(obj, relayed) } }
    fun onMessage(onMsg: (it: WSData, relayed: Boolean) -> Unit) = receiverFunc.add(onMsg)
    fun onClose(onCls: (Boolean) -> Unit) = closingFunc.add(onCls)

    suspend fun await(
        fi: WSData,
        en: Map<String, Any?> = mapOf(),
    ): WSData {
        if(!isConnected()) return fi

        if(fi.methodID.substring(0, 1) != "@") send(fi.copy(methodID = "@" + fi.methodID))

        Log.d("WS_AWAIT", "Waiting for response... [${fi.methodID}:$en]")
        while(true) {
            val it = receiverFlow.first()
            if(fi.playerID == it.playerID && fi.methodID == it.methodID) {
                if(en.isEmpty() || en.map { m ->
                    when(m.key) {
                        "playerAlt" -> it.playerAlt == m.value
                        "boolean" -> it.boolean == m.value
                        else -> true
                    }
                }.reduce { acc, b -> acc && b }) {
                    Log.d("WS_AWAIT", "Response: $it")
                    return it
                }
            }
        }
    }

    fun wrapPlayer(obj: WSData): WSData {
        return obj.copy(
            playerID = if(obj.playerID < 0) {
                this.playerID
            } else {
                obj.playerID
            }
        )
    }

    suspend fun send(obj: WSData) {
        Log.d("WS_SEND", "Object: $obj")
        val txt = Json.encodeToString(obj.copy(
            roomID = roomID, relayed = false,
        ))
        Log.d("WS_SEND", "Message: $txt")

        if(this::session.isInitialized) {
            if(session.isActive) {
                session.send(Frame.Text(txt))
            } else {
                Log.d("WS_SEND", "Client Unconnected")
            }
        } else {
            Log.e("WS_SEND", "Client Uninitialized")
        }
    }
}

/**
 * All the hands in the game, deck and kitty
 */
class Game {
    // Keep track of cards
    val deck: Deck = Deck()
    val hands: List<Hand> = listOf<Hand>(
        Hand(0), Hand(1), Hand(2), Hand(3),
    )
    val kitty: Stack = Stack()

    // OpenHand
    var openHand: Boolean = true

    // Track the turn and dealer
    var turn: Int = 0
    var dealer: Int = 0
    var goingAlone: Int = -1

    // Trick currently in play
    var trick: Int = 0
    var trickCards: Trick = Trick()

    // WebSockets
    val webSocket: WebSocket = WebSocket()
    var isHost: Pair<Int, Int> = Pair(-1, 0)
    val readyChecks: MutableList<Boolean> = mutableListOf(
        false, false, false, false
    )

    // Shuffle the cards
    fun shuffle() { deck.shuffleCards() }
    // Cut the deck
    fun cut() { deck.cut() }
    // What is trump
    fun trump(): String { return trump }

    /**
     * Deal out the cards
     */
    fun deal() {
        var hand = dealer
        var cards = 2
        while(deck.size > 0) {
            if(hands[hand].size < 5) {
                for(i in 0..cards) {
                    if(hands[hand].size < 5) {
                        hands[hand].add(deck.removeFirst())
                    }
                }

                if(hand < 3) {
                    hand++
                    when(cards) {
                        1 -> cards = 2
                        2 -> cards = 1
                    }
                } else {
                    hand = 0
                }
            } else {
                while(deck.size > 0) {
                    kitty.add(deck.removeFirst())
                }
            }
        }
    }

    suspend fun phaseReady() {
        when(isHost.second) {
            1 -> {
                hands.filter { it.playerType == 0 }.map {
                    wsSend(WSData(
                        playerID = it.hand,
                        methodID = "aiOverride",
                        boolean  = false,
                    ))
                }

                wsSend(WSData(
                    methodID = "phaseReady",
                    boolean   = true,
                ))
            }
            0 -> wsSend(WSData(
                methodID = "phaseReady",
                boolean   = true,
            ))
            -1 -> {
                val obj = webSocket.await(WSData(
                    playerAlt = webSocket.playerID,
                    playerID = isHost.first,
                    methodID = "phaseReady",
                    boolean = true,
                ), mapOf("boolean" to true))

                Log.d("EUCHRE_READY", "Object: $obj")
            }
        }
    }

    fun _phaseShuffle(obj: WSData, msg: String = "_phaseShuffle"): WSData {
        this.shuffle()
        obj.stack = this.deck.toStack()
        Log.d("EUCHRE_SHUFFLE", "Local Shuffle; WSData: $obj")
        return obj
    }

    suspend fun phaseShuffle() {
        val obj = WSData(
            playerID = dealer % 4,
            methodID = "phaseShuffle"
        )

        Log.d("EUCHRE_SHUFFLE", "PlayerType: ${hands[dealer % 4].playerType}")

        when(hands[dealer % 4].playerType) {
            in 0..1 -> wsSend(_phaseShuffle(obj))
            else -> {
                val obj = webSocket.await(obj)
                Log.d("EUCHRE_SHUFFLE", "Remote Shuffle; WSData: $obj")
                this.deck.fromStack(obj.stack)
            }
        }

        Log.d("EUCHRE_SHUFFLE", "Deck: ${this.deck}")
    }

    fun _phaseCut(obj: WSData, msg: String = "_phaseCut"): WSData {
        if(obj.boolean) {
            this.cut()
            obj.stack = this.deck.toStack()
            Log.d("EUCHRE_CUT", "Local Cut; WSData: $obj")
        }

        return obj
    }

    suspend fun phaseCut(checkUser: suspend () -> Boolean) {
        val obj = WSData(
            playerID = (dealer + 1) % 4,
            methodID = "phaseCut",
        )

        Log.d("EUCHRE_CUT", "PlayerType: ${hands[(dealer + 1) % 4].playerType}")

        when(hands[(dealer + 1) % 4].playerType) {
            // AI Turn
            0 -> wsSend(_phaseCut(obj.copy(boolean = (0..1).random() > 0)))
            // Local Turn
            1 -> wsSend(_phaseCut(obj.copy(boolean = checkUser())))
            // Remote Turn
            else -> {
                val obj = webSocket.await(obj)
                Log.d("EUCHRE_CUT", "Remote Cut; WSData: $obj")
                this.deck.fromStack(obj.stack)
            }
        }

        Log.d("EUCHRE_CUT", "Deck: ${this.deck}")
    }

    fun _phaseDeal(obj: WSData, kitty: Boolean = false): WSData {
        obj.stack = if(kitty) {
            this.kitty.toStack()
        } else {
            this.hands[obj.playerAlt].toStack()
        }

        return obj
    }

    suspend fun phaseDeal() {
        val obj = WSData(
            playerID = dealer % 4,
            methodID = "phaseDeal",
        )

        Log.d("EUCHRE_DEAL", "PlayerType: ${hands[(dealer + 1) % 4].playerType}")

        when(hands[dealer % 4].playerType) {
            // AI/Local Turn
            in 0..1 -> {
                webSocket.await(obj.copy(methodID = "@" + obj.methodID))
                this.deal()
                this.hands.forEach{ wsSend(_phaseDeal(obj.copy(playerAlt = it.hand))) }
                wsSend(_phaseDeal(obj.copy(playerAlt = 4), kitty = true))
                wsSend(obj.copy(boolean = true))
            }
            // Remote Turn
            else -> {
                webSocket.await(obj, mapOf("boolean" to true))
                Log.d("EUCHRE_DEAL", "Remote Deal: Done")
            }
        }

        Log.d("EUCHRE_DEAL", "Hand: ${this.hands[webSocket.playerID]}")
    }

    suspend fun phasePickItUp(checkUser: suspend () -> Boolean) {
        // Select trump by dealer/kitty exchange
        while(turn < 4) {
            if(
                if(hands[whoseTurn()].playerType == 0) {
                    val check = hands[whoseTurn()].aiShouldPickItUp(kitty)
                    Log.d("EUCHRE", "AI: Should pick it up $check")
                    check
                } else {
                    val check = checkUser()
                    Log.d("EUCHRE", "USER: Should pick it up: $check")
                    check
                }
            ) {
                if(kitty[0].suit != "T") {
                    trump = "${kitty[0].suit}"
                    println("${whoseTurn()} told the dealer to pick up Kitty card\nTrump Selected by Kitty: $trump")
                }
                hands[dealer].pickItUp(kitty)
                break
            }
            turn++
        }
        turn = 0
    }

    suspend fun phaseSelectTrump(checkUser: suspend () -> String) {
        while(trump == "" && turn < 4) {
            trump = if(hands[whoseTurn()].playerType == 0) {
                "${hands[whoseTurn()].selectTrump()}"
            } else {
                "${checkUser()}"
            }

            if(turn == 3 && trump == "") {
                Log.d("EUCHRE","Trump wasn't selected")
                break
            }

            if(trump != "") {
                Log.d("EUCHRE","Trump Selected by ${whoseTurn()}: $trump")
                break
            }

            turn++
        }
        turn = 0
    }

    suspend fun phaseGoAlone(checkUser: suspend () -> Boolean) {
        while(turn < 4) {
            if(if(hands[whoseTurn()].playerType == 0) {
                hands[whoseTurn()].shouldGoAlone()
            } else { checkUser() }) {
                goingAlone = whoseTurn()
                break
            }

            turn++
        }
        turn = 0
    }

    suspend fun phasePlay(checkUser: suspend () -> Card) {
        while(trick < 5) {
            Log.d("EUCHRE", "Trick $trick")
            while (turn < 4) {
                Log.d("EUCHRE", "\tTurn $turn")
                // Skip player if partner is going alone.
                if (goingAlone > -1 && goingAlone != whoseTurn()) {
                    if (goingAlone % 2 == whoseTurn() % 2) {
                        Log.d("EUCHRE", "$goingAlone is going alone.")
                        turn++
                        continue
                    }
                }

                if (hands[whoseTurn()].playerType == 0) {
                    //delay(500)
                    hands[whoseTurn()].play(trickCards)
                } else {
                    val card = checkUser()
                    hands[whoseTurn()].remove(card)
                    trickCards.play(whoseTurn(), card)
                }

                Log.d("EUCHRE","\t${trickCards}")
                turn++
            }
            turn = 0

            hands[trickCards.winningHand()].tricks.add(trickCards)
            delay(1500)
            trickCards = Trick()
            trick++
        }

        trick = 0
    }

    fun play() {
        println(this)
    }

    fun nextHand() {
        if(dealer < 3) {
            dealer++
        } else {
            dealer = 0
        }

        turn = 0
        trump = ""
        goingAlone = -1

        deck.reset()
        for(hand in hands) {
            hand.reset()
        }
        kitty.reset()
    }

    fun reset() {
        nextHand()
        dealer = 0
    }

    fun wsMessage(data: WSData, relayed: Boolean = false) {
        when(data.methodID) {
            // Configure game to accept input from where...
            "aiOverride" -> hands[data.playerID].playerType = if(!relayed) {
                Log.d("WS", "Local User (false is AI): ${data.boolean.toString()}")
                if(data.boolean) { 1 } else { 0 }
            } else {
                Log.d("WS", "Remote Player")
                if(data.boolean) { -1 } else { 2 }
            }
            "claimHost" -> isHost = Pair(data.playerID, if(!relayed) {
                if(data.boolean) { 1 } else { 0 }
            } else {
                if(data.boolean) { -1 } else { 0 }
            })
            "#connect" -> {
                webSocket.session.launch {
                    // advertise our human
                    wsSend(WSData(
                        methodID = "aiOverride",
                        boolean = true,
                    ))

                    // Claim host or send out subsequently on new connection
                    if (isHost == Pair(-1, 0) || isHost == Pair(webSocket.playerID, 1)) {
                        wsSend(WSData(
                            methodID = "claimHost",
                            boolean = true,
                        ))
                    }
                }
            }

            "phaseDeal" -> if(!data.boolean) {
                if(data.playerAlt > 3) {
                    this.kitty.fromStack(data.stack)
                } else {
                    this.hands[data.playerAlt].fromStack(data.stack)
                }

                val dest = if (relayed) { "Remote" } else { "Local" }
                Log.d("EUCHRE_DEAL", "$dest Deal; WSData: $data")
            }
        }
    }

    suspend fun wsSend(data: WSData) {
        val data = webSocket.wrapPlayer(data)
        webSocket.wsMessage(data, relayed = false)
        webSocket.send(data)
    }

    fun readyCheck(playerID: Int, boolean: Boolean): Boolean {
        readyChecks[playerID] = boolean

        var areWeReady: Boolean = true
        readyChecks.forEachIndexed { index, b ->
            if(hands[index].playerType == 0) {
                areWeReady = areWeReady && true
            } else {
                if(index != isHost.first) areWeReady = areWeReady && b
            }
        }

        Log.d("EUCHRE_readyCheck", "${readyChecks} = ${areWeReady}")

        return areWeReady
    }

    fun whoseTurn(offset: Int = 1): Int {
        return (dealer + turn + offset) % 4
    }

    override fun toString(): String {
        return "Trump: ${trump}\nDeck: ${deck}\nHands: ${hands}\nKitty: ${kitty}\n"
    }
}

/**
 * Card object
 */
@Serializable
class Card(val suit: String, val card: String) {
    fun suitedCompareTo(suit: String, b: Card): Int {
        var cardA = this.card
        var cardB = b.card

        // Set the bower cards for higher scoring
        if (cardA == "J" && isBowerSuit(this.suit)) {
            cardA = isBower(this.suit)
        }
        if (cardB == "J" && isBowerSuit(b.suit)) {
            cardB = isBower(b.suit)
        }

        // If a specified suit order position in score index
        when(suit) {
            "" -> {
                // If no suit was specified
            }
            else -> {
                if(this.suit != suit) {
                    cardA = "A${cardA}"
                }

                if(b.suit != suit) {
                    cardB = "A${cardB}"
                }
            }
        }

        println("\t${cardA}: ${scoreOrder.indexOf(cardA)}")
        println("\t${cardB}: ${scoreOrder.indexOf(cardB)}")

        val tmp = scoreOrder.indexOf(cardA).compareTo(
            scoreOrder.indexOf(cardB)
        )

        return when(tmp) {
            -1 -> 1
            1  -> -1
            else -> 0
        }
    }

    // Suitless comparision
    operator fun compareTo(b: Card): Int {
        // Should trump be factored always?
        return suitedCompareTo("", b)
    }

    override operator fun equals(other: Any?): Boolean {
        return if(other is Card) {
            this.toString() == other.toString()
        } else {
            super.equals(other)
        }
    }

    override fun toString(): String {
        return "${this.suit}${this.card}"
    }
}

class Trick : MutableMap<Int, Card> by mutableMapOf() {
    var suit: String = ""

    fun winningHand(): Int {
        println("\tBestPlay: ${bestPlay()}")
        return bestPlay().first
    }

    fun bestCard(): Card {
        return bestPlay().second
    }

    fun bestPlay(): Pair<Int, Card> {
        return toList().sortedBy { (_, card) ->
            scoreOrderIndex(card)
        }[0]
    }

    fun play(hand: Int, card: Card): Card? {
        if(suit == "" && size < 1) {
            // If the card is a Bower infer the trick's suit as trump
            if(card.card == "J" && isBowerSuit(card.suit)) {
                suit = trump
            } else {
                suit = card.suit
            }

            // If the card is trump infer the trick's suit as trump
            if(suit == "T") {
                suit = trump
            }
        }

        return put(hand, card)
    }

    override fun toString(): String {
        return toMap().toString()
    }

    fun compareCards(a: Card, b: Card): Int {
        return a.suitedCompareTo(suit, b)
    }
}

object CardSerializer : JsonContentPolymorphicSerializer<Card>(Card::class) {
    override fun selectDeserializer(element: JsonElement) = Card.serializer()
}

object StackSerializer : KSerializer<List<Card>> {
    private val builtIn: KSerializer<List<Card>> = ListSerializer(CardSerializer)

    override fun deserialize(decoder: Decoder): Stack {
        return Stack(builtIn.deserialize(decoder))
    }

    override val descriptor: SerialDescriptor = builtIn.descriptor

    override fun serialize(encoder: Encoder, value: List<Card>) {
        builtIn.serialize(encoder, value)
    }
}

/**
 * A stack of cards
 */
@Serializable(with = StackSerializer::class)
open class Stack() : MutableList<Card> by mutableListOf() {
    constructor(ni: List<Card>) : this() {
        this.fromList(ni)
    }

    /**
     * Cut the stack of cards by a random amount
     */
    fun cut(cutFunc: (Int) -> Int = { ((it-3)..(it+2)).random() }) {
        val middle = size / 2
        val cutPnt = cutFunc(middle)
        Log.d("CUT", "Middle: ${middle}, Pnt: ${cutPnt}, List: ${toList()}")
        val newList = listOf(
            subList(0, cutPnt), subList(cutPnt, size),
        ).reversed().flatten()

        removeAll{true}
        addAll(newList)
    }

    // Empty the stack
    open fun reset() { removeAll{true} }

    // Suitable cards
    // If bower is lead should use trump suit...
    open fun suited(suit: String): Stack {
        val tmp = Stack()
        tmp.addAll(filter { 
            if(it.card == "J" && isBower(it.suit) == "J2") {
                if(trump == suit) {
                    println("\tLeft bower suited")
                }
                trump == suit
            } else {
                it.suit == suit
            }
        })
        return tmp
    }

    // Best cards
    fun bestCards(): Stack {
        val tmp = Stack()
        tmp.addAll(this.sortedBy {
            scoreOrderIndex(it)
        })
        return tmp
    }

    // Throw away
    fun throwCards(): Stack {
        val tmp = Stack()
        tmp.addAll(this.sortedBy {
            scoreOrderIndex(it, true)
        })
        return tmp
    }
    fun shuffleCards() {
        when((0..5).random()) {
            0 -> shuffleA(2, 3)
            1 -> shuffleA(2, 2)
            2 -> shuffleA(2, 2)
            3 -> shuffleA(3, 1)
            4 -> shuffleA(2, (1..3).random())
            else -> shuffle()
        }
    }

    fun shuffleA(s: Int = 2, t: Int = 1) {
        repeat(t) {
            val tmp: MutableList<MutableList<Card>> = mutableListOf<MutableList<Card>>()
            repeat(s) { tmp.add(mutableListOf<Card>()) }
            forEachIndexed { i, card -> tmp[i % s].add(card) }
            removeAll { true }
            addAll(tmp.flatten())
        }
    }

    override fun toString(): String {
        return toMutableList().toString()
    }

    open fun fromStack(ni: Stack): Stack {
        return fromList(ni.toList())
    }

    open fun fromList(ni: List<Card>): Stack {
        this.removeAll{true}
        this.addAll(ni)
        return this
    }

    open fun toStack(): Stack {
        return this
    }
}

class Hand(val hand: Int) : Stack() {
    var playerType: Int = 0
    val tricks: MutableList<Trick> = mutableListOf()

    fun pickItUp(kitty: Stack) {
        //@TODO query discard input
        val disCard = (0..4).random()
        kitty.add(removeAt(disCard))
        add(kitty.removeFirst())
    }

    fun aiShouldPickItUp(kitty: Stack): Boolean {
        val avgScore = calculateIdealTrump()
        return avgScore.isNotEmpty() && avgScore[0].first == kitty[0].suit
    }

    fun shouldGoAlone(): Boolean {
        //@TODO query user input
        //@TODO write ai for perfect play
        return calculateGoAlone().isNotEmpty()
    }

    /**
     * Calculate the average scoring range of cards per suit.
     */
    fun calculate(vararg suits: String): MutableMap<String, Pair<Int, Int>> {
        val suits = if(suits.isNotEmpty()) { suits } else { arrayOf("♠", "♥", "♣", "♦") }
        val avgScores: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
        for(suit in suits) {
            val suitAvgScore: MutableList<Int> = mutableListOf()
            for(card in suited(suit).bestCards()) {
                suitAvgScore.add(scoreOrderIndex(card))
            }
            avgScores[suit] = Pair(suitAvgScore.size, suitAvgScore.average().toInt())
        }

        return avgScores
    }

    fun calculateGoAlone(): List<Pair<String, Pair<Int, Int>>> {
        return calculate(trump).filterValues { it.first > 2 }  // 3 or more cards in a suit
                               .filterValues { it.second < 7 } // Scoring 6 or less
                               .filterValues { it.second > 2 } // Scoring 3 or more
                               .toList().sortedBy { (_, v) -> v.first }
    }

    /**
     * Calculate the ideal trump based on average scoring cards per suit.
     */
    fun calculateIdealTrump(): List<Pair<String, Pair<Int, Int>>> {
        return calculate().filterValues { it.first > 1 }  // 2 or more cards in a suit
                          .filterValues { it.second < 6 } // Scoring 5 or less
                          .filterValues { it.second > 2 } // Scoring 3 or more
                          .toList().sortedBy { (_, v) -> v.first }
    }

    fun selectTrump(): String {
        //@TODO query user input
        val avgScore = calculateIdealTrump()

        // The first is the ideal
        if (avgScore.isNotEmpty()) {
            return avgScore[0].first
        }

        return ""
    }

    fun play(trick: Trick) {
        //return @TODO query user input OR
        //@TODO chance of randomness maybe
        // a human might save a higher scoring card
        if(trick.size > 0) {
            val bestCard = trick.bestCard()
            val ourCards = suited(trick.suit).bestCards()
            if(ourCards.size > 0) {
                // bestCard > ourCards[0]
                if(trick.compareCards(bestCard, ourCards[0]) > 0) {
                    println("Their card is better")
                    remove(ourCards.last())
                    trick.play(this.hand, ourCards.last())
                } else {
                    println("Our cards are better")
                    remove(ourCards.first())
                    trick.play(this.hand, ourCards.first())
                }
            } else {
                println("Can't follow suit")
                val ourCards = throwCards()
                remove(ourCards.first())
                trick.play(this.hand, ourCards.first())
            }
        } else {
            val perfectGame = this.sortedBy {
                scoreOrderIndex(it)
            }

            remove(perfectGame[0])
            trick.play(this.hand, perfectGame[0])
        }
    }

    override fun fromStack(ni: Stack): Hand {
        super.fromStack(ni)
        return this
    }
}

/**
 * Initialize a deck of cards
 */
class Deck : Stack() {
    init { reset() }
    override fun reset() {
        super.reset()

        for (suit in listOf("♠", "♥", "♣", "♦")) {
            for(card in listOf("A", "K", "Q", "J", "10", "9")) {
                add(Card(suit, card))
            }
        }

        add(Card("T", "T"))
    }

    override fun fromStack(ni: Stack): Deck {
        super.fromStack(ni)
        return this
    }
}

fun main() {
    val game = Game()
    for(i in (0..0)) {
        println("Game $i")
        game.shuffle()
        game.cut()
        game.deal()
        println(game)
        game.play()
        println(game)
        game.nextHand()
    }

    for(hand in game.hands) {
        println("${hand.hand}: ${hand.tricks}")
    }
}
