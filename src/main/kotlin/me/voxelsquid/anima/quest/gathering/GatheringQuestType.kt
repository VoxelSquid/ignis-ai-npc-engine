package me.voxelsquid.anima.quest.gathering

import me.voxelsquid.psyche.race.RaceManager.Companion.race
import me.voxelsquid.anima.Anima.Companion.ignisInstance
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.getMaterialPrice
import org.bukkit.Material
import org.bukkit.entity.Villager
import org.bukkit.entity.Villager.Profession
import org.bukkit.inventory.ItemStack
import kotlin.random.Random

enum class GatheringQuestType(private val promptConfigPath: String, val randomQuestItem: (Villager) -> ItemStack) {

    NONE("", { ItemStack(Material.AIR) }),

    // TODO: Тут есть потенциальный StackOverflowException, т. к. возможен бесконечный цикл.
    PROFESSION_ITEM("profession-quest", { villager ->
        val raceCurrencyPrice = ((villager.race.normalCurrency.get() ?: throw NullPointerException("Can't init currency!")).getMaterialPrice())
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

    ENCHANTED_BOOK("enchanted-book-quest", { villager ->
        me.voxelsquid.anima.Anima.Companion.ignisInstance.questManager.questItemPicker.randomEnchantedBook(villager)
    }),

    TREASURE_HUNT("treasure-hunt-quest", { villager ->
        ignisInstance.questManager.questItemPicker.randomTreasureItem(villager)
    });

    fun getAdditionalQuestInformation() : String {
        return ignisInstance.configManager.prompts.getString("${this.promptConfigPath}.quest-requirements") ?: throw NullPointerException("Can't find additional quest info for $this.")
    }



}