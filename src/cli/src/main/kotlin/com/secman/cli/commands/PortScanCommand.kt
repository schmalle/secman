package com.secman.cli.commands

import com.secman.cli.service.CliHttpClient
import com.secman.cli.service.PortScanCliService
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Files

/**
 * CLI command to port-scan internet-facing assets using nmap.
 *
 * Workflow:
 * 1. Authenticate with backend API
 * 2. Fetch internet-facing assets (EXTERNAL/DMZ network zone with IP)
 * 3. Validate nmap is installed
 * 4. Run nmap against each target
 * 5. Upload scan results to backend via /api/scan/upload-nmap
 *
 * Usage:
 *   secman port-scan [options]
 *   secman port-scan --dry-run
 *   secman port-scan --targets "web-*" --ports "80,443,8080"
 */
@Command(
    name = "port-scan",
    description = ["Port-scan internet-facing assets using nmap"],
    mixinStandardHelpOptions = true
)
class PortScanCommand : Runnable {

    @Inject
    lateinit var cliHttpClient: CliHttpClient

    @Inject
    lateinit var portScanCliService: PortScanCliService

    @Option(names = ["--backend-url"], description = ["Backend API URL (or set SECMAN_HOST env var)"])
    var backendUrl: String? = null

    @Option(names = ["--username"], description = ["Backend username (or set SECMAN_ADMIN_NAME env var)"])
    var username: String? = null

    @Option(names = ["--password"], description = ["Backend password (or set SECMAN_ADMIN_PASS env var)"])
    var password: String? = null

    @Option(names = ["--nmap-path"], description = ["Path to nmap binary (default: nmap)"], defaultValue = "nmap")
    var nmapPath: String = "nmap"

    @Option(names = ["--nmap-args"], description = ["Additional nmap arguments (default: -sV -T4)"], defaultValue = "-sV -T4")
    var nmapArgs: String = "-sV -T4"

    @Option(names = ["--ports", "-p"], description = ["Port range to scan (default: nmap top 1000)"])
    var ports: String? = null

    @Option(names = ["--targets"], description = ["Filter assets by name pattern (e.g., web-*)"])
    var targets: String? = null

    @Option(names = ["--dry-run"], description = ["Show scan targets without running nmap"])
    var dryRun: Boolean = false

    @Option(names = ["--verbose", "-v"], description = ["Enable verbose output"])
    var verbose: Boolean = false

    @Option(names = ["--output-dir"], description = ["Directory to save nmap XML files (default: temp dir)"])
    var outputDir: String? = null

