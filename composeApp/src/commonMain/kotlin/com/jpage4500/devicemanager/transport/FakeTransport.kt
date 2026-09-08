package com.jpage4500.devicemanager.transport

import com.jpage4500.devicemanager.data.*
import com.jpage4500.devicemanager.util.*
import kotlinx.coroutines.flow.*
import kotlin.math.*

/**
 * invented devices, for looking at the UI without a device in reach.
 *
 * Reached by connecting to [HOST] — `0.0.0.0` in either Connect field — which is a route rather than
 * a debug menu on purpose: a fake fleet is just another way of reaching devices, so it goes behind
 * [DeviceTransport] like the real ones and every screen gets it for free.
 *
 * It answers the **real** batch commands with plausible output rather than with an error, so the
 * parsers in [DeviceDetails] run for real and a row fills in the way it would on a bench. What it
 * cannot fake is a screen: `screencap` returns a PNG, and inventing one means writing an encoder, so
 * mirror and screenshot fail here with a message that says why.
 */
class FakeTransport : DeviceTransport {
    private val log = LoggerConfig.createLogger("FakeTransport")

    override val label: String get() = "test devices"

    override fun devices(): Flow<List<Device>> = flowOf(FLEET)

    /**
     * answer the batches [com.jpage4500.devicemanager.manager.DeviceManager] sends, and nothing
     * else.
     *
     * matched on the command text rather than on a flag, because that is what a device does — and it
     * means a batch that changes shape stops matching here loudly instead of quietly.
     */
    override suspend fun exec(serial: String, command: String): Result<String> {
        val seed = seedOf(serial)
        return when (command) {
            DeviceDetails.FIXED_BATCH -> Result.success(fixed(serial, seed))
            DeviceDetails.CHANGING_BATCH -> Result.success(changing(serial, seed))
            else -> {
                log.d("exec: $serial: pretending to run '$command'")
                Result.success("")
            }
        }
    }

    override suspend fun properties(serial: String): Result<Map<String, String>> =
        Result.success(props(serial))

    override suspend fun screenshot(serial: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("a test device has no screen"))

    override suspend fun listFiles(serial: String, path: String): Result<List<DeviceFile>> =
        Result.success(
            listOf(
                DeviceFile("Download", "$path/Download", 4096, 0, AdbFileMode.S_IFDIR or 0b111_101_101),
                DeviceFile("DCIM", "$path/DCIM", 4096, 0, AdbFileMode.S_IFDIR or 0b111_101_101),
                DeviceFile("readme.txt", "$path/readme.txt", 214, 0, AdbFileMode.S_IFREG or 0b110_100_100),
            )
        )

    override suspend fun connect(host: String, port: Int): Result<String> =
        Result.success("already connected to the test devices")

    override suspend fun disconnect(serial: String): Result<String> =
        Result.success("disconnected $serial")

    override suspend fun reboot(serial: String): Result<Unit> {
        log.d("reboot: $serial: pretending")
        return Result.success(Unit)
    }

    /** a stable per-device number, so a row's battery doesn't change every refresh. */
    private fun seedOf(serial: String): Int = abs(serial.hashCode() % 100_000)

    private fun props(serial: String): Map<String, String> {
        val device = FLEET.firstOrNull { it.serial == serial }
        return mapOf(
            Device.PROP_MODEL to (device?.model ?: "Test Device"),
            Device.PROP_OS to (device?.os ?: "16"),
            Device.PROP_SDK to (device?.sdk ?: "36"),
            Device.PROP_TIMEZONE to "America/New_York",
        )
    }

    private fun fixed(serial: String, seed: Int): String = DeviceDetails.joinBatch(
        listOf(
            props(serial).entries.joinToString("\n") { (key, value) -> "[$key]: [$value]" },
            FLEET.firstOrNull { it.serial == serial }?.nickname.orEmpty(),
            // a phone number lives in a Parcel dump; this is the shape parseServiceCall reads, so
            // the parser runs here rather than being skipped
            parcel("555010${seedDigits(seed)}"),
            "",
            parcel("35${seedDigits(seed)}0099${seedDigits(seed)}"),
            "custom1=lab bench\ncustom2=rack ${seed % 4 + 1}",
        )
    )

    private fun seedDigits(seed: Int): String = seed.toString().padStart(5, '0').takeLast(4)

    /**
     * the UTF-16-in-a-hex-dump shape `service call` prints, which [DeviceDetails.parseServiceCall]
     * reads back — every other character is the NUL half of a UTF-16 code unit.
     */
    private fun parcel(digits: String): String {
        val quoted = digits.map { "$it." }.joinToString("")
        return "Result: Parcel(\n  0x00000000: 00000000 0000000b '........$quoted')"
    }

    private fun changing(serial: String, seed: Int): String {
        val totalBlocks = 61_000_000L
        val freeBlocks = totalBlocks - (seed % 40 + 10) * 1_000_000L
        val level = seed % 86 + 14
        val charging = seed % 3 == 0
        return DeviceDetails.joinBatch(
            listOf(
                "Filesystem     1K-blocks     Used Available Use% Mounted on\n" +
                    "/dev/block/dm-9 $totalBlocks ${totalBlocks - freeBlocks} $freeBlocks  35% /data",
                "Current Battery Service state:\n" +
                    "  AC powered: false\n" +
                    "  USB powered: $charging\n" +
                    "  status: ${if (charging) 2 else 3}\n" +
                    "  level: $level\n" +
                    "  temperature: ${250 + seed % 90}\n" +
                    "  voltage: ${3800 + seed % 400}",
                "${seed % 400_000 + 3_600}.24 ${seed % 900_000}.65",
                // the one device that is still coming up stays that way, so the row that draws a
                // half-booted device has something to draw
                if (FLEET.firstOrNull { it.serial == serial }?.isBooted == false) "0" else "1",
            )
        )
    }

    companion object {
        /** the address that reaches this transport, from either Connect field. */
        const val HOST = "0.0.0.0"

        /**
         * a bench worth looking at: USB and wireless, online and not, one still booting and one
         * that never authorised — the states a row has to draw differently.
         */
        private val FLEET = listOf(
            Device(
                serial = "39061FDJH00T2M",
                state = DeviceState.DEVICE,
                model = "Pixel 10a",
                nickname = "joe's pixel",
            ),
            Device(
                serial = "192.168.0.147:5555",
                state = DeviceState.DEVICE,
                model = "Pixel 8 Pro",
                nickname = "kitchen 7",
            ),
            Device(
                serial = "emulator-5554",
                state = DeviceState.DEVICE,
                model = "sdk_gphone64_arm64",
            ),
            Device(
                serial = "R5CT10ABCDE",
                state = DeviceState.DEVICE,
                model = "SM-G991B",
                nickname = "galaxy s21",
                isBooted = false,
            ),
            Device(
                serial = "HT7A1TT01234",
                state = DeviceState.UNAUTHORIZED,
                model = "Nexus 5X",
            ),
            Device(
                serial = "192.168.0.171:5555",
                state = DeviceState.OFFLINE,
                model = "Pixel 6",
                nickname = "bench spare",
            ),
        )
    }
}
