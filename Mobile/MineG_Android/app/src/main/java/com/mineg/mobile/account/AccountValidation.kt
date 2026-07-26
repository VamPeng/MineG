package com.mineg.mobile.account

object AccountValidation {
  private val phone = Regex("^1[3-9][0-9]{9}$")

  fun normalizePhone(value: String): String? {
    val trimmed = value.trim().removePrefix("+86")
    return if (phone.matches(trimmed)) "+86$trimmed" else null
  }

  fun maskedPhone(value: String): String {
    val normalized = normalizePhone(value) ?: return "***********"
    return normalized.substring(3, 6) + "****" + normalized.takeLast(4)
  }

  fun passwordError(value: String): String? {
    if (value.length !in 8..64) return "密码需为 8～64 个字符"
    if (value.none(Char::isLetter) || value.none(Char::isDigit)) return "密码需同时包含字母和数字"
    if (value.any(Char::isISOControl)) return "密码不能包含控制字符"
    return null
  }
}
