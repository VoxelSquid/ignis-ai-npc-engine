package me.voxelsquid.ignis.quest.hunting

import me.voxelsquid.ignis.config.ConfigurationAccessor
import me.voxelsquid.ignis.gameplay.util.InventorySerializer.Companion.fromBase64
import me.voxelsquid.ignis.quest.base.Quest
import me.voxelsquid.ignis.quest.gathering.GatheringQuestType
import me.voxelsquid.ignis.runtime.DatabaseManager
import org.bukkit.entity.EntityType
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.PotionMeta
import kotlin.random.Random

class HuntingQuest(villager: Villager,
                   targetEntityType: String,
                   questItem: DatabaseManager.QuestItem,
                   questType: Type = Type.HUNTING,
                   quantity: Int = if (questItem.quantity <= 1) 1 else Random.nextInt(1, questItem.quantity)
                    ) : Quest(
                        villager,
                        questType,
                        requiredItem = fromBase64(questItem.item).apply { this.amount = quantity },
                        score = questItem.score,
                        requiredQuestItem = questItem,
                        targetEntityType = targetEntityType,
                        quantity = quantity)
{

    init {
        val item = fromBase64(requiredQuestItem.item)
        val baseColor = ConfigurationAccessor("text-formatting.dialogue-box.text.base-color", "&f", mutableListOf("Standard color of common words in dialogue boxes.")).get()
        placeholders["questItem"] = (item.itemMeta?.displayName ?: item.type.toString().lowercase().replace("_", " ")) + baseColor.replace("&", "§")
        placeholders["questItemAmount"] = quantity.toString()
        placeholders["rewardItem"] = bounty.type.toString().lowercase().replace("_", " ")
        placeholders["entityType"] = targetEntityType.lowercase().replace("_", " ")
    }

}