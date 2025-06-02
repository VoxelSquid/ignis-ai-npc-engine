package me.voxelsquid.anima.quest.gathering

import com.cryptomorin.xseries.XPotion
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.getMaterialPrice
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import org.bukkit.Material
import org.bukkit.entity.Villager
import org.bukkit.entity.Villager.Profession
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionData
import kotlin.random.Random

enum class GatheringQuestType(private val promptConfigPath: String, val randomQuestItem: (Villager) -> ItemStack) {

    NONE("", { ItemStack(Material.AIR) }),

    // TODO: Тут есть потенциальный StackOverflowException, т. к. возможен бесконечный цикл.
    PROFESSION_ITEM("profession-quest", { villager ->
        val raceCurrencyPrice = (villager.race.normalCurrency.get()?.getMaterialPrice() ?: throw NullPointerException())
        var item = ItemStack(Material.AIR)
        while (item.calculatePrice() < raceCurrencyPrice * 5 || item.calculatePrice() > me.voxelsquid.anima.humanoid.HumanoidManager.InventoryWealth.getProfessionLimit(villager.villagerLevel).maxWealth) {
            val inventory = villager.subInventory
            if (villager.profession != Profession.NONE)
                ignisInstance.questManager.questItemPicker.professionItems[villager.profession]?.let { prioritizedItems ->
                    for ((material, range) in prioritizedItems.toList().shuffled().toMap()) {
                        if (!inventory.contains(material, range.second))
                            item = ItemStack(material, if (range.first != range.second) Random.nextInt(range.first, range.second) else 1).apply { if (amount > 1 && amount % 2 != 0) amount++ }
                    }
                }
        }
        item
    }),

    FOOD("food-quest", {
        ItemStack(Material.valueOf(ignisInstance.configManager.prompts.getStringList("food-quest.allowed-types").random()), 10 + Random.nextInt(6))
    }),

    MUSIC_DISC("music-disc-quest", {
        ItemStack(ignisInstance.questManager.questItemPicker.discs.random())
    }),

    BOOZE("booze-quest", {
        val allowedPotionTypes = ignisInstance.configManager.prompts.getStringList("booze-quest.allowed-potion-types")
        ItemStack(Material.POTION).apply {
            val randomPotionType = allowedPotionTypes.random()
            itemMeta = (this.itemMeta as PotionMeta).apply {
                try {
                    XPotion.of(randomPotionType).get().potionType?.let {
                        this.basePotionData = PotionData(it)
                    }
                } catch (ignored: Exception) {
                    this.basePotionData = PotionData(XPotion.REGENERATION.potionType!!)
                }
            }
        }
    }),

    SMITHING_TEMPLATE("smithing-template-quest", { villager ->
        ItemStack(Material.entries.filter { it.toString().contains("SMITHING_TEMPLATE") && !villager.subInventory.contains(it) }.random())
    }),

    ENCHANTED_BOOK("enchanted-book-quest", { villager ->
        ignisInstance.questManager.questItemPicker.randomEnchantedBook(villager)
    }),

    TREASURE_HUNT("treasure-hunt-quest", { villager ->
        ignisInstance.questManager.questItemPicker.randomTreasureItem(villager)
    }),

    FUNGUS_SEARCH("fungus-search-quest", {
        ItemStack(Material.valueOf(ignisInstance.configManager.prompts.getStringList("fungus-search-quest.allowed-types").random()), 6 + Random.nextInt(12))
    });

    fun getAdditionalQuestInformation() : String {
        return ignisInstance.configManager.prompts.getString("${this.promptConfigPath}.quest-requirements") ?: throw NullPointerException("Can't find additional quest info for $this.")
    }

}