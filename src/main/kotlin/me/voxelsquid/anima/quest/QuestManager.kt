package me.voxelsquid.anima.quest

import io.lumine.mythic.bukkit.events.MythicMobDeathEvent
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityData
import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.Ignis.Companion.sendFormattedMessage
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.MerchantTradeEvent
import me.voxelsquid.anima.event.PlayerAcceptQuestEvent
import me.voxelsquid.anima.event.QuestInvalidationEvent
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidManager
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.isFromSpawner
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.removeQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.talk
import me.voxelsquid.anima.quest.ProgressTracker.Companion.actualQuests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.experienceEarnedByQuests
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questTracker
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsCompleted
import me.voxelsquid.anima.quest.ProgressTracker.Companion.questsFailed
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.quest.base.Quest.QuestData
import me.voxelsquid.anima.quest.gathering.GatheringQuest
import me.voxelsquid.anima.quest.gathering.QuestItemPicker
import me.voxelsquid.anima.quest.hunting.HuntingQuest
import me.voxelsquid.anima.settlement.ReputationManager.Companion.Reputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.addReputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import me.voxelsquid.anima.utility.InventorySerializer.Companion.fromBase64
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent

class QuestManager : Listener {

    val questItemPicker = QuestItemPicker()
    val progressTracker = ProgressTracker()
    val questItemData   = plugin.controller.databaseManager.getAllQuestItems()

    private val playerQuestLimit           = ConfigurationAccessor(path = "gameplay.quest.player-quest-limit", defaultValue = 3, comments = mutableListOf("The maximum number of quests a player can have at one time.")).get()
    private val questLifetimeDuration      = ConfigurationAccessor(path = "gameplay.core.quest-lifetime-tick", defaultValue = 48000, comments = mutableListOf("Maximum duration of quest existence in ticks.", "When a quest is generated, after the specified number of ticks, it will be deleted and make room for a new one.")).get()
    private val questAcceptedSound         = ConfigurationAccessor(path = "gameplay.quest.accept-sound", defaultValue = Sound.ITEM_BOOK_PAGE_TURN.toString()).get()
    private val reputationPriceMultiplier  = ConfigurationAccessor(path = "reputation.quest.price-multiplier", defaultValue = 0.05, comments = mutableListOf("Reputation multiplier for the price of the item. For example, if an item costs 4000 and the multiplier is 0.05, the player will receive 200 reputation for completing the quest.")).get()
    private val experienceMultiplierPlayer = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.player", defaultValue = 0.05, comments = mutableListOf("Multiplier of experience points the player will receive when the quest is completed.")).get()
    private val experienceMultiplierNPC    = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.villager", defaultValue = 0.0025, comments = mutableListOf("Multiplier of experience points the villager who gave quest will receive when the quest is completed.")).get()

    private val cachedQuests = mutableListOf<Quest>()
    private val cachedNames  = mutableListOf<String>()
    private val language     = plugin.configManager.language

    // В этом тике мы выбираем жителя и создаём для него квест. Вопрос лишь в том, есть ли необходимость генерировать квест сразу. Думаю, да.
    fun tick() {
        val world = this.selectRandomWorld() ?: run {
            plugin.logger.info("Quest generation tick. No world.")
            return
        }

        val villager = this.selectRandomVillager(world) ?: run {
            plugin.logger.info("Quest generation tick. Can't find a villager.")
            return
        }

        if (villager.getPersonalityData() == null) run {
            plugin.logger.info("Quest generation tick. Villager found, but it has no personality.")
            return
        }

        if (villager.profession == Villager.Profession.NONE) run {
            plugin.logger.info("Quest generation tick. Villager found, but it has no profession.")
            return
        }

        if (villager.quests.size > 1 + villager.villagerLevel) run {
            plugin.logger.info("Quest generation tick. Villager found, but it has too much quests already.")
            return
        }

        plugin.logger.info("Quest generation tick. Preparing a new quest...")
        val questType = Quest.Type.entries.random()
        val quest = when (questType) {
            Quest.Type.GATHERING -> GatheringQuest(villager)
            Quest.Type.HUNTING -> {
                val scoreLimit = HumanoidManager.InventoryWealth.getProfessionLimit(villager.villagerLevel).maxWealth
                if (questItemData.isEmpty()) {
                    plugin.logger.severe("Quest item database is empty! Use \"/quest item add\" command to add dynamic quest items!")
                    return
                }
                questItemData.filter { it.score <= scoreLimit }.random().let { item ->
                    if (villager.quests.map { it.getRequiredItem() }.find { it.type == fromBase64(item.item).type } != null) return
                    HuntingQuest(villager, item.entityType, item)
                }
            }
        }

        cachedQuests.add(quest)
        quest.generate()
    }

