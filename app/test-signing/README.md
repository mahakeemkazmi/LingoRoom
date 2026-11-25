# Test signing key

`lingoroom-test.keystore` signs test builds only (the APKs produced by the GitHub Actions build).

It is committed on purpose, with a public password, so that every test build carries the same signature. Android only installs an update over an existing app when the signature matches, so a stable key lets testers update without uninstalling and downloading the models again.

Do not use this key for a public release. A Play Store or other public release must be signed with a private key that is kept out of the repository.
