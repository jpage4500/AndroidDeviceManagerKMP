package com.jpage4500.devicemanager.transport.demo

import com.jpage4500.devicemanager.data.*
import kotlin.random.*

/**
 * one invented device: everything the demo shell answers about it.
 *
 * Fixed for the life of the transport, so a screenshot taken now and one taken in ten minutes show
 * the same bench. What changes as it is used — a new nickname, a file uploaded, a reboot — lives in
 * [DemoShell].
 */
internal data class DemoDevice(
    val index: Int,
    val serial: String,
    val brand: String,
    val model: String,
    val os: String,
    val sdk: String,
    /** the panel's own pixels, as `wm size` reports them. */
    val screenWidth: Int,
    val screenHeight: Int,
    val isTablet: Boolean,
    val carrier: String?,
    val nickname: String?,
    val phone: String?,
    val imei: String?,
    val custom: Map<String, String>,
    val batteryLevel: Int,
    val charging: Boolean,
    val temperatureTenths: Int,
    val storageTotalKb: Long,
    val storageUsedPercent: Int,
    val uptimeSec: Long,
    val state: DeviceState,
    /** still coming up, and stays that way — the row that draws a half-booted device needs one. */
    val booting: Boolean,
    /** answers for a short while, then stops — what the Not responding chip is for. */
    val goesQuiet: Boolean,
    val screenOff: Boolean,
    /** which made-up app its screen shows — see [DemoScreen]. */
    val screen: DemoScreen.Kind,
    /** the wallpaper and accent colour, 0..359. */
    val hue: Int,
) {
    val isWireless: Boolean get() = serial.substringAfterLast(':', "").toIntOrNull() != null

    /** what the device list carries before any getprop — `host:devices-l` has the model. */
    fun toDevice(): Device = Device(serial = serial, state = state, model = model.replace(' ', '_'))
}

/**
 * forty devices worth taking a screenshot of.
 *
 * Laid out the way the app is used: most of them on 16-port USB hubs with a rack-and-port name,
 * a handful on Wi-Fi, three tablets, an emulator — and one of every state a row draws differently:
 * offline, unauthorised, booting, not charging, low battery, low storage, not responding, screen
 * off. Built from a fixed seed, so every launch is the same bench.
 */
internal object DemoFleet {
    private const val SIZE = 40
    private const val SEED = 4500

    private class Model(
        val brand: String,
        val model: String,
        val os: String,
        val sdk: String,
        val width: Int,
        val height: Int,
        val tablet: Boolean = false,
        // the IMEI's type allocation code: the first eight digits, which name the maker and model
        val tac: String = "35917311",
    )

    private val PHONES = listOf(
        Model("google", "Pixel 9 Pro", "15", "35", 1280, 2856, tac = "35537812"),
        Model("google", "Pixel 9", "15", "35", 1080, 2424, tac = "35537711"),
        Model("google", "Pixel 8a", "15", "35", 1080, 2400, tac = "35093424"),
        Model("google", "Pixel 8", "14", "34", 1080, 2400, tac = "35287614"),
        Model("google", "Pixel 7", "14", "34", 1080, 2400, tac = "35183274"),
        Model("google", "Pixel 6a", "13", "33", 1080, 2400, tac = "35124381"),
        Model("samsung", "SM-S928U", "14", "34", 1440, 3120, tac = "35044692"),
        Model("samsung", "SM-S921U", "14", "34", 1080, 2340, tac = "35044195"),
        Model("samsung", "SM-A546U", "14", "34", 1080, 2340, tac = "35255673"),
        Model("samsung", "SM-A156U", "14", "34", 1080, 2340, tac = "35602119"),
        Model("samsung", "SM-G991U", "13", "33", 1080, 2400, tac = "35274511"),
        Model("motorola", "moto g power 5G - 2024", "14", "34", 1080, 2400, tac = "35199482"),
        Model("motorola", "moto g stylus 5G - 2024", "14", "34", 1080, 2400, tac = "35199673"),
        Model("OnePlus", "CPH2581", "14", "34", 1440, 3168, tac = "86411406"),
        Model("HMD Global", "Nokia G310 5G", "13", "33", 720, 1612, tac = "35312265"),
    )

    private val TABLETS = listOf(
        Model("google", "Pixel Tablet", "15", "35", 2560, 1600, tablet = true),
        Model("samsung", "SM-X710", "14", "34", 2560, 1600, tablet = true),
        Model("LENOVO", "TB-X306F", "11", "30", 1280, 800, tablet = true),
    )

    private val EMULATOR = Model("google", "sdk_gphone64_arm64", "15", "35", 1080, 2400)

    private val CARRIERS = listOf("Verizon", "T-Mobile", "AT&T", "Verizon", "Google Fi", "T-Mobile", "US Cellular")
    private val AREA_CODES = listOf("512", "206", "312", "415", "617", "303", "919")
    private val TEAMS = listOf("QA", "QA", "Dev", "Release", "Field", "Support")
    private val WIRELESS_NAMES = listOf("Joe's Pixel", "Field kit 2", "Lobby kiosk", "Warehouse scanner", "Spare")

