package cn.xiaobingkuai123.mcai;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.Inventory;
public class MainMenuHolder implements InventoryHolder {
    enum View { MAIN, HISTORY_CLEAR }
    final View view;
    MainMenuHolder(View view) { this.view = view; }
    @Override public Inventory getInventory() { return null; }
}
