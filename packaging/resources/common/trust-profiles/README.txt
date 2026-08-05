safe-db external trust profiles

Copy an example profile and all referenced files outside the safe-db installation.
Start safe-db with:

  safe-db --launch-profile /absolute/path/to/profile.json

Credential-store source
-----------------------
Provision a generic credential before launch with:

  service: com.safedb.app.trust-store
  account: the profile's reference value

Use macOS Keychain, Windows Credential Manager, or enterprise MDM tooling.
Do not put the password in command history.
The Windows generic credential target is service|account.

Protected-file source
---------------------
The password file contains one UTF-8 line. Restrict it to the desktop user or
managed account (normally mode 0600 on macOS, or a limited Windows ACL).

Startup fails rather than falling back when a selected profile or password
source is unavailable. With no --launch-profile option, safe-db uses the
normal JDBC driver trust defaults. PostgreSQL retains pgjdbc's standard trust
and client-certificate behavior.

Managed desktop launch
----------------------
The macOS package includes launch-safe-db-managed.sh, and the Windows package
includes Launch-SafeDbManaged.ps1. Pass the installed executable and launch-
profile paths. Deployment tools may invoke the executable directly when they
can supply --launch-profile securely.
