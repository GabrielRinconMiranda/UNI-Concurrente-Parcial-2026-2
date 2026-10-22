package main

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "--http" {
		httpAddr := ":8081"
		if envPort := os.Getenv("PORT"); envPort != "" {
			httpAddr = ":" + envPort
		}
		if len(os.Args) > 2 && os.Args[2] != "" {
			httpAddr = os.Args[2]
			if !strings.HasPrefix(httpAddr, ":") {
				httpAddr = ":" + httpAddr
			}
		}
		threads := 4
		if len(os.Args) > 3 {
			if t, err := strconv.Atoi(os.Args[3]); err == nil {
				threads = t
			}
		}
		startHttpService(httpAddr, threads)
		return
	}

	workerID := "node_go_1"
	serverHost := "127.0.0.1"
	serverPort := 9010
	numThreads := 4

	if len(os.Args) > 1 {
		workerID = os.Args[1]
	}
	if len(os.Args) > 2 {
		serverHost = os.Args[2]
	}
	if len(os.Args) > 3 {
		if p, err := strconv.Atoi(os.Args[3]); err == nil {
			serverPort = p
		}
	}
	if len(os.Args) > 4 {
		if t, err := strconv.Atoi(os.Args[4]); err == nil {
			numThreads = t
		}
	}

	addr := fmt.Sprintf("%s:%d", serverHost, serverPort)
	fmt.Printf("[Worker-Go] Starting worker node %s (threads: %d)...\n", workerID, numThreads)
	fmt.Printf("[Worker-Go] Connecting to orchestrator at %s...\n", addr)

	var conn net.Conn
	var err error
	for attempt := 1; attempt <= 10; attempt++ {
		conn, err = net.DialTimeout("tcp", addr, 3*time.Second)
		if err == nil {
			break
		}
		fmt.Printf("[Worker-Go] Retrying connection (%d/10): %v\n", attempt, err)
		time.Sleep(1 * time.Second)
	}

	if err != nil {
		fmt.Printf("[Worker-Go] Fatal: failed to connect to %s: %v\n", addr, err)
		os.Exit(1)
	}
	defer conn.Close()

	// Register with Central Server
	regMsg := fmt.Sprintf("REGISTER|%s|Go|%d|CONV,MLP\n", workerID, numThreads)
	if _, err := conn.Write([]byte(regMsg)); err != nil {
		fmt.Printf("[Worker-Go] Failed to send registration: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("[Worker-Go] Successfully registered with central orchestrator as: %s\n", workerID)

	reader := bufio.NewReader(conn)
	writer := bufio.NewWriter(conn)

	// Wait for registration ACK
	ackLine, err := reader.ReadString('\n')
	if err != nil {
		fmt.Printf("[Worker-Go] Failed to read registration ACK: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("[Worker-Go] Registration confirmed: %s", ackLine)

	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			fmt.Printf("[Worker-Go] Connection closed by server: %v\n", err)
			break
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		resp := handleMessage(line, numThreads)
		if resp != "" {
			if !strings.HasSuffix(resp, "\n") {
				resp += "\n"
			}
			if _, err := writer.WriteString(resp); err != nil {
				fmt.Printf("[Worker-Go] Write error: %v\n", err)
				break
			}
			writer.Flush()
		}
	}
}

func handleMessage(line string, threadCount int) string {
	parts := strings.SplitN(line, "|", 3)
	if len(parts) < 2 {
		return ""
	}
	action := parts[0]
	taskID := parts[1]
	payload := ""
	if len(parts) >= 3 {
		payload = parts[2]
	}

	switch action {
	case "REGISTER_ACK":
		return ""

	case "CONV_REQ":
		tokens := strings.Split(payload, ",")
		pixels := make([]float32, 0, len(tokens))
		for _, tok := range tokens {
			if tok = strings.TrimSpace(tok); tok != "" {
				if v, err := strconv.ParseFloat(tok, 32); err == nil {
					pixels = append(pixels, float32(v))
				}
			}
		}
		feats := ConvolutionChunk(pixels, threadCount)
		return fmt.Sprintf("CONV_RESP|%s|%s", taskID, FeaturesToString(feats))

	case "MLP_REQ":
		feats := FeaturesFromString(payload)
		pred := MlpChunk(feats)
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("%s:%.2f", pred.Label, pred.Confidence*100.0))
		for _, p := range pred.Probabilities {
			sb.WriteString(fmt.Sprintf(";%.4f", p))
		}
		return fmt.Sprintf("MLP_RESP|%s|%s", taskID, sb.String())

	case "INFER_REQ":
		inferParts := strings.SplitN(payload, "|", 2)
		rawPixels := payload
		if len(inferParts) == 2 {
			rawPixels = inferParts[1]
		}
		tokens := strings.Split(rawPixels, ",")
		pixels := make([]float32, 0, len(tokens))
		for _, tok := range tokens {
			if tok = strings.TrimSpace(tok); tok != "" {
				if v, err := strconv.ParseFloat(tok, 32); err == nil {
					pixels = append(pixels, float32(v))
				}
			}
		}
		feats := ConvolutionChunk(pixels, threadCount)
		pred := MlpChunk(feats)
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("%s:%.2f", pred.Label, pred.Confidence*100.0))
		for _, p := range pred.Probabilities {
			sb.WriteString(fmt.Sprintf(";%.4f", p))
		}
		return fmt.Sprintf("INFER_RESP|%s|%s", taskID, sb.String())

	default:
		return fmt.Sprintf("ERROR|%s|Unknown action: %s", taskID, action)
	}
}

