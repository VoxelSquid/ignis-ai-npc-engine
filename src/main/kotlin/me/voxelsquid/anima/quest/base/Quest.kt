package me.voxelsquid.anima.quest.base

import me.voxelsquid.psyche.personality.PersonalityManager.Companion.gender
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityType
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.professionLevelName
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.quest.ProgressTracker.Companion.actualQuests
import me.voxelsquid.anima.quest.QuestManager
import me.voxelsquid.anima.quest.QuestManager.Companion
import me.voxelsquid.anima.quest.gathering.GatheringQuestType
import me.voxelsquid.anima.quest.hunting.HuntingQuest
import me.voxelsquid.anima.runtime.DatabaseManager
import me.voxelsquid.anima.utility.InventorySerializer.Companion.fromBase64
import me.voxelsquid.anima.utility.InventorySerializer.Companion.toBase64
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.calculatePrice
import me.voxelsquid.anima.utility.ItemStackCalculator.Companion.getMaterialPrice
import me.voxelsquid.psyche.data.PersonalityType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.EntityType
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.persistence.PersistentDataType

open class Quest(val villager: Villager, private val questType: Type, val requiredItem: ItemStack = ItemStack(Material.AIR), private val gatheringQuestType: GatheringQuestType = GatheringQuestType.NONE, val requiredQuestItem: DatabaseManager.QuestItem = DatabaseManager.QuestItem.EMPTY, private val targetEntityType: String = EntityType.UNKNOWN.toString(), private val score: Int = requiredItem.calculatePrice(), private val quantity: Int = 1, var questData: QuestData = QuestData(questType, System.currentTimeMillis(), score, toBase64(requiredItem), gatheringQuestType = gatheringQuestType, targetEntityType = targetEntityType, questItem = requiredQuestItem, quantity = quantity)) {

    enum class Type(val questInfo: String) {
        GATHERING("To complete the quest, the player will need to obtain an item {questItem} in the amount of {questItemAmount} and bring it to the villager. The villager promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. When generating the quest, be sure to thoughtfully consider this information. In addition to the previous requirements, follow these guidelines during the generation: {questRequirements}."),
        HUNTING("To complete the quest, the player will need to kill enough {entityType} to collect {questItem} in the amount of {questItemAmount} from them, then bring it back to the villager. The villager promises a reward ({rewardItem}) for the assistance, without specifying what exactly it will be. This quest has to do with hunting creatures for the sake of items that can fall from them — the NPC is required to logically explain the reason. NPCs must tell explicitly that player wants to seek for and hunt down some amount of {entityType}.")
    }

    val bounty = calculateBounty(villager, score)

    val placeholders = mutableMapOf(
        "namingStyle"             to plugin.controller.naming,
        "villagerGender"          to villager.gender.toString(),
        "villagerName"            to (villager.customName ?: "unknown"),
        "villagerRace"            to villager.race.name,
        "villagerProfession"      to "${villager.profession}",
        "villagerPersonality"     to "${villager.getPersonalityType()}",
        "villagerProfessionLevel" to villager.professionLevelName,
        "settlementName"          to (villager.settlement?.data?.settlementName ?: ""),
        "settlementLevel"         to villager.settlement?.size().toString(),
        "raceDescription"         to villager.race.description,
        "questInfo"               to questType.questInfo,
        "extraArguments"          to if (plugin.controller.swearing) when (villager.getPersonalityType()) {
            PersonalityType.ANGRY, PersonalityType.DRUNKARD -> "'20% of words are swearing'"
            else -> ""
        } else ""
    )

