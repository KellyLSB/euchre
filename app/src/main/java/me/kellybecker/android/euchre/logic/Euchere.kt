package me.kellybecker.android.euchre.logic

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
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
import java.io.File
import java.net.URI
import java.util.Collections
import kotlin.random.Random

/**
 * takeDown
 * t: range of object indices
 * d: remove down to number of objects
 * e: entropy in selecting the removed objects
 */
fun <T> MutableList<T>.takeDown(t: IntRange, d: Int = 3, e: Int = size): List<T> {
    val l = t.toMutableList()
    Log.d("SELECT", "$l")
    while(l.size > d) { l.remove(l.shuffled(Random(e)).random()) }
    return select(*l.toIntArray())
}

/**
 * takeDrop
 * t: range of object indices
 * d: remove number of objects
 * e: entropy for selecting removed objects
 */
fun <T> MutableList<T>.takeDrop(t: IntRange, d: Int = 0, e: Int = size): List<T> {
    val l = t.toMutableList()
    repeat(d) { l.remove(l.shuffled(Random(e)).random()) }
    return select(*l.toIntArray())
}

/**
 * takeDiscard
 * t: range of object indices
 * d: remove specific indice
 */
fun <T> MutableList<T>.takeDiscard(t: IntRange, vararg d: T): List<T> {
    val l = takeDrop(t).toMutableList()
    d.forEach{ l.remove(it) }
    return l
}

/**
 * select
 * i: selected indices
 */
fun <T> MutableList<T>.select(vararg i: Int): List<T> = i.map{
    Log.d("SELECT", "${it}: $this")
    get(it)
}

/**
 * prepend
 * e: prepend object to list
 */
infix fun <T> MutableList<T>.prepend(e: T): MutableList<T> = this.prepend(listOf(e))

/**
 * prepend
 * e: prepend collection of objects
 */
infix fun <T> MutableList<T>.prepend(e: Collection<T>): MutableList<T> {
    val tmp = buildList(this.size + e.size) {
        addAll(e)
        addAll(this@prepend)
    }

    return replace(tmp)
}

/**
 * replace
 * e: replace all list objects with the provided collection
 */
infix fun <T> MutableList<T>.replace(e: Collection<T>): MutableList<T> {
    removeAll{true}
    addAll(e)
    return this
}

/**
 * ShuffleA
 * Omni deal shuffler; Zipper shuffler
 *
 * s: number of split decks during shuffle
 * t: number of times to shuffle
 */
fun <T> MutableList<T>.shuffleA(s: Int = 2, t: Int = 1) {
    repeat(t) {
        val tmp: MutableList<MutableList<T>> = mutableListOf<MutableList<T>>()
        repeat(s) { tmp.add(mutableListOf<T>()) }
        forEachIndexed{ i, t -> tmp[i % s].add(t) }
        replace(tmp.flatten())
    }
}

/**
 * shuffleB
 * Outward slight shuffler
 *
 * s: Times to shuffle
 * t: Take number of cards during shuffle
 * d: Reduce number of cards to remain a shuffled card
 * r: Is this a recursive call (i.e. shuffleE)
 * e: Entropy used in random to select points in the deck
 */
fun <T> MutableList<T>.shuffleB(
    s: Int = (5..14).shuffled(Random(rand)).random(),
    t: Int = 4, d: Int = 3,
    r: Boolean = false,
    e: Int = size,
) {
    var c: Int = s
    while(c > 0) {
        val rnd = (0..(size - 1)).shuffled(Random(e)).random()
        val cnt = (1..t).shuffled(Random(e)).random()

        val tmp = if(rnd + cnt >= size) {
            takeDown(rnd..(size - 1), d)
        } else {
            takeDown(rnd..(rnd + cnt), d)
        }

        removeAll(tmp)
        // /|||||\\//||\
        if(c % 2 == 0) {
            addAll(tmp)
        } else {
            prepend(tmp)
        }

        if(!r) {
            shuffleE(c % 2, t, d, true, e)
        } else {
            break
        }

        c--
    }
}

/**
 * shuffleE
 * Inward slight shuffler
 *
 * s: Times to shuffle
 * t: Take number of cards during shuffle
 * d: Reduce number of cards to remain a shuffled card
 * r: Is this a recursive call (i.e. shuffleB)
 * e: Entropy used in random to select points in the deck
 */
