package me.voxelsquid.anima.humanoid.dialogue.menu

import me.voxelsquid.anima.Ignis.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.PlayerAcceptQuestEvent
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.dialogues
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.talk
import me.voxelsquid.anima.humanoid.dialogue.DialogueSession.Companion.getActiveDialogueSession
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidTradeHandler.Companion.openTradeMenu
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager
import me.voxelsquid.anima.humanoid.dialogue.DialogueSession
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.settlement.ReputationManager.Companion.Reputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import me.voxelsquid.psyche.HumanoidController.Companion.instance
import me.voxelsquid.psyche.data.Gender
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.gender
import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityData
import me.voxelsquid.psyche.race.RaceManager.Companion.race
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.*
import kotlin.random.Random

class InteractionManager: Listener {

    private val language = ignisInstance.configManager.language
    private val genericReactionMessages by lazy { plugin.humanoidManager.movementController.personalityManager.genericReactionMessages }
    private val buttonTextColor = ConfigurationAccessor(path = "text-formatting.menu.button.color", defaultValue = "&f", comments = mutableListOf("Color of interactive menu buttons, HEX values.")).get()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            openedMenuList.toList().forEach(Menu::relocate)
        }, 0L, 1L)
    }

    companion object {
        val openedMenuList: MutableList<Menu> = mutableListOf()
        val plugin = ignisInstance
    }

    @EventHandler
    private fun whenVillagerDies(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager ->
            openedMenuList.filter { it.villager == villager }.forEach(Menu::destroy)
            dialogues.values.filter { it.entity == villager }.forEach(DialogueManager.DialogueWindow::destroy)
        }
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        lastInteraction[event.player] = System.currentTimeMillis()
    }

    private val lastInteraction = mutableMapOf<Player, Long>()
    @EventHandler(priority = EventPriority.HIGHEST)
    private fun handleVillagerInteraction(event: PlayerInteractEntityEvent) {
        (event.rightClicked as? Villager)?.let { villager ->

            // Проверка мира при клике. Меню не должно открываться в мирах, где выключен плагин.
            if (!instance.allowedWorlds.contains(villager.world))
                return

            val player: Player = event.player
            val time = System.currentTimeMillis()
            val last = lastInteraction.computeIfAbsent(player) {
                System.currentTimeMillis()
            }

            if (time - last <= 200) {
                return
            } else lastInteraction[player] = time

            // Отмена стандартного события
            event.isCancelled = true

            // Обработка повторного нажатия в случае наличия уже открытого меню
            openedMenuList.find { it.viewer == player }?.let { menu ->
                menu.invokeSelected()
                menu.destroy()
                instance.entityProvider.asHumanoid(villager as LivingEntity).talkingPlayer = null
                return
            }

            // Меню не должно открываться, если житель уже что-то говорит
            if (dialogues.containsKey(player to villager)) {
                return
            }

            val dialogueSession = player.getActiveDialogueSession()
            // Если у игрока есть активная диалоговая сессия, то
            if (dialogueSession != null) {
                if (dialogueSession.entity == villager) this.showDialogueMenu(player, villager)
                return
            }

            // Обработка состояния спящего жителя
            if (villager.pose == Pose.SLEEPING) {
                val message = villager.getPersonalityData()?.sleepInterruptionPhrases?.random() ?: genericReactionMessages.sleepInterruptionPhrases.random()
                villager.talk(player, message, followDuringDialogue = false)
                return
            }

            // TODO: Обработка взаимодействия с детьми.
            player.inventory.heldItemSlot = 4
            this.showDefaultMenu(player, villager)
        }
    }

    @EventHandler
    private fun handlePlayerQuit(event: PlayerQuitEvent) {
        openedMenuList.removeIf { it.viewer == event.player}
    }

    private fun showDialogueMenu(player: Player, villager: Villager) {
        val builder = Builder(villager, player)

        // Quest button.
        builder.button(language.getString("interaction-menu.quests-button")!!) {

            // Когда игрок спрашивает о квестах у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            // Когда игрок спрашивает о квестах у жителя с работой, но без квестов
            if (villager.quests.isEmpty()) {
                val message = villager.getPersonalityData()?.noQuestPhrases?.random() ?: genericReactionMessages.noQuestPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            if (villager.quests.isNotEmpty()) {
                this.showQuestListMenu(player, villager)
            }
        }

        // Trading should be possible during conversation sessions.
        builder.button(language.getString("interaction-menu.trade-button")!!) {

            // Когда игрок спрашивает о торговле у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                if (!villager.openTradeMenu(player)) {
                    val message = villager.getPersonalityData()?.noItemsToTradePhrases?.random() ?: genericReactionMessages.noItemsToTradePhrases.random()
                    villager.talk(player, message, followDuringDialogue = true)
                }
            }, 1L)
        }

        // Gift button.
        builder.button(language.getString("interaction-menu.gift-button")!!) {
            if (player.getActiveDialogueSession()?.giftAwaiting == false) player.getActiveDialogueSession()?.giftAwaiting = true
        }

        // Dialogue interruption button.
        builder.button(language.getString("interaction-menu.interrupt-button")!!) {
            player.getActiveDialogueSession()?.cancelled = true
        }

        // Cancel button.
        builder.button(language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()
    }

    private fun showDefaultMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)

        builder.button(language.getString("interaction-menu.quests-button")!!) {

            // Когда игрок спрашивает о квестах у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            // Когда игрок спрашивает о квестах у жителя с работой, но без квестов
            if (villager.quests.isEmpty()) {
                val message = villager.getPersonalityData()?.noQuestPhrases?.random() ?: genericReactionMessages.noQuestPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            if (villager.quests.isNotEmpty()) {
                this.showQuestListMenu(player, villager)
            }
        }

        builder.button(language.getString("interaction-menu.trade-button")!!) {

            // Когда игрок спрашивает о торговле у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message, followDuringDialogue = true)
                return@button
            }

            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                if (!villager.openTradeMenu(player)) {
                    val message = villager.getPersonalityData()?.noItemsToTradePhrases?.random() ?: genericReactionMessages.noItemsToTradePhrases.random()
                    villager.talk(player, message, followDuringDialogue = true)
                }
            }, 1L)
        }

        builder.button(language.getString("interaction-menu.talk-button")!!) {
            if (player.getActiveDialogueSession() == null) DialogueSession(player, villager)
        }

        if (true) { // TODO: DEBUG
            builder.button("Debug") {
                this.showDebugMenu(player, villager)
            }
        }

        builder.button(language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()
    }

    // TODO: Не забудь перевести эти слова. Они должны быть в language.yml.
    private fun showQuestSuggestionMenu(player: Player, villager: Villager, questData: Quest.QuestData) {

        val builder = Builder(villager, player)
        builder.button(language.getString("interaction-menu.accept-button")!!) {
            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, questData))
        }

        builder.button(language.getString("interaction-menu.decline-button")!!) { menu ->
            menu.destroy()
        }

        builder.button(language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()

    }

    private fun showDebugMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)
        builder.button("Inventory") {
            player.playSound(player.location, Sound.BLOCK_ENDER_CHEST_OPEN, 1F, 1F)
            player.openInventory(villager.subInventory)
        }

        builder.build()

    }

    private fun showQuestListMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)
        villager.quests.forEach { quest ->
            builder.button(quest.questName) {
                val description = (villager.settlement?.let { settlement: Settlement ->
                    return@let when (player.getPlayerReputationStatus(settlement)) {
                        Reputation.EXALTED -> quest.reputationBasedQuestDescriptions[7]
                        Reputation.REVERED -> quest.reputationBasedQuestDescriptions[6]
                        Reputation.HONORED -> quest.reputationBasedQuestDescriptions[5]
                        Reputation.FRIENDLY -> quest.reputationBasedQuestDescriptions[4]
                        Reputation.NEUTRAL -> quest.reputationBasedQuestDescriptions[3]
                        Reputation.UNFRIENDLY -> quest.reputationBasedQuestDescriptions[2]
                        Reputation.HOSTILE -> quest.reputationBasedQuestDescriptions[1]
                        Reputation.EXILED -> quest.reputationBasedQuestDescriptions[0]
                    }
                } ?: quest.reputationBasedQuestDescriptions[4]).replace("%playerName%", player.name) // 4 — это френдли. Логично, что жители будут относиться к игрокам дружелюбно, если они окажутся наедине.

                villager.talk(player, description) {
                    this.showQuestSuggestionMenu(player, villager, quest)
                }
            }
        }

        builder.button(language.getString("interaction-menu.return-button")!!) { menu ->
            menu.destroy()
            this.showDefaultMenu(player, villager)
        }

        builder.build()
    }

    @EventHandler
    private fun onPlayerItemHeld(event: PlayerItemHeldEvent) {

        val player = event.player
        val menu   = openedMenuList.find { it.viewer == player } ?: return

        event.isCancelled = true

        // Double scroll fix.
        if (System.currentTimeMillis() - menu.lastScrollTime > 250) {
            menu.lastScrollTime = System.currentTimeMillis()
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 1F, 2F)
            if (event.previousSlot < event.newSlot) {
                menu.index += 1
            } else menu.index -= 1
        }

    }

    @EventHandler
    private fun onPlayerInteract(event: PlayerInteractEvent) {
        event.clickedBlock?.let { block ->
            (block.blockData as? Bed)?.let { bed ->
                if (bed.isOccupied) event.isCancelled = true
            }
        }
    }

    @EventHandler
    private fun onEntityDamage(event: EntityDamageEvent) {
        (event.entity as? Villager)?.let { entity ->

            val world = entity.world

            // Sound handling
            if (instance.configuration.humanoidVillagers && !dialogues.contains(event.entity.lastDamageCause?.entity to entity)) {

                // Lethal damage check
                if (event.finalDamage >= entity.health) {
                    val sound = if (entity.gender == Gender.MALE) entity.race.maleDeathSound else entity.race.femaleDeathSound
                    world.playSound(entity.eyeLocation, sound.sound.get()?: throw NullPointerException("No death sound to play! (${sound.sound})"), 1F, Random.nextDouble(sound.min, sound.max).toFloat())
                    return
                }

                val sound = if (entity.gender == Gender.MALE) entity.race.maleHurtSound else entity.race.femaleHurtSound
                world.playSound(entity.eyeLocation, sound.sound.get()?: throw NullPointerException("No hurt sound to play! (${sound.sound})"), 1F, Random.nextDouble(sound.min, sound.max).toFloat())
            }

        }
    }

    @EventHandler
    private fun onPlayerDamageEntity(event: EntityDamageByEntityEvent) {
        (event.damager as? Player)?.let { player ->
            (event.entity as? Villager)?.let { entity ->

                if (!instance.humanoidRegistry.contains(entity))
                    return

                // Dialogue skip
                if (dialogues.contains(player to entity)) {
                    dialogues[player to entity]?.destroy()
                    event.isCancelled = true
                    return
                }

                // Lethal damage check
                if (event.finalDamage >= entity.health)
                    return

                // Hurt message
                val message = entity.getPersonalityData()?.damagePhrases?.random() ?: genericReactionMessages.damagePhrases.random()
                entity.talk(player, message, displaySize = 0.55F, followDuringDialogue = false, interruptPreviousDialogue = true)

            }
        }
    }

    class Builder(villager: Villager, viewer: Player) {

        private val menu: Menu = Menu(villager, viewer)

        fun button(name: String, action: (Menu) -> Unit): Builder {
            menu.addLine(name, {
                action(menu)
            })
            return this
        }

        fun build(): Menu {
            return menu
        }

    }

}