    fun generate() {

        plugin.bifrost.client.sendRequest(this.prepareBasicGenerationPrompt(), QuestData::class, onSuccess = { response ->
            plugin.logger.info("Quest generation tick. Quest has been generated successfully!")
            plugin.logger.info(response.toString())
            this.questData.questName = response.questNames.random().replace("*", "").replace("_", "")
            this.questData.extraShortTaskDescription = response.extraShortTaskDescription.replace("*", "").replace("_", "")
            this.questData.shortRequiredQuestItemDescription = response.shortRequiredQuestItemDescription.replace("*", "").replace("_", "")
            this.questData.reputationBasedQuestDescriptions.addAll(response.reputationBasedQuestDescriptions)
            this.questData.reputationBasedQuestFinishingDialogues.addAll(response.reputationBasedQuestFinishingDialogues)
            /* Модифицирование лора необходимо только для охотничьего квеста. */
            if (this is HuntingQuest) {
                this.questData.requiredItem = toBase64(requiredItem.apply { this.itemMeta = itemMeta.apply { this!!.lore = listOf("§7${questData.shortRequiredQuestItemDescription}") } })
            }
            villager.addQuest(questData)
            actualQuests.add(questData.questID)
        }, onFailure = {
            plugin.logger.warning("Error during quest generation.")
        })
    }

    private fun prepareBasicGenerationPrompt() : String {
        val prompt = plugin.configManager.prompts.getString("basic-task-description") ?: throw NullPointerException()
        return prompt.replaceMap(placeholders).replaceMap(placeholders)
    }

    protected fun String.replaceMap(replacements: Map<String, String>): String {
        var result = this
        for ((key, value) in replacements) {
            result = result.replace("{${key}}", value)
        }
        return result
    }

    data class QuestData(val questType: Type,
                         val timeCreated: Long,
                         val score: Int,
                         var requiredItem: String = "",
                         var questName: String = "",
                         var questNames: List<String> = mutableListOf(),
                         var progress: Double = 0.0,
                         var active: Boolean = false,
                         var gatheringQuestType: GatheringQuestType = GatheringQuestType.NONE,
                         val targetEntityType: String = EntityType.UNKNOWN.toString(),
                         val questItem: DatabaseManager.QuestItem = DatabaseManager.QuestItem.EMPTY,
                         val questID: Long = nextQuestID(),
                         var quantity: Int = 1,
                         var extraShortTaskDescription: String = "",
                         var shortRequiredQuestItemDescription: String = "",
                         val reputationBasedQuestDescriptions: MutableList<String> = mutableListOf(),
                         val reputationBasedQuestFinishingDialogues: MutableList<String> = mutableListOf()) {
        fun getRequiredItem() = fromBase64(requiredItem)
    }

    companion object {
        val plugin = ignisInstance

        // ID квестов помогает отслеживать актуальные квесты и те, которые уже не имеют смысла.
        val questCountKey = NamespacedKey(plugin, "TotalQuestCount")

        fun nextQuestID() : Long {
            var questCount = Bukkit.getWorlds()[0]!!.persistentDataContainer.get(questCountKey, PersistentDataType.LONG) ?: 0
            questCount += 1
            Bukkit.getWorlds()[0]!!.persistentDataContainer.set(questCountKey, PersistentDataType.LONG, questCount)
            return questCount
        }

        fun calculateBounty(villager: Villager, score: Int) : ItemStack {

            val race = villager.race
            var amount = score / (race.normalCurrency.get() ?: throw NullPointerException("Can't init currency!")).getMaterialPrice()

            if (amount < 64) return ItemStack(race.normalCurrency.get() ?: throw NullPointerException("Can't init currency!"), amount)

            val items = mutableListOf<ItemStack>()
            while (amount > 64) {
                items.add(ItemStack(race.normalCurrency.get() ?: throw NullPointerException("Can't init currency!"), 64)); amount -= 64;
            }
            items.add(ItemStack(race.normalCurrency.get() ?: throw NullPointerException("Can't init currency!"), amount))

            return this.bundle(items)
        }

        private fun bundle(items: List<ItemStack>): ItemStack {
            return ItemStack(Material.BUNDLE, 1).apply {
                itemMeta = (itemMeta as BundleMeta).apply {
                    items.forEach(::addItem)
                }
            }
        }

    }

}