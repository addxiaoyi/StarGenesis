package dev.stargenesis.integrations;

/** Service contract for an administrator-pinned StarGenesis integration provider. */
public interface IntegrationExtension {
    int API_VERSION = 1;

    String integrationId();

    int apiVersion();

    IntegrationAdapter adapter();
}
