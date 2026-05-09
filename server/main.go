package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	dataDir         = "/root/data"
	devicesFile     = "/root/data/devices.json"
	screenshotsDir  = "/root/data/screenshots"
	filesDir        = "/root/data/files"
	maxScreenshotKB = 10 * 1024  // 10 MB
	maxFileKB       = 100 * 1024 // 100 MB
)

type DeviceInfo struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Model        string `json:"model"`
	Manufacturer string `json:"manufacturer"`
	OSVersion    string `json:"os_version"`
}

type DeviceStatus struct {
	Battery          int       `json:"battery"`
	Charging         bool      `json:"charging"`
	RAMUsed          int64     `json:"ram_used"`
	RAMTotal         int64     `json:"ram_total"`
	StorageUsed      int64     `json:"storage_used"`
	StorageTotal     int64     `json:"storage_total"`
	Network          string    `json:"network"`
	Uptime           int64     `json:"uptime"`
	Latitude         float64   `json:"latitude,omitempty"`
	Longitude        float64   `json:"longitude,omitempty"`
	LocationAccuracy float64   `json:"location_accuracy,omitempty"`
	LocationTime     int64     `json:"location_time,omitempty"`
	UpdatedAt        time.Time `json:"updated_at"`
}

type Device struct {
	Info     DeviceInfo   `json:"info"`
	Status   DeviceStatus `json:"status"`
	Online   bool         `json:"online"`
	LastSeen time.Time    `json:"last_seen"`
	conn     *websocket.Conn
	send     chan []byte
}

type pendingCmd struct {
	done chan json.RawMessage
	ts   time.Time
}

type DeviceResponse struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Model        string       `json:"model"`
	Manufacturer string       `json:"manufacturer"`
	OSVersion    string       `json:"os_version"`
	Online       bool         `json:"online"`
	LastSeen     time.Time    `json:"last_seen"`
	Status       DeviceStatus `json:"status"`
}

type wsMessage struct {
	Type string `json:"type"`
}

var (
	devices  = make(map[string]*Device)
	mu       sync.RWMutex
	pending  sync.Map // requestID → *pendingCmd
	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}
)

func newRequestID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func wsHandler(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("upgrade:", err)
		return
	}
	defer conn.Close()

	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	var info DeviceInfo
	if err := conn.ReadJSON(&info); err != nil {
		log.Println("read device info:", err)
		return
	}
	conn.SetReadDeadline(time.Time{})

	if info.ID == "" {
		info.ID = r.RemoteAddr
	}

	device := &Device{
		Info:     info,
		Online:   true,
		LastSeen: time.Now(),
		conn:     conn,
		send:     make(chan []byte, 16),
	}
	mu.Lock()
	devices[info.ID] = device
	mu.Unlock()
	go saveDevices()

	log.Printf("[+] %s %s connected (id=%s)", info.Manufacturer, info.Model, info.ID)

	conn.SetPongHandler(func(string) error {
		mu.Lock()
		if d, ok := devices[info.ID]; ok {
			d.LastSeen = time.Now()
		}
		mu.Unlock()
		return nil
	})

	done := make(chan struct{})
	go writePump(conn, device.send, done)

	for {
		_, msg, err := conn.ReadMessage()
		if err != nil {
			break
		}
		var probe wsMessage
		_ = json.Unmarshal(msg, &probe)
		switch probe.Type {
		case "status":
			var status DeviceStatus
			if err := json.Unmarshal(msg, &status); err == nil {
				status.UpdatedAt = time.Now()
				mu.Lock()
				if d, ok := devices[info.ID]; ok {
					d.Status = status
					d.LastSeen = time.Now()
				}
				mu.Unlock()
				continue
			}
		case "command_result":
			var res struct {
				ID string `json:"id"`
			}
			if err := json.Unmarshal(msg, &res); err == nil && res.ID != "" {
				log.Printf("[CMD] ← result from %s (id=%s)", info.ID, res.ID)
				if v, ok := pending.Load(res.ID); ok {
					select {
					case v.(*pendingCmd).done <- json.RawMessage(msg):
					default:
					}
				} else {
					log.Printf("[CMD] ! no pending entry for id=%s (timed out?)", res.ID)
				}
			}
		}
		mu.Lock()
		if d, ok := devices[info.ID]; ok {
			d.LastSeen = time.Now()
		}
		mu.Unlock()
	}

	close(done)

	mu.Lock()
	if d, ok := devices[info.ID]; ok {
		d.Online = false
		d.LastSeen = time.Now()
		d.conn = nil
	}
	mu.Unlock()
	go saveDevices()

	log.Printf("[-] %s %s disconnected (id=%s)", info.Manufacturer, info.Model, info.ID)
}

