package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRelayCommandRulesTest {
    @Test
    void allowsFactoryOperationsThatUseTheCanonicalRemoteLedger() {
        assertEquals(java.util.List.of("bid", "factory:frame", "7", "19"),
                FactoryRelayCommandRules.validate(new String[] {"bid", "factory:frame", "7", "19"}));
        assertEquals(java.util.List.of("station", "queue", "factory:plate", "8", "2"),
                FactoryRelayCommandRules.validate(new String[] {"station", "queue", "factory:plate", "8", "2"}));
        assertEquals(java.util.List.of("orders", "2"),
                FactoryRelayCommandRules.validate(new String[] {"orders", "2"}));
    }

    @Test
    void deniesLocalAdministrationAndNestedRelayOperations() {
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayCommandRules.validate(new String[] {"create"}));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayCommandRules.validate(new String[] {"order", "factory:frame", "4", "20", "4", "5"}));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayCommandRules.validate(new String[] {"org", "add", "00000000-0000-0000-0000-000000000001"}));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayCommandRules.validate(new String[] {"remote", "hub_c", "status"}));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayCommandRules.validate(new String[] {"station", "queue", "factory:plate"}));
    }
}
