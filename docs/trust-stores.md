# External trust stores

Managed installations can load a PKCS12 trust store at startup without putting its path or password in a saved connection. The selected profile is read after the supported-platform check but before Compose, JDBC, the credential session, or the data directory initializes; an invalid profile fails startup rather than weakening trust.

## Profile and launch

Copy a packaged template from `packaging/resources/common/trust-profiles/` outside the application installation, then launch with an absolute path:

```text
safe-db --launch-profile /absolute/path/to/production.json
```

Profiles use schema version 1. `trustStore.type` is `PKCS12`; profile, store, and password-file paths must be absolute readable regular files; unknown fields are rejected. The launch command accepts no other application arguments.

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

For `credentialStore`, create a generic platform credential with service `com.safedb.app.trust-store` and the profile reference as its account. Windows uses the target `service|account`; macOS uses the same values as service and account. Use Keychain, Credential Manager, MDM, or a secret-management workflow—not command history. This lookup uses the strict platform backend and never falls back to the in-memory connection store, including when `SAFEDB_KEYCHAIN_BACKEND=disabled` is set for development or CI.

For `file`, store one UTF-8 line. A final LF or CRLF is removed; other whitespace is preserved. Keep it outside the application and source tree, normally mode `0600` on macOS or an ACL limited to the user, SYSTEM, and required administrators on Windows.

## Trust behavior

For verified PostgreSQL, MySQL, and SQL Server connections, launch profiles are the only custom trust-store path. Without one, MySQL and SQL Server use normal JVM trust, PostgreSQL retains pgjdbc's standard trust and client-certificate behavior, and Oracle remains wallet-based. The profile records only a password source: secret passwords never appear in JSON, command arguments, environment variables, or logs. PostgreSQL receives a temporary trusted-roots PEM derived from the PKCS12 certificates; MySQL and SQL Server use JSSE properties.

Import only independently verified CA certificates—never private keys—and restart after changing the profile, store, or password.

## Compatibility verification

For a self-contained local harness, start the root Docker Compose stack. It creates the endpoints, seeds each dialect (including the SQL Server and Oracle sample schemas), and creates trusted and untrusted PKCS12 stores, password-file launch profiles, hostname/IP SAN certificates, and the Oracle wallet-path fixture expected by the integration test:

```sh
scripts/docker_test_databases.sh up
scripts/docker_test_databases.sh verify
```

Run `scripts/docker_test_databases.sh seed` to reload only the SQL Server and Oracle fixtures in an already-running stack.

All generated private keys and trust artifacts remain under the Git-ignored `.docker/safedb-ssl/` directory. They are disposable test credentials and must not be reused outside this local stack.

For externally provisioned endpoints, run the environment-gated suite directly for non-UI checks of launch-profile trust and dialect SSL mapping:

```sh
SAFEDB_TEST_REQUIRE_SSL=true \
SAFEDB_TEST_SSL_LAUNCH_PROFILE=/absolute/path/to/production.json \
SAFEDB_TEST_SSL_WRONG_LAUNCH_PROFILE=/absolute/path/to/wrong.json \
./scripts/verify_ssl_compat.sh
```

`SslCompatIntegrationTest` covers PKCS12 launch-profile application, reserved TLS driver properties, JDBC URL/property mapping for all four dialects, live EncryptOnly/VerifyCa/VerifyIdentity against MySQL, PostgreSQL, and SQL Server when those endpoints are provisioned, Oracle TCP plus wallet-required TCPS configuration, and rejection of an untrusted launch-profile CA. Oracle remains wallet-based: a generic PKCS12 file is not a substitute for an Oracle wallet.
