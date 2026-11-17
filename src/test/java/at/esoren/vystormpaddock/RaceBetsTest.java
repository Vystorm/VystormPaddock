package at.esoren.vystormpaddock;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RaceBetsTest {
    private static final UUID RIDER = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    @Test
    void givenBothSides_whenSettled_thenWinnersSplitPoolMinusCut() {
        List<RaceBets.Bet> bets = List.of(new RaceBets.Bet(A, "A", RaceBets.Side.BEAT, 100), new RaceBets.Bet(B, "B", RaceBets.Side.BEAT, 50),
                new RaceBets.Bet(C, "C", RaceBets.Side.MISS, 150));
        Map<UUID, Double> pay = RaceBets.payouts(bets, RaceBets.Side.BEAT, 0.1);
        assertEquals(180.0, pay.get(A), 1e-9);
        assertEquals(90.0, pay.get(B), 1e-9);
        assertNull(pay.get(C));
    }

    @Test
    void givenOneSidedPool_whenSettled_thenStakesReturnWithoutCut() {
        List<RaceBets.Bet> bets = List.of(new RaceBets.Bet(A, "A", RaceBets.Side.MISS, 40), new RaceBets.Bet(B, "B", RaceBets.Side.MISS, 60));
        assertEquals(Map.of(A, 40.0, B, 60.0), RaceBets.payouts(bets, RaceBets.Side.BEAT, 0.1));
        assertEquals(Map.of(A, 40.0, B, 60.0), RaceBets.payouts(bets, RaceBets.Side.MISS, 0.1));
    }

    @Test
    void givenBook_whenBetting_thenRulesAndLockHold() {
        RaceBets bets = new RaceBets();
        assertEquals("closed", bets.check(RIDER, A, 10, 1, 100, 5));
        bets.open(RIDER, "Rider", "Derby");
        assertEquals("self", bets.check(RIDER, RIDER, 10, 1, 100, 5));
        assertEquals("amount", bets.check(RIDER, A, 500, 1, 100, 5));
        assertEquals("ok", bets.check(RIDER, A, 10, 1, 100, 5));
        bets.place(RIDER, new RaceBets.Bet(A, "A", RaceBets.Side.BEAT, 10));
        assertEquals("twice", bets.check(RIDER, A, 10, 1, 100, 5));
        assertNotNull(bets.lock(RIDER, 60_000));
        assertEquals("closed", bets.check(RIDER, B, 10, 1, 100, 5));
        assertEquals(RaceBets.Side.BEAT, RaceBets.outcome(60_000, 59_999));
        assertEquals(RaceBets.Side.MISS, RaceBets.outcome(60_000, 60_000));
        assertEquals(RaceBets.Side.MISS, RaceBets.outcome(-1, -1));
        assertEquals(RaceBets.Side.BEAT, RaceBets.outcome(-1, 90_000), "no best yet: finishing wins");
    }
    @Test
    void givenMissWins_whenChecked_thenPartyAndAbortAreFlagged() {
        RaceBets.Bet miss = new RaceBets.Bet(A, "A", RaceBets.Side.MISS, 50), beat = new RaceBets.Bet(B, "B", RaceBets.Side.BEAT, 50);
        assertEquals("party", RaceBets.suspicion(miss, RaceBets.Side.MISS, 70_000, true));
        assertEquals("abort", RaceBets.suspicion(miss, RaceBets.Side.MISS, -1, false));
        assertEquals("", RaceBets.suspicion(miss, RaceBets.Side.MISS, 70_000, false), "an honest slow run is fine");
        assertEquals("", RaceBets.suspicion(beat, RaceBets.Side.BEAT, 50_000, true), "betting on a friend to win is fine");
        RaceBets.Book book = new RaceBets.Book(RIDER, "Ri\"der", "Derby");
        String line = RaceBets.logLine(0, book, -1, RaceBets.Side.MISS, miss, 95, "abort");
        assertTrue(line.contains("\"Ri\"\"der\""), line);
        assertTrue(line.endsWith(",MISS,50.00,95.00,abort"), line);
    }
}
