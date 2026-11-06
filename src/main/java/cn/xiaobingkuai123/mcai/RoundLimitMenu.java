package cn.xiaobingkuai123.mcai;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.List;
public final class RoundLimitMenu {
    private final McAI plugin; private final Player player;
    public RoundLimitMenu(McAI plugin, Player player){this.plugin=plugin;this.player=player;}
    public void open(){
        Inventory inv=Bukkit.createInventory(new RoundLimitHolder(),9,plugin.languageManager.get("round-limit-title"));
        ItemStack yes=new ItemStack(Material.GREEN_WOOL), no=new ItemStack(Material.RED_WOOL);
        ItemMeta ym=yes.getItemMeta(),nm=no.getItemMeta(); ym.setDisplayName(plugin.languageManager.get("round-limit-next")); nm.setDisplayName(plugin.languageManager.get("round-limit-cancel")); yes.setItemMeta(ym);no.setItemMeta(nm);inv.setItem(3,yes);inv.setItem(5,no);player.openInventory(inv);
    }
}
