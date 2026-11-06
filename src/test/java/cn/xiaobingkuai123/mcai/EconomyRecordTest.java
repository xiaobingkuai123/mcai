package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyRecordTest {
    @Test void reservationsPreventReentrantOverspendingAndAreReleasedOnFailure() {
        var record = new EconomyManager.DailyUsageRecord();
        assertEquals(-1, record.reservePurchase(8, 10));
        assertEquals(2, record.reservePurchase(3, 10));
        assertEquals(0, record.pointsBought);
        record.finishPurchase(8, false);
        assertEquals(-1, record.reservePurchase(10, 10));
        record.finishPurchase(10, true);
        assertEquals(10, record.pointsBought);
    }
    @Test void reservationsProtectIntegerCapacityAndCommitAcrossMidnight() {
        var record = new EconomyManager.DailyUsageRecord();
        assertEquals(-1, record.reservePurchase(Integer.MAX_VALUE, -1));
        assertEquals(-2, record.reservePurchase(1, -1));
        record.currentDate = "2000-01-01";
        record.finishPurchase(Integer.MAX_VALUE, true);
        assertEquals(Integer.MAX_VALUE, record.pointsBought);
    }
}
