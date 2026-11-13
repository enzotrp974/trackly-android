# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems. Use GitHub's private vulnerability reporting:
<https://github.com/enzotrp974/trackly-android/security/advisories/new>.

Include what you found, how to reproduce it, and the impact you expect. You should get a first answer within a
week.

## Scope

Of particular interest:

- Exposure of the user's OpenAI API key (it is encrypted with the Android Keystore and only sent to OpenAI).
- Data leaving the device to anywhere other than OpenAI or the user's own Google Drive.
- Backup and restore integrity (Drive `drive.file` scope, database copies).
- Prompt-injection paths that could make the assistant record, change or delete entries without the user's intent.

## Supported versions

Only the latest release on the `main` branch is supported.
