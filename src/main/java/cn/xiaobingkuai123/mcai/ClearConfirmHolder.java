package cn.xiaobingkuai123.mcai;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Inventory;
public class ClearConfirmHolder implements InventoryHolder {
    enum Action { CLEAR_PERSONAL_HISTORY, CLEAR_PUBLIC_HISTORY, PUBLIC_RESET_DECISION }
    final Action action;
    final long decisionId;
    ClearConfirmHolder(Action action, long decisionId) { this.action = action; this.decisionId = decisionId; }
    @Override public Inventory getInventory() { return null; }
}
