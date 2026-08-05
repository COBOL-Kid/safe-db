# External trust stores

safe-db can use an administrator-managed PKCS12 trust store without saving its path or password in a database connection profile. The configuration is read once at startup, before any JDBC or TLS objects are created.

## Launch profiles

Start the installed executable with an absolute profile path:

```text
safe-db --launch-profile /absolute/path/to/production.json
```

Examples are included in the packaged application resources under `trust-profiles/`. An active profile and all files it references must live outside the application installation so installer upgrades do not replace environment-specific configuration.

When no launch profile is selected, safe-db uses the bundled JVM's normal certificates. When a profile is selected, any missing or invalid profile, password source, or PKCS12 file stops startup; safe-db never silently changes trust policy.

## Managed Linux desktop launch

The Linux package includes `safe-db-managed.desktop.example`. Update its executable and profile paths, name it `safe-db-managed.desktop`, and install it in one of these graphical-session locations:

- `/usr/share/applications/` to make it available in desktop application menus.
- `~/.local/share/applications/` for one user's application menu.
- `/etc/xdg/autostart/` to start it automatically for managed graphical users.
- `~/.config/autostart/` to start it automatically for one graphical user.

Desktop and autostart entries inherit the user's Wayland or X11 display, D-Bus session, and unlocked desktop keyring. Do not run safe-db as a boot-time system service: it is an interactive desktop application and requires a graphical user session. The packaged `launch-safe-db-managed.sh` wrapper remains available when deployment tooling prefers to supply the executable and profile paths itself.

## Credential-store passwords

For desktop installations, use `source: "credentialStore"`. The fixed service name is `com.safedb.app.trust-store`; the profile's `reference` is the credential account. Provision that generic credential with macOS Keychain, Windows Credential Manager, or the supported Linux desktop keyring before starting safe-db.

On Windows, the generic Credential Manager target is the service and account joined with `|`, for example `com.safedb.app.trust-store|company-roots`. On macOS, create a generic password whose service is `com.safedb.app.trust-store` and account is `company-roots`. Use the platform UI, MDM, or a secret-management workflow that does not place the password in command history.

The trust-store credential lookup is strict. Unlike saved database credentials, it never falls back to an in-memory store when the platform backend is unavailable.

## Protected password files

For managed launches that cannot use a desktop credential store, use `source: "file"` and an absolute password-file path. The file must contain one UTF-8 line; a single final LF or CRLF is removed, while other spaces are preserved.

Provision the file so only the safe-db user and administrators can read it. On POSIX systems, mode `0600` owned by that user is the normal baseline. On Windows, use an ACL limited to the user, SYSTEM, and the required administrators. Do not place the file under the application installation or source tree.

## Trust precedence

For verified PostgreSQL, MySQL, and SQL Server connections, a connection-specific CA PEM overrides the launch-profile PKCS12 store. With no connection CA, the launch profile is used; with no launch profile, the bundled JVM trust store is used. Oracle remains wallet-based.

The password is retrieved only during startup and is never logged or placed in process arguments or environment variables. JDBC drivers consume the standard JSSE trust-store properties, so the resolved value remains in JVM memory for the life of the process.

## Creating the trust store

Import only CA certificates that the organization intends safe-db to trust. Verify certificate fingerprints through an independent channel before importing them. Do not add private keys: this interface is a trust store, not a client-certificate keystore.

Restart safe-db after changing a profile, PKCS12 file, or stored password.
