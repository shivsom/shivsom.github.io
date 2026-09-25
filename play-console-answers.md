# Play Console answers: Crooked Guess

Play Console asks these under **Policy > App content** before the first release.
They match how the app is built right now (AdMob rewarded videos, LAN Versus, scores kept on the device).
If you add features later, check the answers again.

## Privacy policy
URL of your hosted `privacy-policy.html` (README step 7 shows a free way to host it on GitHub Pages).

## Ads
**Yes, my app contains ads.** (Only rewarded videos the player chooses to watch for an extra hint.)

## App access
**All functionality is available without special access.** There's no login.

## Content rating (IARC questionnaire)
- Category: **Game**, puzzle.
- Violence, fear, sexuality, language, drugs, gambling: **No** to all.
- Users can interact or exchange content: Versus only sends names and game moves between two phones on the same local network, with no chat. Answer **No** to "users can communicate with each other" unless Play's wording clearly covers this; if you're unsure, answering Yes only adds a note to the rating.
- Shares user location with other users: **No**. Digital purchases: **No**.
- Expected result: rated for everyone (Everyone / PEGI 3 / 3+).

## Target audience and content
- Target age groups: **13-15, 16-17, 18 and over.**
- Why not under 13: an app aimed at children must follow the Families policy (child-directed ad settings, a stricter review). If you ever want to include under-13s, first set `tagForChildDirectedTreatment` in the AdMob setup and read Play's Families policy.
- "Could your store listing unintentionally appeal to children?" The game is colourful, so Play may ask. Answer honestly: it's a logic puzzle designed for teens and adults.

## Data safety
Everything the app itself saves (settings, name, scores) stays on the device, so it's **not collected**. What *is* collected comes from the Google Mobile Ads SDK (AdMob). Google's own guide for this is at developers.google.com/admob/android/privacy/play-data-disclosure.

**Does your app collect or share any of the required user data types?** Yes.
**Is all of the user data collected by your app encrypted in transit?** Yes.
**Do you provide a way for users to request that their data is deleted?** No account exists; data on the device is deleted with Reset scoreboard / uninstall. You can answer No, or add your email as the way to ask.

| Data type (Play's name) | Collected | Shared | Processed ephemerally | Required or optional | Purposes |
|---|---|---|---|---|---|
| Location > Approximate location (from IP address) | Yes | Yes | No | Required | Advertising or marketing, Analytics, Fraud prevention, security and compliance |
| App activity > App interactions | Yes | Yes | No | Required | Advertising or marketing, Analytics, Fraud prevention, security and compliance |
| App info and performance > Diagnostics | Yes | Yes | No | Required | Analytics |
| Device or other IDs | Yes | Yes | No | Required | Advertising or marketing, Analytics, Fraud prevention, security and compliance |

Not collected: name, email, contacts, photos, messages, precise location, financial info, health, files, calendar.
The Versus player name is sent straight to the other player's phone on the local network and never reaches you (the developer), so it isn't "collected" in Play's sense.

## Advertising ID
**Yes**, the app uses the advertising ID (the AdMob SDK adds the `AD_ID` permission automatically). Purpose: **Advertising or marketing** (and Analytics, Fraud prevention).

## Government apps, financial features, health, news, COVID-19
**No** / not applicable.

## Store settings
- App or game: **Game**. Category: **Puzzle**.
- Contact: soham1studio@gmail.com.
