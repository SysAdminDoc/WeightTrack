package com.weighttrack.core.sync

/**
 * What is wrong with a server address somebody typed.
 *
 * Null means nothing is. Each of these is a thing the person can put right, which is the only
 * reason to tell them apart: a generic "could not connect" an hour later, from a background job
 * nobody is watching, is how a sync that was never going to work looks.
 */
enum class AddressProblem {
    /** Nothing there at all. */
    EMPTY,

    /** Not an address this can make sense of. */
    UNREADABLE,

    /**
     * Plain HTTP.
     *
     * Refused rather than attempted. The password and every weight would go across the network
     * in the clear, the app blocks cleartext at the platform level so the attempt fails anyway,
     * and the failure it produces says nothing about why.
     */
    NOT_ENCRYPTED,

    /** A scheme this does not speak. */
    NOT_WEB,

    /**
     * A password written into the address itself.
     *
     * Legal, and every WebDAV client shows one, but the address is stored and put on the settings
     * screen in plain sight while the password field beside it is kept under the phone's keystore.
     * Accepting it here quietly undoes that.
     */
    CREDENTIALS_IN_ADDRESS,
}

/**
 * Reading a sync address well enough to know where it points.
 *
 * Android 17 stopped letting an app open a socket to another machine on the same network without
 * asking first, which is exactly what syncing to a Nextcloud in the spare room is. The permission
 * is worth asking for only when the address really is on the local network: somebody syncing to a
 * hosted server should never see the prompt, and a prompt for something the app is not doing is
 * how people learn to refuse them.
 *
 * Parsed by hand rather than with a URL class, so this stays testable in the pure module beside
 * the rest of the protocol.
 */
object SyncAddress {

    /**
     * Whether an address can be used at all, and what is wrong with it if not.
     *
     * Read before anything is stored. A refused address used to be stored and then fail an hour
     * later in a background job nobody was watching, with a message that said nothing about why.
     */
    fun problemWith(raw: String): AddressProblem? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return AddressProblem.EMPTY
        if (!trimmed.contains("://")) return AddressProblem.UNREADABLE
        val scheme = trimmed.substringBefore("://").lowercase()
        if (scheme.isEmpty()) return AddressProblem.UNREADABLE
        if (scheme == "http") return AddressProblem.NOT_ENCRYPTED
        if (scheme != "https") return AddressProblem.NOT_WEB
        val authority = trimmed.substringAfter("://")
            .takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return AddressProblem.CREDENTIALS_IN_ADDRESS
        val host = hostOf(trimmed)
        if (host.isNullOrBlank()) return AddressProblem.UNREADABLE
        // Checked rather than merely non-blank. A stray space or an impossible port is stored,
        // and then the request cannot be built at all: the run fails hourly with a message that
        // says only that the server could not be reached, which is the thing this exists to stop.
        if (!isReadableHost(host)) return AddressProblem.UNREADABLE
        val port = portOf(authority)
        if (port != null && port !in 1..65_535) return AddressProblem.UNREADABLE
        return null
    }

    /** Whether a host is one a request can actually be built for. */
    private fun isReadableHost(host: String): Boolean {
        if (host.length > MAX_HOST_LENGTH) return false
        // An IPv6 literal carries colons and hexadecimal and nothing else worth checking here.
        if (host.contains(':')) {
            return host.all { it in "0123456789abcdefABCDEF:.%" }
        }
        if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    /** The port somebody wrote, or null when they wrote none. */
    private fun portOf(authority: String): Int? {
        val hostAndPort = authority.substringAfterLast('@')
        val text = when {
            hostAndPort.startsWith("[") -> hostAndPort.substringAfter(']').removePrefix(":")
            hostAndPort.count { it == ':' } != 1 -> return null
            else -> hostAndPort.substringAfter(':')
        }
        if (text.isEmpty()) return null
        return text.toIntOrNull() ?: 0
    }

    private const val MAX_HOST_LENGTH = 253

    fun isUsable(raw: String): Boolean = problemWith(raw) == null

    /** The host part of an address, without scheme, credentials, port or path. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = url).trim()
        if (afterScheme.isEmpty()) return null
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        // Credentials in the address itself are legal and nobody's business here.
        val hostAndPort = authority.substringAfterLast('@')
        if (hostAndPort.isEmpty()) return null
        val host = when {
            // An IPv6 literal is bracketed precisely so that its colons cannot be read as a port.
            hostAndPort.startsWith("[") ->
                hostAndPort.substringAfter('[').substringBefore(']')
            // Unbracketed and full of colons: an IPv6 address somebody wrote without brackets.
            // Splitting on the first colon would leave "2001", which is not an address at all.
            hostAndPort.count { it == ':' } > 1 -> hostAndPort
            else -> hostAndPort.substringBefore(':')
        }
        // A name may legally end in the root dot. It is the same name.
        return host.removeSuffix(".").takeIf { it.isNotEmpty() }
    }

    /**
     * Whether reaching this address means talking to a machine on the phone's own network.
     *
     * Loopback is deliberately not local in this sense. Android exempts it, because a socket to
     * the phone itself never leaves the phone, and asking for a permission that changes nothing
     * would be noise.
     */
    fun isOnLocalNetwork(url: String): Boolean {
        val host = hostOf(url)?.lowercase() ?: return false
        if (host == "localhost") return false
        val ipv4 = ipv4Octets(host)
        if (ipv4 != null) return isPrivateIpv4(ipv4)
        if (host.contains(':')) return isPrivateIpv6(host)
        // A name the household router made up, or one advertised over mDNS. Neither can be
        // resolved by anything outside the house, so neither is anywhere else.
        if (LOCAL_SUFFIXES.any { host == it.trimStart('.') || host.endsWith(it) }) return true
        return !host.contains('.')
    }

    /**
     * Name endings that cannot mean anywhere but the network the phone is on.
     *
     * `.local` is mDNS, `.home.arpa` is the ending the IETF set aside for exactly this, and the
     * rest are what home routers hand out by default. Between them they cover most of the
     * addresses somebody actually types for a server in their own house.
     */
    private val LOCAL_SUFFIXES = listOf(
        ".local",
        ".lan",
        ".home.arpa",
        ".home",
        ".internal",
        ".localdomain",
        ".fritz.box",
    )

    private fun ipv4Octets(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            part.toInt().also { if (it > 255) return null }
        }
        return octets
    }

    private fun isPrivateIpv4(octets: List<Int>): Boolean {
        val (a, b) = octets
        return when {
            a == 127 -> false
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            // What a device gives itself when no router answered, so both ends are on one cable.
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val address = host.substringBefore('%')
        if (address == "::1") return false
        // fc00::/7 is the address range that is routable inside one site and nowhere else, and
        // fe80::/10 is the link-local range every interface configures for itself.
        if (address.startsWith("fe80:") || address.startsWith("fe80::")) return true
        val first = address.takeWhile { it != ':' }
        if (first.length !in 1..4 || first.any { it !in "0123456789abcdef" }) return false
        val leading = first.padStart(4, '0').substring(0, 2).toIntOrNull(16) ?: return false
        return leading == 0xfc || leading == 0xfd
    }
}