    @EventHandler
    fun onMerchantTrade(event: MerchantTradeEvent) {
        event.player.quests.find { it.getRequiredItem().isSimilar(event.recipe.ingredients.first()) }?.let { questData ->

            event.player.closeInventory()

            when (questData.gatheringQuestType) {
                else -> finishQuest(event.player, event.merchant, questData)
            }

        }
    }

    class MythicMobHandler : Listener {

        @EventHandler
        fun onMythicMobDeath(event: MythicMobDeathEvent) {
            val killer = (event.killer as? Player) ?: return
            if ((event.entity as LivingEntity).isFromSpawner()) { return }
            killer.quests.find { event.mobType.internalName == it.questItem.entityType }?.let { questData ->
                val dropChance     = questData.questItem.dropChance
                val requiredItem   = questData.getRequiredItem()
                val requiredAmount = requiredItem.amount
                val currentAmount  = killer.inventory.contents.filterNotNull().filter { it.isSimilar(requiredItem) }.sumOf { it.amount }
                if (currentAmount < requiredAmount && dropChance >= Math.random()) {
                    killer.world.dropItemNaturally(event.entity.location, requiredItem.apply { amount = 1; })
                }
            }
        }

    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer
        val type = event.entity.type
        if (event.entity.isFromSpawner()) { return }
        killer?.quests?.find { it.targetEntityType == type.toString() }?.let { questData ->
            val dropChance     = questData.questItem.dropChance
            val requiredItem   = questData.getRequiredItem()
            val requiredAmount = requiredItem.amount
            val currentAmount  = killer.inventory.contents.filterNotNull().filter { it.isSimilar(requiredItem) }.sumOf { it.amount }
            if (currentAmount < requiredAmount && dropChance >= Math.random()) {
                killer.world.dropItemNaturally(event.entity.location, requiredItem.apply { amount = 1; })
            }
        }
    }

    @EventHandler
    fun onPlayerAcceptQuest(event: PlayerAcceptQuestEvent) {

        val player      = event.player
        val villager    = event.villager
        val questData   = event.questData /* Эта QuestData всегда имеет прогресс, равный нулю. */
        val cachedQuest = cachedQuests.find { it.questData.questID == questData.questID } /* Однако, если квест уже кэширован, то нет смысла создавать ещё один экземпляр. */

        if (player.quests.any { it.questID == questData.questID }) {
            player.sendFormattedMessage(language.getString("quest.already-accepted")!!)
            return
        }

        if (player.quests.size >= playerQuestLimit) {
            val questLimitMessage = language.getString("quest.limit")!!.replace("{playerQuestLimit}", playerQuestLimit.toString())
            player.sendFormattedMessage(questLimitMessage)
            return
        }

        // Определяем класс квеста по типу.
        val quest = when (questData.questType) {
            Quest.Type.GATHERING -> cachedQuest ?: (GatheringQuest(villager, requiredItem = questData.getRequiredItem()) as Quest).apply { this.questData = questData }
            Quest.Type.HUNTING -> HuntingQuest(villager, questData.targetEntityType, questData.questItem).apply { this.questData = questData }
        }

        // Отправляем информацию о новом квесте игроку.
        player.sendFormattedMessage(language.getString("quest.accepted")!!.replace("{quest}", questData.questName))

        // Только один квест может быть активен.
        if (questTracker[player] == null) {
            questTracker[player] = questData to progressTracker.startTracking(player, questData)
        }

        player.addQuest(quest.questData)
        player.playSound(player, Sound.valueOf(questAcceptedSound), 1F, 1F)
    }

    @EventHandler
    private fun onQuestInvalidation(event: QuestInvalidationEvent) {

        val player = event.player

        /* Обязательно удаляем активный квест-трэкер, если он есть. */
        progressTracker.stopTracking(player, event.questData)

        val message = when (event.reason) {
            QuestInvalidationEvent.Reason.NPC_DEATH -> language.getString("quest.failed.npcDeath")
            QuestInvalidationEvent.Reason.TIME_EXPIRATION -> language.getString("quest.failed.timeExpiration")
            QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE -> language.getString("quest.failed.finishedBySomeoneElse")
            QuestInvalidationEvent.Reason.NOT_ACTUAL -> language.getString("quest.failed.notActual")
        }

        player.sendFormattedMessage(message!!.replace("{quest}", event.questData.questName))
        player.removeQuest(event.questData)
        player.questsFailed += 1
    }

