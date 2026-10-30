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
- **Independent artwork styles:** Adjust game and system artwork separately,
  including scale, width, and corner radius. System artwork supports Logo, Icon,
  and Grid types.
- **Flexible library scanning:** Find ROMs across multiple system-folder aliases,
  filter extensions per platform, and choose whether hidden files are included.
- **Customizable controls:** Remap launcher actions and choose between the
  controller-friendly Piannoli keyboard and Android's system keyboard.
- **Tools and Ports:** Newly added shortcuts use their Android app icons by
  default. Pin up to five Tools to the top without adding them to Favorites.
- **Smoother game returns:** Preserve your selection, search, scroll position,
  and library navigation, while reducing launcher activity in the background.

## Getting started

Add your game folders and configure your emulators in the app. Use
**Settings > Appearance > Artwork** to customize presentation.

For scraping, open **Setup scrapers > Setup SteamGridDB** in the artwork settings
and add a [personal SteamGridDB API key](https://www.steamgriddb.com/profile/preferences/api).
Then use a game or platform submenu to scrape individual artwork, or
**Quick Menu > Scrape All / Scrape Systems** for a bulk run.

ScreenScraper integration is experimental and currently hidden from game menus.

## Development

The Android project can be opened in Android Studio. Active fork development is
on `develop`; `main` currently preserves the upstream starting point.

## Credits and license

Built on Cannoli by [CannoliHQ](https://github.com/CannoliHQ), whose original
design was inspired by [MinUI](https://github.com/shauninman/MinUI).

Licensed under [GPL-3.0](LICENSE). Game artwork belongs to its respective creators.
