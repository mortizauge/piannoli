package dev.piannoli.scorza.input.repo

import dev.piannoli.scorza.input.AnalogRole
import dev.piannoli.scorza.input.CanonicalButton
import dev.piannoli.scorza.input.DeviceMatchRule
import dev.piannoli.scorza.input.DeviceMapping
import dev.piannoli.scorza.input.GlyphStyle
import dev.piannoli.scorza.input.HatDirection
import dev.piannoli.scorza.input.InputBinding
import dev.piannoli.scorza.input.MappingSource
import org.junit.Assert.assertEquals
import org.junit.Test

class MappingIniSerializerTest {

    private val sample = DeviceMapping(
        id = "stadia_controller",
        displayName = "Stadia Controller",
        match = DeviceMatchRule(
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
        ),
        bindings = linkedMapOf(
            CanonicalButton.BTN_SOUTH to listOf(InputBinding.Button(96)),
            CanonicalButton.BTN_L2 to listOf(
                InputBinding.Axis(
                    axis = 17,
                    restingValue = -1f,
                    activeMin = 0f,
                    activeMax = 1f,
                    digitalThreshold = 0.5f,
                ),
                InputBinding.Button(104),
            ),
            CanonicalButton.BTN_UP to listOf(
                InputBinding.Hat(axis = 16, direction = HatDirection.UP),
            ),
        ),
        menuConfirm = CanonicalButton.BTN_EAST,
        menuBack = CanonicalButton.BTN_SOUTH,
        glyphStyle = GlyphStyle.PLUMBER,
        excludeFromGameplay = false,
        defaultControllerTypeId = null,
        source = MappingSource.RETROARCH_AUTOCONFIG,
        userEdited = false,
    )

    @Test
    fun round_trip_preserves_all_fields() {
        val ini = MappingIniSerializer.toIni(sample)
        val parsed = MappingIniSerializer.fromIni(id = "stadia_controller", ini = ini)
        assertEquals(sample, parsed)
    }

    @Test
    fun ini_text_uses_expected_section_names() {
        val ini = MappingIniSerializer.toIni(sample)
        assertEquals(true, ini.contains("[meta]"))
        assertEquals(true, ini.contains("[match]"))
        assertEquals(true, ini.contains("[menu]"))
        assertEquals(true, ini.contains("[glyph]"))
        assertEquals(true, ini.contains("[behavior]"))
        assertEquals(true, ini.contains("[binding.BTN_SOUTH]"))
        assertEquals(true, ini.contains("[binding.BTN_L2]"))
        assertEquals(true, ini.contains("[binding.BTN_UP]"))
    }

    @Test
    fun parsing_unknown_canonical_button_section_is_skipped() {
        val ini = """
            [meta]
            display_name=Test
            source=USER_WIZARD
            user_edited=false

            [match]
            name=Test

            [menu]
            confirm=BTN_EAST
            back=BTN_SOUTH

            [glyph]
            style=GENERIC

            [behavior]
            exclude_from_gameplay=false

            [binding.BTN_SOUTH]
            0=button:96

            [binding.NOT_A_REAL_BUTTON]
            0=button:99
        """.trimIndent()
        val t = MappingIniSerializer.fromIni("test", ini)
        assertEquals(setOf(CanonicalButton.BTN_SOUTH), t.bindings.keys)
    }

    @Test
    fun analog_role_round_trips_for_stick_axes() {
        val template = sample.copy(
            bindings = mapOf(
                CanonicalButton.BTN_L3 to listOf(
                    InputBinding.Axis(
                        axis = 0,
                        restingValue = 0f,
                        activeMin = -1f,
                        activeMax = 1f,
                        digitalThreshold = 0.5f,
                        analogRole = AnalogRole.LEFT_STICK_X,
                    )
                )
            )
        )
        val ini = MappingIniSerializer.toIni(template)
        val parsed = MappingIniSerializer.fromIni("stadia_controller", ini)
        val axis = parsed.bindings[CanonicalButton.BTN_L3]!![0] as InputBinding.Axis
        assertEquals(AnalogRole.LEFT_STICK_X, axis.analogRole)
    }
}
