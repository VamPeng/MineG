#ifndef MINEG_SODIUM_COMPAT_H
#define MINEG_SODIUM_COMPAT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define crypto_secretstream_xchacha20poly1305_ABYTES 17U
#define crypto_secretstream_xchacha20poly1305_HEADERBYTES 24U
#define crypto_secretstream_xchacha20poly1305_KEYBYTES 32U
#define crypto_secretstream_xchacha20poly1305_TAG_MESSAGE 0x00U
#define crypto_secretstream_xchacha20poly1305_TAG_FINAL 0x03U
#define crypto_box_PUBLICKEYBYTES 32U
#define crypto_box_SECRETKEYBYTES 32U
#define crypto_box_SEALBYTES 48U
#define crypto_pwhash_SALTBYTES 16U
#define crypto_pwhash_ALG_ARGON2ID13 2
#define crypto_aead_xchacha20poly1305_ietf_ABYTES 16U
#define crypto_aead_xchacha20poly1305_ietf_NPUBBYTES 24U
#define crypto_hash_sha256_BYTES 32U
#define crypto_auth_hmacsha256_BYTES 32U
#define crypto_auth_hmacsha256_KEYBYTES 32U

typedef struct crypto_hash_sha256_state {
  uint32_t state[8];
  uint64_t count;
  uint8_t buf[64];
} crypto_hash_sha256_state;

typedef struct crypto_auth_hmacsha256_state {
  crypto_hash_sha256_state ictx;
  crypto_hash_sha256_state octx;
} crypto_auth_hmacsha256_state;

typedef struct crypto_secretstream_xchacha20poly1305_state {
  unsigned char k[32];
  unsigned char nonce[12];
  unsigned char _pad[8];
} crypto_secretstream_xchacha20poly1305_state;

int sodium_init(void);
void sodium_memzero(void *, size_t);
void randombytes_buf(void *, size_t);
int crypto_secretstream_xchacha20poly1305_init_push(
    crypto_secretstream_xchacha20poly1305_state *, unsigned char *, const unsigned char *);
int crypto_secretstream_xchacha20poly1305_push(
    crypto_secretstream_xchacha20poly1305_state *, unsigned char *, unsigned long long *,
    const unsigned char *, unsigned long long, const unsigned char *, unsigned long long,
    unsigned char);
int crypto_secretstream_xchacha20poly1305_init_pull(
    crypto_secretstream_xchacha20poly1305_state *, const unsigned char *, const unsigned char *);
int crypto_secretstream_xchacha20poly1305_pull(
    crypto_secretstream_xchacha20poly1305_state *, unsigned char *, unsigned long long *,
    unsigned char *, const unsigned char *, unsigned long long, const unsigned char *,
    unsigned long long);
int crypto_box_keypair(unsigned char *, unsigned char *);
int crypto_scalarmult_base(unsigned char *, const unsigned char *);
int crypto_box_seal(unsigned char *, const unsigned char *, unsigned long long,
                    const unsigned char *);
int crypto_box_seal_open(unsigned char *, const unsigned char *, unsigned long long,
                         const unsigned char *, const unsigned char *);
int crypto_pwhash(unsigned char *, unsigned long long, const char *, unsigned long long,
                  const unsigned char *, unsigned long long, size_t, int);
int crypto_aead_xchacha20poly1305_ietf_encrypt(
    unsigned char *, unsigned long long *, const unsigned char *, unsigned long long,
    const unsigned char *, unsigned long long, const unsigned char *, const unsigned char *,
    const unsigned char *);
int crypto_aead_xchacha20poly1305_ietf_decrypt(
    unsigned char *, unsigned long long *, unsigned char *, const unsigned char *,
    unsigned long long, const unsigned char *, unsigned long long, const unsigned char *,
    const unsigned char *);
int crypto_hash_sha256(unsigned char *, const unsigned char *, unsigned long long);
int crypto_hash_sha256_init(crypto_hash_sha256_state *);
int crypto_hash_sha256_update(crypto_hash_sha256_state *, const unsigned char *, unsigned long long);
int crypto_hash_sha256_final(crypto_hash_sha256_state *, unsigned char *);
int crypto_auth_hmacsha256(unsigned char *, const unsigned char *, unsigned long long,
                           const unsigned char *);
int crypto_auth_hmacsha256_init(crypto_auth_hmacsha256_state *, const unsigned char *, size_t);
int crypto_auth_hmacsha256_update(crypto_auth_hmacsha256_state *, const unsigned char *,
                                  unsigned long long);
int crypto_auth_hmacsha256_final(crypto_auth_hmacsha256_state *, unsigned char *);

#ifdef __cplusplus
}
#endif

#endif
