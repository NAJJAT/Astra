package main

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestIsValidID(t *testing.T) {
	cases := []struct {
		in   string
		want bool
	}{
		{"", false},
		{"abc123", true},
		{"ABCDEF0123456789", true},
		{"deadbeefcafebabe", true},
		{"abc-123", false},
		{"abc/def", false},
		{"../etc/passwd", false},
		{"badid", false}, // 'i' is not hex
		{strings.Repeat("a", 64), true},
		{strings.Repeat("a", 65), false},
	}
	for _, c := range cases {
		if got := isValidID(c.in); got != c.want {
			t.Errorf("isValidID(%q) = %v, want %v", c.in, got, c.want)
		}
	}
}

func TestSanitizeFilenameHeader(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{`hello.txt`, `hello.txt`},
		{`a"b.txt`, `ab.txt`},
		{"a\r\nX-Injected: y", "aX-Injected: y"},
		{"tab\there", "tabhere"},
		{"control\x01\x02chars", "controlchars"},
	}
	for _, c := range cases {
		if got := sanitizeFilenameHeader(c.in); got != c.want {
			t.Errorf("sanitizeFilenameHeader(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestRequireAuth(t *testing.T) {
	authToken = "secret"
	t.Cleanup(func() { authToken = "" })

	called := false
	handler := requireAuth(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	})

	cases := []struct {
		name       string
		header     string
		query      string
		wantStatus int
		wantCalled bool
	}{
		{"no auth", "", "", http.StatusUnauthorized, false},
		{"good bearer", "Bearer secret", "", http.StatusOK, true},
		{"wrong token", "Bearer wrong", "", http.StatusUnauthorized, false},
		{"missing prefix", "secret", "", http.StatusUnauthorized, false},
		{"good query token", "", "secret", http.StatusOK, true},
		{"wrong query token", "", "wrong", http.StatusUnauthorized, false},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			called = false
			u := "/x"
			if c.query != "" {
				u += "?token=" + c.query
			}
			req := httptest.NewRequest("GET", u, nil)
			if c.header != "" {
				req.Header.Set("Authorization", c.header)
			}
			rec := httptest.NewRecorder()
			handler(rec, req)
			if rec.Code != c.wantStatus {
				t.Errorf("status = %d, want %d", rec.Code, c.wantStatus)
			}
			if called != c.wantCalled {
				t.Errorf("inner called = %v, want %v", called, c.wantCalled)
			}
		})
	}
}

func TestRequireAuthRejectsWhenTokenUnset(t *testing.T) {
	authToken = ""
	handler := requireAuth(func(w http.ResponseWriter, r *http.Request) {
		t.Fatal("inner should not run when authToken is unset")
	})
	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer ")
	rec := httptest.NewRecorder()
	handler(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestDevicesPathHandlerRejectsBadDeviceID(t *testing.T) {
	badIDs := []string{
		"../etc/passwd",
		"with space",
		"a$b",
		"badid", // 'i' not hex
		"",
	}
	for _, id := range badIDs {
		t.Run(id, func(t *testing.T) {
			req := &http.Request{
				Method: "GET",
				URL:    &url.URL{Path: "/api/devices/" + id + "/command"},
			}
			rec := httptest.NewRecorder()
			devicesPathHandler(rec, req)
			if rec.Code != http.StatusBadRequest {
				t.Errorf("status = %d, want 400 for id %q", rec.Code, id)
			}
		})
	}
}

func TestFileServeHandlerRejectsBadDeviceID(t *testing.T) {
	req := &http.Request{
		Method: "GET",
		URL:    &url.URL{Path: "/api/files/..%2F..%2Fetc/abc123"},
	}
	rec := httptest.NewRecorder()
	fileServeHandler(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("status = %d, want 400", rec.Code)
	}
}

func TestCleanupOfflineDevices(t *testing.T) {
	now := time.Now()
	mu.Lock()
	devices = map[string]*Device{
		"online_stale":  {Info: DeviceInfo{ID: "online_stale"}, Online: true, LastSeen: now.Add(-48 * time.Hour)},
		"offline_fresh": {Info: DeviceInfo{ID: "offline_fresh"}, Online: false, LastSeen: now.Add(-1 * time.Hour)},
		"offline_stale": {Info: DeviceInfo{ID: "offline_stale"}, Online: false, LastSeen: now.Add(-72 * time.Hour)},
	}
	mu.Unlock()
	t.Cleanup(func() {
		mu.Lock()
		devices = map[string]*Device{}
		mu.Unlock()
		deviceTTL = 0
	})

	deviceTTL = 0
	if got := cleanupOfflineDevices(); got != 0 {
		t.Errorf("ttl disabled: removed %d, want 0", got)
	}

	deviceTTL = 24 * time.Hour
	got := cleanupOfflineDevices()
	if got != 1 {
		t.Errorf("ttl=24h: removed %d, want 1", got)
	}
	mu.RLock()
	_, online := devices["online_stale"]
	_, fresh := devices["offline_fresh"]
	_, stale := devices["offline_stale"]
	mu.RUnlock()
	if !online {
		t.Error("online device was removed")
	}
	if !fresh {
		t.Error("fresh offline device was removed")
	}
	if stale {
		t.Error("stale offline device was not removed")
	}
}

func TestExtractToken(t *testing.T) {
	req := httptest.NewRequest("GET", "/x?token=fromquery", nil)
	if got := extractToken(req); got != "fromquery" {
		t.Errorf("query: got %q, want fromquery", got)
	}

	req = httptest.NewRequest("GET", "/x?token=fromquery", nil)
	req.Header.Set("Authorization", "Bearer fromheader")
	if got := extractToken(req); got != "fromheader" {
		t.Errorf("header takes precedence: got %q, want fromheader", got)
	}
}
