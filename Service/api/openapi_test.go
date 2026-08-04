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
	resultPurpose := document.Components.Schemas["PrivateMediaAccessResult"].Value.Properties["purpose"].Value
	if !reflect.DeepEqual(resultPurpose.Enum, []any{"VIEW", "STREAM"}) {
		t.Fatalf("private media access result purposes = %#v, want VIEW and STREAM", resultPurpose.Enum)
	}
}

func TestPrivateMediaResponsesRequireContentRevision(t *testing.T) {
	document, err := openapi3.NewLoader().LoadFromFile("openapi.yaml")
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"PrivateMediaSummary", "PrivateMediaDetail"} {
		schema := document.Components.Schemas[name].Value
		if _, exists := schema.Properties["content_revision"]; !exists {
			t.Fatalf("%s omits content_revision", name)
		}
		if !contains(schema.Required, "content_revision") {
			t.Fatalf("%s required fields = %#v, want content_revision", name, schema.Required)
		}
	}
}

func contains(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
