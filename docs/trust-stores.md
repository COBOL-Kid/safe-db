# External trust stores

Managed installations can load a PKCS12 trust store at startup without putting its path or password in a saved connection. The selected profile is read before Compose or JDBC initializes; an invalid profile fails startup rather than weakening trust.

## Profile and launch

Copy a packaged template from `packaging/resources/common/trust-profiles/` outside the application installation, then launch with an absolute path:

```text
safe-db --launch-profile /absolute/path/to/production.json
```

Profiles use schema version 1. `trustStore.type` is `PKCS12`; profile, store, and password-file paths must be absolute readable regular files; unknown fields are rejected.

```json
{
  "schemaVersion": 1,
  "trustStore": {
    "type": "PKCS12",
    "path": "/absolute/path/to/company-roots.p12",
    "password": { "source": "credentialStore", "reference": "company-roots" }
  }
}
```

For a protected password file, replace `password` with:

```json
{ "source": "file", "path": "/absolute/path/to/company-roots.password" }
```

`credentialStore` requires a nonblank `reference` and no path; `file` requires a path and no reference. The macOS package includes `launch-safe-db-managed.sh`; the Windows package includes `Launch-SafeDbManaged.ps1`.

## Password provisioning

For `credentialStore`, create a generic platform credential with service `com.safedb.app.trust-store` and the profile reference as its account. Windows uses the target `service|account`; macOS uses the same values as service and account. Use Keychain, Credential Manager, MDM, or a secret-management workflow—not command history. This lookup is strict and never falls back to the in-memory connection store.

For `file`, store one UTF-8 line. A final LF or CRLF is removed; other whitespace is preserved. Keep it outside the application and source tree, normally mode `0600` on macOS or an ACL limited to the user, SYSTEM, and required administrators on Windows.

## Trust behavior

For verified PostgreSQL, MySQL, and SQL Server connections, launch profiles are the only custom trust-store path. Without one, MySQL and SQL Server use normal JVM trust, PostgreSQL retains pgjdbc's standard trust and client-certificate behavior, and Oracle remains wallet-based. Profile passwords never appear in JSON, command arguments, environment variables, or logs. PostgreSQL receives a temporary trusted-roots PEM; MySQL and SQL Server use JSSE properties.

Import only independently verified CA certificates—never private keys—and restart after changing the profile, store, or password.
