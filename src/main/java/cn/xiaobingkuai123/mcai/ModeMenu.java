package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;

public class ModeMenu {
    private final McAI plugin;
    private final Player viewer;
    private final boolean isPro;
    public ModeMenu(McAI plugin, Player viewer, boolean isPro) {
        this.plugin = plugin; this.viewer = viewer; this.isPro = isPro;
    }

    public void open() {
        LanguageManager lang = plugin.languageManager;
        Inventory inventory = Bukkit.createInventory(new ModeMenuHolder(), 9, lang.get("mode-title"));
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.setDisplayName(" ");
        glass.setItemMeta(glassMeta);
        inventory.setItem(0, glass);
        inventory.setItem(8, glass);

        int noThinkCost = isPro ? plugin.proCostPoints : plugin.normalCostPoints;
        int thinkCost = isPro ? plugin.proThinkCostPoints : plugin.normalThinkCostPoints;

        ItemStack noThinkButton = new ItemStack(Material.IRON_INGOT);
        ItemMeta noThinkMeta = noThinkButton.getItemMeta();
        noThinkMeta.setDisplayName(lang.get("mode-nothink"));
        noThinkMeta.setLore(List.of(lang.get("mode-nothink-lore", "cost", String.valueOf(noThinkCost))));
        noThinkButton.setItemMeta(noThinkMeta);
        inventory.setItem(2, noThinkButton);

        ItemStack thinkButton = new ItemStack(Material.DIAMOND);
        ItemMeta thinkMeta = thinkButton.getItemMeta();
        thinkMeta.setDisplayName(lang.get("mode-think"));
        thinkMeta.setLore(List.of(lang.get("mode-think-lore", "cost", String.valueOf(thinkCost))));
        thinkButton.setItemMeta(thinkMeta);
        inventory.setItem(4, thinkButton);

        ItemStack backButton = new ItemStack(Material.ARROW);
        ItemMeta backMeta = backButton.getItemMeta();
        backMeta.setDisplayName(lang.get("mode-back"));
        backButton.setItemMeta(backMeta);
        inventory.setItem(6, backButton);

        viewer.openInventory(inventory);
    }
}
