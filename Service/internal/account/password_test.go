package account

import (
	"strings"
	"testing"
)

func TestPasswordRulesAndArgon2id(t *testing.T) {
	for _, value := range []string{"short1", "letters-only", "12345678", strings.Repeat("a", 65) + "1"} {
		if ValidatePassword(value) == nil {
			t.Fatalf("password %q unexpectedly passed validation", value)
		}
	}
	password := "family-photo-2026"
	encoded, err := HashPassword(password)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(encoded, "$argon2id$") {
		t.Fatalf("unexpected encoding: %q", encoded)
	}
	if !VerifyPassword(encoded, password) {
		t.Fatal("valid password was rejected")
	}
	if VerifyPassword(encoded, password+"x") {
		t.Fatal("invalid password was accepted")
	}
}

func TestPhoneNormalizationAndMasking(t *testing.T) {
	for _, value := range []string{"13800138000", "+8613800138000", " 13800138000 "} {
		got, err := NormalizePhone(value)
		if err != nil || got != "+8613800138000" {
			t.Fatalf("NormalizePhone(%q) = %q, %v", value, got, err)
		}
	}
	for _, value := range []string{"1380013800", "23800138000", "+1 13800138000"} {
		if _, err := NormalizePhone(value); err == nil {
			t.Fatalf("NormalizePhone(%q) unexpectedly passed", value)
		}
	}
	if got := MaskPhone("+8613800138000"); got != "138****8000" {
		t.Fatalf("mask = %q", got)
	}
}
