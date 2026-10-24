package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;

public class JavaMainMenu {
    private final McAI plugin;
    private final Player viewer;
    public JavaMainMenu(McAI plugin, Player viewer) { this.plugin = plugin; this.viewer = viewer; }

    public void open() {
        LanguageManager lang = plugin.languageManager;
        Inventory inventory = Bukkit.createInventory(new MainMenuHolder(), 9, lang.get("gui-main-title"));
        ItemStack glassPane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glassPane.getItemMeta();
        glassMeta.setDisplayName(" ");
        glassPane.setItemMeta(glassMeta);
        inventory.setItem(0, glassPane);
        inventory.setItem(8, glassPane);

        ItemStack normalButton = new ItemStack(Material.PAPER);
        ItemMeta normalMeta = normalButton.getItemMeta();
        normalMeta.setDisplayName(lang.get("gui-normal-btn"));
        normalMeta.setLore(List.of(lang.get("gui-normal-lore")));
        normalButton.setItemMeta(normalMeta);
        inventory.setItem(1, normalButton);

        ItemStack proButton = new ItemStack(Material.DIAMOND);
        ItemMeta proMeta = proButton.getItemMeta();
        proMeta.setDisplayName(lang.get("gui-pro-btn"));
        proMeta.setLore(List.of(lang.get("gui-pro-lore")));
        proButton.setItemMeta(proMeta);
        inventory.setItem(3, proButton);

        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.setDisplayName(lang.get("gui-clear-btn"));
        clearMeta.setLore(List.of(lang.get("gui-clear-lore")));
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(5, clearButton);

        ItemStack buyButton = new ItemStack(Material.GOLD_INGOT);
        ItemMeta buyMeta = buyButton.getItemMeta();
        buyMeta.setDisplayName(lang.get("gui-buy-btn"));
        int freePoints = plugin.economyManager.getRemainingPoints(viewer);
        if (plugin.economyEnabled) {
            var record = plugin.economyManager.dailyUsageRecords.get(viewer.getUniqueId());
            int bought = record != null ? record.pointsBought : 0;
            buyMeta.setLore(List.of(lang.get("gui-buy-lore",
                    "free", String.valueOf(freePoints), "bought", String.valueOf(bought))));
        } else {
            buyMeta.setLore(List.of(lang.get("gui-buy-lore-no-economy", "free", String.valueOf(freePoints))));
        }
        buyButton.setItemMeta(buyMeta);
        inventory.setItem(7, buyButton);
        viewer.openInventory(inventory);
    }
}
