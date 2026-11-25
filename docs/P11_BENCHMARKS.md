# P11 Server Benchmarks

`scripts/server_benchmark.mjs` runs a Paper or Folia server with the current StarGenesis plugin and four offline Mineflayer clients. By default it requires an isolated Java host; explicitly pass `--allow-concurrent-java` to measure on a shared developer host. The clients create a four-member virtual factory, query its persisted status concurrently, and advance the deterministic simulation in bounded batches. The server uses the same view distance, simulation distance, JVM heap, processor count, and workload for either server kind.

The benchmark records server-reported TPS windows, tick-time distributions, process CPU time, system available memory, resident/private memory, JVM heap usage, client command latency, artifact hashes, basic OS and hardware information, an operator-supplied note about other server workloads, and the server log. It prefers Spark output and also queries native `/tps` and `/mspt` commands. For Folia, it parses median region TPS and highest-utilization-region MSPT from the native health report when global TPS output is unavailable. Each sample records its metric source and window. JVM heap is sampled at the configured memory interval. System available memory is recorded at startup and every metric sample. Results include Java process descriptions at startup, newly observed process descriptions with PIDs and first observation times, five-second observation counts, plus benchmark server PID and lifecycle times. The default monitor uses `jcmd -l` on non-Windows platforms and the Windows `Win32_Process` inventory on Windows; a failed query is an error, never an empty baseline. By default startup refuses any pre-existing Java process and any newly observed Java process stops the run; `--allow-concurrent-java` records concurrent processes and continues while preserving process-probe failure handling and all performance thresholds. Results mark shared-host runs explicitly; their numbers are not isolated-host comparisons. Windows exit codes above `0x7fffffff` are reported in signed form with the original value retained. Failure JSON includes elapsed time, recent client chat messages, and disconnect or error events. Measurements do not establish human usability or behavior under machine power loss.

Use an empty, unique `--directory` for every run. `--runtime-directory` must contain Paper's bootstrapped `cache`, `libraries`, and `versions` directories plus `config/paper-global.yml`. The script copies those runtime inputs, copies the Paper global configuration, and refuses to reuse a non-empty server directory. It also checks the local port before startup. The default run lasts 30 minutes, samples server metrics every 15 seconds, and samples JVM heap every 60 seconds.

Pass `--detach` to launch the benchmark worker in a detached process group. The launcher prints the worker PID and result path, then exits. Before starting Java, the worker saves a snapshot with `lifecycle: "running"` and `passed: false`; after cleanup it writes `lifecycle: "completed"` with the final outcome. Each JSON replacement uses a same-directory temporary file and rename. A result file's existence is not proof of completion: if the host terminates the worker, the initial snapshot can remain. Confirm the actual worker/server process state before treating an incomplete snapshot as stopped. This keeps server lifecycle independent of the launching terminal session.

Log-write exceptions and asynchronous pipe errors are routed through the main failure and cleanup path. Every created client is registered before connection finishes, so partial connection failure still cleans up those clients. A client end, kick or runtime error before intentional cleanup aborts the run, including during idle intervals between commands; the initial connected-client count alone is not treated as sustained participation. A client cleanup exception does not prevent attempts to stop the owned server; stop timeout triggers termination and a bounded wait for process closure. The result retains the primary error and `cleanup_errors`. A cleanup failure keeps `passed` false. The regression suite uses short Node child processes and real filesystem failures to exercise these paths; it does not substitute for a Paper/Folia runtime benchmark.

```powershell
node scripts/server_benchmark.mjs `
  --kind paper `
  --server-jar .work/paper-1.21.11-132.jar `
  --plugin-jar stargenesis-paper/target/stargenesis-paper-4.0.0-SNAPSHOT.jar `
  --runtime-directory .work/factory-paper-cached `
  --directory .work/p11-paper-run-01 `
  --result docs/evidence/p11/paper-run-01.json `
  --java C:/path/to/java.exe `
  --port 25601 `
  --environment-note "No other Java servers were running" `
  --accept-eula
```

Run Folia separately with its matching server jar and bootstrapped runtime directory, a new empty `--directory`, another port, and a distinct result path. Do not run Paper and Folia benchmarks simultaneously when comparing measurements. Record the machine, OS, Java version, server build, plugin SHA-256, heap arguments, duration, workload, and other server activity when interpreting a comparison.

On a shared host, add `--allow-concurrent-java` and an accurate `--environment-note`. The result records the full JVM baseline and every newly observed JVM, continues to sample the original server, and applies the same TPS, tick, system memory, RSS, heap, duration, and coverage gates. A shared-host pass meets those numeric limits under the recorded activity; it does not provide an isolated Paper/Folia comparison.

A shorter `--duration-seconds` run validates the harness but does not satisfy the P11 duration gate. Folia does not expose bundled Spark commands in every configuration, so the harness falls back to native metrics and fails collection if neither path returns TPS and tick-time data.

Folia fallback TPS is the median across regions, while MSPT comes from the highest-utilization region. These aggregates differ from Paper's Spark distributions and are interpreted separately.

The initial release gate assumes the supplied four-core, 8 GiB host, four offline clients, and `-Xmx512M`. At least 80% of expected TPS, tick-time, available-memory, RSS, and heap samples must be present. TPS P50 must be at least 19, tick-time P95 at most 50 ms, available system memory at least 1,024 MiB, server RSS at most 1,536 MiB, and observed JVM heap at most 480 MiB. Paper uses server-reported tick P95 when available. Folia does not provide a region MSPT P95 distribution, so the gate uses sampled highest-utilization-region MSPT values and identifies that source in the result. These are initial project targets and can be revised when a production hardware profile is adopted.

If a run stops after collecting samples, the result still includes summary statistics and individual gate outcomes. `passed` remains false unless the requested duration, workload, sample coverage, performance and memory gates, and normal server exit all complete.
