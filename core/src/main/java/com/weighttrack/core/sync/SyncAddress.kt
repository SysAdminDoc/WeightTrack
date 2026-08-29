package com.weighttrack.core.sync

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
