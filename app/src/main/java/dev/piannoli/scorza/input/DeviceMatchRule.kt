package dev.piannoli.scorza.input

data class MatchInput(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val androidBuildModel: String,
    val sourceMask: Int,
    val descriptor: String? = null,
)

data class DeviceMatchRule(
    val name: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val androidBuildModel: String? = null,
    val sourceMask: Int? = null,
    val descriptor: String? = null,
) {
    fun score(input: MatchInput): Int {
        // Hard reject when the rule and the input both name a device and the names disagree.
        // Without this gate, two physically different controllers that report the same VID/PID
        // (Retroid handhelds fake VID/PID for built-in + BT pads) and run on the same Build.MODEL
        // score high enough on those two signals alone to inherit each other's saved mappings,
        // even though the names plainly say they're different devices. Compare trimmed strings
        // so kernel-side whitespace inconsistencies (trailing spaces on some Android builds) do
        // not break legitimate matches.
        //
        // Exception: when the names share a non-trivial prefix at a word boundary, treat it as
        // the same physical pad surfaced under a different EventHub sub-device label. Android
        // merges multi-interface HID controllers (e.g. GameSir-Pocket exposes "Keyboard" /
        // "Consumer Control" / "GameSir-Pocket 1" evdev nodes) into one InputDevice but seeds
        // the merged name from whichever sub-device opened first, so the surfaced name flips
        // across boots while VID/PID stay stable. The Retroid VID/PID-collision case still
        // rejects because the colliding pads (built-in vs. BT) share no meaningful prefix.
        val ruleName = name?.trim()
        val inputName = input.name.trim()
        if (!ruleName.isNullOrEmpty() && inputName.isNotEmpty() && ruleName != inputName) {
            if (sharedPrefixAtWordBoundary(ruleName, inputName) < MIN_NAME_PREFIX_LEN) {
                return 0
            }
        }

        var score = 0

        val ruleVid = vendorId
        val rulePid = productId
        val vidPidMatched = ruleVid != null && rulePid != null && ruleVid != 0 && rulePid != 0 &&
            ruleVid == input.vendorId && rulePid == input.productId
        if (vidPidMatched) {
            score += 100
        }

        // Name accrues independently of VID/PID. Compositing the two strengthens the signal when
        // both agree (the strongest identification Android reliably provides), and lets name carry
        // the match on its own when VID/PID is 0:0 (common on Bluetooth controllers whose kernel
        // driver does not surface VID/PID through InputDevice).
        if (!ruleName.isNullOrEmpty() && ruleName == inputName) {
            score += 50
        }

        val ruleModel = androidBuildModel
        if (ruleModel != null && ruleModel.isNotEmpty() && ruleModel == input.androidBuildModel) {
            score += 100
        }

        // Descriptor is a tiebreaker, not a primary key. Android salts the descriptor with a
        // per-session nonce on some kernels (notably for BT controllers and phantom-rewrite hosts),
        // so descriptor stability across reconnect cycles is not guaranteed. Keep a small bonus so
        // that on stable kernels two physically distinct pads of the same make/model can still be
        // distinguished by their descriptors, but rely on name+VID/PID for the main signal.
        val ruleDescriptor = descriptor
        val inputDescriptor = input.descriptor
        val descriptorMatched = ruleDescriptor != null && ruleDescriptor.isNotEmpty() &&
            inputDescriptor != null && inputDescriptor == ruleDescriptor
        if (descriptorMatched) {
            score += 30
        }

        val ruleMask = sourceMask
        if (ruleMask != null && ruleMask != 0 && (ruleMask and input.sourceMask) == ruleMask) {
            score += 10
        }

        return score
    }

    private companion object {
        const val MIN_NAME_PREFIX_LEN = 5

        fun sharedPrefixAtWordBoundary(a: String, b: String): Int {
            val rawCommon = a.commonPrefixWith(b)
            if (rawCommon.length < MIN_NAME_PREFIX_LEN) return 0
            val aRest = a.substring(rawCommon.length)
            val bRest = b.substring(rawCommon.length)
            val endsInWhitespace = rawCommon.last().isWhitespace()
            val aBoundary = aRest.isEmpty() || aRest[0].isWhitespace()
            val bBoundary = bRest.isEmpty() || bRest[0].isWhitespace()
            if (!endsInWhitespace && !aBoundary && !bBoundary) return 0
            return rawCommon.trimEnd().length
        }
    }
}
