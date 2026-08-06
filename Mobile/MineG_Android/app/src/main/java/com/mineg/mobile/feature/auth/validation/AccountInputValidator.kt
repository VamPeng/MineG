package com.mineg.mobile.feature.auth.validation

/**
 * Performs fast, presentation-layer validation for account input fields.
 *
 * The C++ Core remains authoritative; this validator only provides immediate UI feedback and
 * canonical phone formatting before a command crosses the Core bridge.
 */
object AccountInputValidator {
  private val phone = Regex("^1[3-9][0-9]{9}$")

  /** Normalizes a mainland China mobile number to its `+86` wire representation. */
  fun normalizePhone(value: String): String? {
    val trimmed = value.trim().removePrefix("+86")
    return if (phone.matches(trimmed)) "+86$trimmed" else null
  }

  /** Produces a display-safe phone number without exposing its middle digits. */
  fun maskedPhone(value: String): String {
    val normalized = normalizePhone(value) ?: return "***********"
    return normalized.substring(3, 6) + "****" + normalized.takeLast(4)
  }

  /** Returns the first presentation validation error, or `null` when the password is acceptable. */
  fun passwordError(value: String): String? {
    if (value.length !in 8..64) return "密码需为 8～64 个字符"
    if (value.none(Char::isLetter) || value.none(Char::isDigit)) return "密码需同时包含字母和数字"
    if (value.any(Char::isISOControl)) return "密码不能包含控制字符"
    return null
  }
}
