# Localization

GrowBuddy ships in **English** (the default) and **Danish**. Every language
lives in its own files, so a new translation needs no code changes.

| Part | English original | A translation |
|---|---|---|
| Android app | `android/app/src/main/res/values/strings.xml` | `android/app/src/main/res/values-<code>/strings.xml` |
| Push notifications (server) | `server/src/growbuddy/locales/en.json` | `server/src/growbuddy/locales/<code>.json` |

`<code>` is the two-letter ISO 639-1 code (`da`, `de`, `sv`, `nl`, …). Use
the same code in both places, so the app can set the matching notification
language.

## Choosing the language

- **App:** Settings › Language lists "System default" and every language in
  the APK (Android 13 and newer; the list comes from the `values-<code>`
  folders through AGP's generated locale config). Older Android versions
  follow the phone's language. Android's own per-app language setting works
  too.
- **Notifications:** picking a language in the app also sets the server's
  notification language (`alerts.language`), if the server has a file for it.
  It can also be set separately in Alerts › ⚙ › Notification language.

## Adding a language to the app

1. Copy `values/strings.xml` to `values-<code>/strings.xml`.
2. Translate the text between the tags. Leave alone:
   - the `name="…"` keys and entries marked `translatable="false"`;
   - placeholders such as `%1$s` and `%2$d` (their order in the sentence may
     change, their numbers may not);
   - `%%`, which is a literal percent sign in strings with placeholders;
   - `\'` and `\"`, the escaped apostrophe and quote.
3. Plurals (`<plurals>`) need the quantities your language uses
   (`one`/`other` for most European languages).
4. Build and check: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
   `LocalizationTest` reports unknown keys and placeholder mismatches; lint
   reports missing strings (`MissingTranslation`). A missing string falls back
   to English at runtime.

Strings that only exist in code are a bug: all visible text belongs in
`strings.xml`. Composables use `stringResource()`, everything else
(view models, notifications, widgets) `appString()` from `ui/common/AppText.kt`.

## Adding a notification language to the server

Copy `en.json` to `<code>.json` and translate the values. The keys and the
`{placeholders}` must stay the same (`tests/test_messages.py` checks both).
Details are in `server/src/growbuddy/locales/README.md`.

## What isn't translated

- **Content you or the seed files provide:** plant, sensor and strain names,
  the strain catalog's descriptions (Danish, from the source shop), feeding
  schedule notes, nutrient names and log comments.
- **Server error messages** shown in the app (for example a refused delete)
  and the **system event log**: these come from the server in English. Alert
  events are written in the notification language at the time.
- Units and abbreviations that are the same everywhere (°C, kPa, pH, EC, VPD).