fun <T> MutableList<T>.shuffleE(
    s: Int = (5..14).shuffled(Random(rand)).random(),
    t: Int = 4, d: Int = 3,
    r: Boolean = false,
    e: Int = size,
) {
    var c: Int = s
    while(c > 0) {
        var rnd = (0..(size - 1)).shuffled(Random(e)).random()
        val cnt = (1..t).shuffled(Random(e)).random()

        // /|||||\\//||\
        val i = if(c % 2 == 0) { size - cnt - 1 } else 0
        val tmp = takeDown(i..(i + cnt), d)
        removeAll(tmp)

        if(rnd >= size) rnd = size - 1
        replace(listOf(
            subList(0, rnd),
            tmp,
            subList(rnd, size),
        ).flatten())

        if(!r) {
            shuffleB(c % 2, t, d, true, e)
        } else {
            break
        }

        c--
    }
}

/**
 * shuffleV
 * Vortex Shuffler
 * 
 * t: Maximum number of card to shuffle at once
 * d: Reduce number of cards shuffled at once to increase entropy
 * e: Entropy for selecting random points in the deck
 */
fun <T> MutableList<T>.shuffleV(
    t: Int = 4, d: Int = 3,
    e: Int = size,
) {
    val tmp = mutableListOf<T>()
    var cycles = 0
    while(size > 0) {
        val rnd = (0..(size - 1)).shuffled(Random(e)).random()
        val cnt = (1..t).shuffled(Random(e)).random()

        val tmp2 = if(rnd + cnt >= size) {
            takeDown(rnd..(size - 1), d)
        } else {
            takeDown(rnd..(rnd + cnt), d)
        }

        removeAll(tmp2)
        // /|||||\\//||\
        if(cycles % 2 == 0) {
            tmp.addAll(tmp2)
        } else {
            tmp.prepend(tmp2)
        }

        cycles++
    }

    addAll(tmp)
}

/**
 * Variables best left semi-global
 */
var trump: String = ""
var maker: Int = -1

/**
 * Constant for randomness of a euchre deck
 */
const val rand: Int = 25

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
    "T", "J1", "J2",
    "A",  "K",  "Q",  "J",  "10",  "9",
    "AA", "AK", "AQ", "AJ", "A10", "A9",
    "BA", "BK", "BQ", "BJ", "B10", "B9"
)

fun scoreOrderIndex(card: Card, suit: String, reverse: Boolean = false): Int {
    val tmpScoreOrder: List<String> = if(reverse) {
        scoreOrder.asReversed()
    } else {
        scoreOrder.toList()
    }

    // If the card is a bower apply the appropriate position.
    val scoreCard = if(trump != "" && card.card == "J" && isBowerSuit(card.suit)) {
        isBower(card.suit)
    } else {
        // Since trump isn't the card suit bypass A/B in the event suit is trump
        if(trump != "" && card.card != "T" && trump != card.suit) {
            // If suit isn't the card suit; file it tertiary.
            if(suit != "" && suit != card.suit) { "B" } else { "A" }
        } else {""} + card.card
    }
    val score = tmpScoreOrder.indexOf(scoreCard)

    Log.d("EUCHRE", "$card ($scoreCard): $score")
    return score
}

/**
 * Weights for ordering the cards by scoring values
 */
fun scoreOrderIndex(card: Card, reverse: Boolean = false): Int {
    return scoreOrderIndex(card, trump, reverse)
}

