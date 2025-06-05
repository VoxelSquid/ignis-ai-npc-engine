package me.voxelsquid.anima.utility

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.manager.server.ServerVersion
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta

class ItemStackCalculator {

    companion object {

        private val plugin = ignisInstance

        fun Collection<ItemStack>.calculatePrice(): Int {
            return this.filterNot{ it.type.isEdible || it.type == Material.ENCHANTED_BOOK }.sumOf { it.calculatePrice() }
        }

        /* Подсчитывание цены для всего стака. */
        fun ItemStack.calculatePrice(): Int {
            return (this.type.getMaterialPrice() * this.amount + ((this.itemMeta as? PotionMeta)?.let {
                val type = if (PacketEvents.getAPI().serverManager.version.isNewerThanOrEquals(ServerVersion.V_1_20_6)) it.basePotionType else it.basePotionData!!.type
                plugin.configManager.prices.getInt("effect-type.$type")
            } ?: 0))
        }

        fun Material.getMaterialPrice(defaultPrice: Int = 50): Int {

            val pricingConfig = plugin.configManager.prices

            return if (pricingConfig.contains(this.name))
                pricingConfig.getInt(this.name)
            else defaultPrice.also {
                if (this != Material.AIR) plugin.logger.warning("Price for material $this not found.")
            }
        }

    }

}