func writePump(conn *websocket.Conn, send <-chan []byte, done <-chan struct{}) {
	ticker := time.NewTicker(25 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		case msg := <-send:
			if err := conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-done:
			return
		}
	}
}

func devicesHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	mu.RLock()
	list := make([]DeviceResponse, 0, len(devices))
	for _, d := range devices {
		list = append(list, DeviceResponse{
			ID:           d.Info.ID,
			Name:         d.Info.Name,
			Model:        d.Info.Model,
			Manufacturer: d.Info.Manufacturer,
			OSVersion:    d.Info.OSVersion,
			Online:       d.Online,
			LastSeen:     d.LastSeen,
			Status:       d.Status,
		})
	}
	mu.RUnlock()

	json.NewEncoder(w).Encode(list)
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

var allowedCommands = map[string]bool{
	"ping":          true,
	"get_info":      true,
	"vibrate":       true,
	"screenshot":    true,
	"ls":            true,
	"download_file": true,
}

func isValidID(s string) bool {
	if len(s) == 0 || len(s) > 64 {
		return false
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
			return false
		}
	}
	return true
}

func devicesPathHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	rest := strings.TrimPrefix(r.URL.Path, "/api/devices/")
	parts := strings.SplitN(rest, "/", 3)
	if len(parts) < 2 || parts[0] == "" {
		http.Error(w, `{"error":"bad path"}`, http.StatusBadRequest)
		return
	}
	deviceID := parts[0]
	switch parts[1] {
	case "command":
		commandHandler(w, r, deviceID)
	case "screenshots":
		if len(parts) < 3 || parts[2] == "" {
			http.Error(w, `{"error":"screenshot id required"}`, http.StatusBadRequest)
			return
		}
		screenshotUploadHandler(w, r, deviceID, parts[2])
	case "files":
		if len(parts) < 3 || parts[2] == "" {
			http.Error(w, `{"error":"file id required"}`, http.StatusBadRequest)
			return
		}
		fileUploadHandler(w, r, deviceID, parts[2])
	default:
		http.Error(w, `{"error":"unknown action"}`, http.StatusNotFound)
	}
}

func fileUploadHandler(w http.ResponseWriter, r *http.Request, deviceID, reqID string) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}
	if !isValidID(reqID) {
		http.Error(w, `{"error":"bad id"}`, http.StatusBadRequest)
		return
	}
	mu.RLock()
	_, ok := devices[deviceID]
	mu.RUnlock()
	if !ok {
		http.Error(w, `{"error":"device not found"}`, http.StatusNotFound)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxFileKB*1024))
	if err != nil {
		http.Error(w, `{"error":"body too large"}`, http.StatusRequestEntityTooLarge)
		return
	}
	dir := filepath.Join(filesDir, deviceID)
	if err := os.MkdirAll(dir, 0755); err != nil {
		http.Error(w, `{"error":"mkdir"}`, http.StatusInternalServerError)
		return
	}
	filename := r.Header.Get("X-Filename")
	if filename == "" {
		filename = reqID + ".bin"
	}
	filename = filepath.Base(filename)

	dataPath := filepath.Join(dir, reqID+".bin")
	if err := os.WriteFile(dataPath, body, 0644); err != nil {
		http.Error(w, `{"error":"write"}`, http.StatusInternalServerError)
		return
	}
	metaPath := filepath.Join(dir, reqID+".meta.json")
	meta, _ := json.Marshal(map[string]interface{}{
		"filename":    filename,
		"size":        len(body),
		"uploaded_at": time.Now().Format(time.RFC3339),
	})
	_ = os.WriteFile(metaPath, meta, 0644)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":       true,
		"size":     len(body),
		"filename": filename,
	})
}

func fileServeHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	rest := strings.TrimPrefix(r.URL.Path, "/api/files/")
	parts := strings.SplitN(rest, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	deviceID, reqID := parts[0], parts[1]
	if !isValidID(reqID) {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	dir := filepath.Join(filesDir, deviceID)
	dataPath := filepath.Join(dir, reqID+".bin")
	metaPath := filepath.Join(dir, reqID+".meta.json")

	if metaBytes, err := os.ReadFile(metaPath); err == nil {
		var meta struct {
			Filename string `json:"filename"`
		}
		if json.Unmarshal(metaBytes, &meta) == nil && meta.Filename != "" {
			w.Header().Set("Content-Disposition",
				`attachment; filename="`+strings.ReplaceAll(meta.Filename, `"`, ``)+`"`)
		}
	}
	http.ServeFile(w, r, dataPath)
}

func commandHandler(w http.ResponseWriter, r *http.Request, deviceID string) {
	w.Header().Set("Content-Type", "application/json")

	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}

	body, _ := io.ReadAll(r.Body)
	var req map[string]interface{}
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, `{"error":"bad json"}`, http.StatusBadRequest)
		return
	}
	cmdStr, _ := req["command"].(string)
	if cmdStr == "" {
		http.Error(w, `{"error":"command required"}`, http.StatusBadRequest)
		return
	}
	if !allowedCommands[cmdStr] {
		http.Error(w, `{"error":"unknown command"}`, http.StatusBadRequest)
		return
	}

	mu.RLock()
	d, ok := devices[deviceID]
	mu.RUnlock()
	if !ok {
		http.Error(w, `{"error":"device not found"}`, http.StatusNotFound)
		return
	}
	if !d.Online || d.send == nil {
		http.Error(w, `{"error":"device offline"}`, http.StatusServiceUnavailable)
		return
	}

	reqID := newRequestID()
	req["type"] = "command"
	req["id"] = reqID
	cmdJSON, _ := json.Marshal(req)

	pc := &pendingCmd{done: make(chan json.RawMessage, 1), ts: time.Now()}
	pending.Store(reqID, pc)
	defer pending.Delete(reqID)

	log.Printf("[CMD] → %s %s (id=%s)", deviceID, cmdStr, reqID)

	select {
	case d.send <- cmdJSON:
	case <-time.After(2 * time.Second):
		log.Printf("[CMD] ✗ send queue full for %s", deviceID)
		http.Error(w, `{"error":"send queue full"}`, http.StatusServiceUnavailable)
		return
	}

	timeout := 5 * time.Second
	if cmdStr == "screenshot" || cmdStr == "download_file" {
		timeout = 30 * time.Second
	}

	select {
	case res := <-pc.done:
		latency := time.Since(pc.ts).Milliseconds()
		log.Printf("[CMD] ✓ %s replied in %dms (id=%s)", cmdStr, latency, reqID)
		resp := map[string]interface{}{
			"ok":         true,
			"command":    cmdStr,
			"latency_ms": latency,
			"result":     res,
		}
		if cmdStr == "screenshot" {
			resp["screenshot_url"] = "/api/screenshots/" + reqID
		}
		if cmdStr == "download_file" {
			resp["file_url"] = "/api/files/" + deviceID + "/" + reqID
		}
		json.NewEncoder(w).Encode(resp)
	case <-time.After(timeout):
		log.Printf("[CMD] ✗ timeout waiting for %s (id=%s)", cmdStr, reqID)
		http.Error(w, `{"error":"command timeout"}`, http.StatusGatewayTimeout)
	}
}

