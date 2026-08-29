package com.weighttrack.data.sync

/**
 * Somewhere a folder of sync files can live.
 *
 * Two of these: a folder on the phone that something like Syncthing keeps in step with another
 * device, and a directory on a WebDAV server such as Nextcloud. Both are just a place to put
 * files, which is the point. There is no account, no service, and nothing here that knows what
 * the files contain.
 */
interface SyncTarget {

    /** A name for what this is, short enough to put in a settings row. */
    val describe: String

    /** The names of the files in the folder. */
    suspend fun list(): SyncOutcome<List<String>>

    /** One file's contents, or null when it is not there. */
    suspend fun read(name: String): SyncOutcome<String?>

    /** Writes a file, replacing whatever was there. */
    suspend fun write(name: String, content: String): SyncOutcome<Unit>
}

/**
 * How an attempt went.
 *
 * Failures are values rather than exceptions. Syncing runs in the background against somebody
 * else's server over somebody else's network, so it failing is an ordinary event that has to be
 * reported to the person in words, not a crash.
 */
sealed interface SyncOutcome<out T> {
    data class Ok<T>(val value: T) : SyncOutcome<T>

    /** Something the person can fix: a wrong password, a folder they have since deleted. */
    data class Refused(val reason: String) : SyncOutcome<Nothing>

    /** Something that may well work later: no signal, a server having a bad day. */
    data class Unreachable(val reason: String) : SyncOutcome<Nothing>
}

/** Runs [block] on the value, keeping any failure as it was. */
inline fun <T, R> SyncOutcome<T>.map(block: (T) -> R): SyncOutcome<R> = when (this) {
    is SyncOutcome.Ok -> SyncOutcome.Ok(block(value))
    is SyncOutcome.Refused -> this
    is SyncOutcome.Unreachable -> this
}
