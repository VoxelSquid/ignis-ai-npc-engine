package me.voxelsquid.anima.quest.gathering

import com.cryptomorin.xseries.XEnchantment
import com.cryptomorin.xseries.XMaterial
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.hunger
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.quest.base.Quest.Companion.plugin
import me.voxelsquid.anima.quest.base.Quest.Type
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Villager
import org.bukkit.entity.Villager.Profession
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import kotlin.random.Random

class QuestItemPicker {

    val discs = Material.entries.filter { material: Material -> material.isRecord }
    val professionItems = mutableMapOf<Profession, Map<Material, Pair<Int, Int>>>()
    private val treasureItems: MutableList<Triple<Material, Pair<Int, Int>, String>> =
        mutableListOf<Triple<Material, Pair<Int, Int>, String>>().apply {
            val data = plugin.configManager.prompts.getStringList("treasure-hunt-quest.allowed-items")
            for (line in data) {
                val (materialName, amount, description) = line.split("~")
                val (min, max) = amount.split("-")
                this.add(Triple(XMaterial.valueOf(materialName).get() ?: continue, min.toInt() to max.toInt(), description))
            }
        }

    init {
        this.initializeProfessionItems()
    }

    fun determineGatheringQuestType(villager: Villager): GatheringQuestType {

        if (true) return GatheringQuestType.BOOZE

        val types = mutableListOf(GatheringQuestType.PROFESSION_ITEM, GatheringQuestType.MUSIC_DISC, GatheringQuestType.BOOZE)

        // Some quests are profession specific
        when (villager.profession) {
            Profession.ARMORER -> types += GatheringQuestType.SMITHING_TEMPLATE
            Profession.LIBRARIAN -> { types += GatheringQuestType.ENCHANTED_BOOK; types += GatheringQuestType.TREASURE_HUNT }
            Profession.CARTOGRAPHER -> types += GatheringQuestType.TREASURE_HUNT
            Profession.CLERIC -> types += GatheringQuestType.FUNGUS_SEARCH
            else -> {}
        }

        // Villagers can't have two quests of the same type.
        types.apply {
            removeIf { type ->
                villager.quests.filter { it.questType == Type.GATHERING }.find { it.gatheringQuestType == type } != null
            }
        }

        return when {
            villager.quests.find { it.gatheringQuestType == GatheringQuestType.PROFESSION_ITEM } == null -> GatheringQuestType.PROFESSION_ITEM
            villager.hunger <= 10 -> GatheringQuestType.FOOD
            else -> types.random()
        }
    }

    fun getTreasureItemDescription(item: ItemStack): String {
        return this.treasureItems.find { it.first == item.type }?.third ?: ""
    }

    fun randomTreasureItem(villager: Villager): ItemStack {
        val (material, range) = treasureItems.filter { !villager.subInventory.contains(it.first) }.random()
        val amount = (range.first + Random.nextInt(range.second)).apply { if (this != 1 && this % 2 != 0) this.inc() }
        return ItemStack(material, amount)
    }

    fun randomEnchantedBook(villager: Villager): ItemStack {

        fun EnchantmentStorageMeta.hasStoredEnchant(key: String): Boolean {
            val enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key.lowercase())) ?: return false
            return this.hasStoredEnchant(enchantment)
        }

        val allowedEnchantments = plugin.configManager.prompts.getStringList("enchanted-book-quest.allowed-enchantments")
        allowedEnchantments.removeIf { enchantName ->
            villager.subInventory.contents.filterNotNull().any { item ->
                item.itemMeta is EnchantmentStorageMeta && (item.itemMeta as EnchantmentStorageMeta).hasStoredEnchant(enchantName)
            }
        }

        val defaultEnchantment = XEnchantment.UNBREAKING.get()!!
        val enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(allowedEnchantments.random().lowercase())) ?: defaultEnchantment

        return ItemStack(Material.ENCHANTED_BOOK).apply {
            itemMeta = (itemMeta as EnchantmentStorageMeta).apply {
                addStoredEnchant(enchantment, enchantment.maxLevel, false)
            }
        }
    }

    private fun initializeProfessionItems() {
        Registry.VILLAGER_PROFESSION.filter { it != Profession.NONE }.forEach { profession ->

            // Создаем карту для хранения предметов
            val items = mutableMapOf<Material, Pair<Int, Int>>()

            // Извлекаем и обрабатываем список предметов
            for (line in plugin.configManager.professions.getStringList("villager-item-producing.profession.$profession.item-priority")) {
                val (materialName, amountRange) = line.split("~")
                val (min, max) = amountRange.split("-").map(String::toInt).let { range ->
                    if (range.size == 1) range[0] to range[0] else range[0] to range[1]
                }

                val amount = min to max
                if (materialName.contains('@')) {
                    Material.entries.filter { material: Material -> material.toString().contains(materialName.removePrefix("@")) }.forEach {
                        items[it] = amount
                    }
                } else items[XMaterial.valueOf(materialName).get() ?: continue] = amount
            }

            professionItems[profession] = items
        }
    }

}