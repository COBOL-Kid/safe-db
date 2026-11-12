package com.safedb.mcp

import java.nio.file.Path

internal const val MINIMAL_RECIPE_JSON =
    """{
      "schemaVersion": 1,
      "id": "ignored",
      "name": "By region",
      "createdAt": "1",
      "updatedAt": "1",
      "defaultMode": "Pivot",
      "pivot": { "schemaVersion": 1 }
    }"""

internal fun validNonInteractiveAddArgs(
    passwordFile: Path,
    dialect: String = "postgres",
): Array<String> =
    arrayOf(
        "setup",
        "--dialect",
        dialect,
        "--database",
        "app",
        "--username",
        "readonly",
        "--password-file",
        passwordFile.toString(),
    )