    // which slot plays which part — spread out so no screenful of the table is all one kind
    private val TABLET_SLOTS = mapOf(7 to 0, 19 to 1, 31 to 2)
    private val WIRELESS_SLOTS = listOf(4, 12, 23, 30, 37)
    private const val EMULATOR_SLOT = 39
    private const val OFFLINE_SLOT = 37
    private const val UNAUTHORIZED_SLOT = 38
    private const val BOOTING_SLOT = 36
    private val QUIET_SLOTS = setOf(14, 27)
    private val SCREEN_OFF_SLOTS = setOf(9, 22, 33)
    private val LOW_STORAGE_SLOTS = setOf(5, 17, 28)
    private val LOW_BATTERY_SLOTS = mapOf(11 to 9, 25 to 17)
    private val NOT_CHARGING_SLOTS = setOf(3, 16, 29)

    fun build(): List<DemoDevice> {
        val random = Random(SEED)
        return (0 until SIZE).map { i -> device(i, random) }
    }

    private fun device(i: Int, random: Random): DemoDevice {
        val tablet = TABLET_SLOTS[i]?.let { TABLETS[it] }
        val emulator = i == EMULATOR_SLOT
        val model = when {
            emulator -> EMULATOR
            tablet != null -> tablet
            else -> PHONES[(i * 7) % PHONES.size]
        }
        val wireless = i in WIRELESS_SLOTS
        val serial = when {
            emulator -> "emulator-5554"
            wireless -> "192.168.1.${101 + i}:5555"
            else -> usbSerial(model.brand, random)
        }

        val hasSim = !model.tablet && !emulator && i % 9 != 8
        val carrier = when {
            emulator -> "Android"
            hasSim -> CARRIERS[i % CARRIERS.size]
            else -> null
        }
        val phone = if (hasSim) "1${AREA_CODES[i % AREA_CODES.size]}55501${(i + 10).toString().padStart(2, '0')}" else null
        val imei = if (model.tablet || emulator) null else luhn(model.tac + (100_000 + i * 7_919 % 900_000).toString())

        val hub = i / 16 + 1
        val port = i % 16 + 1
        val rack = 'A' + (i / 16)
        val nickname = when {
            wireless -> WIRELESS_NAMES[WIRELESS_SLOTS.indexOf(i)]
            emulator -> null
            i % 5 == 2 -> null
            else -> "Rack $rack-${port.toString().padStart(2, '0')}"
        }
        val custom = buildMap {
            put(Device.CUST_PROP_1, TEAMS[i % TEAMS.size])
            if (!wireless && !emulator) put(Device.CUST_PROP_2, "hub $hub · port $port")
        }

        val usedPercent = if (i in LOW_STORAGE_SLOTS) 91 + i % 7 else 28 + random.nextInt(52)
        val totalGb = when {
            model.tablet -> 128
            model.brand == "samsung" && model.model.startsWith("SM-S") -> 256
            model.model.contains("Pro") -> 512
            else -> 128
        }

        return DemoDevice(
            index = i,
            serial = serial,
            brand = model.brand,
            model = model.model,
            os = model.os,
            sdk = model.sdk,
            screenWidth = model.width,
            screenHeight = model.height,
            isTablet = model.tablet,
            carrier = carrier,
            nickname = nickname,
            phone = phone,
            imei = imei,
            custom = custom,
            batteryLevel = LOW_BATTERY_SLOTS[i] ?: (42 + random.nextInt(58)),
            // USB devices sit on powered hubs; wireless ones are on their own batteries
            charging = !wireless && !emulator && i !in NOT_CHARGING_SLOTS,
            temperatureTenths = 255 + random.nextInt(110),
            storageTotalKb = totalGb * 1_000_000L * 1024 / 1000,
            storageUsedPercent = usedPercent,
            uptimeSec = 3_600L + random.nextLong(20 * 86_400L),
            state = when (i) {
                OFFLINE_SLOT -> DeviceState.OFFLINE
                UNAUTHORIZED_SLOT -> DeviceState.UNAUTHORIZED
                else -> DeviceState.DEVICE
            },
            booting = i == BOOTING_SLOT,
            goesQuiet = i in QUIET_SLOTS,
            screenOff = i in SCREEN_OFF_SLOTS,
            screen = DemoScreen.Kind.entries[(i * 3 + i / 4) % DemoScreen.Kind.entries.size],
            hue = (i * 47 + 200) % 360,
        )
    }

    /** a serial shaped like the maker's own, so the column reads like a real bench. */
    private fun usbSerial(brand: String, random: Random): String {
        fun chars(pool: String, n: Int) = (1..n).map { pool[random.nextInt(pool.length)] }.joinToString("")
        val upper = "0123456789ABCDEFGHJKLMNPRSTUVWXYZ"
        return when (brand) {
            "google" -> chars("0123456789", 2) + chars(upper, 12)
            "samsung" -> "R5C" + chars(upper, 8)
            "motorola" -> "ZY22" + chars(upper, 6)
            "OnePlus" -> chars("0123456789abcdef", 8)
            "LENOVO" -> "HA1" + chars(upper, 5)
            else -> chars(upper, 12)
        }
    }

    /** [digits] with the Luhn check digit every IMEI ends in. */
    private fun luhn(digits: String): String {
        val sum = digits.reversed().mapIndexed { index, c ->
            val d = c.digitToInt()
            if (index % 2 == 0) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return digits + ((10 - sum % 10) % 10)
    }
}
