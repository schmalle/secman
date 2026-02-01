// Secman MCP Client Example
//
// A Go client that communicates with the Secman MCP server using JSON-RPC 2.0
// over HTTP. Demonstrates how to authenticate, discover tools, and call them.
//
// Usage:
//
//	export SECMAN_BASE_URL=http://localhost:8080
//	export SECMAN_API_KEY=sk-your-api-key
//	export SECMAN_USER_EMAIL=admin@example.com  # optional, for user delegation
//	go run main.go [command] [flags]
//
// Commands:
//
//	capabilities     List server capabilities and available tools
//	call <tool>      Call a tool by name (pass arguments as JSON via --args)
//	assets           List assets (shorthand for call get_assets)
//	vulnerabilities  List vulnerabilities (shorthand for call get_vulnerabilities)
//	requirements     List requirements (shorthand for call get_requirements)
//	users            List users (requires ADMIN delegation)
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// --- JSON-RPC 2.0 types ---

type JSONRPCRequest struct {
	JSONRPC string      `json:"jsonrpc"`
	ID      string      `json:"id"`
	Method  string      `json:"method"`
	Params  interface{} `json:"params,omitempty"`
}

type JSONRPCResponse struct {
	JSONRPC string           `json:"jsonrpc"`
	ID      string           `json:"id"`
	Result  *json.RawMessage `json:"result,omitempty"`
	Error   *JSONRPCError    `json:"error,omitempty"`
}

type JSONRPCError struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data,omitempty"`
}

// --- MCP types ---

type ToolCallParams struct {
	Name      string                 `json:"name"`
	Arguments map[string]interface{} `json:"arguments,omitempty"`
}

type ToolDefinition struct {
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	InputSchema map[string]interface{} `json:"inputSchema,omitempty"`
}

type CapabilitiesResponse struct {
	Capabilities struct {
		Tools []ToolDefinition `json:"tools"`
	} `json:"capabilities"`
	ServerInfo map[string]interface{} `json:"serverInfo"`
}

type ToolCallResult struct {
	Content  interface{}            `json:"content"`
	IsError  bool                   `json:"isError"`
	Metadata map[string]interface{} `json:"metadata,omitempty"`
}

// --- Client ---

type McpClient struct {
	baseURL   string
	apiKey    string
	userEmail string
	http      *http.Client
	requestID int
}

