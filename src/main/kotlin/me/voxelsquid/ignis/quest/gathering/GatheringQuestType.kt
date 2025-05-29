package me.voxelsquid.ignis.quest.gathering

import me.voxelsquid.ignis.Ignis.Companion.pluginInstance
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager
import me.voxelsquid.ignis.gameplay.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.ignis.gameplay.humanoid.race.HumanoidRaceManager.Companion.race
import me.voxelsquid.ignis.gameplay.util.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.ignis.gameplay.util.ItemStackCalculator.Companion.getMaterialPrice
import org.bukkit.Material
import org.bukkit.entity.Villager
import org.bukkit.entity.Villager.Profession
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType
import kotlin.random.Random

enum class GatheringQuestType(private val promptConfigPath: String, val randomQuestItem: (Villager) -> ItemStack) {

    NONE("", { ItemStack(Material.AIR) }),

    // TODO: Тут есть потенциальный StackOverflowException, т. к. возможен бесконечный цикл.
    PROFESSION_ITEM("profession-quest", { villager ->
        val raceCurrencyPrice = (villager.race?.normalCurrency?.getMaterialPrice() ?: throw NullPointerException())
        var item = ItemStack(Material.AIR)
        while (item.calculatePrice() < raceCurrencyPrice * 5 || item.calculatePrice() > HumanoidManager.InventoryWealth.getProfessionLimit(villager.villagerLevel).maxWealth) {
            val inventory = villager.subInventory
            if (villager.profession != Profession.NONE)
                pluginInstance.questManager.questItemPicker.professionItems[villager.profession]?.let { prioritizedItems ->
                    for ((material, range) in prioritizedItems.toList().shuffled().toMap()) {
                        if (!inventory.contains(material, range.second))
                            item = ItemStack(material, if (range.first != range.second) Random.nextInt(range.first, range.second) else 1).apply { if (amount > 1 && amount % 2 != 0) amount++ }
                    }
                }
        }
        item
    }),

    FOOD("food-quest", {
        ItemStack(Material.valueOf(pluginInstance.configManager.prompts.getStringList("food-quest.allowed-types").random()), 10 + Random.nextInt(6))
    }),

    MUSIC_DISC("music-disc-quest", {
        ItemStack(pluginInstance.questManager.questItemPicker.discs.random())
    }),

    BOOZE("booze-quest", {
        val allowedPotionTypes = pluginInstance.configManager.prompts.getStringList("booze-quest.allowed-potion-types")
        ItemStack(Material.POTION).apply {
            itemMeta = (this.itemMeta as PotionMeta).apply {
                this.basePotionType = PotionType.valueOf(allowedPotionTypes.random())
            }
        }
    }),

    SMITHING_TEMPLATE("smithing-template-quest", { villager ->
        ItemStack(Material.entries.filter { it.toString().contains("SMITHING_TEMPLATE") && !villager.subInventory.contains(it) }.random())
    }),

    ENCHANTED_BOOK("enchanted-book-quest", { villager ->
        pluginInstance.questManager.questItemPicker.randomEnchantedBook(villager)
    }),

    TREASURE_HUNT("treasure-hunt-quest", { villager ->
        pluginInstance.questManager.questItemPicker.randomTreasureItem(villager)
    }),

    FUNGUS_SEARCH("fungus-search-quest", {
        ItemStack(Material.valueOf(pluginInstance.configManager.prompts.getStringList("fungus-search-quest.allowed-types").random()), 6 + Random.nextInt(12))
    });

    fun getAdditionalQuestInformation() : String {
        return pluginInstance.configManager.prompts.getString("${this.promptConfigPath}.quest-requirements") ?: throw NullPointerException("Can't find additional quest info for $this.")
    }

}