#ifndef MINEG_CORE_IMPLEMENTATION_H
#define MINEG_CORE_IMPLEMENTATION_H

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include "mineg/mineg_core.h"
#include "sqlite_compat.h"

namespace mineg {

class Core final {
 public:
	explicit Core(const std::string &database_path);
	~Core();
	Core(const Core &) = delete;
	Core &operator=(const Core &) = delete;

	mineg_error_code_t execute(uint64_t operation_id, const std::string &command, std::string &result);
	mineg_error_code_t start_operation(uint64_t operation_id, const std::string &command, std::string &result);
	mineg_error_code_t resume_operation(uint64_t operation_id, const std::string &effect_result, std::string &result);
	mineg_error_code_t recover_operations(std::string &result);
	mineg_error_code_t query(const std::string &query, std::string &result);
	mineg_error_code_t subscribe(std::function<void(const std::string &)> callback, uint64_t &token);
	mineg_error_code_t unsubscribe(uint64_t token);
	mineg_error_code_t cancel(uint64_t operation_id);

 private:
	struct AccountOperation;
	struct ActiveAccountSession;

	void open_and_migrate(const std::string &database_path);
	void exec_sql(const char *sql);
	std::string read_probe_locked();
	std::string read_account_state_locked();
	std::string read_backup_settings_locked(const std::string &query);
	std::string read_backup_overview_locked(const std::string &query);
	std::string read_local_album_backup_progress_locked(const std::string &query);
	std::string read_backup_queue_summary_locked(const std::string &query);
	std::string read_scan_state_locked(const std::string &query);
	std::string read_local_library_summary_locked(const std::string &query);
	std::string list_local_albums_locked(const std::string &query);
	std::string list_local_media_locked(const std::string &query);
	mineg_error_code_t apply_local_media_batch_locked(const std::string &command);
	bool prepare_local_scan_locked(const std::string &user_id, const std::string &generation_id,
	                               bool clone_active_generation = false);
	bool write_local_scan_albums_locked(const std::string &user_id, const std::string &generation_id, const std::string &effect_result);
	bool write_local_scan_page_locked(const std::string &user_id, const std::string &generation_id, const std::string &effect_result, int64_t &item_count);
	bool finalize_local_scan_locked(const std::string &user_id, const std::string &generation_id, int64_t indexed_count, const std::string &completed_at);
	int64_t discover_backup_tasks_locked(const std::string &user_id, const std::string &device_id);
	bool begin_backup_scan_locked(const std::string &user_id, const std::string &device_id,
	                              std::string &generation_id, bool &incremental,
	                              std::string &cursor_json, int64_t &indexed_count,
	                              bool &resuming);
	bool persist_backup_scan_progress_locked(const std::string &user_id, const std::string &device_id,
	                                        const std::string &generation_id, int64_t indexed_count,
	                                        const std::string &cursor_json);
	bool finish_backup_scan_locked(const std::string &user_id, const std::string &device_id,
	                               const std::string &generation_id, int64_t discovered_count,
	                               const std::string &completed_at,
	                               const std::string &cursor_json);
	bool claim_next_backup_task_locked(AccountOperation &operation);
	bool renew_backup_task_lease_locked(const AccountOperation &operation);
	bool persist_backup_resource_manifest_locked(AccountOperation &operation);
	bool reconcile_backup_confirmed_parts_locked(const AccountOperation &operation,
	                                           const std::string &session_json);
	bool mark_backup_parts_transferred_locked(const AccountOperation &operation,
	                                         const std::vector<int64_t> &part_indexes);
	bool confirm_backup_part_locked(const AccountOperation &operation, int64_t part_number,
	                                const std::string &etag);
	bool finish_backup_task_locked(const AccountOperation &operation);
	bool backup_task_should_pause_locked(const AccountOperation &operation);
	bool pause_backup_task_locked(const AccountOperation &operation);
	bool fail_backup_task_locked(const AccountOperation &operation, const std::string &code,
	                             bool retryable, int64_t retry_after_seconds = 0);
	mineg_error_code_t update_backup_settings_locked(const std::string &command);
	mineg_error_code_t enqueue_backup_media_locked(const std::string &command, std::string &result);
	mineg_error_code_t retry_backup_queue_locked(const std::string &command);
	mineg_error_code_t notify_library_changed_locked(const std::string &command);
	mineg_error_code_t read_operation_step_locked(uint64_t operation_id, std::string &result, std::string *command_json = nullptr, std::string *effect_result_json = nullptr);
	mineg_error_code_t start_account_operation_locked(uint64_t operation_id, const std::string &command, std::string &result);
	mineg_error_code_t resume_account_operation_locked(uint64_t operation_id, const std::string &effect_result, std::string &result);
	mineg_error_code_t account_operation_step_locked(AccountOperation &operation, std::string &result);
	void set_account_effect_locked(AccountOperation &operation, const std::string &effect_type, const std::string &payload, const std::string &stage);
	void finish_account_error_locked(AccountOperation &operation, const std::string &code, bool retryable, const std::string &request_id = {});
	mineg_error_code_t issue_account_request_locked(AccountOperation &operation, const std::string &purpose);
	void issue_session_read_locked(AccountOperation &operation);
	void issue_session_write_locked(AccountOperation &operation, const std::string &continuation);
	void issue_session_cleanup_locked(AccountOperation &operation, const std::string &completion);
	bool activate_account_session_locked(AccountOperation &operation);
	std::string read_current_profile_snapshot_locked();
	bool persist_current_profile_locked(const std::string &profile_json, const std::string &contract_version);
	std::string read_private_media_snapshot_locked(int limit);
	bool has_private_media_cache_locked();
	bool persist_private_media_locked(const std::string &page_json);
	std::string read_private_media_page_v2_locked(int limit);
	bool has_private_media_page_v2_locked();
	std::string read_private_media_next_cursor_v2_locked();
	bool persist_private_media_page_v2_locked(const std::string &page_json, bool replace);
	std::string read_private_media_detail_v2_locked(const std::string &media_id);
	bool persist_private_media_detail_v2_locked(const std::string &detail_json);
	bool remove_private_media_v2_locked(const std::string &media_id);
	bool begin_private_media_save_locked(AccountOperation &operation);
	bool prepare_private_media_save_resources_locked(AccountOperation &operation,
	                                                 const std::string &access_json);
	bool prepare_private_media_view_resource_locked(AccountOperation &operation,
	                                                const std::string &access_json);
	bool update_private_media_save_state_locked(const AccountOperation &operation,
	                                           const std::string &state,
	                                           const std::string &failure_code = {});
	bool update_private_media_save_resource_locked(const AccountOperation &operation,
	                                              const std::string &resource_id,
	                                              const std::string &state,
	                                              const std::string &verified_path = {});
	bool persist_private_media_download_receipt_locked(const AccountOperation &operation);
	std::string read_private_media_save_operation_locked(const std::string &media_id);
	bool cancel_private_media_save_locked(const std::string &media_id);
	void clear_account_session_locked();
	bool execute_json_statement_locked(const char *sql, const std::string &json);
	bool execute_json_update_locked(const char *sql, const std::string &json);
	void emit_locked(const std::string &event);

	sqlite3 *database_ = nullptr;
	std::mutex mutex_;
	uint64_t next_subscription_ = 1;
	uint64_t event_sequence_ = 0;
	std::unordered_map<uint64_t, std::function<void(const std::string &)>> subscribers_;
	std::unordered_set<uint64_t> cancelled_operations_;
	std::unordered_map<uint64_t, std::unique_ptr<AccountOperation>> account_operations_;
	std::unique_ptr<ActiveAccountSession> active_account_session_;
};

}  // namespace mineg

#endif
