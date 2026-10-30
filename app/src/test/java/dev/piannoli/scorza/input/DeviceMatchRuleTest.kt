package dev.piannoli.scorza.input

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceMatchRuleTest {

    private val device = MatchInput(
        name = "Stadia Controller",
        vendorId = 6353,
        productId = 37888,
        androidBuildModel = "RP4PRO",
        sourceMask = 0x00000401,
    )

    @Test
    fun vendor_and_product_match_scores_100() {
        val rule = DeviceMatchRule(vendorId = 6353, productId = 37888)
        assertEquals(100, rule.score(device))
    }

    @Test
    fun name_and_vendor_product_match_scores_150() {
        val rule = DeviceMatchRule(
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
        )
        assertEquals(150, rule.score(device))
    }

    @Test
    fun descriptor_only_match_scores_30() {
        val rule = DeviceMatchRule(descriptor = "abcdef")
        val deviceWithDescriptor = device.copy(descriptor = "abcdef")
        assertEquals(30, rule.score(deviceWithDescriptor))
    }

    @Test
    fun name_plus_vid_pid_beats_descriptor_only_for_same_pad_class() {
        val rule = DeviceMatchRule(
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            descriptor = "X",
        )
        val sameDeviceNewDescriptor = device.copy(descriptor = "Y")
        assertEquals(150, rule.score(sameDeviceNewDescriptor))
    }

    @Test
    fun name_only_match_scores_50() {
        val rule = DeviceMatchRule(name = "Stadia Controller")
        assertEquals(50, rule.score(device))
    }

    @Test
    fun android_build_model_match_scores_100() {
        val rule = DeviceMatchRule(androidBuildModel = "RP4PRO")
        assertEquals(100, rule.score(device))
    }

    @Test
    fun source_mask_match_adds_10() {
        val rule = DeviceMatchRule(name = "Stadia Controller", sourceMask = 0x00000401)
        assertEquals(60, rule.score(device))
    }

    @Test
    fun no_fields_set_yields_zero() {
        val rule = DeviceMatchRule()
        assertEquals(0, rule.score(device))
    }

    @Test
    fun vendor_match_without_product_does_not_score_100() {
        val rule = DeviceMatchRule(vendorId = 6353, productId = 0)
        // productId 0 is treated as unset; expect 0 because we require both vid+pid.
        assertEquals(0, rule.score(device))
    }

    @Test
    fun mismatched_name_scores_zero() {
        val rule = DeviceMatchRule(name = "Wrong Controller")
        assertEquals(0, rule.score(device))
    }

    @Test
    fun mismatched_source_mask_does_not_add_bonus() {
        val rule = DeviceMatchRule(name = "Stadia Controller", sourceMask = 0xDEAD)
        // Mask must AND-match a non-zero subset; mismatched mask contributes 0 bonus.
        assertEquals(50, rule.score(device))
    }

    @Test
    fun named_rule_rejects_input_with_different_name_even_when_vid_pid_match() {
        // Retroid kernel fakes the same VID/PID for the internal pad and BT pads, so a saved
        // mapping for the internal pad would otherwise score high on VID/PID + Build.MODEL alone
        // and adopt e.g. a Switch Pro Controller. The name disagreement must be a hard reject.
        val rule = DeviceMatchRule(
            name = "Retroid Pocket Controller",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "RP4PRO",
            descriptor = "dc75afea56e3c3a269b97967aa26b8c93c0bd3fb",
        )
        val proPad = MatchInput(
            name = "Nintendo Switch Pro Controller",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "RP4PRO",
            sourceMask = 0,
            descriptor = "c575e892a6bb353df4b1327e81beedf84b540eb4",
        )
        assertEquals(0, rule.score(proPad))
    }

    @Test
    fun named_rule_still_matches_input_with_empty_name() {
        // If the kernel leaves the device name empty, we fall back to other signals rather than
        // rejecting; the gate only triggers when both sides name a device and the names differ.
        val rule = DeviceMatchRule(name = "Stadia Controller", vendorId = 6353, productId = 37888)
        val noName = device.copy(name = "")
        assertEquals(100, rule.score(noName))
    }

    @Test
    fun named_rule_matching_second_same_model_pad_still_scores_150() {
        // Two physically distinct Pro pads share name + VID/PID and have different descriptors.
        // The saved mapping for pad #1 should still win for pad #2 because nothing about the
        // name signals "different device."
        val rule = DeviceMatchRule(
            name = "Nintendo Switch Pro Controller",
            vendorId = 1406,
            productId = 8201,
            descriptor = "first-pad-descriptor",
        )
        val secondPad = MatchInput(
            name = "Nintendo Switch Pro Controller",
            vendorId = 1406,
            productId = 8201,
            androidBuildModel = "RP4PRO",
            sourceMask = 0,
            descriptor = "second-pad-descriptor",
        )
        assertEquals(150, rule.score(secondPad))
    }

    @Test
    fun named_rule_matches_input_when_names_share_word_boundary_prefix() {
        // GameSir-Pocket exposes three evdev nodes (Keyboard / Consumer Control / GameSir-Pocket 1)
        // and Android seeds the merged InputDevice name from whichever sub-device opened first.
        // The surfaced name flips across boots while VID/PID stay stable, so the gate must allow
        // the match when the names share a real prefix at a word boundary.
        val rule = DeviceMatchRule(
            name = "GameSir-Pocket 1 Keyboard",
            vendorId = 13623,
            productId = 4402,
            descriptor = "90b9e58f89ba3547e8eec21b9fb2ce1adb55fb05",
        )
        val rebootedAs = MatchInput(
            name = "GameSir-Pocket 1 Consumer Control",
            vendorId = 13623,
            productId = 4402,
            androidBuildModel = "moto g play - 2024",
            sourceMask = 0,
            descriptor = "f1507ba28615dd3b7df034bb29eb4efcc97f2e9c",
        )
        assertEquals(100, rule.score(rebootedAs))
    }

    @Test
    fun named_rule_rejects_when_shared_prefix_falls_below_word_boundary_threshold() {
        // Two Retroid pads with faked-identical VID/PID that share only a few characters
        // ("Sony" vs "Sony D..." doesn't apply here — pick names that share <5 chars at a
        // boundary). The gate must still reject.
        val rule = DeviceMatchRule(
            name = "Xbox Wireless Controller",
            vendorId = 8226,
            productId = 12289,
        )
        val otherPad = MatchInput(
            name = "Xbla Pro",
            vendorId = 8226,
            productId = 12289,
            androidBuildModel = "RP4PRO",
            sourceMask = 0,
        )
        assertEquals(0, rule.score(otherPad))
    }

    @Test
    fun all_signals_match_scores_name_plus_vid_pid_plus_mask() {
        val rule = DeviceMatchRule(
            name = "Stadia Controller",
            vendorId = 6353,
            productId = 37888,
            sourceMask = 0x00000401,
        )
        assertEquals(160, rule.score(device))
    }
}
