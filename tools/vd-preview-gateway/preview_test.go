package main

import (
	"bytes"
	"os"
	"os/exec"
	"testing"
)

// The browser regression suite executes index.html's actual script. Keep it in
// the Go test entry point as well, since this is the page compiled into 3070.
// These tests never start the server or invoke Android/owner commands.
func TestEmbeddedPreviewPage(t *testing.T) {
	page, err := os.ReadFile("index.html")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(indexHTML, page) {
		t.Fatal("3070 must embed the tested preview page")
	}
}

// Guard the embedded protocol even on builders without Node. The behavioral
// suite below verifies PNG bytes/MIME, request and response identity headers,
// JPEG rejection in Eta only, and the unchanged legacy screenshot path.
func TestEmbeddedPreviewProtocol(t *testing.T) {
	for _, required := range []string{
		"etaFetch('/eta-preview/displays', controller)",
		"etaFetch('/eta-preview/frame', controller, {",
		"'X-Eta-Display-Id': String(selected.displayId)",
		"'X-Eta-Display-Unique-Id': selected.uniqueId",
		"Authorization: 'Bearer ' + etaConfig.token",
		"new Blob(chunks, { type: 'image/png' })",
		"[137, 80, 78, 71, 13, 10, 26, 10]",
		"img.src = '/api/screenshot?t=' + Date.now()",
	} {
		if !bytes.Contains(indexHTML, []byte(required)) {
			t.Errorf("embedded preview protocol missing %q", required)
		}
	}
	for _, obsolete := range []string{"etaReadJpeg", "/eta-preview/frame?", "image/jpeg"} {
		if bytes.Contains(indexHTML, []byte(obsolete)) {
			t.Errorf("embedded preview still contains obsolete Eta protocol %q", obsolete)
		}
	}
}

func TestPreviewPageBehavior(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("Node.js 18+ is needed: node --test index.test.cjs")
	}
	out, err := exec.Command(node, "--test", "index.test.cjs").CombinedOutput()
	if err != nil {
		t.Fatalf("preview page regression tests failed: %v\n%s", err, out)
	}
}