    @EventHandler
    /* Проходимся по всем квестам жителя, когда он умирает. Удаляем их из списка актуальных квестов, и у всех игроков, которые онлайн и имеют этот квест у себя. */
    private fun onVillagerDeath(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager: Villager ->
            villager.quests.forEach { questData ->
                this.invalidateQuest(questData, QuestInvalidationEvent.Reason.NPC_DEATH)
            }
        }
    }

    fun cancelQuest(player: Player, questData: QuestData) {
        progressTracker.stopTracking(player, questData)
        player.removeQuest(questData)
    }

    private fun finishQuest(player: Player, villager: Villager, quest: QuestData, onFinish: () -> Unit = {}) {

        val score = quest.score
        val playerReputation   = (score * reputationPriceMultiplier).toInt()
        val playerExperience   = (score * experienceMultiplierPlayer).toInt()
        val villagerExperience = (score * experienceMultiplierNPC).toInt()

        player.giveExp(playerExperience)
        villager.settlement?.addReputation(player, playerReputation)
        villager.villagerExperience += villagerExperience

        val finishMessage = language.getString("quest.finished")!!.replace("{quest}", quest.questName)
        player.sendFormattedMessage(finishMessage)

        progressTracker.stopTracking(player, quest)
        villager.removeQuest(quest)
        player.removeQuest(quest)

        /* Обновляем статистику. */
        player.questsCompleted += 1
        player.experienceEarnedByQuests += playerExperience

        onFinish.invoke()
        this.invalidateQuest(quest, QuestInvalidationEvent.Reason.FINISHED_BY_SOMEONE_ELSE)
        villager.talk(player, this.determineFinishingDialogue(player, villager, quest))
    }

    /* Определяем текст после завершения квеста на основании репутации игрока. */
    private fun determineFinishingDialogue(player: Player, villager: Villager, questData: QuestData) : String {
        return (villager.settlement?.let { settlement: Settlement ->
            return@let when (player.getPlayerReputationStatus(settlement)) {
                Reputation.EXALTED -> questData.reputationBasedQuestFinishingDialogues[7]
                Reputation.REVERED -> questData.reputationBasedQuestFinishingDialogues[6]
                Reputation.HONORED -> questData.reputationBasedQuestFinishingDialogues[5]
                Reputation.FRIENDLY -> questData.reputationBasedQuestFinishingDialogues[4]
                Reputation.NEUTRAL -> questData.reputationBasedQuestFinishingDialogues[3]
                Reputation.UNFRIENDLY -> questData.reputationBasedQuestFinishingDialogues[2]
                Reputation.HOSTILE -> questData.reputationBasedQuestFinishingDialogues[1]
                Reputation.EXILED -> questData.reputationBasedQuestFinishingDialogues[0]
            }
        } ?: questData.reputationBasedQuestFinishingDialogues[4]).replace("%playerName%", player.name)
    }

    /* Общий метод инвалидации квеста. Указанный квест будет удалён из списка актуальных квестов, и у всех игроков онлайн. */
    fun invalidateQuest(questData: QuestData, reason: QuestInvalidationEvent.Reason) {
        actualQuests.remove(questData.questID)
        Bukkit.getOnlinePlayers().forEach { onlinePlayer ->
            onlinePlayer.quests.find { it.questID == questData.questID }?.let {
                HumanoidManager.plugin.server.pluginManager.callEvent(QuestInvalidationEvent(onlinePlayer, it, reason))
            }
        }
    }

    // Выбираем случайного жителя, предварительно отчистив старые квесты.
    private fun selectRandomVillager(world: World) : Villager? {
        return world.entities.filterIsInstance<Villager>().onEach { villager: Villager ->
            villager.quests.forEach { quest ->
                if ((System.currentTimeMillis() - quest.timeCreated) / 1000 * 20 > questLifetimeDuration) {
                    this.invalidateQuest(quest, QuestInvalidationEvent.Reason.TIME_EXPIRATION)
                    villager.removeQuest(quest)
                }
            }
        }.filter { it.quests.size < it.villagerLevel + 1 }.randomOrNull()
    }

    // Находим мир, где есть жители. Фильтрация позволит пропускать итерации, в которых ничего не происходит.
    private fun selectRandomWorld() : World? {
        return plugin.allowedWorlds.filter { it.entities.filterIsInstance<Villager>().isNotEmpty() }.randomOrNull()
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.server.pluginManager.isPluginEnabled("MythicMobs")) {
            plugin.server.pluginManager.registerEvents(MythicMobHandler(), plugin)
        }
    }

    companion object {
        val plugin = ignisInstance
    }

}