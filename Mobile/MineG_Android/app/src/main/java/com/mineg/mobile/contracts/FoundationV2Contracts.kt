package com.mineg.mobile.contracts

import org.json.JSONObject

const val FOUNDATION_V2_CONTRACT = "foundation-v2"

enum class CoreOperationStatus {
  WAITING_FOR_EFFECT,
  COMPLETED,
  FAILED,
  CANCELLED,
}

enum class PlatformEffectType(val wireName: String) {
  TRANSPORT("TransportEffect"),
  SECURE_STORE("SecureStoreEffect"),
  MEDIA_SOURCE("MediaSourceEffect"),
  FILE("FileEffect"),
  CONNECTIVITY("ConnectivityEffect"),
  MEDIA_PLAYBACK("MediaPlaybackEffect"),
  SYSTEM_ALBUM("SystemAlbumEffect");

  companion object {
    fun fromWireName(value: String): PlatformEffectType =
      entries.firstOrNull { it.wireName == value }
        ?: throw IllegalArgumentException("Unsupported platform effect type: $value")
  }
}

data class PlatformEffect(
  val contractVersion: String,
  val operationId: Long,
  val sequence: Long,
  val effectType: PlatformEffectType,
  val payloadJson: String,
)

enum class PlatformEffectResultStatus { SUCCEEDED, FAILED, CANCELLED }

data class PlatformEffectError(
  val code: String,
  val retryable: Boolean,
  val message: String? = null,
) {
  internal fun toJsonObject(): JSONObject = JSONObject()
    .put("code", code)
    .put("retryable", retryable)
    .also { value -> message?.let { value.put("message", it) } }
}

data class PlatformEffectResult(
  val operationId: Long,
  val sequence: Long,
  val effectType: PlatformEffectType,
  val status: PlatformEffectResultStatus,
  val payloadJson: String? = null,
  val error: PlatformEffectError? = null,
) {
  init {
    require(operationId > 0 && sequence > 0)
    require((status == PlatformEffectResultStatus.FAILED) == (error != null))
  }

  fun toJson(): String = JSONObject()
    .put("contractVersion", FOUNDATION_V2_CONTRACT)
    .put("operationId", operationId)
    .put("sequence", sequence)
    .put("effectType", effectType.wireName)
    .put("status", status.name)
    .also { envelope ->
      when (status) {
        PlatformEffectResultStatus.SUCCEEDED ->
          envelope.put("payload", payloadJson?.let(::JSONObject) ?: JSONObject.NULL)
        PlatformEffectResultStatus.FAILED -> envelope.put("error", checkNotNull(error).toJsonObject())
        PlatformEffectResultStatus.CANCELLED ->
          envelope.put("error", PlatformEffectError("PLATFORM_CANCELLED", false).toJsonObject())
      }
    }
    .toString()
}

data class CoreOperationStep(
  val contractVersion: String,
  val operationId: Long,
  val sequence: Long,
  val status: CoreOperationStatus,
  val effect: PlatformEffect?,
  val resultJson: String?,
  val errorJson: String?,
) {
  init {
    require(contractVersion == FOUNDATION_V2_CONTRACT && operationId > 0 && sequence > 0)
    require((status == CoreOperationStatus.WAITING_FOR_EFFECT) == (effect != null))
  }

  companion object {
    fun parse(json: String): CoreOperationStep = parseObject(JSONObject(json))

    fun parseRecovery(json: String): List<CoreOperationStep> {
      val envelope = JSONObject(json)
      require(envelope.getString("contractVersion") == FOUNDATION_V2_CONTRACT)
      val operations = envelope.getJSONArray("operations")
      return List(operations.length()) { parseObject(operations.getJSONObject(it)) }
    }

    private fun parseObject(envelope: JSONObject): CoreOperationStep {
      val contractVersion = envelope.getString("contractVersion")
      val operationId = envelope.getLong("operationId")
      val sequence = envelope.getLong("sequence")
      val status = CoreOperationStatus.valueOf(envelope.getString("status"))
      val effect = envelope.optJSONObject("effect")?.let { value ->
        require(value.getString("contractVersion") == contractVersion)
        require(value.getLong("operationId") == operationId)
        require(value.getLong("sequence") == sequence)
        PlatformEffect(
          contractVersion = contractVersion,
          operationId = operationId,
          sequence = sequence,
          effectType = PlatformEffectType.fromWireName(value.getString("effectType")),
          payloadJson = value.getJSONObject("payload").toString(),
        )
      }
      return CoreOperationStep(
        contractVersion = contractVersion,
        operationId = operationId,
        sequence = sequence,
        status = status,
        effect = effect,
        resultJson = envelope.opt("result")?.takeUnless { it == JSONObject.NULL }?.toString(),
        errorJson = envelope.optJSONObject("error")?.toString(),
      )
    }
  }
}
