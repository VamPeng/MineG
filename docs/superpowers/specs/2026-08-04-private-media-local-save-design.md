# Private Media Local Save Design

## Goal

Saving a private image that has already been opened must use the verified private original cached on the Android device. It must not request a new service access grant or re-download the media from OSS.

The save result is device-local: it records the mapping from cloud media to the Android MediaStore asset, removes the private original cache, and lets later backup scans skip that system-album asset.

## Scope

- Android private-media saving for original image resources.
- The local Core SQLite `download_receipts` mapping.
- Removal of the server-side private-media `DOWNLOAD` access purpose and the Core save-download state machine.

`VIEW` and `STREAM` access remain unchanged. This change does not sync MediaStore IDs or paths to the service.

## Save Flow

1. Read the existing local mapping for the cloud media and check whether its MediaStore asset is still readable.
2. If the mapping is valid, delete any matching private-original cache file and complete without making a copy or network request.
3. If the mapping is absent or stale, validate the private-original cache using the expected original resource size and SHA-256.
4. If the cache is valid, stream it directly to Android MediaStore using the system-album writer.
5. Persist the resulting `cloud_media_id -> platform_asset_ref` receipt in Core SQLite.
6. Delete the private-original cache file and report completion.
7. If the private-original cache is absent or invalid, fail with an actionable local `original not ready` error. Do not call the service or OSS.

For all successful saves, the durable mapping is written before cache cleanup. If cleanup fails, the result is retryable; a retry first recognizes the valid MediaStore mapping, performs only cache cleanup, and does not create a duplicate media item.

## Boundaries

- `AndroidMineGAppRuntime` owns finding and integrity-checking `PrivateOriginalDiskStore` files, and directly invokes the Android system-album writer.
- `AndroidSystemAlbumWriterPort` is extended to accept only a verified private-original cache source from the account-scoped private-cache root; it continues to reject arbitrary paths.
- Core owns durable receipt persistence and exposes a local-only command for recording a successful system-album save. It verifies the active account, media ID, original resource metadata, and platform asset reference before upserting `download_receipts`.
- The service never receives a device MediaStore identifier, path, or URI.

## Removals

- Remove `DOWNLOAD` from the private-media access API schema and server validation/resource selection.
- Remove the Core save workflow stages that request `/access`, allocate a download temp file, download an OSS object, or validate a download result.
- Remove associated contracts and tests. The regular private-media view download path remains because it populates the verified private-original cache.

## Error Handling

- Existing valid mapping: no network; cache cleanup failure is retryable and cannot duplicate the system-album asset.
- Stale mapping plus valid cache: a MediaStore write failure leaves the cache intact and does not create a receipt.
- MediaStore write succeeds but receipt persistence fails: delete the newly-created MediaStore asset; retain the cache for retry.
- Cache miss or integrity failure: return `PRIVATE_MEDIA_ORIGINAL_NOT_READY`; do not fall back to remote download.

## Verification

- Core tests cover receipt lookup/upsert, stale mapping behavior, and receipt-persistence rollback handling.
- Android tests cover cache validation, existing MediaStore mapping short-circuit, direct cache-to-MediaStore save, cleanup retry, and no-network cache-miss failure.
- Service/OpenAPI tests verify `DOWNLOAD` is rejected or absent while `VIEW` and `STREAM` continue to work.
