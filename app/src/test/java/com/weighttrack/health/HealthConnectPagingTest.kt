package com.weighttrack.health

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Walking every page of a Health Connect query.
 *
 * The bug this covers was silent by nature: a single read answered with the first page, the
 * import reported a cheerful count, and the four years of weigh-ins behind it were never
 * mentioned. Everything here is about the walk ending for the right reason.
 */
class HealthConnectPagingTest {

    /** Splits a list into fixed-size pages and hands out tokens the way the platform does. */
    private fun pagedReader(
        all: List<Int>,
        pageSize: Int,
        lastToken: String? = null,
    ): suspend (String?) -> Pair<List<Int>, String?> = { token ->
        val from = token?.toInt() ?: 0
        val page = all.drop(from).take(pageSize)
        val next = from + page.size
        if (next >= all.size) page to lastToken else page to next.toString()
    }

    @Test
    fun `two and a half thousand readings all arrive`() = runTest {
        val everything = (1..2_500).toList()

        val read = readAllPages(read = pagedReader(everything, pageSize = 1_000))

        assertThat(read).hasSize(2_500)
        assertThat(read).isEqualTo(everything)
    }

    @Test
    fun `an empty page token ends the walk`() = runTest {
        var calls = 0
        // Health Connect answers with "" rather than null when there is no next page, so a
        // null check on its own would ask for a page that does not exist, for ever.
        val read = readAllPages<Int> { _ ->
            calls++
            listOf(1, 2, 3) to ""
        }

        assertThat(read).containsExactly(1, 2, 3)
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a null page token ends the walk`() = runTest {
        var calls = 0
        val read = readAllPages<Int> { _ ->
            calls++
            listOf(7) to null
        }

        assertThat(read).containsExactly(7)
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `a provider that repeats its token is not read twice`() = runTest {
        var calls = 0
        // No progress is being made, so following the token again would return the same page
        // and double every record on it.
        val read = readAllPages<Int> { _ ->
            calls++
            listOf(calls) to "stuck"
        }

        assertThat(calls).isEqualTo(2)
        assertThat(read).containsExactly(1, 2)
    }

    @Test
    fun `the walk stops at the page limit`() = runTest {
        var calls = 0
        val read = readAllPages<Int>(pageLimit = 5) { token ->
            calls++
            listOf(calls) to ((token?.toInt() ?: 0) + 1).toString()
        }

        assertThat(calls).isEqualTo(5)
        assertThat(read).hasSize(5)
    }

    @Test
    fun `the first page is asked for with no token`() = runTest {
        val asked = mutableListOf<String?>()
        readAllPages<Int> { token ->
            asked += token
            if (asked.size == 1) listOf(1) to "second" else listOf(2) to null
        }

        assertThat(asked).containsExactly(null, "second").inOrder()
    }

    @Test
    fun `a query with nothing in it reads one page and stops`() = runTest {
        var calls = 0
        val read = readAllPages<Int> { _ ->
            calls++
            emptyList<Int>() to null
        }

        assertThat(read).isEmpty()
        assertThat(calls).isEqualTo(1)
    }
}
