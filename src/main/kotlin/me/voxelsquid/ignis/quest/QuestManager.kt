package me.voxelsquid.ignis.quest

import com.google.gson.JsonSyntaxException
import me.voxelsquid.ignis.Ignis
import me.voxelsquid.ignis.configuration.ConfigurationAccessor
import me.voxelsquid.ignis.gameplay.settlement.Settlement
import me.voxelsquid.ignis.quest.ProgressTracker.Companion.actualQuests
import me.voxelsquid.ignis.quest.ProgressTracker.Companion.experienceEarnedByQuests
import me.voxelsquid.ignis.quest.ProgressTracker.Companion.questTracker
import me.voxelsquid.ignis.quest.ProgressTracker.Companion.questsCompleted
import me.voxelsquid.ignis.quest.ProgressTracker.Companion.questsFailed
import me.voxelsquid.ignis.quest.base.Quest
import me.voxelsquid.ignis.quest.base.Quest.QuestData
import me.voxelsquid.ignis.quest.gathering.GatheringQuest
import me.voxelsquid.ignis.quest.gathering.GatheringQuestType
import me.voxelsquid.ignis.quest.gathering.QuestItemPicker
import me.voxelsquid.ignis.quest.hunting.HuntingQuest
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType

class QuestManager : Listener {

    val questItemPicker = QuestItemPicker()
    val progressTracker = ProgressTracker()
    val questItemData   = plugin.controller.databaseManager.getAllQuestItems()

    private val playerQuestLimit           = ConfigurationAccessor(path = "gameplay.quest.player-quest-limit", defaultValue = 3, comments = mutableListOf("The maximum number of quests a player can have at one time.")).get()
    private val questLifetimeDuration      = ConfigurationAccessor(path = "gameplay.core.quest-lifetime-tick", defaultValue = 48000, comments = mutableListOf("Maximum duration of quest existence in ticks.", "When a quest is generated, after the specified number of ticks, it will be deleted and make room for a new one.")).get()
    private val questAcceptedSound         = ConfigurationAccessor(path = "gameplay.quest.accept-sound", defaultValue = Sound.ENTITY_VILLAGER_WORK_LIBRARIAN.toString()).get()
    private val reputationPriceMultiplier  = ConfigurationAccessor(path = "reputation.quest.price-multiplier", defaultValue = 0.05, comments = mutableListOf("Reputation multiplier for the price of the item. For example, if an item costs 4000 and the multiplier is 0.05, the player will receive 200 reputation for completing the quest.")).get()
    private val experienceMultiplierPlayer = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.player", defaultValue = 0.05, comments = mutableListOf("Multiplier of experience points the player will receive when the quest is completed.")).get()
    private val experienceMultiplierNPC    = ConfigurationAccessor(path = "gameplay.quest.experience-multiplier.villager", defaultValue = 0.0025, comments = mutableListOf("Multiplier of experience points the villager who gave quest will receive when the quest is completed.")).get()

    private val cachedQuests = mutableListOf<Quest>()
    private val cachedNames  = mutableListOf<String>()
    private val language     = plugin.configManager.language

    // В этом тике мы выбираем жителя и создаём для него квест. Вопрос лишь в том, есть ли необходимость генерировать квест сразу. Думаю, да.
    fun tick() {
        val world = this.selectRandomWorld() ?: return
        val villager = this.selectRandomVillager(world) ?: return
        if (villager.getHumanoidController() == null) return
        if (villager.getPersonality() == null) { this.requestPersonalDataFor(villager); return }
        if (villager.profession == Villager.Profession.NONE) return
        if (villager.quests.size > 1 + villager.villagerLevel) return

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
                GatheringQuestType.BOOZE -> {
                    this.finishBrewQuest(event.merchant, questData.getRequiredItem()) {
                        plugin.questManager.finishQuest(event.player, event.merchant, questData)
                    }
                }
                GatheringQuestType.FUNGUS_SEARCH -> {
                    this.finishFungusQuest(event.merchant, questData.getRequiredItem()) {
                        plugin.questManager.finishQuest(event.player, event.merchant, questData)
                    }
                }
                else -> plugin.questManager.finishQuest(event.player, event.merchant, questData)
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

    // Генерация персоны.
    private fun requestPersonalDataFor(villager: Villager) {

        val extraArguments = "Avoid these names: $cachedNames."
        val race = villager.race?.name?.uppercase() ?: "human"
        val raceDescription = villager.race?.description ?: ""

        val placeholders = mapOf(
            "villagerGender"      to villager.gender.toString(),
            "villagerRace"        to race,
            "villagerPersonality" to "${villager.getCharacterType()}",
            "villagerGrowthStage" to if (villager.isAdult) "ADULT" else "KID",
            "language"            to plugin.controller.language,
            "randomLetter"        to QuestingUtility.getRandomLetter(),
            "extraArguments"      to extraArguments,
            "namingStyle"         to plugin.controller.namingStyle,
            "raceDescription"     to raceDescription
        )

        val prompt = placeholders.entries.fold(plugin.configManager.prompts.getString("personal-villager-data")!!) { acc, entry ->
            acc.replace("{${entry.key}}", entry.value)
        }

        plugin.geminiProvider.sendGenerationRequest(prompt) { cleanedJsonResponse ->
            plugin.server.scheduler.runTask(plugin) { _ ->
                try {
                    val personalHumanoidData = gson.fromJson(cleanedJsonResponse, HumanoidManager.PersonalityData::class.java)
                    this.cachedNames.add(personalHumanoidData.villagerName)
                    plugin.server.pluginManager.callEvent(HumanoidPersonalDataGeneratedEvent(villager, personalHumanoidData))
                } catch (exception: JsonSyntaxException) {
                    plugin.logger.warning("JsonSyntaxException during generating PersonalHumanoidData! Please, report this to the developer!")
                    plugin.logger.warning(cleanedJsonResponse)
                }
            }
        }
    }

    private fun finishBrewQuest(villager: Villager, potion: ItemStack, reply: () -> Unit) {
        val meta = (potion.clone().itemMeta as? PotionMeta) ?: throw NullPointerException("Potion meta is null during finishing booze quest!")
        villager.consume(potion, Sound.ENTITY_GENERIC_DRINK, 5, 7) {
            villager.addPotionEffect(meta.basePotionType!!.potionEffects.first())
            villager.takeItemFromQuillInventory(potion, 1)
            villager.addItemToQuillInventory(ItemStack(Material.GLASS_BOTTLE))
            reply.invoke()
        }
    }

    private fun finishFungusQuest(villager: Villager, fungus: ItemStack, reply: () -> Unit) {
        villager.consume(ItemStack(Material.SUSPICIOUS_STEW), Sound.ENTITY_GENERIC_EAT, 5, 7) {
            PotionType.entries.random().potionEffects.firstOrNull()?.let { villager.addPotionEffect(it) }
            villager.takeItemFromQuillInventory(fungus, fungus.amount)
            reply.invoke()
        }
    }

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.server.pluginManager.isPluginEnabled("MythicMobs")) {
            plugin.server.pluginManager.registerEvents(MythicMobHandler(), plugin)
        }
    }

    companion object {
        val plugin = Ignis.pluginInstance
    }

}