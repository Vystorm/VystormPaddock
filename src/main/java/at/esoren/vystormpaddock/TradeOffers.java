package at.esoren.vystormpaddock;

import java.math.BigDecimal;
import java.util.*;

/**
 * Animal trade offers: a seller offers one claimed animal to one buyer, optionally for a price. One open offer per
 * buyer (a new one replaces it), offers expire. In memory only. Pure data, server thread.
 */
final class TradeOffers {
    record Offer(UUID seller, String sellerName, UUID buyer, UUID animal, String animalName, double price, long until) {}

    private final Map<UUID, Offer> byBuyer = new HashMap<>();

    void put(Offer offer) { byBuyer.put(offer.buyer(), offer); }

    /** The buyer's open offer, removed; empty when none or expired. */
    Optional<Offer> take(UUID buyer, long now) {
        Offer offer = byBuyer.remove(buyer);
        return offer == null || offer.until() < now ? Optional.empty() : Optional.of(offer);
    }

    Optional<Offer> peek(UUID buyer, long now) {
        Offer offer = byBuyer.get(buyer);
        return offer == null || offer.until() < now ? Optional.empty() : Optional.of(offer);
    }

    /** Drops every offer of a seller or for a buyer who left, and offers for an animal that changed hands. */
    void forget(UUID player) { byBuyer.values().removeIf(o -> o.seller().equals(player) || o.buyer().equals(player)); }
    void forgetAnimal(UUID animal) { byBuyer.values().removeIf(o -> o.animal().equals(animal)); }

    /** Price from text: empty = 0, otherwise a non-negative number with at most two decimals and at most 1e12; null if invalid. */
    static Double price(String text) {
        if (text == null || text.isBlank()) return 0.0;
        try {
            BigDecimal value = new BigDecimal(text.strip().replace(',', '.'));
            if (value.signum() < 0 || value.scale() > 2 || value.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) return null;
            return value.doubleValue();
        } catch (NumberFormatException invalid) { return null; }
    }
}