func NewMcpClient(baseURL, apiKey, userEmail string) *McpClient {
	return &McpClient{
		baseURL:   strings.TrimRight(baseURL, "/"),
		apiKey:    apiKey,
		userEmail: userEmail,
		http: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

func (c *McpClient) nextID() string {
	c.requestID++
	return fmt.Sprintf("req-%d", c.requestID)
}

// doRequest sends a JSON-RPC request to the MCP tools/call endpoint.
func (c *McpClient) doRequest(method string, params interface{}) (*json.RawMessage, error) {
	req := JSONRPCRequest{
		JSONRPC: "2.0",
		ID:      c.nextID(),
		Method:  method,
		Params:  params,
	}

	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	httpReq, err := http.NewRequest("POST", c.baseURL+"/api/mcp/tools/call", bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("X-MCP-API-Key", c.apiKey)
	if c.userEmail != "" {
		httpReq.Header.Set("X-MCP-User-Email", c.userEmail)
	}

	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("http request: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(respBody))
	}

	var rpcResp JSONRPCResponse
	if err := json.Unmarshal(respBody, &rpcResp); err != nil {
		return nil, fmt.Errorf("unmarshal response: %w", err)
	}

	if rpcResp.Error != nil {
		return nil, fmt.Errorf("RPC error %d: %s", rpcResp.Error.Code, rpcResp.Error.Message)
	}

	return rpcResp.Result, nil
}

// GetCapabilities fetches the server capabilities (tool list).
func (c *McpClient) GetCapabilities() (*CapabilitiesResponse, error) {
	httpReq, err := http.NewRequest("GET", c.baseURL+"/api/mcp/capabilities", nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	httpReq.Header.Set("X-MCP-API-Key", c.apiKey)
	if c.userEmail != "" {
		httpReq.Header.Set("X-MCP-User-Email", c.userEmail)
	}

	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("http request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body))
	}

	var caps CapabilitiesResponse
	if err := json.Unmarshal(body, &caps); err != nil {
		return nil, fmt.Errorf("unmarshal capabilities: %w", err)
	}

	return &caps, nil
}

// CallTool invokes an MCP tool by name with the given arguments.
func (c *McpClient) CallTool(name string, args map[string]interface{}) (*ToolCallResult, error) {
	params := ToolCallParams{
		Name:      name,
		Arguments: args,
	}

	result, err := c.doRequest("tools/call", params)
	if err != nil {
		return nil, err
	}

	var toolResult ToolCallResult
	if result != nil {
		if err := json.Unmarshal(*result, &toolResult); err != nil {
			return nil, fmt.Errorf("unmarshal tool result: %w", err)
		}
	}

	return &toolResult, nil
}

// --- CLI ---

func printJSON(v interface{}) {
	out, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error formatting output: %v\n", err)
		return
	}
	fmt.Println(string(out))
}

func usage() {
	fmt.Fprintf(os.Stderr, `Secman MCP Client - Go Example

Usage: go run main.go <command> [flags]

Commands:
  capabilities          List available MCP tools
  call <tool> [--args]  Call a tool (pass arguments as JSON)
  assets                List assets (optional: --name, --type, --page, --pageSize)
  vulnerabilities       List vulnerabilities (optional: --severity, --page, --pageSize)
  requirements          List requirements (optional: --status, --priority, --limit)
  users                 List users (requires ADMIN delegation)
  scans                 List scan history

Environment Variables:
  SECMAN_BASE_URL       Backend URL (default: http://localhost:8080)
  SECMAN_API_KEY        MCP API key (required)
  SECMAN_USER_EMAIL     User email for delegation (optional)

Examples:
  # List all available tools
  go run main.go capabilities

  # Get first page of assets
  go run main.go assets --page 0 --pageSize 10

  # Get critical vulnerabilities
  go run main.go vulnerabilities --severity CRITICAL

  # Call any tool with raw JSON arguments
  go run main.go call get_asset_profile --args '{"assetId": 42}'

  # List users (requires admin delegation)
  SECMAN_USER_EMAIL=admin@example.com go run main.go users
`)
	os.Exit(1)
}

func envOrDefault(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func main() {
	if len(os.Args) < 2 {
		usage()
	}

	baseURL := envOrDefault("SECMAN_BASE_URL", "http://localhost:8080")
	apiKey := os.Getenv("SECMAN_API_KEY")
	userEmail := os.Getenv("SECMAN_USER_EMAIL")

	if apiKey == "" {
		fmt.Fprintln(os.Stderr, "Error: SECMAN_API_KEY environment variable is required")
		os.Exit(1)
	}

	client := NewMcpClient(baseURL, apiKey, userEmail)
	command := os.Args[1]

	switch command {
	case "capabilities":
		cmdCapabilities(client)
	case "call":
		cmdCall(client, os.Args[2:])
	case "assets":
		cmdAssets(client, os.Args[2:])
	case "vulnerabilities":
		cmdVulnerabilities(client, os.Args[2:])
	case "requirements":
		cmdRequirements(client, os.Args[2:])
	case "users":
		cmdUsers(client)
	case "scans":
		cmdScans(client, os.Args[2:])
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n\n", command)
		usage()
	}
}

func cmdCapabilities(client *McpClient) {
	caps, err := client.GetCapabilities()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Server: %v\n\n", caps.ServerInfo["name"])
	fmt.Printf("Available tools (%d):\n", len(caps.Capabilities.Tools))
	for _, tool := range caps.Capabilities.Tools {
		fmt.Printf("  %-35s %s\n", tool.Name, tool.Description)
	}
}

func cmdCall(client *McpClient, osArgs []string) {
	if len(osArgs) < 1 {
		fmt.Fprintln(os.Stderr, "Error: tool name required")
		fmt.Fprintln(os.Stderr, "Usage: go run main.go call <tool-name> [--args '{...}']")
		os.Exit(1)
	}

	toolName := osArgs[0]

	fs := flag.NewFlagSet("call", flag.ExitOnError)
	argsJSON := fs.String("args", "{}", "Tool arguments as JSON")
	fs.Parse(osArgs[1:])

	var args map[string]interface{}
	if err := json.Unmarshal([]byte(*argsJSON), &args); err != nil {
		fmt.Fprintf(os.Stderr, "Error parsing --args JSON: %v\n", err)
		os.Exit(1)
	}

	result, err := client.CallTool(toolName, args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}

func cmdAssets(client *McpClient, osArgs []string) {
	fs := flag.NewFlagSet("assets", flag.ExitOnError)
	name := fs.String("name", "", "Filter by name (partial match)")
	assetType := fs.String("type", "", "Filter by type (SERVER, WORKSTATION, etc.)")
	ip := fs.String("ip", "", "Filter by IP (partial match)")
	owner := fs.String("owner", "", "Filter by owner")
	page := fs.Int("page", 0, "Page number (0-indexed)")
	pageSize := fs.Int("pageSize", 100, "Items per page (max 500)")
	fs.Parse(osArgs)

	args := map[string]interface{}{
		"page":     *page,
		"pageSize": *pageSize,
	}
	if *name != "" {
		args["name"] = *name
	}
	if *assetType != "" {
		args["type"] = *assetType
	}
	if *ip != "" {
		args["ip"] = *ip
	}
	if *owner != "" {
		args["owner"] = *owner
	}

	result, err := client.CallTool("get_assets", args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}

func cmdVulnerabilities(client *McpClient, osArgs []string) {
	fs := flag.NewFlagSet("vulnerabilities", flag.ExitOnError)
	severity := fs.String("severity", "", "Filter by severity (CRITICAL, HIGH, MEDIUM, LOW)")
	assetID := fs.String("assetId", "", "Filter by asset ID")
	minDaysOpen := fs.Int("minDaysOpen", -1, "Minimum days open")
	page := fs.Int("page", 0, "Page number (0-indexed)")
	pageSize := fs.Int("pageSize", 100, "Items per page (max 500)")
	fs.Parse(osArgs)

	args := map[string]interface{}{
		"page":     *page,
		"pageSize": *pageSize,
	}
	if *severity != "" {
		args["severity"] = *severity
	}
	if *assetID != "" {
		id, err := strconv.Atoi(*assetID)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error: invalid assetId: %v\n", err)
			os.Exit(1)
		}
		args["assetId"] = id
	}
	if *minDaysOpen >= 0 {
		args["minDaysOpen"] = *minDaysOpen
	}

	result, err := client.CallTool("get_vulnerabilities", args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}

func cmdRequirements(client *McpClient, osArgs []string) {
	fs := flag.NewFlagSet("requirements", flag.ExitOnError)
	status := fs.String("status", "", "Filter by status (DRAFT, ACTIVE, DEPRECATED, ARCHIVED)")
	priority := fs.String("priority", "", "Filter by priority (LOW, MEDIUM, HIGH, CRITICAL)")
	limit := fs.Int("limit", 0, "Maximum number to return (0 = all)")
	fs.Parse(osArgs)

	args := map[string]interface{}{}
	if *status != "" {
		args["status"] = *status
	}
	if *priority != "" {
		args["priority"] = *priority
	}
	if *limit > 0 {
		args["limit"] = *limit
	}

	result, err := client.CallTool("get_requirements", args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}

func cmdUsers(client *McpClient) {
	result, err := client.CallTool("list_users", map[string]interface{}{})
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}

func cmdScans(client *McpClient, osArgs []string) {
	fs := flag.NewFlagSet("scans", flag.ExitOnError)
	scanType := fs.String("type", "", "Filter by scan type (nmap, masscan)")
	uploadedBy := fs.String("uploadedBy", "", "Filter by uploader")
	page := fs.Int("page", 0, "Page number (0-indexed)")
	pageSize := fs.Int("pageSize", 100, "Items per page (max 500)")
	fs.Parse(osArgs)

	args := map[string]interface{}{
		"page":     *page,
		"pageSize": *pageSize,
	}
	if *scanType != "" {
		args["scanType"] = *scanType
	}
	if *uploadedBy != "" {
		args["uploadedBy"] = *uploadedBy
	}

	result, err := client.CallTool("get_scans", args)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}

	printJSON(result)
}
