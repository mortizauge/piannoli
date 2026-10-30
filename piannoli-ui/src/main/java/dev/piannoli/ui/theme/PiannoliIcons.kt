package dev.piannoli.ui.theme

/**
 * Every Nerd Font glyph the launcher draws, in one place.
 *
 * [glyphName] is resolved out of the cmap of app/src/main/assets/fonts/MPlus-1c-NerdFont-Bold.ttf,
 * not copied from an MDI listing. Nerd Fonts remap Material Design Icons into the private use
 * area at whatever MDI version they bundled, and those assignments move between releases, so a
 * codepoint taken from a cheat sheet usually still renders something and is usually the wrong
 * glyph. The debug icon gallery renders [all], so what you see there is what the app draws.
 */
data class PiannoliIcon(
    val glyph: String,
    val constantName: String,
    val glyphName: String,
    val purpose: String,
    val category: String,
)

object PiannoliIcons {

    // Status bar
    val Wifi = PiannoliIcon("\uDB81\uDDA9", "Wifi", "md-wifi", "Wi-Fi is connected", "Status bar")
    val Bluetooth = PiannoliIcon("\uDB80\uDCAF", "Bluetooth", "md-bluetooth", "A Bluetooth device is connected", "Status bar")
    val Vpn = PiannoliIcon("\uDB82\uDFC4", "Vpn", "md-shield_key", "A VPN is active", "Status bar")
    val Update = PiannoliIcon("\uDB81\uDEB0", "Update", "md-update", "An app update is available", "Status bar")
    val Download = PiannoliIcon("\uF019", "Download", "fa-download", "A download is in progress", "Status bar")
    val Charging = PiannoliIcon("\uF0E7", "Charging", "fa-flash", "The device is charging", "Status bar")
    val BatteryFull = PiannoliIcon("\uDB80\uDC79", "BatteryFull", "md-battery", "Battery full", "Status bar")
    val Battery90 = PiannoliIcon("\uDB80\uDC82", "Battery90", "md-battery_90", "Battery at 90 percent", "Status bar")
    val Battery80 = PiannoliIcon("\uDB80\uDC81", "Battery80", "md-battery_80", "Battery at 80 percent", "Status bar")
    val Battery70 = PiannoliIcon("\uDB80\uDC80", "Battery70", "md-battery_70", "Battery at 70 percent", "Status bar")
    val Battery60 = PiannoliIcon("\uDB80\uDC7F", "Battery60", "md-battery_60", "Battery at 60 percent", "Status bar")
    val Battery50 = PiannoliIcon("\uDB80\uDC7E", "Battery50", "md-battery_50", "Battery at 50 percent", "Status bar")
    val Battery40 = PiannoliIcon("\uDB80\uDC7D", "Battery40", "md-battery_40", "Battery at 40 percent", "Status bar")
    val Battery30 = PiannoliIcon("\uDB80\uDC7C", "Battery30", "md-battery_30", "Battery at 30 percent", "Status bar")
    val Battery20 = PiannoliIcon("\uDB80\uDC7B", "Battery20", "md-battery_20", "Battery at 20 percent", "Status bar")
    val Battery10 = PiannoliIcon("\uDB80\uDC7A", "Battery10", "md-battery_10", "Battery at 10 percent", "Status bar")
    val BatteryAlert = PiannoliIcon("\uDB80\uDC83", "BatteryAlert", "md-battery_alert", "Battery critically low", "Status bar")

    // Status bar sync
    val CloudSync = PiannoliIcon("\uDB81\uDE3F", "CloudSync", "md-cloud_sync", "Save sync in progress", "Status bar sync")
    val CloudCheck = PiannoliIcon("\uDB80\uDD60", "CloudCheck", "md-cloud_check", "Saves are in sync", "Status bar sync")
    val CloudAlert = PiannoliIcon("\uDB82\uDDE0", "CloudAlert", "md-cloud_alert", "Save sync hit a conflict", "Status bar sync")
    val CloudOff = PiannoliIcon("\uDB80\uDD64", "CloudOff", "md-cloud_off_outline", "Save sync is offline", "Status bar sync")
    val AlertCircle = PiannoliIcon("\uDB80\uDC28", "AlertCircle", "md-alert_circle", "Save sync is in an error state", "Status bar sync")
    val DatabaseSync = PiannoliIcon("\uDB83\uDCFF", "DatabaseSync", "md-database_sync", "RomM library cache is syncing", "Status bar sync")
    val DatabaseAlert = PiannoliIcon("\uDB85\uDE3A", "DatabaseAlert", "md-database_alert", "RomM library cache sync failed", "Status bar sync")

    // Dialogs
    val Primary = PiannoliIcon("\u2605", "Primary", "uni2605", "Multi-disc picker: the primary disc", "Dialogs")
    val CheckCircle = PiannoliIcon("\uF058", "CheckCircle", "fa-ok_sign", "Download list: this download finished", "Dialogs")
    val SyncDownload = PiannoliIcon("\uF063", "SyncDownload", "fa-arrow_down", "Save sync: pulling from the server", "Dialogs")
    val SyncUpload = PiannoliIcon("\uF062", "SyncUpload", "fa-arrow_up", "Save sync: pushing to the server", "Dialogs")
    val SyncAlert = PiannoliIcon("\uF071", "SyncAlert", "fa-warning", "Save sync: conflict or error on this save", "Dialogs")

    // Emulator mapping
    val NotInstalled = PiannoliIcon("\uDB80\uDC26", "NotInstalled", "md-alert", "Core or app confirmed absent", "Emulator mapping")
    val Unknown = PiannoliIcon("\uDB81\uDE25", "Unknown", "md-help_circle_outline", "Cannot tell whether the core is installed", "Emulator mapping")

    // RomM lists
    val Variants = PiannoliIcon("\uDB85\uDFF2", "Variants", "md-card_multiple_outline", "This row folds multiple versions", "RomM lists")
    val Pin = PiannoliIcon("\uDB81\uDC03", "Pin", "md-pin", "This tool is pinned to the top", "Library lists")

    /** Declaration order, which the gallery groups by [PiannoliIcon.category]. */
    val all: List<PiannoliIcon> = listOf(
        Wifi, Bluetooth, Vpn, Update,
        Download, Charging, BatteryFull,
        Battery90, Battery80, Battery70, Battery60,
        Battery50, Battery40, Battery30, Battery20,
        Battery10, BatteryAlert, CloudSync, CloudCheck,
        CloudAlert, CloudOff, AlertCircle, DatabaseSync,
        DatabaseAlert, Primary, CheckCircle, SyncDownload,
        SyncUpload, SyncAlert, NotInstalled, Unknown,
        Variants, Pin,
    )
}
