package dev.stargenesis.api.sim;
import java.util.Map;
/** Integer units only, simulation assets only. No minting or external-inventory payouts. */
public interface ResourceLedger {
    long balance(String account, String resource);
    TransferResult transfer(EvidenceId key, String source, String target, String resource, long amount);
    Map<String, Map<String, Long>> snapshot();
    enum TransferResult { APPLIED, ALREADY_APPLIED, INSUFFICIENT_FUNDS }
}
