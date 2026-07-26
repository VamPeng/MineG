#ifndef MINEG_SODIUM_COMPAT_H
#define MINEG_SODIUM_COMPAT_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define crypto_secretstream_xchacha20poly1305_ABYTES 17U
#define crypto_secretstream_xchacha20poly1305_HEADERBYTES 24U
#define crypto_secretstream_xchacha20poly1305_KEYBYTES 32U
#define crypto_secretstream_xchacha20poly1305_TAG_MESSAGE 0x00U
#define crypto_secretstream_xchacha20poly1305_TAG_FINAL 0x03U

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

#ifdef __cplusplus
}
#endif

#endif
