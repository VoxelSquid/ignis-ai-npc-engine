package me.voxelsquid.anima.quest

import me.voxelsquid.anima.Anima.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.QuestInvalidationEvent
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.addQuest
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.removeQuest
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.quest.base.Quest.QuestData
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType

class ProgressTracker : Listener {

    private val progressTickerPauseDuration = ConfigurationAccessor(path = "gameplay.quest.tracker-update", defaultValue = 20L, comments = mutableListOf("How often will the progress tracker be updated.")).get()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            this.tick()
        }, 0L, progressTickerPauseDuration)
    }

    /* Обновление боссбаров происходит тут. Логика обновления зависит от типа квеста. */
    private fun tick() {
        questTracker.forEach { player, (quest, bar) ->
            when (quest.questType) {
                Quest.Type.GATHERING, Quest.Type.HUNTING -> {
                    val requiredAmount = quest.getRequiredItem().amount
                    val currentAmount  = player.inventory.contents.filterNotNull().filter { it.isSimilar(quest.getRequiredItem()) }.sumOf { it.amount }
                    val step = 1.0 / requiredAmount
                    bar.progress = (currentAmount * step).coerceAtMost(1.0)
                    quest.progress = bar.progress
                    if (bar.progress == 1.0) {
                        if (bar.color != BarColor.GREEN) player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F)
                        bar.color = BarColor.GREEN
                    } else bar.color = BarColor.RED
                }
            }
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.quests.forEach { questData ->
            if (!actualQuests.contains(questData.questID)) {
                plugin.questManager.invalidateQuest(questData, QuestInvalidationEvent.Reason.NOT_ACTUAL)
                return@forEach
            }
            if (actualQuests.contains(questData.questID) && questData.active) {
                questTracker[player] = questData to this.startTracking(player, questData)
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        questTracker[player]?.let { (quest) ->
            player.removeQuest(quest)
            player.addQuest(quest)
        }
        questTracker.remove(player)
    }

    fun startTracking(player: Player, questData: QuestData): BossBar {
        val progressBar = Bukkit.createBossBar("§6${questData.questName}§f: ${questData.extraShortTaskDescription}", BarColor.RED, BarStyle.SEGMENTED_20)
        progressBar.progress = questData.progress
        progressBar.addPlayer(player)
        questTracker[player] = questData to progressBar
        questData.active = true
        return progressBar
    }

    fun stopTracking(player: Player, questData: QuestData) {
        player.getTrackedQuest()?.let { (trackedQuest, progressBar) ->
            if (trackedQuest.questID == questData.questID) {
                progressBar.removePlayer(player)
                questTracker.remove(player)
            }
        }
    }

    companion object {
        val plugin = ignisInstance
        var actualQuests = mutableListOf<Long>()
        val questTracker = mutableMapOf<Player, Pair<QuestData, BossBar>>()
        fun Player.getTrackedQuest() : Pair<QuestData, BossBar>? = questTracker[this]

        var Player.questsCompleted : Long
            get() = this.persistentDataContainer.get(questCompletedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(questCompletedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(questCompletedKey, PersistentDataType.LONG, value)

        var Player.questsFailed : Long
            get() = this.persistentDataContainer.get(questFailedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(questFailedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(questFailedKey, PersistentDataType.LONG, value)

        var Player.experienceEarnedByQuests : Long
            get() = this.persistentDataContainer.get(experienceEarnedKey, PersistentDataType.LONG) ?: 0L.also{this.persistentDataContainer.set(experienceEarnedKey, PersistentDataType.LONG, it)}
            set(value) = this.persistentDataContainer.set(experienceEarnedKey, PersistentDataType.LONG, value)

        private val questCompletedKey = NamespacedKey(plugin, "QuestsCompleted")
        private val questFailedKey = NamespacedKey(plugin, "QuestsFailed")
        private val experienceEarnedKey = NamespacedKey(plugin, "ExperienceEarned")

    }

}