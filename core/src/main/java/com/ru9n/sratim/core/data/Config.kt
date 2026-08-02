package com.ru9n.sratim.core.data

data class Config(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val token: String = ""
) {
    val isValid: Boolean
        get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank() && token.isNotBlank()
}
