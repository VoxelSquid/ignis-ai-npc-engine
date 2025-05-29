package me.voxelsquid.ignis.quest.gathering

import me.voxelsquid.ignis.quest.base.Quest
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta

// TODO: Определение GatheringQuestType не должно быть рандомным. Там должна быть какая-то логика.
// Основная логика вроде калькуляции, выбора квестового предмета и квест-финишера находится внутри класса с квестом. Даёшь инкапсуляцию!
class GatheringQuest(villager: Villager,
                     questType: Type = Type.GATHERING,
                     gatheringQuestType: GatheringQuestType = plugin.questManager.questItemPicker.determineGatheringQuestType(villager),
                     requiredItem: ItemStack = gatheringQuestType.randomQuestItem(villager)) : Quest(villager, questType, requiredItem, gatheringQuestType) {

    /* Добавляем нужные нам значения в карту плейсхолдеров, находящуюся в суперклассе. */
    init {
        placeholders["questItem"] = requiredItem.type.toString().lowercase().replace("_", " ")
        placeholders["questItemAmount"] = requiredItem.amount.toString()
        placeholders["rewardItem"] = bounty.type.toString().lowercase().replace("_", " ")
        (requiredItem.itemMeta as? PotionMeta)?.basePotionType?.let {
            placeholders["potionType"] = it.name.replace("_", " ")
        }
        (requiredItem.itemMeta as? EnchantmentStorageMeta)?.let {
            placeholders["enchantmentType"] = it.storedEnchants.keys.first().keyOrNull?.key!!.replace("_", " ")
        }
        if (gatheringQuestType == GatheringQuestType.TREASURE_HUNT) {
            placeholders["treasureDescription"] = plugin.questManager.questItemPicker.getTreasureItemDescription(requiredItem)
        }
        placeholders["questRequirements"] = gatheringQuestType.getAdditionalQuestInformation().replaceMap(placeholders)
    }

}