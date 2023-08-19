package me.kellybecker.android.euchre.logic

import org.junit.Test

import org.junit.Assert.*

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class EuchreTest {
    val gameInstance = Game()

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    // Ensure deal provides the correct number of cards
    fun deal() {
        assertEquals(25, gameInstance.deck.size)

        gameInstance.deal()

        assertEquals(5, gameInstance.kitty.size)
        gameInstance.hands.forEach {
            assertEquals(5, it.size)
        }
    }

    @Test
    // Ensure dealt cards follow the euchre deal pattern
    fun ensureDeal() {
        gameInstance.deal()

        // Player 0
        assertArrayEquals(arrayOf(
            Card("♠", "A"),
            Card("♠", "K"),
            Card("♠", "Q"),
            Card("♥", "10"),
            Card("♥", "9")
        ), gameInstance.hands[0].toTypedArray())

        // Player 1
        assertArrayEquals(arrayOf(
            Card("♠", "J"),
            Card("♠", "10"),
            Card("♣", "A"),
            Card("♣", "K"),
            Card("♣", "Q")
        ), gameInstance.hands[1].toTypedArray())

        // Player 2
        assertArrayEquals(arrayOf(
            Card("♠", "9"),
            Card("♥", "A"),
            Card("♥", "K"),
            Card("♣", "J"),
            Card("♣", "10")
        ), gameInstance.hands[2].toTypedArray())

        // Player 3
        assertArrayEquals(arrayOf(
            Card("♥", "Q"),
            Card("♥", "J"),
            Card("♣", "9"),
            Card("♦", "A"),
            Card("♦", "K")
        ), gameInstance.hands[3].toTypedArray())

        // Kitty
        assertArrayEquals(arrayOf(
            Card("♦", "Q"),
            Card("♦", "J"),
            Card("♦", "10"),
            Card("♦", "9"),
            Card("T", "T")
        ), gameInstance.kitty.toTypedArray())
    }

    @Test
    fun cut() {
        val middle = gameInstance.deck.size / 2
        val list = listOf(
            gameInstance.deck.subList(0, middle),
            gameInstance.deck.subList(middle, gameInstance.deck.size)
        ).reversed().flatten().toTypedArray()

        // Cut the deck perfectly down the middle
        gameInstance.deck.cut{it}
        assertArrayEquals(list, gameInstance.deck.toTypedArray())
    }

    @Test
    fun shuffleA() {
        gameInstance.deck.shuffleA(2, 2)
        assertEquals("[♠A, ♠10, ♥Q, ♣A, ♣10, ♦Q, TT, ♠J, ♥K, ♥9, ♣J, ♦K, ♦9, ♠Q, ♥A, ♥10, ♣Q, ♦A, ♦10, ♠K, ♠9, ♥J, ♣K, ♣9, ♦J]", gameInstance.deck.toString())
    }
}