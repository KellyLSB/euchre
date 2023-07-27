package me.kellybecker.android.euchre.logic

import android.util.Log
import kotlinx.coroutines.delay

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
    "T", "J1", "J2", "J", "A", "K", "Q", "10", "9",
    "AJ", "AK", "AQ", "A10", "A9",
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
    
    return if(trump != "" && card.card == "J" && isBowerSuit(card.suit)) {
        //println("\t${card.suit}${isBower(card.suit)}")
        tmpScoreOrder.indexOf(isBower(card.suit))
    } else {
        if(trump != "" && card.card != "T" && trump != card.suit) {
            //println("\t${card.suit}A${card.card}")
            tmpScoreOrder.indexOf("A${card.card}")
        } else {
            //println("\t${card.suit}${card.card}")
            tmpScoreOrder.indexOf(card.card)
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

//    // Callback Register
//    var onShouldPickItUp: ((Hand, Stack) -> Boolean)? = {},
//    var onSelectTrump: ((Hand) -> String)? = {},
//    var onShouldGoAlone: ((Hand) -> Boolean)? = {},
//    var onPlayCard: ((Hand, Trick) -> Unit)? = {},

    // Shuffle the cards
    fun shuffle() { deck.shuffle() }
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

    suspend fun phasePickItUp(checkUser: suspend () -> Boolean) {
        // Select trump by dealer/kitty exchange
        while(turn < 4) {
            if(
                if(hands[whoseTurn()].isAI) {
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
            trump = if(hands[whoseTurn()].isAI) {
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
            if(if(hands[whoseTurn()].isAI) {
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

                if (hands[whoseTurn()].isAI) {
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

    fun whoseTurn(): Int {
        return (dealer + turn) % 4
    }

    override fun toString(): String {
        return "Trump: ${trump}\nDeck: ${deck}\nHands: ${hands}\nKitty: ${kitty}\n"
    }
}

/**
 * Card object
 */
class Card(suit: String, card: String) {
    val suit: String = suit
    val card: String = card

    // Suitless comparision
    operator fun compareTo(b: Card): Int {
        println("\t${card}: ${scoreOrder.indexOf(card)}")
        println("\t${b.card}: ${scoreOrder.indexOf(b.card)}")
        var cardA = card
        var cardB = b.card

        if(cardA == "J") {
            cardA = isBower(suit)
        }

        if(cardB == "J") {
            cardB = isBower(b.suit)
        }

        val tmp = scoreOrder.indexOf(cardA).compareTo(
            scoreOrder.indexOf(cardB)
        )

        return when(tmp) {
            -1 -> 1
            1  -> -1
            else -> 0
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
            suit = card.suit
            if(suit == "T") {
                suit = trump
            }
        }

        return put(hand, card)
    }

    override fun toString(): String {
        return toMap().toString()
    }
}

/**
 * A stack of cards
 */
open class Stack : MutableList<Card> by mutableListOf() {

    /**
     * Cut the stack of cards by a random amount
     */
    fun cut() {
        val middle = size / 2
        val cutPnt = ((middle-3)..(middle+2)).random()
        val newList = listOf(
            subList(0, cutPnt), subList(cutPnt, size),
        ).reversed().flatten()

        removeAll{true}
        addAll(newList)
    }

    // Empty the stack
    open fun reset() { removeAll{true} }

    // Suitable cards
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

    override fun toString(): String {
        return toMutableList().toString()
    }
}

class Hand(hand: Int) : Stack() {
    var isAI: Boolean = true
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
                if(bestCard > ourCards[0]) {
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