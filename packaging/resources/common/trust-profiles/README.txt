safe-db external trust profiles

Copy an example profile and all referenced files outside the safe-db installation.
Start safe-db with:

  safe-db --launch-profile /absolute/path/to/profile.json

Credential-store source
-----------------------
Provision a generic credential before launch with:

  service: com.safedb.app.trust-store
  account: the profile's reference value

Use macOS Keychain, Windows Credential Manager, a supported Linux desktop
keyring, or enterprise MDM tooling. Do not put the password in command history.
The Windows generic credential target is service|account.

Protected-file source
---------------------
The password file contains one UTF-8 line. Restrict it to the safe-db user or
service identity (normally mode 0600 on POSIX, or a limited Windows ACL).

Startup fails rather than falling back when a selected profile or password
source is unavailable. With no --launch-profile option, safe-db uses the
bundled JVM trust store.
