package me.voxelsquid.anima.humanoid.interaction

import me.voxelsquid.psyche.personality.PersonalityManager.Companion.getPersonalityData
import me.voxelsquid.anima.Anima.Companion.ignisInstance
import me.voxelsquid.anima.configuration.ConfigurationAccessor
import me.voxelsquid.anima.event.PlayerAcceptQuestEvent
import me.voxelsquid.anima.gameplay.settlement.Settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.humanoidRegistry
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.quests
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.settlement
import me.voxelsquid.anima.humanoid.HumanoidManager.HumanoidEntityExtension.subInventory
import me.voxelsquid.anima.humanoid.HumanoidTradeHandler.Companion.openTradeMenu
import me.voxelsquid.anima.humanoid.dialogue.DialogueManager.Companion.talk
import me.voxelsquid.anima.humanoid.interaction.InteractionMenuManager.Companion.openedMenuList
import me.voxelsquid.anima.quest.base.Quest
import me.voxelsquid.anima.settlement.ReputationManager.Companion.Reputation
import me.voxelsquid.anima.settlement.ReputationManager.Companion.getPlayerReputationStatus
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.*
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.*
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

class InteractionMenuManager: Listener {

    private val buttonTextColor = ConfigurationAccessor(path = "text-formatting.menu.button.color", defaultValue = "&f", comments = mutableListOf("Color of interactive menu buttons, HEX values.")).get()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, { _ ->
            openedMenuList.toList().forEach(InteractionMenu::relocate)
        }, 0L, 1L)
    }

    companion object {
        val openedMenuList: MutableList<InteractionMenu> = mutableListOf()
        val plugin = ignisInstance
    }

    @EventHandler
    private fun whenVillagerDies(event: EntityDeathEvent) {
        (event.entity as? Villager)?.let { villager ->
            openedMenuList.filter { it.villager == villager }.forEach(InteractionMenu::destroy)
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
            if (!plugin.controller.allowedWorlds.contains(villager.world.name))
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
                // (villager as CraftVillager).handle.tradingPlayer = null
                return
            }

            // Обработка состояния спящего жителя
            if (villager.pose == Pose.SLEEPING) {
                val message = villager.getPersonalityData()?.sleepInterruptionPhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.sleepInterruptionPhrases.random()
                villager.talk(player, message)
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

    private fun showDefaultMenu(player: Player, villager: Villager) {

        val builder = Builder(villager, player)

        builder.button(plugin.configManager.language.getString("interaction-menu.quests-button")!!) {

            // Когда игрок спрашивает о квестах у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message)
                return@button
            }

            // Когда игрок спрашивает о квестах у жителя с работой, но без квестов
            if (villager.quests.isEmpty()) {
                val message = villager.getPersonalityData()?.noQuestPhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.noQuestPhrases.random()
                villager.talk(player, message)
                return@button
            }

            if (villager.quests.isNotEmpty()) {
                this.showQuestListMenu(player, villager)
            }
        }

        builder.button(plugin.configManager.language.getString("interaction-menu.trade-button")!!) {

            // Когда игрок спрашивает о торговле у безработного жителя
            if (villager.profession == Villager.Profession.NONE) {
                val message = villager.getPersonalityData()?.joblessPhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.joblessPhrases.random()
                villager.talk(player, message)
                return@button
            }

            plugin.server.scheduler.runTaskLater(plugin, { _ ->
                if (!villager.openTradeMenu(player)) {
                    val message = villager.getPersonalityData()?.noItemsToTradePhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.noItemsToTradePhrases.random()
                    villager.talk(player, message)
                }
            }, 1L)
        }

        builder.button(plugin.configManager.language.getString("interaction-menu.close-button")!!) { menu ->
            menu.destroy()
        }

        builder.build()
    }

    // TODO: Не забудь перевести эти слова. Они должны быть в language.yml.
    private fun showQuestSuggestionMenu(player: Player, villager: Villager, questData: Quest.QuestData) {

        val builder = Builder(villager, player)
        builder.button(plugin.configManager.language.getString("interaction-menu.accept-button")!!) {
            plugin.server.pluginManager.callEvent(PlayerAcceptQuestEvent(player, villager, questData))
        }

        builder.button(plugin.configManager.language.getString("interaction-menu.decline-button")!!) { menu ->
            menu.destroy()
        }

        builder.button(plugin.configManager.language.getString("interaction-menu.close-button")!!) { menu ->
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

                villager.talk(player, description)
            }
        }

        builder.button(plugin.configManager.language.getString("interaction-menu.return-button")!!) { menu ->
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
    private fun onPlayerDamageEntity(event: EntityDamageByEntityEvent) {
        (event.damager as? Player)?.let { player ->
            (event.entity as? Villager)?.let { entity ->

                if (!humanoidRegistry.contains(entity))
                    return

                // Lethal damage check
                if (event.finalDamage >= entity.health)
                    return

                // Hurt message
                val message = entity.getPersonalityData()?.damagePhrases?.random() ?: plugin.humanoidManager.genericReactionMessages.damagePhrases.random()
                entity.talk(player, message)

            }
        }
    }

    class Builder(villager: Villager, viewer: Player) {

        private val menu: InteractionMenu = InteractionMenu(villager, viewer)

        fun button(name: String, action: (InteractionMenu) -> Unit): Builder {
            menu.addLine(name, {
                action(menu)
            })
            return this
        }

        fun build(): InteractionMenu {
            return menu
        }

    }

}

class InteractionMenu(
    val villager: Villager,
    val viewer: Player,
    var lastScrollTime: Long = 0
) {

    // Позиция для отображения текста
    private val pivot: Location = calculatePosition()
    private val textDisplays: MutableMap<TextDisplay, () -> Unit> = mutableMapOf()

    private val height = 1.4
    private val maxDistance = 5.5
    private val size = 0.4F
    private val step = 0.125

    private val defaultColor = Color.fromARGB(150, 0, 0, 0)
    private val selectedColor = Color.fromARGB(150, 200, 200, 0)

    init {
        openedMenuList.add(this)
    }

    /**
     * Вычисляет позицию отображения текста относительно игрока и жителя.
     */
    private fun calculatePosition(): Location {
        return viewer.eyeLocation.add(villager.location.add(0.0, height, 0.0)).multiply(0.5)
    }

    /**
     * Перемещает GUI, если игрок находится в пределах допустимого расстояния.
     */
    fun relocate() {
        if (viewer.location.distance(villager.location.add(0.0, height, 0.0)) > maxDistance) {
            destroy()
            return
        }
        updatePosition()
    }

    private fun updatePosition() {
        val newLocation = calculatePosition()
        textDisplays.keys.forEachIndexed { index, display ->
            display.teleport(newLocation.clone().add(0.0, -index * step, 0.0))
        }
    }

    var index: Int = 0
        set(value) {
            field = cyclicIndex(value)
            updateSelection()
        }

    /**
     * Добавляет текстовую строку и связанную с ней функцию в GUI.
     *
     * @param text Текст для отображения.
     * @param function Функция, вызываемая при выборе этой строки.
     */
    fun addLine(text: String, function: () -> Unit) {
        val display = createButtonDisplay(text)
        textDisplays[display] = function
        updateSelection()
    }

    private fun createButtonDisplay(text: String): TextDisplay {
        val display = viewer.world.spawnEntity(
            pivot.clone().add(0.0, -textDisplays.size * step, 0.0),
            EntityType.TEXT_DISPLAY
        ) as TextDisplay
        display.isVisibleByDefault = false
        viewer.showEntity(ignisInstance, display)
        display.transformation = Transformation(Vector3f(0f, 0f, 0f), AxisAngle4f(), Vector3f(size, size, size), AxisAngle4f())
        display.text = text
        display.billboard = Display.Billboard.CENTER
        return display
    }

    private fun updateSelection() {
        textDisplays.keys.forEach { display -> display.backgroundColor = defaultColor }
        textDisplays.keys.toList().getOrNull(index)?.backgroundColor = selectedColor
    }

    /**
     * Вызывает функцию, связанную с текущим выбранным элементом.
     */
    fun invokeSelected() {
        textDisplays.keys.toList().getOrNull(index)?.let { display ->
            textDisplays[display]?.invoke()
        }
    }

    /**
     * Уничтожает GUI и освобождает ресурсы.
     */
    fun destroy() {
        openedMenuList.remove(this)
        textDisplays.keys.forEach(TextDisplay::remove)
        textDisplays.clear()
    }

    /**
     * Циклический индекс для навигации по доступным строкам.
     */
    private fun cyclicIndex(value: Int): Int {
        return when {
            value >= textDisplays.size -> 0
            value < 0 -> textDisplays.size - 1
            else -> value
        }
    }

}