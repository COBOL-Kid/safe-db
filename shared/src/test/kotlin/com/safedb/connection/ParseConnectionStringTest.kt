package com.safedb.connection

import com.safedb.model.ConnectionDef
import com.safedb.model.Dialect
import com.safedb.model.DriverProperty
import com.safedb.model.TransportSecurity
import com.safedb.model.TransportSecurityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParseConnectionStringTest {
    @Test
    fun parsesPostgreSqlUriWithVerifyFull() {
        val parsed =
            parseConnectionString(
                "postgresql://u:p%40ss@db.example.com:5432/app?sslmode=verify-full"
            )

        assertEquals(Dialect.Postgres, parsed.dialect)
        assertEquals("db.example.com", parsed.host)
        assertEquals(5432, parsed.port)
        assertEquals("app", parsed.database)
        assertEquals("u", parsed.username)
        assertEquals("p@ss", parsed.password)
        assertEquals(DatabaseLocation.Cloud, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
        assertFalse(parsed.sanitizedInput.contains("p%40ss"))
    }

    @Test
    fun importsSafePostgresExtrasAndDropsSensitiveValuesFromSanitizedInput() {
        val parsed =
            parseConnectionString(
                "postgresql://u:p@host/db?sslmode=verify-full&currentSchema=reporting&apiToken=do-not-store"
            )

        assertEquals(listOf(DriverProperty("currentSchema", "reporting")), parsed.driverProperties)
        assertTrue(parsed.warnings.single().contains("apiToken"))
        assertFalse(parsed.sanitizedInput.contains("do-not-store"))
        assertFalse(parsed.sanitizedInput.contains(":p@"))
    }

    @Test
    fun encodedDelimitersCannotSplitSensitiveValuesIntoPersistableProperties() {
        val parsed =
            parseConnectionString("postgresql://u@host/db?apiToken=first%26second%3Dsecret")

        assertEquals(emptyList(), parsed.driverProperties)
        assertTrue(parsed.warnings.single().contains("apiToken"))
        assertFalse(parsed.sanitizedInput.contains("first"))
        assertFalse(parsed.sanitizedInput.contains("second"))
        assertFalse(parsed.sanitizedInput.contains("secret"))
    }

    @Test
    fun encodedDelimitersRemainInsideSafeDriverPropertyValues() {
        val parsed =
            parseConnectionString("postgresql://u@host/db?applicationName=R%26D%3Dreporting")

        assertEquals(
            listOf(DriverProperty("applicationName", "R&D=reporting")),
            parsed.driverProperties,
        )
        assertTrue(parsed.sanitizedInput.contains("applicationName=R%26D%3Dreporting"))
    }

    @Test
    fun duplicateExtrasUseLastValueWithWarning() {
        val parsed =
            parseConnectionString("postgresql://u@host/db?currentSchema=one&CURRENTSCHEMA=two")

        assertEquals(listOf(DriverProperty("CURRENTSCHEMA", "two")), parsed.driverProperties)
        assertTrue(parsed.warnings.single().contains("last value"))
    }

    @Test
    fun parsesPostgreSqlJdbcWithRequire() {
        val parsed = parseConnectionString("jdbc:postgresql://host:5432/db?sslmode=require")

        assertEquals(Dialect.Postgres, parsed.dialect)
        assertEquals(TransportSecurityMode.EncryptOnly, parsed.transportSecurity.mode)
    }

    @Test
    fun mapsPostgreSqlSslmodeDisableToDisabledTransport() {
        val parsed = parseConnectionString("postgres://u@localhost/db?sslmode=disable")

        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
    }

    @Test
    fun defaultsPostgreSqlLocalConnectionsWithoutSslParamsToDisabledTransport() {
        val parsed = parseConnectionString("postgres://u@localhost/db")

        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
    }

    @Test
    fun defaultsPostgreSqlRemoteConnectionsWithoutSslParamsToVerifyIdentity() {
        val parsed = parseConnectionString("postgres://u@db.example.com/db")

        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
        assertEquals(DatabaseLocation.Cloud, parsed.inferredLocation)
    }

    @Test
    fun parsesMySqlUriAndPreservesEmptyPassword() {
        val parsed = parseConnectionString("mysql://u:@localhost:3306/db?ssl-mode=VERIFY_IDENTITY")

        assertEquals(Dialect.MySql, parsed.dialect)
        assertEquals("localhost", parsed.host)
        assertEquals(3306, parsed.port)
        assertEquals("db", parsed.database)
        assertEquals("u", parsed.username)
        assertEquals("", parsed.password)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
    }

    @Test
    fun importsSafeMySqlDriverProperties() {
        val parsed =
            parseConnectionString(
                "jdbc:mysql://host:3306/db?sslMode=VERIFY_IDENTITY&serverTimezone=UTC"
            )

        assertEquals(listOf(DriverProperty("serverTimezone", "UTC")), parsed.driverProperties)
    }

    @Test
    fun directsMySqlCaPathsToLaunchProfileJson() {
        val parsed = parseConnectionString("jdbc:mysql://host:3306/db?ssl_ca=/tmp/ca.pem")

        assertEquals(
            listOf(
                "A CA path was included in the URL and was not imported. Configure custom trust through launch-profile JSON."
            ),
            parsed.warnings,
        )
    }

    @Test
    fun defaultsMySqlLocalConnectionsWithoutSslParamsToDisabledTransport() {
        val parsed = parseConnectionString("mysql://u@localhost/db")

        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
    }

    @Test
    fun parsesSqlServerAdoNetStrings() {
        val parsed =
            parseConnectionString(
                "Server=host,1433;Database=db;User ID=u;Password={p;semi};Encrypt=True;TrustServerCertificate=False"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1433, parsed.port)
        assertEquals("db", parsed.database)
        assertEquals("u", parsed.username)
        assertEquals("p;semi", parsed.password)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
        assertTrue(parsed.sanitizedInput.contains("Password="))
        assertFalse(parsed.sanitizedInput.contains("p;semi"))
    }

    @Test
    fun parsesSqlServerAliasesAndDisabledEncryption() {
        val parsed =
            parseConnectionString(
                "Data Source=tcp:db.example.com,1444;Initial Catalog=warehouse;UID=readonly;PWD=p;Encrypt=no"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("db.example.com", parsed.host)
        assertEquals(1444, parsed.port)
        assertEquals("warehouse", parsed.database)
        assertEquals("readonly", parsed.username)
        assertEquals("p", parsed.password)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
    }

    @Test
    fun parsesSqlServerJdbcStrings() {
        val parsed =
            parseConnectionString(
                "jdbc:sqlserver://host:1433;databaseName=db;user=u;password=p;encrypt=true;trustServerCertificate=true"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1433, parsed.port)
        assertEquals("db", parsed.database)
        assertEquals("u", parsed.username)
        assertEquals("p", parsed.password)
        assertEquals(TransportSecurityMode.EncryptOnly, parsed.transportSecurity.mode)
    }

    @Test
    fun importsSafeSqlServerPropertiesButKeepsSecurityManaged() {
        val parsed =
            parseConnectionString(
                "jdbc:sqlserver://host:1433;databaseName=db;user=u;password=p;encrypt=true;" +
                    "applicationName=Reporting;keyVaultProviderClientKey={do-not;store}"
            )

        assertEquals(
            listOf(DriverProperty("applicationName", "Reporting")),
            parsed.driverProperties,
        )
        assertFalse(parsed.driverProperties.any { it.name.equals("encrypt", ignoreCase = true) })
        assertTrue(parsed.warnings.single().contains("keyVaultProviderClientKey"))
        assertFalse(parsed.sanitizedInput.contains("do-not"))
    }

    @Test
    fun defaultsSqlServerJdbcLocalhostConnectionsWithoutEncryptToDisabledTransport() {
        val parsed =
            parseConnectionString(
                "jdbc:sqlserver://localhost:1433;databaseName=db;user=u;password=p"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("localhost", parsed.host)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
    }

    @Test
    fun defaultsSqlServerAdoNetLoopbackConnectionsWithoutEncryptToDisabledTransport() {
        val parsed = parseConnectionString("Server=127.0.0.1,1433;Database=db;User ID=u;Password=p")

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("127.0.0.1", parsed.host)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
    }

    @Test
    fun defaultsSqlServerRemoteConnectionsWithoutEncryptToVerifyIdentity() {
        val parsed =
            parseConnectionString(
                "jdbc:sqlserver://db.example.com:1433;databaseName=db;user=u;password=p"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("db.example.com", parsed.host)
        assertEquals(DatabaseLocation.Cloud, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
    }

    @Test
    fun preservesExplicitEncryptTrueOnSqlServerLocalhostAsVerifyIdentity() {
        val parsed =
            parseConnectionString(
                "Server=localhost,1433;Database=db;User ID=u;Password=p;Encrypt=True"
            )

        assertEquals(Dialect.Mssql, parsed.dialect)
        assertEquals("localhost", parsed.host)
        assertEquals(DatabaseLocation.Local, parsed.inferredLocation)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
    }

    @Test
    fun parsesOracleTcpsWithWalletLocation() {
        val parsed =
            parseConnectionString(
                "jdbc:oracle:thin:@tcps:host:1521/svc?wallet_location=/path/to/wallet"
            )

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
        assertEquals("/path/to/wallet", parsed.transportSecurity.oracleWalletLocation)
        assertEquals(emptyList(), parsed.warnings)
    }

    @Test
    fun importsSafeOracleQueryProperties() {
        val parsed =
            parseConnectionString(
                "jdbc:oracle:thin:@//host:1521/svc?defaultRowPrefetch=50&apiToken=do-not-store"
            )

        assertEquals(listOf(DriverProperty("defaultRowPrefetch", "50")), parsed.driverProperties)
        assertFalse(parsed.sanitizedInput.contains("do-not-store"))
        assertTrue(parsed.warnings.single().contains("apiToken"))
    }

    @Test
    fun formatsCanonicalPasswordFreeConnectionStringsWithEncodedProperties() {
        val def =
            ConnectionDef(
                id = "c1",
                name = "Test",
                dialect = Dialect.Postgres,
                host = "db.example.com",
                port = 5432,
                database = "analytics db",
                username = "read only",
                transportSecurity = TransportSecurity(TransportSecurityMode.VerifyIdentity),
                driverProperties = listOf(DriverProperty("currentSchema", "team reports")),
            )

        val formatted = formatConnectionString(def)

        assertTrue(formatted.contains("read%20only@"))
        assertTrue(formatted.contains("analytics%20db"))
        assertTrue(formatted.contains("currentSchema=team%20reports"))
        assertFalse(formatted.contains("password"))
    }

    @Test
    fun everyPostgresAndMySqlTlsModeSurvivesFormattingAndReimport() {
        for (mode in TransportSecurityMode.entries) {
            for ((dialect, port) in listOf(Dialect.Postgres to 5432, Dialect.MySql to 3306)) {
                val def =
                    ConnectionDef(
                        id = "c1",
                        name = "Test",
                        dialect = dialect,
                        host = "db.example.com",
                        port = port,
                        database = "app",
                        username = "readonly",
                        transportSecurity = TransportSecurity(mode),
                    )

                val parsed = parseConnectionString(formatConnectionString(def))

                assertEquals(mode, parsed.transportSecurity.mode, "$dialect $mode")
            }
        }
    }

    @Test
    fun sqlServerFormattingRoundTripsEscapedValues() {
        val def =
            ConnectionDef(
                id = "c1",
                name = "Test",
                dialect = Dialect.Mssql,
                host = "db.example.com",
                port = 1433,
                database = "analytics; archive",
                username = "read only",
                transportSecurity = TransportSecurity(TransportSecurityMode.VerifyIdentity),
                driverProperties =
                    listOf(DriverProperty("applicationName", "North; {Ops} ; reporting")),
            )

        val formatted = formatConnectionString(def)
        val parsed = parseConnectionString(formatted)

        assertTrue(formatted.contains("databaseName={analytics; archive}"))
        assertTrue(formatted.contains("applicationName={North; {Ops}} ; reporting}"))
        assertEquals(def.database, parsed.database)
        assertEquals(def.username, parsed.username)
        assertEquals(def.driverProperties, parsed.driverProperties)
        assertEquals(def.transportSecurity.mode, parsed.transportSecurity.mode)
        assertEquals(null, parsed.password)
    }

    @Test
    fun oracleFormattingOmitsTheEntireCredentialPair() {
        val def =
            ConnectionDef(
                id = "c1",
                name = "Test",
                dialect = Dialect.Oracle,
                host = "db.example.com",
                port = 1521,
                database = "service",
                username = "readonly",
                transportSecurity = TransportSecurity(TransportSecurityMode.Disabled),
            )

        val formatted = formatConnectionString(def)
        val parsed = parseConnectionString(formatted)

        assertEquals("jdbc:oracle:thin:@//db.example.com:1521/service", formatted)
        assertEquals("", parsed.username)
        assertEquals(null, parsed.password)
    }

    @Test
    fun parsesOracleUserPasswordEasyConnectAndSanitizesPassword() {
        val parsed = parseConnectionString("jdbc:oracle:thin:user/p%40ss@//host:1521/svc")

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals("user", parsed.username)
        assertEquals("p@ss", parsed.password)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
        assertEquals("jdbc:oracle:thin:@//host:1521/svc", parsed.sanitizedInput)
    }

    @Test
    fun parsesOracleEasyConnectPasswordsWithLiteralAtSign() {
        val parsed = parseConnectionString("jdbc:oracle:thin:user/p@ss@//host:1521/svc")

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals("user", parsed.username)
        assertEquals("p@ss", parsed.password)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
        assertEquals("jdbc:oracle:thin:@//host:1521/svc", parsed.sanitizedInput)
    }

    @Test
    fun parsesOracleTcpsCredentialsWhenWalletLocationContainsAtSign() {
        val parsed =
            parseConnectionString(
                "jdbc:oracle:thin:user/pass@tcps:host:1521/svc?wallet_location=/wallets/team@example"
            )

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals("user", parsed.username)
        assertEquals("pass", parsed.password)
        assertEquals(TransportSecurityMode.VerifyIdentity, parsed.transportSecurity.mode)
        assertEquals("/wallets/team@example", parsed.transportSecurity.oracleWalletLocation)
        assertEquals(
            "jdbc:oracle:thin:@tcps:host:1521/svc?wallet_location=/wallets/team@example",
            parsed.sanitizedInput,
        )
    }

    @Test
    fun parsesOraclePlainEasyConnect() {
        val parsed = parseConnectionString("jdbc:oracle:thin:@//host:1521/svc")

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
    }

    @Test
    fun parsesOracleThinHostServiceFormAsPlainTcpUnlessTcpsIsExplicit() {
        val parsed = parseConnectionString("jdbc:oracle:thin:@host:1521/svc")

        assertEquals(Dialect.Oracle, parsed.dialect)
        assertEquals("host", parsed.host)
        assertEquals(1521, parsed.port)
        assertEquals("svc", parsed.database)
        assertEquals(TransportSecurityMode.Disabled, parsed.transportSecurity.mode)
        assertEquals(emptyList(), parsed.warnings)
    }

    @Test
    fun warnsWhenOracleTcpsHasNoWallet() {
        val parsed = parseConnectionString("jdbc:oracle:thin:@tcps:host:1521/svc")

        assertEquals(
            listOf("Oracle TCPS requires a wallet location before testing or saving."),
            parsed.warnings,
        )
    }

    @Test
    fun failsOnMalformedInputWithStructuredError() {
        assertFailsWith<ConnectionStringParseError> {
            parseConnectionString("not a connection string")
        }
    }

    @Test
    fun rejectsAmbiguousSchemeLessUrls() {
        val error =
            assertFailsWith<ConnectionStringParseError> {
                parseConnectionString("//db.example.com:5432/app")
            }
        assertEquals(
            "This connection string format is not recognized. Try the guided setup instead.",
            error.message,
        )
    }

    @Test
    fun rejectsUnsupportedOracleTnsDescriptionBlocks() {
        val error =
            assertFailsWith<ConnectionStringParseError> {
                parseConnectionString(
                    "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcps)(HOST=host)(PORT=1521)))"
                )
            }
        assertTrue(error.message!!.contains("Oracle TNS DESCRIPTION blocks are not supported"))
    }
}
