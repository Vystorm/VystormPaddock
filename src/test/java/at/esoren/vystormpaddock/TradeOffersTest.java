package at.esoren.vystormpaddock;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TradeOffersTest {
    private static final UUID SELLER = UUID.randomUUID(), BUYER = UUID.randomUUID(), HORSE = UUID.randomUUID();

    private static TradeOffers.Offer offer(long until) { return new TradeOffers.Offer(SELLER, "Sam", BUYER, HORSE, "Comet", 250, until); }

    @Test
    void givenOffer_whenTakenInTime_thenReturnedOnce() {
        TradeOffers offers = new TradeOffers();
        offers.put(offer(1000));
        assertTrue(offers.peek(BUYER, 500).isPresent());
        assertEquals("Comet", offers.take(BUYER, 500).orElseThrow().animalName());
        assertTrue(offers.take(BUYER, 500).isEmpty());
    }

    @Test
    void givenExpiredOffer_whenTaken_thenEmpty() {
        TradeOffers offers = new TradeOffers();
        offers.put(offer(1000));
        assertTrue(offers.take(BUYER, 1001).isEmpty());
    }

    @Test
    void givenLeavingPlayerOrSoldAnimal_whenForgotten_thenOfferGone() {
        TradeOffers offers = new TradeOffers();
        offers.put(offer(1000));
        offers.forget(SELLER);
        assertTrue(offers.peek(BUYER, 0).isEmpty());
        offers.put(offer(1000));
        offers.forgetAnimal(HORSE);
        assertTrue(offers.peek(BUYER, 0).isEmpty());
    }

    @Test
    void givenPriceTexts_whenParsed_thenOnlySensibleValues() {
        assertEquals(0.0, TradeOffers.price(null));
        assertEquals(0.0, TradeOffers.price(" "));
        assertEquals(250.0, TradeOffers.price("250"));
        assertEquals(99.5, TradeOffers.price("99,50"));
        assertNull(TradeOffers.price("-1"));
        assertNull(TradeOffers.price("1.234"));
        assertNull(TradeOffers.price("abc"));
        assertNull(TradeOffers.price("2000000000000"));
    }
}
