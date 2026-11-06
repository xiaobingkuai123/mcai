package cn.xiaobingkuai123.mcai;

import org.junit.jupiter.api.Test;
import static cn.xiaobingkuai123.mcai.EconomyManager.PurchaseStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyRecordTest {
    @Test void reservationsPreventReentrantOverspendingAndAreReleasedOnFailure() {
        var record = new EconomyManager.DailyUsageRecord();
        assertEquals(RESERVED, record.reservePurchase(8, 10).status());
        var rejected = record.reservePurchase(3, 10);
        assertEquals(DAILY_LIMIT_REACHED, rejected.status());
        assertEquals(2, rejected.remainingAllowance());
        assertEquals(0, record.pointsBought);
        record.finishPurchase(8, false);
        assertEquals(RESERVED, record.reservePurchase(10, 10).status());
        record.finishPurchase(10, true);
        assertEquals(10, record.pointsBought);
    }
    @Test void reservationsProtectIntegerCapacityAndCommitAcrossMidnight() {
        var record = new EconomyManager.DailyUsageRecord();
        assertEquals(RESERVED, record.reservePurchase(Integer.MAX_VALUE, -1).status());
        assertEquals(INVALID_AMOUNT, record.reservePurchase(1, -1).status());
        record.currentDate = "2000-01-01";
        record.finishPurchase(Integer.MAX_VALUE, true);
        assertEquals(Integer.MAX_VALUE, record.pointsBought);
    }
    @Test void groupHistoryDefaultsAreIndependentOfLegacyGlobalKeys() {
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("chat.max-history", 99);
        var section = yaml.createSection("permissions.groups.default");
        assertEquals(5, EconomyManager.groupConfig("default", section).maxHistory);
        section.set("max-history", 12);
        assertEquals(12, EconomyManager.groupConfig("default", section).maxHistory);
        section.set("max-history", -1);
        assertEquals(0, EconomyManager.groupConfig("default", section).maxHistory);
    }
}