func screenshotUploadHandler(w http.ResponseWriter, r *http.Request, deviceID, reqID string) {
	if r.Method != http.MethodPost && r.Method != http.MethodPut {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}
	if !isValidID(reqID) {
		http.Error(w, `{"error":"bad id"}`, http.StatusBadRequest)
		return
	}
	mu.RLock()
	_, ok := devices[deviceID]
	mu.RUnlock()
	if !ok {
		http.Error(w, `{"error":"device not found"}`, http.StatusNotFound)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxScreenshotKB*1024))
	if err != nil {
		http.Error(w, `{"error":"body too large"}`, http.StatusRequestEntityTooLarge)
		return
	}
	if err := os.MkdirAll(screenshotsDir, 0755); err != nil {
		http.Error(w, `{"error":"mkdir"}`, http.StatusInternalServerError)
		return
	}
	path := filepath.Join(screenshotsDir, reqID+".jpg")
	if err := os.WriteFile(path, body, 0644); err != nil {
		http.Error(w, `{"error":"write"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"ok":   true,
		"size": len(body),
	})
}

func screenshotsRouteHandler(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		screenshotServeHandler(w, r)
	case http.MethodDelete:
		screenshotDeleteHandler(w, r)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func screenshotServeHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	id := strings.TrimPrefix(r.URL.Path, "/api/screenshots/")
	if !isValidID(id) {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	path := filepath.Join(screenshotsDir, id+".jpg")
	w.Header().Set("Cache-Control", "public, max-age=31536000")
	http.ServeFile(w, r, path)
}

type ScreenshotEntry struct {
	ID        string    `json:"id"`
	URL       string    `json:"url"`
	Size      int64     `json:"size"`
	CreatedAt time.Time `json:"created_at"`
}

func screenshotsListHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")

	entries := []ScreenshotEntry{}
	files, err := os.ReadDir(screenshotsDir)
	if err == nil {
		for _, f := range files {
			if f.IsDir() || !strings.HasSuffix(f.Name(), ".jpg") {
				continue
			}
			id := strings.TrimSuffix(f.Name(), ".jpg")
			if !isValidID(id) {
				continue
			}
			info, err := f.Info()
			if err != nil {
				continue
			}
			entries = append(entries, ScreenshotEntry{
				ID:        id,
				URL:       "/api/screenshots/" + id,
				Size:      info.Size(),
				CreatedAt: info.ModTime(),
			})
		}
	}
	sort.Slice(entries, func(i, j int) bool {
		return entries[i].CreatedAt.After(entries[j].CreatedAt)
	})
	json.NewEncoder(w).Encode(entries)
}

func screenshotDeleteHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	w.Header().Set("Access-Control-Allow-Origin", "*")
	id := strings.TrimPrefix(r.URL.Path, "/api/screenshots/")
	id = strings.TrimSuffix(id, "/")
	if !isValidID(id) {
		http.Error(w, "bad id", http.StatusBadRequest)
		return
	}
	path := filepath.Join(screenshotsDir, id+".jpg")
	if err := os.Remove(path); err != nil {
		if os.IsNotExist(err) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, "delete failed", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func loadDevices() {
	data, err := os.ReadFile(devicesFile)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("load devices: %v", err)
		}
		return
	}
	var loaded map[string]*Device
	if err := json.Unmarshal(data, &loaded); err != nil {
		log.Printf("parse devices: %v", err)
		return
	}
	mu.Lock()
	for id, d := range loaded {
		d.Online = false
		d.conn = nil
		devices[id] = d
	}
	mu.Unlock()
	log.Printf("loaded %d device(s) from %s", len(loaded), devicesFile)
}

func saveDevices() {
	mu.RLock()
	data, err := json.MarshalIndent(devices, "", "  ")
	mu.RUnlock()
	if err != nil {
		log.Printf("marshal devices: %v", err)
		return
	}
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		log.Printf("mkdir %s: %v", dataDir, err)
		return
	}
	tmp := filepath.Join(dataDir, "devices.json.tmp")
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		log.Printf("write devices: %v", err)
		return
	}
	if err := os.Rename(tmp, devicesFile); err != nil {
		log.Printf("rename devices: %v", err)
	}
}

func periodicSave() {
	for range time.Tick(10 * time.Second) {
		saveDevices()
	}
}

func main() {
	loadDevices()
	go periodicSave()

	http.HandleFunc("/ws", wsHandler)
	http.HandleFunc("/api/devices", devicesHandler)
	http.HandleFunc("/api/devices/", devicesPathHandler)
	http.HandleFunc("/api/screenshots", screenshotsListHandler)
	http.HandleFunc("/api/screenshots/", screenshotsRouteHandler)
	http.HandleFunc("/api/files/", fileServeHandler)
	http.HandleFunc("/health", healthHandler)

	log.Println("MeshConnect server listening on :8443")
	log.Fatal(http.ListenAndServe(":8443", nil))
}
