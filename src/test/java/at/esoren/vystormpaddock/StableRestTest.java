package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StableRestTest {
    private static final long TEN_MINUTES = 600_000;

    @Test
    void animalsHealEvenlyAndFullyAfterTheRestTime() {
        assertEquals(10, PaddockPlugin.restedHealth(10, 30, 0, TEN_MINUTES), 1e-9);
        assertEquals(20, PaddockPlugin.restedHealth(10, 30, TEN_MINUTES / 2, TEN_MINUTES), 1e-9);
        assertEquals(30, PaddockPlugin.restedHealth(10, 30, TEN_MINUTES, TEN_MINUTES), 1e-9);
        assertEquals(30, PaddockPlugin.restedHealth(10, 30, TEN_MINUTES * 50, TEN_MINUTES), 1e-9);
    }

    @Test
    void noHealingWhenSwitchedOffOrAlreadyHealthy() {
        assertEquals(10, PaddockPlugin.restedHealth(10, 30, TEN_MINUTES, 0), 1e-9);
        assertEquals(30, PaddockPlugin.restedHealth(30, 30, TEN_MINUTES, TEN_MINUTES), 1e-9);
        assertEquals(10, PaddockPlugin.restedHealth(10, 30, -5, TEN_MINUTES), 1e-9, "clock went backwards");
    }
}
