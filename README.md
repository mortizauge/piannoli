# Piannoli

Piannoli is a customizable Android emulation frontend, forked from
[Cannoli](https://github.com/CannoliHQ/cannoli). It keeps a focused,
controller-friendly experience while giving you more control over your library
and its appearance.

Play using supported standalone emulators or RetroArch. Piannoli is a frontend:
it no longer includes an emulator engine, bundled cores or an in-game menu.

## What's new

- **In-app scraping:** Find artwork for games and systems through SteamGridDB,
  individually or in bulk.
- **Personalized artwork:** Choose your own game, folder, and category images,
  customize their presentation, or hide artwork entirely.
- **Flexible libraries:** Scan alternative system-folder names, filter file
  types, and hide or restore individual games.
- **Apps and Ports:** Launch Android shortcuts with their app icons and pin
  your most-used apps. New entries join alphabetically without resetting manual
  ordering or pins; scanned games sort alphabetically within their folder or platform group.
- **Customizable input:** Remap navigation controls, choose your keyboard, and enable
  navigation sounds and haptic feedback.
- **Smoother browsing:** Background artwork loading, improved scrolling and
  search navigation, and preserved browsing positions when returning from games.
- **Original Xbox support:** Launch games with X1 BOX or hakuX.

## Getting started

Add your game folders and configure your emulators in the app. Customize artwork
under **Settings > Appearance > Artwork**.

Unset platforms and **Reset** prefer the first installed supported standalone in
the platform's curated app list, then an installed core in the configured RetroArch
package. Missing-app/core guidance
opens **Emulator Mapping**, where you can install/configure an external emulator
and its cores. Existing external platform and per-game choices are preserved.
Legacy **Built-in** choices are cleared to adopt the external auto default,
without deleting or converting any save data.
If no RetroArch package was configured, Piannoli selects an installed package
(RicottaArch first, then 64-bit RetroArch, then RetroArch). An explicit package
selection is kept even if that app is later uninstalled.

Return using the emulator's quit/back controls; Piannoli retains your library,
search and browsing position. Standalone apps manage their own saves and need
their own ROM/storage setup; Piannoli does not promise shared states or Resume.
RicottaArch supports Piannoli's resume slots; stock RetroArch uses its own controls.
New RicottaArch states are isolated by package/core under
`Save States/<system>/<game>/External/` so built-in or different-core states are
not offered for external Resume. Legacy built-in states are left untouched but
cannot be resumed by Piannoli; existing external states can be loaded
manually in their original emulator. Battery saves and existing save-sync identity
are unchanged; standalone save syncing requires files in Piannoli's supported
save locations. RicottaArch and Delfino launch contracts, controller mappings,
theme settings and external save locations remain supported. Browse-time
PDF/text/image guides, bookmarks, RomM save synchronization and artwork remain
frontend features.

RetroAchievements credentials and offline metadata remain available independently
of emulation. Cartridge identification is managed Kotlin (including header
normalization, N64 byte order and arcade romset names). PlayStation identification
also runs in Kotlin: ISO, BIN/IMG, CUE (Mode 1/2 data tracks) and M3U playlists
use the official rcheevos boot-executable/name hash, not a whole-disc MD5.
Playlists identify their first disc; CUE references and stored pregaps are honored.
CHD/PBP and other unsupported disc containers require a known RA game ID, for
example from RomM; the removed native reader had no CHD/PBP decoding callbacks.
These containers remain playable externally, but are not offered uncached RA
preloading. Other disc consoles also require a known game ID.
Unsupported identification is logged and reports a failure rather than
claiming a game has no achievements. Actual achievement execution belongs to the
external emulator.

## Building

Use JDK 21 and an Android SDK with API 36. No NDK, CMake, engine submodules or core
downloads are required:

```sh
./scripts/fetch-autoconfig.sh
./gradlew :app:assembleDebug
```

Controller profiles come from the public
[libretro autoconfig library](https://github.com/libretro/retroarch-joypad-autoconfig);
the fetch step preserves its license and fails visibly on download errors.
Shader assets and their notices remain for the external RicottaArch integration.

For scraping, add a [SteamGridDB API key](https://www.steamgriddb.com/profile/preferences/api)
through **Setup scrapers**, then use game/platform menus or the Quick Menu.
More scraping sources are planned for future updates.

## Credits and license

Built on Cannoli by [CannoliHQ](https://github.com/CannoliHQ), inspired by
[MinUI](https://github.com/shauninman/MinUI). Licensed under [GPL-3.0](LICENSE).
Game artwork belongs to its respective creators.

Category icons are adapted from [Font Awesome Free 6.7.2](https://fontawesome.com)
by Fonticons, Inc., Copyright 2024, under
[CC BY 4.0](licenses/libraries/font-awesome-free-CC-BY-4.0.txt).
