package at.esoren.vystormpaddock;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StableReleaseTest {
    @TempDir Path folder;
    private final UUID owner = UUID.randomUUID();
    private final Stable.Stored horse = new Stable.Stored("horse", "Horse", "HORSE", 1, new byte[]{1,2,3});
    private Stable prepared() throws Exception {
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        stable.save(owner, List.of(horse));
        return stable;
    }
    @Test void cancelledSpawnRetainsAnimal() throws Exception {
        Stable stable=prepared();var animals=stable.list(owner);
        assertFalse(stable.release(owner,animals,animals.getFirst(),()->false,()->false));
        assertArrayEquals(horse.data(),stable.list(owner).getFirst().data());
    }
    @Test void throwingSpawnRetainsAnimal() throws Exception {
        Stable stable=prepared();var animals=stable.list(owner);
        assertThrows(IllegalStateException.class,()->stable.release(owner,animals,animals.getFirst(),()->{throw new IllegalStateException("spawn failed");},()->false));
        assertArrayEquals(horse.data(),stable.list(owner).getFirst().data());
    }
    @Test void exceptionAfterWorldInsertionDoesNotDuplicateAnimal() throws Exception {
        Stable stable=prepared();var animals=stable.list(owner);
        assertThrows(IllegalStateException.class,()->stable.release(owner,animals,animals.getFirst(),()->{throw new IllegalStateException("after spawn");},()->true));
        assertTrue(stable.list(owner).isEmpty());
    }
    @Test void successfulSpawnConsumesExactlyOneAnimal() throws Exception {
        Stable stable=prepared();var animals=stable.list(owner);
        var chosen=animals.getFirst();
        assertTrue(stable.release(owner,animals,chosen,()->true,()->true));
        assertTrue(stable.list(owner).isEmpty());
        assertFalse(stable.release(owner,animals,chosen,()->{fail("must not spawn twice");return true;},()->true));
    }
}
