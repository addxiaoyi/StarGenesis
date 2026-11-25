# Integration Extension SDK

StarGenesis integration providers are ordinary Bukkit plugins. They register an `IntegrationExtension` service from `stargenesis-integrations` during their `onEnable`; StarGenesis does not load extension JARs or execute package contents itself. The current service contract is `IntegrationExtension.API_VERSION = 1`.

An administrator must enable `integrations.extensions.enabled` and add an exact provider grant to `plugins/StarGenesis/config.yml`. Each grant binds an `extension.*` ID to one plugin name, exact plugin version, SHA-256 of its JAR, and up to 64 capability names. The provider's service implementation must be packaged in that same JAR. The provider's adapter result is intersected with the configured capabilities by `CapabilityRegistry`, so it cannot activate a capability absent from the grant.

```yaml
integrations:
  extensions:
    enabled: true
    providers:
      - id: extension.example
        plugin: ExampleAdapter
        version: 1.2.3
        sha256: 64-lowercase-hex-characters-from-the-installed-jar
        capabilities:
          - items.read
```

Replace the sample digest with the exact installed artifact digest. For example, administrators can calculate it on Windows with `Get-FileHash plugins/ExampleAdapter.jar -Algorithm SHA256`. Add a grant only after reviewing the provider artifact and requested capabilities. Restart the server after changing the grant.

Provider plugins should declare `depend: [StarGenesis]` so the SDK interface is available from the StarGenesis plugin class loader. Compile against the matching `StarGenesis-Integrations-SDK.jar` release asset and Paper API with provided scope. Register the service from the provider's `onEnable`, with the provider plugin as owner and a stable `extension.*` ID.

```java
getServer().getServicesManager().register(
        IntegrationExtension.class,
        new ExampleIntegrationExtension(),
        this,
        ServicePriority.Normal
);
```

StarGenesis discovers a provider after its enable event, validates its grant, and refreshes `/genesis debug capabilities`. Disabling the provider unregisters its adapter and calls `close`. `IntegrationAdapter.verify` must be bounded, read-only and idempotent, and `close` must release provider resources.

The JAR digest and version pin control whether StarGenesis will call the provider adapter. They do not sandbox code. Bukkit has already loaded and enabled the provider plugin, which runs with the server's process privileges. Providers must be installed and reviewed as trusted server code. Real settlement remains separately disabled and has no verified adapter.
