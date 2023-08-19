package me.kellybecker.android.euchre.logic

import kotlinx.coroutines.runBlocking
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
    fun shuffleA() {
        gameInstance.deck.shuffleA(2, 2)
        assertArrayEquals(arrayOf(
            Card("♠", "A"),
            Card("♠","10"),
            Card("♥","Q"),
            Card("♣", "A"),
            Card("♣","10"),
            Card("♦", "Q"),
            Card("T", "T"),
            Card("♠", "J"),
            Card("♥", "K"),
            Card("♥", "9"),
            Card("♣", "J"),
            Card("♦", "K"),
            Card("♦", "9"),
            Card("♠", "Q"),
            Card("♥", "A"),
            Card("♥", "10"),
            Card("♣", "Q"),
            Card("♦", "A"),
            Card("♦", "10"),
            Card("♠", "K"),
            Card("♠", "9"),
            Card("♥", "J"),
            Card("♣", "K"),
            Card("♣", "9"),
            Card("♦", "J")
        ), gameInstance.deck.toTypedArray())
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
    fun playthrough1() {
        gameInstance.deck.shuffleA(2, 2)
        //gameInstance.cut()
        gameInstance.deal()


        // Ensure trump was set by the pick it up phase
        val kittyCard = gameInstance.kitty.first()
        runBlocking {
            gameInstance.phasePickItUp { /*never reached*/ false }
        }
        assertEquals(trump, "♠")
        assert(kittyCard in gameInstance.hands[0])
        // Phase Select Trump Skipped by Pick it up


        // Player 0 is going to go alone
        assertEquals(-1, gameInstance.goingAlone)
        runBlocking {
            gameInstance.phaseGoAlone { /*never reached*/ false }
        }
        assertEquals(0, gameInstance.goingAlone)


        // Play phase
        runBlocking {
            gameInstance.phasePlay { /*never reached*/ Card("", "") }
        }
        //
        assertEquals(Card("♠", "Q"), gameInstance.hands[0].tricks[0].get(1))
        assertEquals(Card("♠", "K"), gameInstance.hands[0].tricks[0].get(3))
        //assertEquals(Card("♣", "J"), gameInstance.hands[0].tricks[0].get(0))
        //
        assertEquals(Card("♣", "10"), gameInstance.hands[0].tricks[1].get(1))
        assertEquals(Card("♦", "10"), gameInstance.hands[0].tricks[1].get(3))
        assertEquals(Card("♠", "9"), gameInstance.hands[0].tricks[1].get(0))
        //
        assertEquals(Card("♦", "9"), gameInstance.hands[0].tricks[2].get(1))
        assertEquals(Card("♦", "A"), gameInstance.hands[0].tricks[2].get(3))
        //assertEquals(Card("♠", "10"), gameInstance.hands[0].tricks[2].get(0))
        //
        assertEquals(Card("♣", "A"), gameInstance.hands[1].tricks[0].get(1))
        assertEquals(Card("♥", "9"), gameInstance.hands[1].tricks[0].get(3))
        assertEquals(Card("♥", "Q"), gameInstance.hands[1].tricks[0].get(0))
        //
        assertEquals(Card("♥", "A"), gameInstance.hands[1].tricks[1].get(1))
        assertEquals(Card("♥", "K"), gameInstance.hands[1].tricks[1].get(3))
        assertEquals(Card("♦", "K"), gameInstance.hands[1].tricks[1].get(0))
    }
}