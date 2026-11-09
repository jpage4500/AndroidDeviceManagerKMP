package com.jpage4500.devicemanager.data

import com.jpage4500.devicemanager.util.*

/**
 * every built-in column the device table can show, in the order the Swing app declares them —
 * a [CustomColumn] is the other kind of [TableColumn].
 *
 * ported from `table/DeviceTableModel.Columns`. [defaultWidth] is a starting point only — a width
 * the user drags is kept in [com.jpage4500.devicemanager.data.AppPref.COLUMN_WIDTHS].
 *
 * a column knows how to render itself only as far as a *sort key*: what a cell actually draws is
 * decided in the table, because a battery is a bar and a name is an icon plus text.
 */
enum class DeviceColumn(
    override val label: String,
    override val defaultWidth: Int,
    val hideByDefault: Boolean = false,
) : TableColumn {
    NAME("Name", 160),
    SERIAL("Serial", 200),
    MODEL("Model", 110),
    BATTERY("Battery", 70),
    OS("OS", 50),
    CUSTOM1("Custom 1", 90),
    CUSTOM2("Custom 2", 90),
    CARRIER("Carrier", 90),
    PHONE("Phone", 110),
    IMEI("IMEI", 110),
    FREE("Free", 70),
    BOOTED("Booted", 90, hideByDefault = true),
    ;

    override val id: String get() = name

    /** the text a cell shows, and what a sort compares when [sortKey] has nothing better. */
    override fun text(device: Device): String? = when (this) {
        NAME -> device.displayName
        SERIAL -> device.serial
        MODEL -> device.model
        BATTERY -> device.batteryLevel?.let { "$it%" }
        OS -> device.os
        CUSTOM1 -> device.customProperty(Device.CUST_PROP_1)
        CUSTOM2 -> device.customProperty(Device.CUST_PROP_2)
        CARRIER -> device.carrier
        // a number someone typed in wins over one read off the SIM, which is often missing or wrong
        PHONE -> device.customProperty(Device.CUST_PROP_PHONE) ?: device.phone
        IMEI -> device.customProperty(Device.CUST_PROP_IMEI) ?: device.imei
        FREE -> Utils.bytesToDisplayString(device.freeSpace)
        BOOTED -> device.bootTimeMs?.let { DateUtils.formatDate(DateUtils.timeToDate(it), "MM/dd h:mm a") }
    }

    /**
     * what a sort actually compares.
     *
     * a number sorted as text puts 100% before 9%, and a size sorted as "42.8G" puts G before M —
     * so the columns holding a number sort on the number and everything else on its text, folded to
     * lowercase so a sort doesn't put every capital first.
     */
    override fun sortKey(device: Device): Comparable<*>? = when (this) {
        BATTERY -> device.batteryLevel
        FREE -> device.freeSpace
        OS -> device.sdk?.toIntOrNull() ?: device.os?.substringBefore('.')?.toIntOrNull()
        BOOTED -> device.bootTimeMs
        else -> text(device)?.lowercase()
    }

    companion object {
        val defaultOrder: List<DeviceColumn> = entries.toList()

        val defaultHidden: Set<DeviceColumn> = entries.filter { it.hideByDefault }.toSet()
    }
}
