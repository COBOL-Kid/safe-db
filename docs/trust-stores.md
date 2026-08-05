# External trust stores

safe-db can use an administrator-managed PKCS12 trust store without saving its path or password in a database connection profile. The configuration is read once at startup, before any JDBC or TLS objects are created.

## Launch profiles

Start the installed executable with an absolute profile path:

```text
safe-db --launch-profile /absolute/path/to/production.json
```

Examples are included in the packaged application resources under `trust-profiles/`. An active profile and all files it references must live outside the application installation so installer upgrades do not replace environment-specific configuration.

When no launch profile is selected, MySQL and SQL Server use the bundled JVM's normal certificates, while PostgreSQL keeps pgjdbc's standard trust and client-certificate behavior. When a profile is selected, any missing or invalid profile, password source, or PKCS12 file stops startup; safe-db never silently changes trust policy.

## Managed desktop launch

The macOS package includes `launch-safe-db-managed.sh`, and the Windows package includes `Launch-SafeDbManaged.ps1`. Each wrapper accepts the installed executable and launch-profile paths, then starts safe-db with `--launch-profile`. Deployment tooling may invoke the executable directly when it can supply the same argument securely.

## Credential-store passwords

For desktop installations, use `source: "credentialStore"`. The fixed service name is `com.safedb.app.trust-store`; the profile's `reference` is the credential account. Provision that generic credential with macOS Keychain or Windows Credential Manager before starting safe-db.

On Windows, the generic Credential Manager target is the service and account joined with `|`, for example `com.safedb.app.trust-store|company-roots`. On macOS, create a generic password whose service is `com.safedb.app.trust-store` and account is `company-roots`. Use the platform UI, MDM, or a secret-management workflow that does not place the password in command history.

The trust-store credential lookup is strict. Unlike saved database credentials, it never falls back to an in-memory store when the platform backend is unavailable.

## Protected password files

For managed launches that cannot use a desktop credential store, use `source: "file"` and an absolute password-file path. The file must contain one UTF-8 line; a single final LF or CRLF is removed, while other spaces are preserved.

Provision the file so only the safe-db user and administrators can read it. On macOS, mode `0600` owned by that user is the normal baseline. On Windows, use an ACL limited to the user, SYSTEM, and the required administrators. Do not place the file under the application installation or source tree.

## Trust precedence

For verified PostgreSQL, MySQL, and SQL Server connections, a connection-specific CA PEM overrides the launch-profile PKCS12 store. With no connection CA, the launch profile is used. With no launch profile, MySQL and SQL Server use the bundled JVM trust store; PostgreSQL uses pgjdbc's standard certificate locations and preserves its standard client-certificate loading. Oracle remains wallet-based.

The password is retrieved only during startup and is never logged or placed in process arguments or environment variables. MySQL and SQL Server consume the standard JSSE trust-store properties. PostgreSQL receives a temporary PEM containing only the trusted certificates, allowing pgjdbc to retain its normal client-certificate handling. The resolved password remains in JVM memory for the life of the process.

## Creating the trust store

Import only CA certificates that the organization intends safe-db to trust. Verify certificate fingerprints through an independent channel before importing them. Do not add private keys: this interface is a trust store, not a client-certificate keystore.

Restart safe-db after changing a profile, PKCS12 file, or stored password.
