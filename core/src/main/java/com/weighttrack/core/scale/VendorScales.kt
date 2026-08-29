package com.weighttrack.core.scale

import java.util.UUID

/**
 * Every vendor protocol this app speaks, and the services worth listening for.
 *
 * A scale is matched by the name it advertises, not by its service alone: Beurer's diagnostic
 * scales advertise a vendor service beside the standard ones, and picking on the service would
 * take them down the wrong path.
 */
object VendorScales {

    /** A fresh state machine per connection; these are not reusable across one. */
    private val builders: List<() -> VendorScaleProtocol> = listOf(
        { BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF700) },
        { BeurerSanitasProtocol(BeurerSanitasProtocol.Family.BF710) },
        { EufyProtocols.OneByOne() },
        { EufyProtocols.BodySense() },
        { QnScaleProtocol() },
    )

    /** The protocol for a scale calling itself this, or null when none of them claim it. */
    fun forName(deviceName: String?): VendorScaleProtocol? =
        builders.asSequence().map { it() }.firstOrNull { it.handles(deviceName) }

    /** Everything a scan should filter on, standard services included. */
    val serviceUuids: List<UUID> = listOf(
        shortBluetoothUuid(0x181D),
        shortBluetoothUuid(0x181B),
        shortBluetoothUuid(BeurerSanitasProtocol.SERVICE),
        shortBluetoothUuid(EufyProtocols.OneByOne.SERVICE),
        shortBluetoothUuid(QnScaleProtocol.SERVICE_FFF0),
        UUID.fromString(EufyProtocols.BodySense.SERVICE_UUID),
    ).distinct()
}
