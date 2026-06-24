use safe_db_lib::secrets;
use uuid::Uuid;

#[test]
fn secrets_round_trip() {
    secrets::init_store().expect("keyring store should initialize");

    let connection_id = format!("smoke-test-{}", Uuid::new_v4());

    secrets::save_password(&connection_id, "smoke-test-password")
        .expect("save_password should succeed");

    let password = secrets::get_password(&connection_id)
        .expect("get_password should succeed")
        .expect("password should exist after save");
    assert_eq!(password, "smoke-test-password");

    secrets::delete_password(&connection_id).expect("delete_password should succeed");

    let deleted =
        secrets::get_password(&connection_id).expect("get_password after delete should succeed");
    assert!(deleted.is_none());
}
