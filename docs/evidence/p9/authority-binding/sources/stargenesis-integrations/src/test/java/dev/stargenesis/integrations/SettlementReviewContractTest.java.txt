package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.stargenesis.integrations.IdempotentSettlementPort.Observation.*;
import static dev.stargenesis.integrations.SettlementOperation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReviewContractTest {
    @Test void onlyFourConsistentObservationCombinationsCanLeaveReview() {
        var resolved = Map.of(
                "NOT_APPLIED/NOT_APPLIED/NOT_APPLIED", SettlementSaga.State.FAILED,
                "APPLIED/APPLIED/NOT_APPLIED", SettlementSaga.State.COMPLETED,
                "APPLIED/NOT_APPLIED/APPLIED", SettlementSaga.State.COMPENSATED,
                "APPLIED/NOT_APPLIED/NOT_APPLIED", SettlementSaga.State.COMPENSATION_REQUIRED);
        for (var payment : IdempotentSettlementPort.Observation.values()) {
            for (var delivery : IdempotentSettlementPort.Observation.values()) {
                for (var refund : IdempotentSettlementPort.Observation.values()) {
                    var observations = Map.of(PAYMENT, payment, DELIVERY, delivery, COMPENSATION, refund);
                    var expected = resolved.getOrDefault(payment + "/" + delivery + "/" + refund, SettlementSaga.State.MANUAL_REVIEW);
                    assertEquals(expected, SettlementReview.resolution(observations, Set.of()), observations.toString());
                }
            }
        }
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, SettlementReview.resolution(
                Map.of(PAYMENT, NOT_APPLIED, DELIVERY, NOT_APPLIED, COMPENSATION, NOT_APPLIED), Set.of(PAYMENT)));
        assertThrows(IllegalArgumentException.class, () -> SettlementReview.resolution(Map.of(PAYMENT, APPLIED), Set.of()));
    }

    @Test void sharedOperationFactoryPreservesTheLegacyKeysAndCompletePayloads() {
        var buyer = new UUID(0, 1101);
        var seller = new UUID(0, 1102);
        var request = new SettlementRequest(new UUID(0, 1103), buyer, buyer, seller, 10, "test:ore", 2, 100);
        assertEquals(new SettlementOperation("8ab3e060028385d4731fe76341e2267e05d26f922c881bfe3dfd1b2ee73e0094",
                buyer, seller, SettlementOperation.Asset.CREDITS, "credits", 10), SettlementOperation.forPhase(request, PAYMENT));
        assertEquals(new SettlementOperation("c584ec1d45c4db798177b5c6607d94d7a3d779335ec029342f9b4cc20b0e63a1",
                seller, buyer, SettlementOperation.Asset.ITEM, "test:ore", 2), SettlementOperation.forPhase(request, DELIVERY));
        assertEquals(new SettlementOperation("83d4190d122be599d0c2a95618823b73d5dd1c1c04187451331f163f2ddfb6a4",
                seller, buyer, SettlementOperation.Asset.CREDITS, "credits", 10), SettlementOperation.forPhase(request, COMPENSATION));
    }
}
