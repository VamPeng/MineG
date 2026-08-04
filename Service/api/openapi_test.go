package api_test

import (
	"context"
	"reflect"
	"testing"

	"github.com/getkin/kin-openapi/openapi3"
)

func TestOpenAPIContractIsValid(t *testing.T) {
	document, err := openapi3.NewLoader().LoadFromFile("openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if err := document.Validate(context.Background()); err != nil {
		t.Fatal(err)
	}
	if document.OpenAPI != "3.1.0" {
		t.Fatalf("OpenAPI version = %q", document.OpenAPI)
	}
}

func TestPrivateMediaAccessPurposeExcludesDownload(t *testing.T) {
	document, err := openapi3.NewLoader().LoadFromFile("openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	purpose := document.Components.Schemas["PrivateMediaAccessRequest"].Value.Properties["purpose"].Value
	if !reflect.DeepEqual(purpose.Enum, []any{"VIEW", "STREAM"}) {
		t.Fatalf("private media access purposes = %#v, want VIEW and STREAM", purpose.Enum)
	}
}
