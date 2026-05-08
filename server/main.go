package main

import (
	"encoding/json"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

type DeviceInfo struct {
	ID           string `json:"id"`
	Name         string `json:"name"`
	Model        string `json:"model"`
	Manufacturer string `json:"manufacturer"`
	OSVersion    string `json:"os_version"`
}

type Device struct {
	Info     DeviceInfo `json:"info"`
	Online   bool       `json:"online"`
	LastSeen time.Time  `json:"last_seen"`
	conn     *websocket.Conn
}

type DeviceResponse struct {
	ID           string    `json:"id"`
	Name         string    `json:"name"`
	Model        string    `json:"model"`
	Manufacturer string    `json:"manufacturer"`
	OSVersion    string    `json:"os_version"`
	Online       bool      `json:"online"`
	LastSeen     time.Time `json:"last_seen"`
}

var (
	devices  = make(map[string]*Device)
	mu       sync.RWMutex
	upgrader = websocket.Upgrader{
		CheckOrigin: func(r *http.Request) bool { return true },
	}
)

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

	mu.Lock()
	devices[info.ID] = &Device{
		Info:     info,
		Online:   true,
		LastSeen: time.Now(),
		conn:     conn,
	}
	mu.Unlock()

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
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				conn.WriteMessage(websocket.PingMessage, nil)
			case <-done:
				return
			}
		}
	}()

	for {
		_, _, err := conn.ReadMessage()
		if err != nil {
			break
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
	}
	mu.Unlock()

	log.Printf("[-] %s %s disconnected (id=%s)", info.Manufacturer, info.Model, info.ID)
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
		})
	}
	mu.RUnlock()

	json.NewEncoder(w).Encode(list)
}

func healthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

func main() {
	http.HandleFunc("/ws", wsHandler)
	http.HandleFunc("/api/devices", devicesHandler)
	http.HandleFunc("/health", healthHandler)

	log.Println("MeshConnect server listening on :8443")
	log.Fatal(http.ListenAndServe(":8443", nil))
}
