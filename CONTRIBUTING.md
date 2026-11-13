# Contributing to Trackly

Thanks for your interest. Issues and pull requests are welcome.

## Before you start

- For anything larger than a small fix, open an issue first so we can agree on the approach.
- The interface and the assistant's instructions are in French today. Translations (Android string resources) and
  an English assistant prompt are very welcome.

## Ground rules of the app

These rules are what make Trackly trustworthy; changes must keep them:

1. **The app computes the numbers.** Totals, what is left, goals and reviews come from the `:core` rules engine.
   The model never does arithmetic that is shown to the user as a fact.
2. **The user stays in control of the journal.** Only what the user says they ate is recorded; suggestions need an
   explicit tap. Every entry can be undone.
3. **No silent rewrites.** Corrections and deletions create a new version (`state: deleted`), never a physical
   delete. Goals, calories and meal templates are never adjusted automatically.
4. **No photos are kept**, only the structured data the user accepted.
5. **No new network destinations** besides OpenAI (and the user's own Google Drive when enabled). No analytics.
6. **Schema changes** need a Room migration and a migration test.

## Development

```sh
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:verifyRoborazziDebug     # screenshot tests
```

- Keep business rules in `:core` and test them there (plain JVM, fast).
- UI changes: re-record screenshots with `./gradlew :app:recordRoborazziDebug` and include the updated PNGs.
- Tests must never call the real OpenAI API; use the fakes and the JSON responses in
  `app/src/test/resources/openai/`. Live checks are opt-in (`-PliveChecks=true`).
- Never commit keys, keystores, `local.properties`, or real personal data. Test data must be fictional.

## Pull requests

- One topic per pull request, with tests.
- Describe what changes for the user and how you tested it.
- By contributing, you agree that your contribution is licensed under the AGPL-3.0.
