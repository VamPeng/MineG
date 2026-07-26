package account

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

const (
	argonMemory      = 64 * 1024
	argonIterations  = 3
	argonParallelism = 2
	argonSaltLength  = 16
	argonKeyLength   = 32
)

var dummyPasswordHash = mustHashPassword("MineG-dummy-password-01")

func ValidatePassword(password string) error {
	length := utf8.RuneCountInString(password)
	if length < 8 || length > 64 {
		return errors.New("password must contain 8 to 64 characters")
	}
	var letter, digit bool
	for _, value := range password {
		if unicode.IsLetter(value) {
			letter = true
		}
		if unicode.IsDigit(value) {
			digit = true
		}
		if unicode.IsControl(value) {
			return errors.New("password contains a control character")
		}
	}
	if !letter || !digit {
		return errors.New("password must contain at least one letter and one digit")
	}
	return nil
}

func HashPassword(password string) (string, error) {
	salt := make([]byte, argonSaltLength)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("generate password salt: %w", err)
	}
	derived := argon2.IDKey([]byte(password), salt, argonIterations, argonMemory, argonParallelism, argonKeyLength)
	return fmt.Sprintf(
		"$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version,
		argonMemory,
		argonIterations,
		argonParallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(derived),
	), nil
}

func VerifyPassword(encoded, password string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false
	}
	parameters := strings.Split(parts[3], ",")
	if len(parameters) != 3 {
		return false
	}
	memory, okMemory := parseArgonParameter(parameters[0], "m")
	iterations, okIterations := parseArgonParameter(parameters[1], "t")
	parallelism, okParallelism := parseArgonParameter(parameters[2], "p")
	if !okMemory || !okIterations || !okParallelism || memory > 256*1024 || iterations > 10 || parallelism > 8 {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil || len(salt) < 16 || len(salt) > 64 {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[5])
	if err != nil || len(want) < 16 || len(want) > 64 {
		return false
	}
	got := argon2.IDKey([]byte(password), salt, uint32(iterations), uint32(memory), uint8(parallelism), uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1
}

func VerifyPasswordOrDummy(encoded, password string) bool {
	if encoded == "" {
		_ = VerifyPassword(dummyPasswordHash, password)
		return false
	}
	return VerifyPassword(encoded, password)
}

func parseArgonParameter(value, name string) (uint64, bool) {
	key, raw, ok := strings.Cut(value, "=")
	if !ok || key != name {
		return 0, false
	}
	parsed, err := strconv.ParseUint(raw, 10, 32)
	return parsed, err == nil && parsed > 0
}

func mustHashPassword(password string) string {
	value, err := HashPassword(password)
	if err != nil {
		panic(err)
	}
	return value
}
