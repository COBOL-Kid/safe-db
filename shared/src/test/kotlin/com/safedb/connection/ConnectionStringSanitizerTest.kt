package com.safedb.connection

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionStringSanitizerTest {
    @Test
    fun scrubsUnrecognizedUriAndDb2BestEffortPasswords() {
        assertEquals("", sanitizeBestEffortConnectionString("   "))
        assertEquals(
            "custom://user@host:1234/db?password=&token=1",
            sanitizeBestEffortConnectionString(
                "custom://user:topsecret@host:1234/db?password=abc&token=1",
            ),
        )
        assertEquals(
            "jdbc:db2://127.0.0.1:50000/SAFEDB:password=;broken",
            sanitizeBestEffortConnectionString(
                "jdbc:db2://127.0.0.1:50000/SAFEDB:password=secret;broken",
            ),
        )
        assertEquals(
            "jdbc:db2://127.0.0.1:50000/SAFEDB:password=;broken",
            sanitizeBestEffortConnectionString(
                "jdbc:db2://127.0.0.1:50000/SAFEDB:password=p#1?a&b;broken",
            ),
        )
        assertEquals(
            "custom://host/db?x=a@b",
            sanitizeBestEffortConnectionString("custom://host/db?x=a@b"),
        )
    }

    @Test
    fun scrubsKeyValueAndQueryPasswordsWithDelimitersQuotesAndBraces() {
        assertEquals(
            "Server=host;Password=;Database=app",
            sanitizeBestEffortConnectionString("Server=host;Password={ab;cd};Database=app"),
        )
        // Unquoted KV passwords containing URI delimiters (#, ?, &).
        assertEquals(
            "Server=host;Password=;Database=app",
            sanitizeBestEffortConnectionString("Server=host;Password=p#1?a&b=2;Database=app"),
        )
        // jdbc:sqlserver:// KV password containing '@' must not be mangled by URI authority scrub.
        assertEquals(
            "jdbc:sqlserver://host:1433;user=sa;password=;encrypt=true",
            sanitizeBestEffortConnectionString(
                "jdbc:sqlserver://host:1433;user=sa;password=p@ss;encrypt=true",
            ),
        )
        // Doubled quotes and trailing text after closed delimiters must not leak password suffixes.
        assertEquals(
            "Server=host;Password=;Database=app",
            sanitizeBestEffortConnectionString(
                """Server=host;Password="my""secret";Database=app""",
            ),
        )
        assertEquals(
            "Server=host;Password=;Database=app",
            sanitizeBestEffortConnectionString("Server=host;Password='my''secret';Database=app"),
        )
        assertEquals(
            "Server=host;Password=;Database=app",
            sanitizeBestEffortConnectionString("Server=host;Password={ab}extra;Database=app"),
        )
        assertEquals(
            "custom://host:1234/db?password=&token=1",
            sanitizeBestEffortConnectionString(
                """custom://host:1234/db?password="a""b"&token=1""",
            ),
        )
        assertEquals(
            "custom://host:1234/db?password=&token=1",
            sanitizeBestEffortConnectionString("custom://host:1234/db?password= {a&b}&token=1"),
        )
        // Unclosed delimited KV passwords over-scrub through end-of-string.
        assertEquals(
            "Server=host;Password=",
            sanitizeBestEffortConnectionString("Server=host;Password={ab;cd;Database=app"),
        )
        assertEquals(
            "Server=host;Password=",
            sanitizeBestEffortConnectionString("""Server=host;Password="ab;cd;Database=app"""),
        )
        assertEquals(
            "Server=host;Password=",
            sanitizeBestEffortConnectionString("Server=host;Password='ab;cd;Database=app"),
        )
        // URI query password containing '?' stops at '#frag' or '&'.
        assertEquals(
            "custom://user@host:1234/db?password=#frag",
            sanitizeBestEffortConnectionString(
                "custom://user:topsecret@host:1234/db?password=a?b#frag",
            ),
        )
    }

    @Test
    fun scrubsOracleBestEffortCredentialsWhilePreservingLeadingAtForms() {
        assertEquals(
            "jdbc:oracle:thin:scott@(DESCRIPTION=(ADDRESS=(HOST=h)))",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/tiger@(DESCRIPTION=(ADDRESS=(HOST=h)))",
            ),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@tcps://host:2484/svc",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/tiger@tcps://host:2484/svc",
            ),
        )
        // Passwords containing '=', ';', and whitespace are scrubbed, while leading-@ strings
        // without inline user/pass are preserved.
        assertEquals(
            "jdbc:oracle:thin:scott@//host:1521/svc",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/p=a;s s@//host:1521/svc",
            ),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@tcps://host:2484/svc",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/p=a;s s@tcps://host:2484/svc",
            ),
        )
        assertEquals(
            "jdbc:oracle:thin:@(DESCRIPTION=(USER=a/b@c))",
            sanitizeBestEffortConnectionString("jdbc:oracle:thin:@(DESCRIPTION=(USER=a/b@c))"),
        )
        // Passwords containing '?', '#', or '@' before the connect target.
        assertEquals(
            "jdbc:oracle:thin:scott@//host:1521/svc",
            sanitizeBestEffortConnectionString("jdbc:oracle:thin:scott/p?ss@//host:1521/svc"),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@tcps://host:2484/svc",
            sanitizeBestEffortConnectionString("jdbc:oracle:thin:scott/p#ss@tcps://host:2484/svc"),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@(DESCRIPTION=(ADDRESS=(HOST=h)))",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/p?ss@(DESCRIPTION=(ADDRESS=(HOST=h)))",
            ),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@//host:1521/svc?wallet_location=/w&x=a@b",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/p@?s@//host:1521/svc?wallet_location=/w&x=a@b",
            ),
        )
        assertEquals(
            "jdbc:oracle:thin:scott@host:1521/svc?wallet_location=/w",
            sanitizeBestEffortConnectionString(
                "jdbc:oracle:thin:scott/p?ss@host:1521/svc?wallet_location=/w",
            ),
        )
    }
}
