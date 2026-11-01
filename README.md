# Piannoli

Piannoli is a customizable Android emulation frontend, forked from
[Cannoli](https://github.com/CannoliHQ/cannoli). It keeps the original's focused,
controller-friendly experience while adding more control over your library,
artwork, and launcher.

Play using built-in libretro cores, RetroArch, or standalone emulators.

## What's new

- **In-app artwork scraping:** Search SteamGridDB, preview results, and choose
  artwork for individual games, Ports, and systems.
- **Bulk scraping:** Scrape games or systems in automatic or interactive mode,
  with skip/overwrite controls and configurable handling of unmatched titles.
- **Custom artwork:** Choose your own JPG, PNG, WebP, or GIF image for platforms
  and main-menu categories. GIF imports use the first frame.
  Subfolder artwork is shared across the local library by folder name
  (case-insensitive, regardless of platform or parent folder). New selections
  override older individual folder artwork without deleting it. **Delete Shared
  Art (restore individual)** removes the shared image for all matching folders
  and restores any older individual artwork; **Delete Art** then removes only
  the selected folder's individual artwork. Game covers are unaffected.
- **Independent artwork styles:** Adjust game and system artwork separately,
  including scale, width, and corner radius. System artwork supports Logo, Icon,
  and Grid types. **Artwork: Show/Hide** is the first option in each submenu. Both default
  to Show; Hide removes the panel (including category fallback icons) without
  changing saved images, width, or style. Game artwork also applies to RomM
  library covers and game details. Hidden categories skip image lookup, decoding,
  display requests, and fallback painting; their queued recent selections are dropped.
  A native decode already running may finish, but its result is discarded unless
  a still-visible category also needs it. Existing images may remain in the bounded
  shared cache; hiding one category does not evict the other's artwork.
  Scraping and choosing artwork remain available while hidden.
- **Background library artwork:** List selection moves immediately; one shared
  worker prioritizes the current uncached image, then a small newest-first backlog
  of recent selections. Already decoded images display immediately from a shared
  24 MiB memory cache. Artwork revisions invalidate cache hits.
  Pending images leave an empty artwork panel rather than showing the previous
  selection. One shared decoder limits concurrent work; artwork does not start
  loading while the launcher is inactive or the artwork panel is disabled.
- **Flexible library scanning:** Find ROMs across multiple system-folder aliases,
  filter extensions per platform, and choose whether hidden files are included.
- **Hidden game recovery:** Unhide games from **Settings > Library > Manage Hidden
  Games**, not the main menu.
- **Customizable controls:** Remap launcher actions and choose between the
  controller-friendly Piannoli keyboard and Android's system keyboard.
  Hold the Piannoli keyboard's delete action or touch backspace to delete rapidly
  after a 400 ms delay (one character every 60 ms); release to stop.
  Android Back and controller Mode buttons act once per press, without held-key repeats.
  Enable optional **Settings > Input > Haptic feedback** for menu navigation,
  confirm, and back actions, plus typing on the Piannoli keyboard (the system
  keyboard keeps its own feedback). It defaults off, works independently of
  navigation sound, and respects Android's haptic settings and device support.
- **Apps and Ports:** Newly added shortcuts use their Android app icons by
  default. Pin up to five Apps to the top without adding them to Favorites.
  Select shortcuts via **Settings > Library > Manage Apps**. Missing/empty category
  names and the exact former stock name `Tools` display as `Apps`; other custom
  names (including different capitalization) are preserved. The legacy settings,
  shortcut storage, and artwork identity remain unchanged, so assigned images stay.
- **Smoother game returns:** Preserve your selection, search, scroll position,
  and library navigation, while reducing launcher activity in the background.
- **List navigation:** Held directional input and page jumps stop at either end,
  suppressing further actions, sound, and vibration until release or direction change.
  Release and press again to wrap single-step navigation; page jumps stay bounded.
- **Search navigation:** Back from launcher search results reopens the saved
  query. Cancel the keyboard to return to the original unfiltered list and
  position (or the main menu for a library-wide search).

## Getting started

Add your game folders and configure your emulators in the app. Use
**Settings > Appearance > Artwork** to customize presentation.

For scraping, open **Setup scrapers > Setup SteamGridDB** in the artwork settings
and add a [personal SteamGridDB API key](https://www.steamgriddb.com/profile/preferences/api).
Then use a game or platform submenu to scrape individual artwork, or
**Quick Menu > Scrape All / Scrape Systems** for a bulk run.

ScreenScraper integration is experimental and currently hidden from game menus.

### Original Xbox

Use an `XBOX`, `Microsoft Xbox`, or `Original Xbox` ROM folder (case-insensitive).
Piannoli scans `.iso`, `.xiso`, `.xboe`, and `.dotno` files and offers **X1 BOX**
and **hakuX** as standalone emulators; no libretro core is bundled or assigned.
X1 BOX receives a content URI string in `AutoStartFile`; hakuX receives it as
intent data, both with read access. Piannoli does not force-stop X1 BOX.

The emulator picker currently filters by installation, not file extension.
Choose X1 BOX for `.xboe`/`.dotno`; hakuX is specified for `.iso`/`.xiso` only.
These launch mappings have not been verified on emulator hardware.

## Development

The Android project can be opened in Android Studio. Active fork development is
on `develop`; `main` currently preserves the upstream starting point.

## Credits and license

Built on Cannoli by [CannoliHQ](https://github.com/CannoliHQ), whose original
design was inspired by [MinUI](https://github.com/shauninman/MinUI).

Licensed under [GPL-3.0](LICENSE). Game artwork belongs to its respective creators.

Favorites, Recently Played, and Apps have large, transparent, theme-tinted default
artwork (star, history clock, and four-cell application grid). These follow system
artwork scale, width, and radius settings in both home layouts; width 0 hides them.
All system/category artwork is centered and capped at 60% of its available content
panel height, after title, controls, insets, and layout padding, regardless of width.
Fit preserves the image's aspect ratio; other scales are clipped within the cap.
Transparent category defaults retain their full silhouette in fill modes too.
Game artwork settings are unchanged.
Game and system/category artwork reserve the app status bar's measured height at
the top of their panels, including RomM covers. Titles and lists stay in place;
an empty or hidden status bar reserves no space. System/category size caps use
the remaining panel height.
Custom artwork for the selected Logo/Icon/Grid type takes priority, including while
loading or if its file is corrupt. Removing the custom image restores the default;
defaults are not saved as custom images and do not affect scraping or image assignment.
Ports and Collections do not receive these defaults.

The three icons are [Font Awesome Free 6.7.2](https://fontawesome.com) by Fonticons,
Inc., Copyright 2024, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
SVGs were converted to Android vectors and tinted; no runtime library is added.
This permits royalty-free commercial use with attribution, not attribution-free use.
See [icon attribution](licenses/libraries/font-awesome-free-CC-BY-4.0.txt) and in-app credits.