    override fun run() {
        println("============================================================")
        println("Port Scan - Internet-Facing Assets")
        println("============================================================")
        println()

        try {
            // 1. Authenticate
            val effectiveUrl = getEffectiveBackendUrl()
            val effectiveUsername = getEffectiveUsername()
            val effectivePassword = getEffectivePassword()

            if (verbose) {
                println("Backend URL: $effectiveUrl")
                println("Username: $effectiveUsername")
                println()
            }

            print("Authenticating... ")
            val authToken = cliHttpClient.authenticate(effectiveUsername, effectivePassword, effectiveUrl)
                ?: throw RuntimeException("Authentication failed. Check credentials.")
            println("OK")

            // 2. Validate nmap
            print("Checking nmap... ")
            val nmapVersion = portScanCliService.validateNmap(nmapPath)
            if (nmapVersion == null) {
                println("FAILED")
                System.err.println()
                System.err.println("Error: nmap not found at '$nmapPath'")
                System.err.println("Install nmap:")
                System.err.println("  Ubuntu/Debian: sudo apt install nmap")
                System.err.println("  macOS:         brew install nmap")
                System.err.println("  RHEL/CentOS:   sudo yum install nmap")
                System.err.println("Or specify the path: --nmap-path /usr/local/bin/nmap")
                System.exit(1)
                return
            }
            println("OK ($nmapVersion)")

            // 3. Fetch targets
            print("Fetching internet-facing assets... ")
            val assets = portScanCliService.fetchInternetFacingAssets(effectiveUrl, authToken, targets)
            println("found ${assets.size} target(s)")

            if (assets.isEmpty()) {
                println()
                println("No internet-facing assets found.")
                println("Ensure assets have networkZone set to EXTERNAL or DMZ and have an IP address.")
                return
            }

            println()
            println("Targets:")
            for ((i, asset) in assets.withIndex()) {
                println("  ${i + 1}. ${asset.name} (${asset.ip}) [${asset.networkZone ?: "UNKNOWN"}]")
            }
            println()

            // 4. Dry-run check
            if (dryRun) {
                val nmapArgsList = parseNmapArgs()
                println("Dry-run mode - nmap commands that would be executed:")
                println()
                for (asset in assets) {
                    val cmd = buildList {
                        add(nmapPath)
                        add("-oX")
                        add("<output.xml>")
                        addAll(nmapArgsList)
                        if (ports != null) {
                            add("-p")
                            add(ports!!)
                        }
                        add(asset.ip)
                    }
                    println("  ${cmd.joinToString(" ")}")
                }
                return
            }

            // 5. Prepare output directory
            val scanOutputDir = if (outputDir != null) {
                File(outputDir!!).also { it.mkdirs() }
            } else {
                Files.createTempDirectory("secman-portscan-").toFile()
            }

            if (verbose) {
                println("Output directory: ${scanOutputDir.absolutePath}")
                println()
            }

            // 6. Run scans
            val nmapArgsList = parseNmapArgs()
            var scanned = 0
            var succeeded = 0
            var failed = 0
            var uploaded = 0
            var totalOpenPorts = 0

            println("Scanning ${assets.size} target(s)...")
            println()

            for ((i, asset) in assets.withIndex()) {
                val outputFile = File(scanOutputDir, "scan_${asset.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}_${asset.ip.replace('.', '-')}.xml")

                print("[${i + 1}/${assets.size}] Scanning ${asset.name} (${asset.ip})... ")

                val result = portScanCliService.runNmapScan(nmapPath, nmapArgsList, asset.ip, ports, outputFile)
                scanned++

                if (result.success && result.outputFile != null) {
                    succeeded++
                    println("done")

                    if (verbose && result.errorOutput != null) {
                        println("    nmap stderr: ${result.errorOutput.take(200)}")
                    }

                    // Upload result
                    if (verbose) print("    Uploading... ")
                    val uploadResult = portScanCliService.uploadScanResult(effectiveUrl, authToken, result.outputFile)
                    if (uploadResult.success) {
                        uploaded++
                        if (verbose) println("OK")
                    } else {
                        if (verbose) println("FAILED: ${uploadResult.message}")
                        else System.err.println("    Upload failed: ${uploadResult.message}")
                    }
                } else {
                    failed++
                    println("FAILED (exit code: ${result.exitCode})")
                    if (result.errorOutput != null) {
                        System.err.println("    Error: ${result.errorOutput.take(200)}")
                    }
                }
            }

            // 7. Summary
            println()
            println("============================================================")
            println("Scan Summary")
            println("============================================================")
            println("Targets:    ${assets.size}")
            println("Scanned:    $scanned")
            println("Succeeded:  $succeeded")
            println("Failed:     $failed")
            println("Uploaded:   $uploaded")
            println("Output dir: ${scanOutputDir.absolutePath}")

            if (failed > 0) {
                System.exit(1)
            }

        } catch (e: IllegalArgumentException) {
            System.err.println("Error: ${e.message}")
            System.exit(1)
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (verbose) {
                e.printStackTrace()
            }
            System.exit(1)
        }
    }

    private fun parseNmapArgs(): List<String> {
        return nmapArgs.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun getEffectiveUsername(): String {
        return username ?: System.getenv("SECMAN_ADMIN_NAME")
            ?: throw IllegalArgumentException("Backend username required. Use --username flag or set SECMAN_ADMIN_NAME environment variable")
    }

    private fun getEffectivePassword(): String {
        return password ?: System.getenv("SECMAN_ADMIN_PASS")
            ?: throw IllegalArgumentException("Backend password required. Use --password flag or set SECMAN_ADMIN_PASS environment variable")
    }

    private fun getEffectiveBackendUrl(): String {
        val url = backendUrl ?: System.getenv("SECMAN_HOST") ?: System.getenv("SECMAN_BACKEND_URL") ?: "http://localhost:8080"
        return if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    }
}