func startHttpService(addr string, threads int) {
	fmt.Printf("[Worker-Go-HTTP] Starting Cloud HTTP Worker on %s (threads: %d)...\n", addr, threads)
	http.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, "{\"status\":\"ok\",\"worker\":\"node_go_cloud\",\"language\":\"Go\",\"threads\":%d}\n", threads)
	})
	http.HandleFunc("/conv", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		tokens := strings.Split(string(body), ",")
		pixels := make([]float32, 0, len(tokens))
		for _, tok := range tokens {
			if tok = strings.TrimSpace(tok); tok != "" {
				if v, err := strconv.ParseFloat(tok, 32); err == nil {
					pixels = append(pixels, float32(v))
				}
			}
		}
		feats := ConvolutionChunk(pixels, threads)
		w.Header().Set("Content-Type", "text/plain")
		fmt.Fprint(w, FeaturesToString(feats))
	})
	http.HandleFunc("/mlp", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		feats := FeaturesFromString(string(body))
		pred := MlpChunk(feats)
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("%s:%.2f", pred.Label, pred.Confidence*100.0))
		for _, p := range pred.Probabilities {
			sb.WriteString(fmt.Sprintf(";%.4f", p))
		}
		w.Header().Set("Content-Type", "text/plain")
		fmt.Fprint(w, sb.String())
	})
	http.HandleFunc("/infer", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		tokens := strings.Split(string(body), ",")
		pixels := make([]float32, 0, len(tokens))
		for _, tok := range tokens {
			if tok = strings.TrimSpace(tok); tok != "" {
				if v, err := strconv.ParseFloat(tok, 32); err == nil {
					pixels = append(pixels, float32(v))
				}
			}
		}
		feats := ConvolutionChunk(pixels, threads)
		pred := MlpChunk(feats)
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("%s:%.2f", pred.Label, pred.Confidence*100.0))
		for _, p := range pred.Probabilities {
			sb.WriteString(fmt.Sprintf(";%.4f", p))
		}
		w.Header().Set("Content-Type", "text/plain")
		fmt.Fprint(w, sb.String())
	})
	if err := http.ListenAndServe(addr, nil); err != nil {
		fmt.Printf("[Worker-Go-HTTP] Server failed: %v\n", err)
	}
}
