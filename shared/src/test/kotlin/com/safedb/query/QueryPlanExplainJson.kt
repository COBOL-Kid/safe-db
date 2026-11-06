package com.safedb.query

internal const val POSTGRES_UNBOUNDED_SEQ_SCAN =
    """[{"Plan":{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000}}]"""

internal const val POSTGRES_LIMITED_SEQ_SCAN =
    """[{"Plan":{"Node Type":"Limit","Plan Rows":1,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000}]}}]"""

internal const val MYSQL_UNBOUNDED_TABLE_SCAN =
    """{"query_block":{"query_cost":"204212.05","table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":1000000}}}"""

internal const val MYSQL_LIMITED_TABLE_SCAN =
    """{"query_block":{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":906091,"rows":906091}}}"""

internal const val POSTGRES_UNBOUNDED_NESTED_LOOP =
    """[{"Plan":{"Node Type":"Nested Loop","Plan Rows":1000000,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000},{"Node Type":"Index Scan","Schema":"public","Relation Name":"customers","Alias":"t1","Plan Rows":1,"Index Cond":"(id = customer_id)"}]}}]"""

internal const val POSTGRES_LIMITED_NESTED_LOOP =
    """[{"Plan":{"Node Type":"Limit","Plan Rows":1,"Plans":[{"Node Type":"Nested Loop","Plan Rows":1000000,"Plans":[{"Node Type":"Seq Scan","Schema":"analytics","Relation Name":"scan_events","Alias":"t0","Plan Rows":1000000},{"Node Type":"Index Scan","Schema":"public","Relation Name":"customers","Alias":"t1","Plan Rows":1,"Index Cond":"(id = customer_id)"}]}]}}]"""

internal const val MYSQL_SCAN_EVENTS_OUTER_EQ_REF_JOIN =
    """{"query_block":{"nested_loop":[{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ALL","rows_examined_per_scan":906091,"rows_produced_per_join":906091}},{"table":{"table_name":"customers","table_alias":"t1","access_type":"eq_ref","rows_examined_per_scan":1,"rows_produced_per_join":1}}]}}"""

// Live MySQL 8.4 drives the small customers table and looks up scan_events by the FK ref.
internal const val MYSQL_CUSTOMERS_DRIVE_REF_JOIN =
    """{"query_block":{"nested_loop":[{"table":{"table_name":"customers","table_alias":"t1","access_type":"ALL","rows_examined_per_scan":15,"rows_produced_per_join":15}},{"table":{"table_name":"scan_events","table_alias":"t0","access_type":"ref","possible_keys":["fk_scan_events_customer"],"key":"fk_scan_events_customer","used_key_parts":["customer_id"],"ref":["t1.id"],"rows_examined_per_scan":313725,"rows_produced_per_join":941176}}]}}"""

internal const val POSTGRES_SMALL_INDEXED_JOIN =
    """[{"Plan":{"Node Type":"Nested Loop","Plan Rows":12,"Plans":[{"Node Type":"Index Scan","Schema":"public","Relation Name":"orders","Alias":"t0","Plan Rows":12,"Index Cond":"(customer_id = 1)"},{"Node Type":"Index Scan","Schema":"public","Relation Name":"customers","Alias":"t1","Plan Rows":1,"Index Cond":"(id = customer_id)"}]}}]"""

internal const val MYSQL_SMALL_INDEXED_JOIN =
    """{"query_block":{"nested_loop":[{"table":{"table_name":"orders","table_alias":"t0","access_type":"ref","rows_examined_per_scan":12,"rows_produced_per_join":12}},{"table":{"table_name":"customers","table_alias":"t1","access_type":"eq_ref","rows_examined_per_scan":1,"rows_produced_per_join":12}}]}}"""
