package com.weighttrack.health

/**
 * Why a Health Connect call did not answer.
 *
 * Every failure used to be treated as an expired cursor: the token was thrown away and the next
 * run read five years of records again. A provider having a bad minute, a rate limit, and a
 * permission withdrawn months ago all cost the same thing, which is the most expensive query the
 * app can make, repeated every hour until whatever it was went away.
 *
 * The four here want four different answers, so they are told apart rather than lumped together.
 */
enum class HealthFailure {
    /**
     * The place in the queue is no longer any good.
     *
     * Health Connect says so either by setting a flag on the answer or by refusing to answer at
     * all, and both mean the same thing. Only this one replaces the cursor.
     */
    EXPIRED_TOKEN,

    /** The grant has gone. Nothing to retry: the cursor is kept and Settings says so. */
    NOT_ALLOWED,

    /** Asked too often. Worth another go later, from exactly where it left off. */
    RATE_LIMITED,

    /** Something else, and probably temporary. The cursor is kept and the next run tries again. */
    TRANSIENT,
    ;

    companion object {

        /**
         * Reads a failure for what it is.
         *
         * By the type where the platform gives a distinct one, and by the wording where it does
         * not. Health Connect reports an unknown changes token as a plain
         * [IllegalStateException] on several provider versions, which is why the words matter:
         * without them a stale token is stored for ever and the sync never works again.
         */
        fun of(cause: Throwable): HealthFailure {
            // The whole chain, not just the top of it. An SDK that wraps a security failure in
            // something of its own would otherwise read as a bad minute, and the app would go on
            // asking hourly for something it is no longer allowed to have.
            val chain = generateSequence(cause) { it.cause }.take(MAX_DEPTH).toList()
            if (chain.any { it is SecurityException }) return NOT_ALLOWED
            val words = chain
                .joinToString(" ") { it.message.orEmpty() + " " + it.javaClass.name }
                .lowercase()
            return when {
                words.contains("rate limit") || words.contains("ratelimit") -> RATE_LIMITED
                // Read before the token, because a message that says both is telling us the
                // grant has gone: acting on that as a lost cursor throws away a perfectly good
                // one and reads five years the moment the grant comes back.
                words.contains("permission") || words.contains("not allowed") -> NOT_ALLOWED
                words.contains("token") -> EXPIRED_TOKEN
                else -> TRANSIENT
            }
        }

        /** A cause chain longer than this is a loop, and loops here would hang the sync. */
        private const val MAX_DEPTH = 10
    }
}
