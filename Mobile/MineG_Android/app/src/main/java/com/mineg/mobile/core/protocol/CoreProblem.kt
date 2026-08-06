/** Domain-neutral terminal problem envelope returned by native Core. */
package com.mineg.mobile.core.protocol

/**
 * Stable business failure returned by C++ Core after command and transport validation.
 *
 * The type is domain-neutral because account, media, backup, sharing and feedback operations all
 * use the same error envelope.
 */
data class CoreProblem(
  val code: String,
  val messageKey: String,
  val retryable: Boolean,
  val requestId: String,
  val details: Map<String, String> = emptyMap(),
) : RuntimeException(code)
