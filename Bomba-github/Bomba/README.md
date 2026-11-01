# Bomba

Seven app icons on a floating soccer-ball frame. Touch an icon to open that app.

## Get the APK
1. Upload everything in this folder to a GitHub repo, on the `main` branch.
2. Open the repo's Actions tab. "Build APK" starts on its own, or tap "Run workflow".
3. When the run turns green, open it and download "Bomba-apk" at the bottom.
4. Unzip it on your phone and tap the .apk to install.

## Change an app link
Edit app/src/main/assets/index.html, find the app's name near the top of the script,
and paste the link into its url:"". Commit, and a new APK builds.

## Apps inside the APK
Aquatics Tech, Parks Map and Field Reports are copied into app/src/main/assets/apps/,
so they open full screen inside Bomba with no header. Press Back to return to the ball.
When you change one of those apps on Claude, ask for a fresh copy to put in that folder.

## Field Reports saving
In the APK, Field Reports saves on the phone. Export (CSV) and "Back up to OneDrive"
open the share menu — pick OneDrive. "Restore from backup" reads a backup file back in.

## Home screen + live wallpaper
- Home screen: open Bomba, swipe up from the bottom, tap "Home screen settings", pick Bomba.
- Wallpaper: in the same drawer tap "Bomba wallpaper", then "Set wallpaper" (choose Home and lock screen if asked).
- Swipe up from the bottom of the ball for all your phone's apps.

## Bomba Live (all your screens at once)
- Phone/tablet: Bomba drawer → Live screens. Watch shows every device; Share sends this screen.
- Computers (Windows, Mac): open https://YOUR-GITHUB-NAME.github.io/Bomba/ in Chrome, Edge or Safari → Share.
- Same wall code on every device. iPhone/iPad can watch, not share.
