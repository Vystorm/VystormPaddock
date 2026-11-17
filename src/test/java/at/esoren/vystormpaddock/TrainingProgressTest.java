package at.esoren.vystormpaddock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrainingProgressTest {
    @Test void partialProgressSurvivesEntitySnapshotRoundtrip(){
        var progress=TrainingProgress.EMPTY.add(true,1500,4000,5,1000,60000);
        assertEquals(1500,TrainingProgress.decode(progress.encode()).riding());
        progress=TrainingProgress.decode(progress.encode()).add(true,2500,4000,5,2000,60000);
        assertEquals(1,progress.speedLevel());assertEquals(0,progress.riding());assertEquals(62000,progress.cooldownUntil());
    }
    @Test void sharedRecoveryPreventsAlternatingExerciseFarming(){
        var level=TrainingProgress.EMPTY.add(false,1,1,5,1000,60000);
        assertEquals(level,level.add(true,4000,4000,5,2000,60000));
        assertEquals(level,TrainingProgress.decode(level.encode()).add(false,1,1,5,60999,60000));
        assertEquals(1,level.add(true,4000,4000,5,61000,60000).speedLevel());
    }
    @Test void hardCapAndNoOverflowLevelsFromLargeSingleSample(){
        var value=TrainingProgress.EMPTY;
        for(int i=0;i<100;i++)value=value.add(true,100000,4000,5,i*1000,0);
        assertEquals(5,value.speedLevel());assertEquals(0,value.jumpLevel());assertEquals(0,value.riding());
        assertEquals(TrainingProgress.EMPTY,TrainingProgress.EMPTY.add(true,-1,4000,5,1,0));
    }
    @Test void malformedOrUnknownPersistentDataFailsClosed(){
        for(String raw:new String[]{"2;0;0;0;0;0","1;-1;0;0;0;0","1;0;0;11;0;0","1;0;0;0;0;-1","1;NaN;0;0;0;0","1;0"})
            assertThrows(IllegalArgumentException.class,()->TrainingProgress.decode(raw));
        assertEquals(TrainingProgress.EMPTY,TrainingProgress.decode(null));
    }
}
