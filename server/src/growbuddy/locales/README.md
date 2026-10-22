# Notification languages

Push notifications (alerts, "back to normal", the test message) are written by
the server in the language set in the app (Settings › Language, or the alert
settings), stored as `alerts.language`.

To add a language, copy `en.json` to `<code>.json` (an ISO 639-1 code such as
`de` or `sv`, matching the app's `values-<code>` folder) and translate the
values:

- Keep every key, and keep the `{placeholders}` exactly as they are
  (`tests/test_messages.py` checks both). `{Metric}` is the metric name with a
  capital first letter, `{metric}` without.
- `language_name` is the language's own name ("Deutsch"), shown in the app.
- `decimal_separator` is `.` or `,`.

The new file is picked up on the next server start; no code changes needed.