@Serializable
data class WSData(
    val methodID: String,
    val playerID: Int = -1,
    val playerAlt: Int = -1,
    val roomID: String = "",
    val loopback: Boolean = false,
    val relayed: Boolean = false,
    val waiting: Boolean = false,
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
        wsURI = if(uri.toString() == "") {
            URI("ws://10.0.2.2:8080/ws")
        } else {
            uri
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
            wsMessage(obj, obj.relayed)
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
                Log.e("WS_RECEIVE", "$e")
            }
        }
    }

    fun isInitialized(): Boolean = this::session.isInitialized
    fun isConnected(): Boolean {
        if(isInitialized()) return session.isActive
        return false
    }

    suspend fun close() {
        Log.d("WS_CLOSE", "Closed Websocket Connection...")
        if(this::receiverJob.isInitialized) {
            receiverJob.cancel(
                message = "Closed by Client"
            )
        }

        if(isConnected()) {
            session.close(CloseReason(
                CloseReason.Codes.GOING_AWAY,
                "Closed by Client"
            ))
        }

        closingFunc.forEach{ it(isConnected()) }
    }

    suspend fun wsMessage(obj: WSData, relayed: Boolean = true) {
        receiverFlow.emit(obj.copy(relayed = relayed))
        receiverFunc.forEach{ it(obj, relayed) }
    }
    fun onMessage(onMsg: (it: WSData, relayed: Boolean) -> Unit) = receiverFunc.add(onMsg)
    fun onClose(onCls: (Boolean) -> Unit) = closingFunc.add(onCls)

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        if(isConnected()) session.launch(block = block)
    }

    suspend fun await(
        fi: WSData,
        en: Map<String, Any?> = mapOf(),
    ): WSData {
        if(!isConnected()) return fi

        if(fi.methodID.substring(0, 1) != "@") send(fi.copy(methodID = "@" + fi.methodID))

        Log.d("WS_AWAIT", "Waiting for response... [${fi.methodID}:$en]")

        val obj = receiverFlow.filter {
            fi.playerID == it.playerID && fi.methodID == it.methodID
        }.filter {
            en.isEmpty() || en.map { m ->
                when (m.key) {
                    "playerAlt" -> it.playerAlt == m.value
                    "boolean" -> it.boolean == m.value
                    else -> true
                }
            }.reduce{ acc, b -> acc && b }
        }.first()

        Log.d("WS_AWAIT", "Object: $obj")

        return obj
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

    suspend fun send(obj: WSData): WSData {
        Log.d("WS_SEND", "Object: $obj")
        val txt = Json.encodeToString(obj.copy(
            roomID = roomID, relayed = false,
        ))
        Log.d("WS_SEND", "Message: $txt")

        if(isInitialized()) {
            if(session.isActive) {
                session.send(Frame.Text(txt))
            } else {
                Log.d("WS_SEND", "Client Unconnected")
            }
        } else {
            Log.e("WS_SEND", "Client Uninitialized")
        }

        return obj
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

    // Score
    var scoreA: Int = 0
    var scoreB: Int = 0

    // OpenHand
    var openHand: Boolean = false

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

    // What is trump
    fun trump(): String { return trump }

    /**
     * Deal out the cards
     */
    fun deal() {
        var hand = dealer
        var loop = 0
        var cards = 2

        while(deck.size > 0) {
            if(hands[hand % 4].size < 5) {
                for(i in 0..cards) {
                    if(hands[hand % 4].size < 5) {
                        hands[hand % 4].add(deck.removeFirst())
                    }
                }

                if((loop % 4) < 3) when(cards) {
                    1 -> cards++
                    2 -> cards--
                }

                hand++
                loop++
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
        deck.shuffleCards(obj.string)
        obj.stack = this.deck.toStack()
        Log.d("EUCHRE_SHUFFLE", "Local Shuffle; WSData: $obj")
        return obj
    }

    suspend fun phaseShuffle(
        entropy: Int = rand,
        checkUser: suspend () -> String,
    ) {
        val obj = WSData(
            playerID = dealer % 4,
            methodID = "phaseShuffle"
        )

        Log.d("EUCHRE_SHUFFLE", "PlayerType: ${hands[dealer % 4].playerType}")

        when(hands[dealer % 4].playerType) {
            0 -> wsSend(_phaseShuffle(obj.copy(string = listOf(
                "&", "Δ", "E", "☆", "⛧", "♡", "V", "%"
            ).shuffled(Random(entropy)).random())))
            1 -> wsSend(_phaseShuffle(obj.copy(string = checkUser())))
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
            deck.cut()
            obj.stack = this.deck.toStack()
            Log.d("EUCHRE_CUT", "Local Cut; WSData: $obj")
        }

        return obj
    }

    suspend fun phaseCut(
        entropy: Int = rand,
        checkUser: suspend () -> Boolean,
    ) {
        val obj = WSData(
            playerID = (dealer + 1) % 4,
            methodID = "phaseCut",
        )

        Log.d("EUCHRE_CUT", "PlayerType: ${hands[(dealer + 1) % 4].playerType}")

        when(hands[(dealer + 1) % 4].playerType) {
            // AI Turn
            0 -> wsSend(_phaseCut(obj.copy(
                boolean = (0..1).shuffled(Random(entropy)).random() > 0,
            )))
            // Local Turn
            1 -> wsSend(_phaseCut(obj.copy(boolean = checkUser())))
            // Remote Turn
            else -> {
                val obj = webSocket.await(obj)
                Log.d("EUCHRE_CUT", "Remote Cut; WSData: $obj")
                if(obj.boolean) this.deck.fromStack(obj.stack)
            }
        }

        Log.d("EUCHRE_CUT", "Deck: ${this.deck}")
    }

    fun _phaseDeal(obj: WSData, kitty: Boolean = false): WSData {
        Log.d("PHASE_deal", "$this")

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

        Log.d("EUCHRE_DEAL", "PlayerType: ${hands[dealer % 4].playerType}")

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
            var obj = WSData(
                playerID = whoseTurn(),
                methodID = "phasePickItUp",
            )

            Log.d("EUCHRE_PICKITUP", "PlayerType: ${hands[whoseTurn()].playerType}")

            when(hands[whoseTurn()].playerType) {
                // AI/Local Turn
                0 -> {
                    obj = obj.copy(boolean = hands[whoseTurn()].aiShouldPickItUp(kitty))
                    Log.d("EUCHRE_PICKITUP", "AI PickUp: ${obj.boolean}")
                    wsSend(obj)
                }
                1 -> {
                    obj = obj.copy(boolean = checkUser())
                    Log.d("EUCHRE_PICKITUP", "User PickUp: ${obj.boolean}")
                    wsSend(obj)
                }
                // Remote Turn
                else -> {
                    obj = webSocket.await(obj, mapOf("waiting" to true))
                    Log.d("EUCHRE_PICKITUP", "Remote PickUp: ${obj.boolean}")
                }
            }

            if(obj.boolean) break
            turn++
        }
        turn = 0
    }

    suspend fun phaseSelectTrump(checkUser: suspend () -> String) {
        while(trump == "" && turn < 4) {
            var obj = WSData(
                playerID = whoseTurn(),
                methodID = "phaseSelectTrump",
            )

            Log.d("EUCHRE_SELECTTRUMP", "PlayerType: ${hands[whoseTurn()].playerType}")

            when(hands[whoseTurn()].playerType) {
                // AI/Local Turn
                0 -> {
                    obj = obj.copy(string = hands[whoseTurn()].selectTrump())
                    Log.d("EUCHRE_SELECTTRUMP", "AI Select Trump: ${obj.string}")
                    wsSend(obj)
                }
                1 -> {
                    obj = obj.copy(string = checkUser())
                    Log.d("EUCHRE_SELECTTRUMP", "User Select Trump: ${obj.string}")
                    wsSend(obj)
                }
                // Remote Turn
                else -> {
                    obj = webSocket.await(obj, mapOf("waiting" to true))
                    Log.d("EUCHRE_SELECTTRUMP", "Remote Select Trump: ${obj.string}")
                }
            }

            if(obj.string != "") break

            if(turn == 3 && obj.string == "") {
                Log.d("EUCHRE_SELECTTRUMP","Trump wasn't selected")
                break
            }

            turn++
        }
        turn = 0
    }

    suspend fun phaseGoAlone(checkUser: suspend () -> Boolean) {
        if(trump == "") return

        while(turn < 4) {
            var obj = WSData(
                playerID = whoseTurn(),
                methodID = "phaseGoAlone",
            )

            Log.d("EUCHRE_GOALONE", "PlayerType: ${hands[whoseTurn()].playerType}")

            obj = when(hands[whoseTurn()].playerType) {
                0 -> wsSend(obj.copy(boolean = hands[whoseTurn()].shouldGoAlone()))
                1 -> wsSend(obj.copy(boolean = checkUser()))
                else -> webSocket.await(obj, mapOf("waiting" to true))
            }

            Log.d("EUCHRE_GOALONE", "Going Alone: $goingAlone")

            if(obj.boolean) break

            turn++
        }
        turn = 0
    }

    suspend fun phasePlay(checkUser: suspend () -> Card) {
        if(trump == "") return

        while(trick < 5) {
            Log.d("EUCHRE_PLAY", "Trick $trick")

            while (turn < 4) {
                // Skip player if partner is going alone.
                if(goingAlone > -1 && goingAlone != whoseTurn()) {
                    if(goingAlone % 2 == whoseTurn() % 2) {
                        Log.d("EUCHRE_PLAY", "$goingAlone is going alone.")
                        turn++
                        continue
                    }
                }

                var obj = WSData(
                    playerID = whoseTurn(),
                    methodID = "phasePlay",
                )

                Log.d("EUCHRE_PLAY", "Player: ${whoseTurn()}")
                Log.d("EUCHRE_PLAY", "PlayerType: ${hands[whoseTurn()].playerType}")

                obj = when(hands[whoseTurn()].playerType) {
                    0 -> wsSend(obj.copy(card = hands[whoseTurn()].play(trickCards)))
                    1 -> wsSend(obj.copy(card = checkUser()))
                    else -> webSocket.await(obj, mapOf("waiting" to true))
                }

                Log.d("EUCHRE_PLAY", "Object: $obj")

                turn++
            }
            turn = 0

            // Discard played cards for reshuffle
            Log.d("EUCHRE_DISCARD", "trickCard: ${trickCards.map{ it.value }}")
            Log.d("EUCHRE_DISCARD", "deck(${deck.size}): $deck")
            deck.addAll(trickCards.map{ it.value })

            // Remember who won which tricks
            hands[trickCards.winningHand()].tricks.add(trickCards)
            delay(1500)
            trickCards = Trick()
            trick++
        }


        Log.d("EUCHRE_DISCARD", "kitty: $kitty")
        Log.d("EUCHRE_DISCARD", "deck(${deck.size}): $deck")
        deck.addAll(kitty)
        trick = 0
    }

    suspend fun phaseRecollect() {
        // If trump was never selected recollect the kitty
        // nextHand() recollects the unplayed hands.
        if(trump == "") {
            deck.addAll(kitty)
            nextHand()
            return
        }

        val makers: MutableList<Trick> = mutableListOf()
        val defend: MutableList<Trick> = mutableListOf()

        hands.forEach { hand ->
            hand.tricks.forEach { trick ->
                if(trick.winningHand() % 2 == maker) {
                    makers.add(trick)
                } else {
                    defend.add(trick)
                }
            }

            hands[hand.hand].tricks.removeAll{true}
        }

        Log.d("EUCHRE_SCORING", "Makers: $makers")
        Log.d("EUCHRE_SCORING", "Defend: $defend")

        Log.d("EUCHRE_SCORING", "GoingAlone: $goingAlone")
        Log.d("EUCHRE_SCORING", "Maker: $maker")

        when(makers.size) {
            in 3..4 -> if(maker == 0) { scoreA++ } else { scoreB++ }
            5 -> if(goingAlone > -1 && goingAlone % 2 == maker) {
                if(maker == 0) { scoreA += 5 } else { scoreB += 5 }
            } else {
                if(maker == 0) { scoreA += 2 } else { scoreB += 2 }
            }
        }

        when(defend.size) {
            in 3..5 -> if(goingAlone > -1 && goingAlone % 2 != maker) {
                if(maker != 0) { scoreA += 4 } else { scoreB += 4 }
            } else {
                if(maker != 0) { scoreA += 2 } else { scoreB += 2 }
            }
        }

        Log.d("EUCHRE_SCORING", "ScoreA(0:2): $scoreA")
        Log.d("EUCHRE_SCORING", "ScoreB(1:3): $scoreB")

        nextHand()
    }

    fun play() {
        println(this)
    }

    fun nextHand() {
        dealer++
        turn = 0
        maker = -1
        trump = ""
        goingAlone = -1

        Log.d("EUCHRE_NEXT", "Deck(${deck.size}): $deck")

        deck.fromList(
            // Prepend the remaining cards
            this.hands.flatten(),
            deck.toList(),
        )

        Log.d("EUCHRE_NEXT", "Deck(${deck.size}): $deck")

        hands.forEach{ it.reset() }
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
                Log.d("WS", "Local User (false is AI): ${data.boolean}")
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
                webSocket.launch {
                    delay(300)

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

                val dest = if(relayed) { "Remote" } else { "Local" }
                Log.d("EUCHRE_DEAL", "$dest Deal; WSData: $data")
            } else { this.deck.removeAll{true} }

            "phasePickItUp" -> {
                if(!data.waiting) {
                    val dest = if(relayed) { "Remote" } else { "Local" }
                    Log.d("EUCHRE_PICKITUP", "$dest PickUp: ${data.boolean}")

                    if(data.boolean) {
                        if(kitty[0].suit != "T") {
                            trump = "${kitty[0].suit}"
                            maker = data.playerID % 2
                            println("${whoseTurn()} told the dealer to pick up Kitty card\nTrump Selected by Kitty: $trump")
                        }

                        hands[dealer % 4].pickItUp(kitty)
                    }

                    webSocket.launch {
                        delay(300)
                        wsSend(data.copy(waiting = true, string = trump), false)
                    }
                }
            }

            "phaseSelectTrump" -> {
                if(!data.waiting) {
                    val dest = if(relayed) { "Remote" } else { "Local" }
                    Log.d("EUCHRE_SELECTTRUMP", "$dest Select Trump: ${data.string}")

                    if(data.string != "") {
                        trump = data.string
                        maker = data.playerID % 2
                        Log.d("EUCHRE_SELECTTRUMP","Trump Selected by ${whoseTurn()}: $trump")
                    }

                    webSocket.launch {
                        delay(300)
                        wsSend(data.copy(waiting = true, string = trump), false)
                    }
                }
            }

            "phaseGoAlone" -> {
                if(!data.waiting) {
                    val dest = if(relayed) { "Remote" } else { "Local" }
                    Log.d("EUCHRE_GOALONE", "$dest Go Alone: $data")

                    if(data.boolean) goingAlone = data.playerID

                    webSocket.launch {
                        delay(300)
                        wsSend(data.copy(waiting = true), false)
                    }
                }
            }

            "phasePlay" -> {
                if(!data.waiting) {
                    val dest = if(relayed) { "Remote" } else { "Local" }
                    Log.d("EUCHRE_PLAY", "$dest Play: $data")
                    hands[data.playerID].playCard(trickCards, data.card)

                    webSocket.launch {
                        delay(300)
                        wsSend(data.copy(waiting = true), false)
                    }
                }
            }
        }
    }

    suspend fun wsSend(data: WSData, relay: Boolean = true): WSData {
        val data = webSocket.wrapPlayer(data)
        webSocket.wsMessage(data, relayed = false)
        return if(relay) webSocket.send(data)
        else data
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

        Log.d("EUCHRE_readyCheck", "$readyChecks = $areWeReady")

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
        return bestPlay().first
    }

    fun bestCard(): Card {
        return bestPlay().second
    }

    fun bestPlay(): Pair<Int, Card> {
        val list = Collections.synchronizedMap(this).toMap().map {
            Pair(it.key, Pair(it.value, scoreOrderIndex(it.value, this.suit)))
        }.sortedBy {
            it.second.second
        }.map {
            Pair(it.first, it.second.first)
        }

        return if(list.isEmpty()) {
            Log.e("BESTPLAY", "${this}\n${Throwable().stackTraceToString()}")
            Pair(-1, Card("", ""))
        } else list.first()
    }

    fun play(hand: Int, card: Card): Card? {
        if(suit == "" && size < 1) {
            // If the card is a Bower infer the trick's suit as trump
            suit = if(card.card == "J" && isBowerSuit(card.suit)) {
                trump
            } else {
                card.suit
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

    fun toStack(): Stack = Stack(toCardList())
    fun toCardList(): List<Card> = this.values.toList()

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
    constructor(vararg ni: List<Card>) : this() {
        this.fromList(*ni)
    }

    /**
     * Cut the stack of cards by a random amount
     */
    fun cut(cutFunc: (Int) -> Int = {
        ((it-3)..(it+2)).shuffled(Random(rand)).random()
    }) {
        val middle = size / 2
        val cutPnt = cutFunc(middle)
        Log.d("CUT", "Middle: ${middle}, Pnt: ${cutPnt}, List: ${toList()}")

        val newList = listOf(
            subList(0, cutPnt),
            subList(cutPnt, size),
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

    // @TODO: Select shuffling technique?
    fun shuffleCards(
        strategy: String = listOf(
            "&", "Δ", "E", "☆", "⛧", "♡", "V", "%"
        ).shuffled(Random(rand)).random(),
        entropy: Int = rand,
    ) {
        when(strategy) {
            "&" -> shuffleA(2, (1..3).shuffled(Random(entropy)).random())
            "Δ" -> shuffleA(2, 2)
            "E" -> shuffleE()
            "☆" -> shuffleA(2, 3)
            "⛧" -> shuffleA(3, 1)
            "♡" -> shuffleB()
            "V" -> shuffleV()
            "%" -> shuffle()
        }
    }

    override fun toString(): String {
        return toMutableList().toString()
    }

    open fun fromStack(vararg ni: Stack): Stack {
        return fromList(*ni.map{ it.toList() }.toTypedArray())
    }

    open fun fromList(vararg ni: List<Card>): Stack {
        this.removeAll{true}
        this.addAll(ni.reduce{ acc, cards ->
            val acc = acc.toMutableList()
            acc.addAll(cards)
            acc.toList()
        })
        return this
    }

    open fun toStack(): Stack {
        return this
    }

    open fun toJSON(): String = Json.encodeToString(this)
    open fun fromJSON(txt: String) = fromStack(Json.decodeFromString<Stack>(txt))
}

class Hand(val hand: Int) : Stack() {
    var playerType: Int = 0
    val tricks: MutableList<Trick> = mutableListOf()

    fun pickItUp(
        kitty: Stack,
        entropy: Int = rand,
    ) {
        //@TODO query discard input
        val disCard = (0..4).shuffled(Random(entropy)).random()
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
    // {suit=(number, average)
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
                               .filterValues { it.second < 6 } // Scoring 5 or less
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
        Log.d("EUCHRE_SELECTTRUMP", "${calculate()}")

        //@TODO query user input
        val avgScore = calculateIdealTrump()

        // The first is the ideal
        if (avgScore.isNotEmpty()) {
            return avgScore[0].first
        }

        return ""
    }

    fun playCard(trick: Trick, card: Card) {
        if(remove(card)) trick.play(this.hand, card)
    }

    fun play(trick: Trick): Card {
        //return @TODO query user input OR
        //@TODO chance of randomness maybe
        // a human might save a higher scoring card
        if(trick.size > 0) {
            val bestCard = trick.bestCard()
            val ourCards = suited(trick.suit).bestCards()
            return if(ourCards.size > 0) {
                // bestCard > ourCards[0]
                if(trick.compareCards(bestCard, ourCards[0]) > 0) {
                    println("Their card is better")
                    ourCards.last()
                } else {
                    println("Our cards are better")
                    ourCards.first()
                }
            } else {
                println("Can't follow suit")
                val ourCards = throwCards()
                ourCards.first()
            }
        } else {
            // Perfect Game
            return minByOrNull { scoreOrderIndex(it) }!!
        }
    }

    override fun fromStack(vararg ni: Stack): Hand {
        super.fromStack(*ni)
        return this
    }
}

/**
 * Initialize a deck of cards
 */
@Serializable
class Deck(
    var name: String = "luckystar"
) : Stack() {
    init { reset() }

    constructor(context: Context, ni: String? = null) : this() {
        this.loadDeck(context, name)
    }

    override fun reset() {
        super.reset()

        for (suit in listOf("♠", "♥", "♣", "♦")) {
            for(card in listOf("A", "K", "Q", "J", "10", "9")) {
                add(Card(suit, card))
            }
        }

        add(Card("T", "T"))
    }

    fun saveDeck(context: Context, filename: String? = null) {
        if(size != 25) throw Exception("Wrong size deck: $size")
        if(filename != null) name = filename
        val filename = getFilename(name)
        val json = toJSON()

        Log.d("EUCHRE_SAVEDECK", "Filename: $filename")
        Log.d("EUCHRE_SAVEDECK", "JSON: $json")

        File(context.cacheDir, filename).writeText(json)
    }

    fun loadDeck(context: Context, filename: String? = null) {
        if(filename != null) name = filename

        val decks = context.cacheDir.list{ _, filename ->
            filename.startsWith(name)
        }.sorted()

        Log.d("EUCHRE_LOADDECK", "Decks: $decks")

        if(decks.isNotEmpty()) {
            val file = File(context.cacheDir, decks.last())
            val json = file.readText()

            Log.d("EUCHRE_LOADDECK", "JSON: $json")

            fromJSON(json)
            if(size != 25) throw Exception("Wrong file size: $size")

            Log.d("EUCHRE_LOADDECK", "Deck: $this")
        }
    }

    fun getFilename(filename: String): String {
        //val datetime = (System.currentTimeMillis()/1000).toString()
        //val datahash = MessageDigest.getInstance("SHA-1")
        //    .digest(toString().toByteArray()).toString()

        //return "${filename}_${datetime}_${datahash}.deck"
        return "${filename}.deck"
    }

    override fun fromStack(vararg ni: Stack): Deck {
        super.fromStack(*ni)
        return this
    }

    override fun toString(): String {
        return "(${size}) ${super.toString()}"
    }
}

fun main() {
    val game = Game()
    for(i in (0..0)) {
        println("Game $i")
        game.deck.shuffleCards()
        game.deck.cut()
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
