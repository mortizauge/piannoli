package dev.piannoli.scorza.launcher

import android.os.Parcel
import dev.piannoli.igm.BatteryDisplayMode
import dev.piannoli.igm.IgmColors
import dev.piannoli.igm.IgmDisplaySettings
import dev.piannoli.igm.ProtocolMismatchException
import dev.piannoli.igm.RICOTTA_PROTOCOL_VERSION
import dev.piannoli.igm.RicottaLaunchParams
import dev.piannoli.igm.TimeFormatMode
import dev.piannoli.ui.ButtonLabelSet
import dev.piannoli.ui.ConfirmButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RicottaLaunchParamsTest {

    private fun sample() = RicottaLaunchParams(
        coreId = "gambatte",
        romPath = "/sd/roms/gb/zelda.gb",
        configFilePath = "/sd/piannoli/retroarch.cfg",
        gameTitle = "Zelda",
        stateBasePath = "/sd/piannoli/states/zelda",
        piannoliRoot = "/sd/piannoli",
        platformTag = "GB",
        platformName = "Game Boy",
        igmTriggerKeycodes = listOf(4, 110),
        quitOnFocusLoss = true,
        preferredRefreshRate = 60,
        colors = IgmColors(
            highlight = "#0AB9E6",
            text = "#FFFFFF",
            highlightText = "#000000",
            accent = "#E8C896",
            title = "#0AB9E6",
        ),
        displaySettings = IgmDisplaySettings(
            fontSizeSp = 24,
            portraitMarginPx = 0,
            geometryWidthPct = 100,
            geometryHeightPct = 100,
            geometryXPct = 0,
            geometryYPct = 0,
            showWifi = true,
            showBluetooth = true,
            showVpn = true,
            showClock = true,
            batteryDisplay = BatteryDisplayMode.ICON,
            timeFormat = TimeFormatMode.TWENTY_FOUR_HOUR,
            buttonLabelSet = ButtonLabelSet.PLUMBER,
            confirmButton = ConfirmButton.SOUTH,
        ),
        inputMapping = dev.piannoli.igm.IgmInputMapping(
            buttonKeycodes = mapOf(
                dev.piannoli.igm.CanonicalButton.BTN_WEST to listOf(100),
                dev.piannoli.igm.CanonicalButton.BTN_EAST to listOf(96),
            ),
            menuConfirm = dev.piannoli.igm.CanonicalButton.BTN_EAST,
            menuBack = dev.piannoli.igm.CanonicalButton.BTN_SOUTH,
        ),
        localeTag = "pt-BR",
    )

    private fun roundTrip(params: RicottaLaunchParams): RicottaLaunchParams {
        val parcel = Parcel.obtain()
        try {
            params.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return RicottaLaunchParams.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `round trips all fields`() {
        val original = sample()
        val restored = roundTrip(original)
        assertEquals(original, restored)
        assertEquals(listOf(100), restored.inputMapping?.buttonKeycodes?.get(dev.piannoli.igm.CanonicalButton.BTN_WEST))
        assertEquals(dev.piannoli.igm.CanonicalButton.BTN_EAST, restored.inputMapping?.menuConfirm)
        assertEquals(dev.piannoli.igm.CanonicalButton.BTN_SOUTH, restored.inputMapping?.menuBack)
    }

    @Test fun `round trips null optional fields`() {
        val original = sample().copy(
            configFilePath = null,
            preferredRefreshRate = null,
            colors = null,
        )
        assertEquals(original, roundTrip(original))
    }

    // localeTag is the last field precisely so this degrades instead of corrupting the ones before
    // it. Sender and receiver ship together, so this only covers a stale install.
    @Test fun `an older parcel that omits the trailing locale tag reads as no override`() {
        val params = sample()
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(RICOTTA_PROTOCOL_VERSION)
            parcel.writeString(params.coreId)
            parcel.writeString(params.romPath)
            parcel.writeString(params.configFilePath)
            parcel.writeString(params.gameTitle)
            parcel.writeString(params.stateBasePath)
            parcel.writeString(params.piannoliRoot)
            parcel.writeString(params.platformTag)
            parcel.writeString(params.platformName)
            parcel.writeIntArray(params.igmTriggerKeycodes.toIntArray())
            parcel.writeInt(1)
            parcel.writeInt(1)
            parcel.writeInt(params.preferredRefreshRate!!)
            parcel.writeParcelable(params.colors, 0)
            parcel.writeParcelable(params.displaySettings, 0)
            parcel.writeParcelable(params.inputMapping, 0)
            parcel.setDataPosition(0)

            assertEquals(params.copy(localeTag = ""), RicottaLaunchParams.CREATOR.createFromParcel(parcel))
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `rejects mismatched protocol version before reading other fields`() {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(RICOTTA_PROTOCOL_VERSION + 1) // wrong version as field #1
            parcel.writeString("garbage")                  // would mis-parse if read
            parcel.setDataPosition(0)
            val ex = assertThrows(ProtocolMismatchException::class.java) {
                RicottaLaunchParams.CREATOR.createFromParcel(parcel)
            }
            assertEquals(RICOTTA_PROTOCOL_VERSION + 1, ex.found)
            assertEquals(RICOTTA_PROTOCOL_VERSION, ex.expected)
        } finally {
            parcel.recycle()
        }
    }

    @Test fun `writeToIntent then readFromIntent round trips`() {
        val intent = android.content.Intent()
        val params = sample()
        params.writeToIntent(intent)
        assertEquals(RICOTTA_PROTOCOL_VERSION, intent.getIntExtra(RicottaLaunchParams.EXTRA_PROTOCOL, -1))
        assertEquals(params, RicottaLaunchParams.readFromIntent(intent))
    }

    @Test fun `readFromIntent returns null on missing or mismatched protocol`() {
        val empty = android.content.Intent()
        org.junit.Assert.assertNull(RicottaLaunchParams.readFromIntent(empty))

        val bad = android.content.Intent()
        sample().writeToIntent(bad)
        bad.putExtra(RicottaLaunchParams.EXTRA_PROTOCOL, RICOTTA_PROTOCOL_VERSION + 1)
        org.junit.Assert.assertNull(RicottaLaunchParams.readFromIntent(bad))
    }
}
