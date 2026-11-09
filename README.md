# Iqama Times – a Projectivy Launcher wallpaper plugin

Shows your mosque's iqamah times (from its public Masjidbox page) across the top of the
Projectivy home screen. The next iqamah is highlighted in gold, prayers that have passed fade
out, and on Fridays the Dhuhr slot shows Jumu'ah. Times refresh by themselves a few times a day.

Default mosque: **Masjid Al Falah Coventry** (`masjid-al-falah-coventry`).

## 1. Build it (no Android Studio needed)

1. Sign in to <https://github.com> (a free account is fine) and click **New repository**.
   Call it `iqama-times`, make it **Public**, and create it.
2. On the new repo's page click **uploading an existing file**, then drag in **everything
   inside** this folder (including the hidden `.github` folder – on Windows, tick
   *View › Hidden items* in File Explorer first). Click **Commit changes**.
3. Open the **Actions** tab. A "Build APK" run starts on its own and takes about 5 minutes.
   When it's green, your APK is at:

   `https://github.com/Midgetmcnugget/iqama-times/releases/download/latest/iqama-times.apk`

## 2. Install it on the TV

1. On the TV, install **Downloader** (by AFTVnews) from Google Play.
2. Open Downloader, type the link above, and install when asked. (The first time, Android asks
   you to allow Downloader to install apps – allow it.)

## 3. Turn it on in Projectivy

Projectivy › Settings › Appearance › Wallpaper › Launcher wallpaper › **🔌 Iqama Times**.

Set the wallpaper update interval to the shortest option, so the gold "next iqamah"
highlight moves on promptly after each prayer.

## Settings (open the plugin's settings from the same Projectivy screen, or the Iqama Times app)

| Setting | What it does |
| --- | --- |
| Masjidbox mosque | The part after `masjidbox.com/prayer-times/` in the mosque's link, or the whole link |
| Mosque name | Show or hide the name; the panel shrinks to fit when hidden |
| Font | Default (Roboto) or a shortlist of Google Fonts |
| Font by name | Any Google Fonts family, e.g. the one chosen in Projectivy's own font setting |
| Move panel down / Move panel up | Shifts the panel 20 px per click; the home screen updates straight away |
| Reset position | Puts the panel back where it started (190 px from the top) |
| Clock | 24-hour (default) or 12-hour |
| Iqamah after adhan / Maghrib iqamah | For mosques whose Masjidbox page only lists adhan times (e.g. Chingford Mosque): shows iqamah this many minutes after adhan |
| Choose background picture | Pick a picture with a file explorer app on the TV (e.g. X-plore), from the TV or a USB stick |
| Background image URL | Or use a picture from a link |
| Remove background | Back to the green star pattern |
| Refresh times now | Re-reads the mosque's page and shows what it found |

## Phone widget (iqama-widget.apk)

The same times as a home-screen widget for Android phones (Android 8 or newer).

1. On your phone, open `https://github.com/Midgetmcnugget/iqama-times/releases/latest`
   and tap **iqama-widget.apk**. Allow your browser to install apps when asked.
2. Long-press an empty spot on the home screen › **Widgets** › **Iqama Times**, and drag it on.
3. Tap the widget to choose its mosque (pick a recent one or type a new one) and, for mosques
   whose page only lists adhan times, set **Iqamah after adhan**.

* **One row high (e.g. 5×1):** every prayer's name, adhan time (bold) and iqamah time; the
  next prayer's iqamah is in a gold box.
* **Two rows or taller:** a banner with the mosque, the next prayer and a live countdown to its
  adhan or iqamah, above the same row.
* Each widget can show a different mosque. Sunrise, the mosque name and the 12-hour clock can be
  switched on or off. **Allow exact timing** (in settings) makes the highlight and countdown
  switch over exactly at each adhan and iqamah.

## How it works

* `MasjidboxFetcher` opens the mosque's Masjidbox page in an off-screen WebView – the same page
  you'd see in a browser – and reads the times off it. It refreshes when the stored times are
  for another day or more than 6 hours old.
* `WallpaperRenderer` draws a 1920×1080 image; `WallpaperImageProvider` hands it to Projectivy.
* Projectivy asks for a new image every minute (`itemsCacheDurationMillis`), so the highlight
  stays current.

If Masjidbox changes how its page is laid out, the times may stop appearing – the plugin then
keeps showing the last times it read, marked "times from <date>".

Based on Projectivy's official sample: <https://github.com/spocky/projectivy-plugin-wallpaper-provider>
