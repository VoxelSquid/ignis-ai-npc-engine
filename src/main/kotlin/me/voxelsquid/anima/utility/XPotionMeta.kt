package me.voxelsquid.anima.utility

import com.cryptomorin.xseries.XPotion
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionData

object XVanillaPotion {

    fun toItemStack(basePotionType: String) : ItemStack {
        val type = XPotion.of(basePotionType).get().potionType
        val meta = if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_6)) {
            (Bukkit.getItemFactory().getItemMeta(Material.POTION) as PotionMeta).apply { this.basePotionType = type }
        } else (Bukkit.getItemFactory().getItemMeta(Material.POTION) as PotionMeta).apply { this.basePotionData = PotionData(type!!) }
        val item = ItemStack(Material.POTION).apply { this.itemMeta = meta }
        return item
    }

}