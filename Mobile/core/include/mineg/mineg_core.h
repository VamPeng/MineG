#ifndef MINEG_CORE_H
#define MINEG_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define MINEG_API __declspec(dllexport)
#else
#define MINEG_API __attribute__((visibility("default")))
#endif

#define MINEG_ABI_VERSION 6U

typedef struct mineg_core mineg_core_t;

typedef enum mineg_error_code {
	MINEG_OK = 0,
	MINEG_INVALID_ARGUMENT = 1,
	MINEG_CLOSED = 2,
	MINEG_DATABASE_ERROR = 3,
	MINEG_CRYPTO_ERROR = 4,
	MINEG_INTEGRITY_ERROR = 5,
	MINEG_CANCELLED = 6,
	MINEG_NOT_FOUND = 7,
	MINEG_INTERNAL_ERROR = 8
} mineg_error_code_t;

typedef struct mineg_buffer {
	uint8_t *data;
	size_t size;
} mineg_buffer_t;

typedef void (*mineg_event_callback_t)(const uint8_t *event_json, size_t event_size, void *user_data);

MINEG_API uint32_t mineg_abi_version(void);
MINEG_API mineg_error_code_t mineg_core_create(const char *database_path, mineg_core_t **out_core);
MINEG_API mineg_error_code_t mineg_core_execute(mineg_core_t *core, uint64_t operation_id,
											const uint8_t *command_json, size_t command_size,
											mineg_buffer_t *out_result_json);
MINEG_API mineg_error_code_t mineg_core_start_operation(
		mineg_core_t *core, uint64_t operation_id, const uint8_t *command_json, size_t command_size,
		mineg_buffer_t *out_operation_step_json);
MINEG_API mineg_error_code_t mineg_core_resume_operation(
		mineg_core_t *core, uint64_t operation_id, const uint8_t *effect_result_json,
		size_t effect_result_size, mineg_buffer_t *out_operation_step_json);
MINEG_API mineg_error_code_t mineg_core_recover_operations(mineg_core_t *core,
		mineg_buffer_t *out_operations_json);
MINEG_API mineg_error_code_t mineg_core_query(mineg_core_t *core, const uint8_t *query_json,
											 size_t query_size, mineg_buffer_t *out_result_json);
MINEG_API mineg_error_code_t mineg_core_subscribe(mineg_core_t *core, mineg_event_callback_t callback,
												  void *user_data, uint64_t *out_subscription_token);
MINEG_API mineg_error_code_t mineg_core_unsubscribe(mineg_core_t *core, uint64_t subscription_token);
MINEG_API mineg_error_code_t mineg_core_cancel(mineg_core_t *core, uint64_t operation_id);
MINEG_API void mineg_buffer_free(mineg_buffer_t *buffer);
MINEG_API void mineg_core_close(mineg_core_t *core);

#ifdef __cplusplus
}
#endif

#endif
