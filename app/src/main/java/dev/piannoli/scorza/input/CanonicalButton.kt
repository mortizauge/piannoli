package dev.piannoli.scorza.input

// Moved to piannoli-igm so the IGM input translator and the launcher share one enum.
// Kept as a typealias here so existing `dev.piannoli.scorza.input.CanonicalButton` imports
// and `CanonicalButton.BTN_*` references continue to resolve unchanged.
typealias CanonicalButton = dev.piannoli.igm.CanonicalButton
