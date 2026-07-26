package com.safedb.secrets

internal class JavaKeyringDelegate(private val keyring: Any) : CredentialStore {
    private val setPassword = keyring.javaClass.getMethod(
        "setPassword",
        String::class.java,
        String::class.java,
        String::class.java,
    )
    private val getPassword = keyring.javaClass.getMethod(
        "getPassword",
        String::class.java,
        String::class.java,
    )
    private val deletePassword = keyring.javaClass.getMethod(
        "deletePassword",
        String::class.java,
        String::class.java,
    )

    override fun setPassword(service: String, account: String, password: String) {
        setPassword.invoke(keyring, service, account, password)
    }

    override fun getPassword(service: String, account: String): String? =
        getPassword.invoke(keyring, service, account) as String?

    override fun deletePassword(service: String, account: String) {
        deletePassword.invoke(keyring, service, account)
    }

    override fun vendor(): String = "java-keyring"
}

internal fun createJavaKeyringDelegateOrNull(): CredentialStore? = runCatching {
    val keyringClass = Class.forName("com.github.javakeyring.Keyring")
    val create = keyringClass.getMethod("create")
    val keyring = create.invoke(null)
    JavaKeyringDelegate(keyring)
}.getOrNull()
