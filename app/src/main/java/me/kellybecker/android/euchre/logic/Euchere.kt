package me.kellybecker.android.euchre.logic

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val playerID: Int,
    val methodID: String,
    val roomID: String = "",
    val boolean: Boolean = false,
    val string: String = "",
    val card: Card = Card("", ""),
)

class WebSocket {
    val client: HttpClient = HttpClient {
        install(WebSockets)
    }

    lateinit var session: DefaultClientWebSocketSession
    lateinit var roomID: String

    suspend fun connect(host: String = "10.0.2.2", port: Int = 8080) {
        session = client.webSocketSession(
            method = HttpMethod.Get,
            host = host,
            port = port,
            path = "/ws"
        )
    }

    suspend fun close() { session.close() }

    suspend fun onMessage(onMsg: (WSData) -> Unit) {
        session.launch {
            for(msg in session.incoming) {
                msg as? Frame.Text ?: continue
                val txt: String = msg.readText()
                println("Incoming: $txt")
                onMsg(Json.decodeFromString<WSData>(txt))
            }
        }
    }

    suspend fun send(msg: WSData) {
        val txt: String = Json.encodeToString(msg.copy(
            roomID = roomID
        ))

        println("Outgoing: $txt")
        session.send(Frame.Text(txt))
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

    suspend fun phaseCut(checkUser: suspend () -> Boolean) {
        if(hands[(dealer + 1) % 4].playerType == 0) {
            if((0..1).random() > 0) {
                this.cut()
            }
        } else {
            if(checkUser()) this.cut()
        }
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
                    Log.d("EUCHRE", "USER: Should pick it up: $checkUser")
                    checkUser()
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
                if(data.boolean) { 1 } else { 0 }
                Log.d("WS", data.boolean.toString())
            } else {
                Log.d("WS", "Remote Player")
                -1
            }
        }
    }

    suspend fun wsSend(data: WSData) {
        wsMessage(data, relayed = false)
        webSocket.send(data)
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

/**
 * A stack of cards
 */
open class Stack : MutableList<Card> by mutableListOf() {

    /**
     * Cut the stack of cards by a random amount
     */
    fun cut(cutFunc: (Int) -> Int = { ((it-3)..(it+2)).random() }) {
        val middle = size / 2
        val cutPnt = cutFunc(middle)
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
}

class Hand(hand: Int) : Stack() {
    var playerType: Int = 0
    val tricks: MutableList<Trick> = mutableListOf()
    val hand: Int = hand

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
